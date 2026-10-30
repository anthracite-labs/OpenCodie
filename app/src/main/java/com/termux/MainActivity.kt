package com.termux

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.provider.DocumentsContract
import android.os.SystemClock
import android.util.Log
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows the OpenCode WebUI served by the bundled server on loopback.
 * The desktop is the server's own UI; this activity only hosts it, forwards
 * file uploads and implements file downloads through the platform picker.
 */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var loadedUrl: String? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private val pendingDownloads = PendingDownloads()
    private val activeDownloads = ConcurrentHashMap<Uri, DownloadTransfer>()
    private var noticeUntil = 0L
    private var resumed = false

    // termux-setup-storage broadcasts this action from the app's own Termux process.
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == STORAGE_ACTION && intent.getStringExtra(STORAGE_ACTION) == "storage") {
                requestStorageAccess()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(STORAGE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(storageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(storageReceiver, filter)
    }

    override fun onStop() {
        unregisterReceiver(storageReceiver)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        status = TextView(this).apply {
            text = getString(R.string.starting)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                if (activeDownloads.isNotEmpty()) activeDownloads.values.forEach { it.cancel() }
                else if (OpenCodeHost.retryAvailable) OpenCodeHost.retry(this@MainActivity)
            }
        }
        webView = WebView(this).apply {
            settings.allowFileAccess = false
            // The document picker grants selected content URIs for uploads.
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = (resources.configuration.fontScale * 100).toInt()
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = client
            webChromeClient = chrome
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (LocalOrigin.allows(url)) download(url, contentDisposition, mimeType)
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(webView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(status, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        })

        startForegroundService(Intent(this, OpenCodeService::class.java))
        poll()
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (LocalOrigin.allows(url.toString())) return false
            if (request.isForMainFrame && url.scheme in listOf("http", "https")) openExternally(url)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (LocalOrigin.allows(request.url.toString())) return null
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageFinished(view: WebView, url: String) {
            status.visibility = View.GONE
        }

        /** The supervisor restarts a dead server; keep the user informed meanwhile. */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            status.visibility = View.VISIBLE
            status.text = getString(R.string.reconnecting)
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileChooser?.onReceiveValue(null)
            fileChooser = callback
            return try {
                startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST)
                true
            } catch (e: ActivityNotFoundException) {
                fileChooser = null
                false
            }
        }
    }

    @Deprecated("super.onActivityResult still delivers the picker results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        pendingDownloads.take(requestCode)?.let { request ->
            if (resultCode == RESULT_OK && data?.data != null) save(request, data.data!!)
            return
        }
        when (requestCode) {
            FILE_CHOOSER_REQUEST -> {
                val uris: Array<Uri>? = if (resultCode != RESULT_OK) {
                    null
                } else {
                    data?.clipData?.let { clip ->
                        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.toTypedArray()
                    } ?: data?.data?.let { arrayOf(it) }
                }
                fileChooser?.onReceiveValue(uris)
                fileChooser = null
            }
            STORAGE_SETTINGS_REQUEST -> {
                if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                    setupStorageLinks()
                } else show("Storage access not granted; termux-setup-storage can be retried")
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_PERMISSION_REQUEST) return
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            setupStorageLinks()
        } else show("Storage permission denied; termux-setup-storage can be retried")
    }

    /** Bootstrap installs in the service; ask only once after its ready marker exists. */
    private fun maybeRequestBootstrapStorage() {
        if (!resumed || !File(filesDir, ".opencodie-ready").isFile) return
        val preferences = getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE)
        if (preferences.getBoolean("storage_prompted", false)) return
        // Commit before opening Android settings so process death cannot cause prompt loops.
        if (preferences.edit().putBoolean("storage_prompted", true).commit()) {
            requestStorageAccess()
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                setupStorageLinks()
                return
            }
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivityForResult(intent, STORAGE_SETTINGS_REQUEST)
            } catch (_: ActivityNotFoundException) {
                try {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        STORAGE_SETTINGS_REQUEST,
                    )
                } catch (_: ActivityNotFoundException) {
                    show("Android storage settings unavailable")
                }
            }
        } else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            setupStorageLinks()
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
        }
    }

    /** Idempotent Termux links. Never clear ~/storage or overwrite a user's files. */
    private fun setupStorageLinks() {
        Thread {
            try {
                val storage = File(Termux.HOME, "storage")

                val links = linkedMapOf(
                    "shared" to Environment.getExternalStorageDirectory(),
                    "downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "dcim" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "podcasts" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
                    "audiobooks" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS),
                )
                getExternalFilesDirs(null).forEachIndexed { index, dir ->
                    if (dir != null) links["external-$index"] = dir
                }
                externalMediaDirs.forEachIndexed { index, dir ->
                    if (dir != null) links["media-$index"] = dir
                }
                StorageLinks.setup(storage, links)
                show("Termux storage ready: ~/storage")
            } catch (e: Exception) {
                Log.e("OpenCodieStorage", "Could not set up shared storage")
                show("Storage setup failed; existing files were preserved. Check ~/storage for conflicting entries.")
            }
        }.start()
    }

    /** Asks the platform picker where to save, then streams the loopback response there. */
    private fun download(url: String, contentDisposition: String?, mimeType: String?) {
        val requestCode = try {
            pendingDownloads.add(url, CookieManager.getInstance().getCookie(url))
        } catch (_: IllegalStateException) {
            show("Too many pending downloads")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, URLUtil.guessFileName(url, contentDisposition, mimeType))
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            pendingDownloads.take(requestCode)
            show("No file picker available")
        }
    }

    private fun save(request: DownloadRequest, destination: Uri) {
        val transfer = DownloadTransfer()
        if (activeDownloads.putIfAbsent(destination, transfer) != null) {
            show("This destination is already being saved")
            return
        }
        show("Saving… Tap to cancel.")
        Thread {
            var message = getString(R.string.saved)
            try {
                transfer.copy(request, {
                    checkNotNull(contentResolver.openOutputStream(destination, "w")) { "Cannot open selected document" }
                })
            } catch (_: Exception) {
                // ACTION_CREATE_DOCUMENT creates a new document; remove only that incomplete output.
                val removed = try { DocumentsContract.deleteDocument(contentResolver, destination) } catch (_: Exception) { false }
                message = if (transfer.cancelled) "Download canceled" else "Download failed; please retry"
                if (!removed) message += "; remove the incomplete document from the selected folder"
            } finally {
                activeDownloads.remove(destination, transfer)
            }
            show(message)
        }.apply { isDaemon = true; start() }
    }

    /**
     * The WebUI is served only once the server is ready. Keep watching
     * afterwards so a restarted server is picked up again.
     */
    private fun poll() {
        handler.postDelayed({
            maybeRequestBootstrapStorage()
            val error = OpenCodeHost.error
            val url = OpenCodeHost.pairingUrl
            if (url != null && url != loadedUrl) {
                loadedUrl = url
                webView.loadUrl(url)
            }
            when {
                activeDownloads.isNotEmpty() -> {
                    status.visibility = View.VISIBLE
                    status.text = "Saving ${activeDownloads.size} download(s): ${activeDownloads.values.sumOf { it.bytesCopied }} bytes. Tap to cancel."
                }
                SystemClock.uptimeMillis() < noticeUntil -> Unit
                url != null -> { status.visibility = View.GONE }
                loadedUrl != null -> {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.reconnecting)
                }
                error != null -> {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.failed, error) + if (OpenCodeHost.retryAvailable) "\nTap to retry." else ""
                }
                else -> {
                    status.visibility = View.VISIBLE
                    status.text = OpenCodeHost.status
                }
            }
            poll()
        }, if (loadedUrl == null) POLL_MS else RECONNECT_POLL_MS)
    }

    private fun show(message: String) {
        handler.post {
            noticeUntil = SystemClock.uptimeMillis() + NOTICE_MS
            status.visibility = View.VISIBLE
            status.text = message
            handler.postDelayed({ if (loadedUrl != null) status.visibility = View.GONE }, NOTICE_MS)
        }
    }

    private fun openExternally(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (e: ActivityNotFoundException) {
            show("No application available to open this external link")
        }
    }

    @Deprecated("WebView back navigation")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        activeDownloads.values.forEach { it.cancel() }
        pendingDownloads.clear()
        fileChooser?.onReceiveValue(null)
        fileChooser = null
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val POLL_MS = 400L
        const val RECONNECT_POLL_MS = 2_000L
        const val NOTICE_MS = 3_000L
        const val FILE_CHOOSER_REQUEST = 1
        const val STORAGE_PERMISSION_REQUEST = 3
        const val STORAGE_SETTINGS_REQUEST = 4
        const val STORAGE_ACTION = "com.termux.app.reload_style"
    }
}

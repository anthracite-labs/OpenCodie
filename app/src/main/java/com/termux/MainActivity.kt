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
import android.system.Os
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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

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
    private var pendingDownload: String? = null

    // termux-setup-storage broadcasts this action from the app's own Termux process.
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == STORAGE_ACTION && intent.getStringExtra(STORAGE_ACTION) == "storage") {
                requestStorageAccess()
            }
        }
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
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        status = TextView(this).apply {
            text = getString(R.string.starting)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = (resources.configuration.fontScale * 100).toInt()
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = client
            webChromeClient = chrome
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                val uri = Uri.parse(url)
                if (uri.scheme == "http" && uri.host == OpenCodeHost.HOST &&
                    uri.port == OpenCodeHost.PORT
                ) download(url, contentDisposition, mimeType)
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
            if (url.scheme == "http" && url.host == OpenCodeHost.HOST && url.port == OpenCodeHost.PORT) return false
            openExternally(url)
            return true
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
            SAVE_FILE_REQUEST -> {
                val url = pendingDownload
                pendingDownload = null
                if (resultCode == RESULT_OK && url != null && data?.data != null) {
                    save(url, data.data!!)
                }
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
                check(!Files.isSymbolicLink(storage.toPath())) { "~/storage is a symlink" }
                check(storage.isDirectory || storage.mkdirs()) { "Cannot create ~/storage" }

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
                for ((name, target) in links) {
                    val link = File(storage, name).toPath()
                    if (Files.isSymbolicLink(link)) {
                        check(Files.readSymbolicLink(link).toString() == target.absolutePath) {
                            "Existing ~/storage/$name link points elsewhere"
                        }
                    } else {
                        check(!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                            "~/storage/$name already exists; keeping user data"
                        }
                        Os.symlink(target.absolutePath, link.toString())
                    }
                }
                show("Termux storage ready: ~/storage")
            } catch (e: Exception) {
                Log.e("OpenCodieStorage", "Could not set up shared storage", e)
                show("Storage setup failed: ${e.message}")
            }
        }.start()
    }

    /** Asks the platform picker where to save, then streams the loopback response there. */
    private fun download(url: String, contentDisposition: String?, mimeType: String?) {
        pendingDownload = url
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, URLUtil.guessFileName(url, contentDisposition, mimeType))
        }
        try {
            startActivityForResult(intent, SAVE_FILE_REQUEST)
        } catch (e: ActivityNotFoundException) {
            pendingDownload = null
            show("No file picker available")
        }
    }

    private fun save(url: String, destination: Uri) {
        show(getString(R.string.saving))
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                connection.connectTimeout = 10_000
                connection.inputStream.use { input ->
                    contentResolver.openOutputStream(destination, "w").use { output ->
                        checkNotNull(output) { "Could not open $destination" }
                        input.copyTo(output)
                    }
                }
                show(getString(R.string.saved))
            } catch (e: Exception) {
                show(getString(R.string.download_failed, e.message ?: e.toString()))
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * The WebUI is served only once the server is ready. Keep watching
     * afterwards so a restarted server is picked up again.
     */
    private fun poll() {
        handler.postDelayed({
            val error = OpenCodeHost.error
            val url = OpenCodeHost.pairingUrl
            when {
                url != null -> {
                    status.visibility = View.GONE
                    if (url != loadedUrl) {
                        loadedUrl = url
                        webView.loadUrl(url)
                    }
                }
                loadedUrl != null -> {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.reconnecting)
                }
                error != null -> {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.failed, error)
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
            status.visibility = View.VISIBLE
            status.text = message
            handler.postDelayed({ if (loadedUrl != null) status.visibility = View.GONE }, NOTICE_MS)
        }
    }

    private fun openExternally(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (e: ActivityNotFoundException) {
            show(getString(R.string.failed, url.toString()))
        }
    }

    @Deprecated("WebView back navigation")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val POLL_MS = 400L
        const val RECONNECT_POLL_MS = 2_000L
        const val NOTICE_MS = 3_000L
        const val FILE_CHOOSER_REQUEST = 1
        const val SAVE_FILE_REQUEST = 2
        const val STORAGE_PERMISSION_REQUEST = 3
        const val STORAGE_SETTINGS_REQUEST = 4
        const val STORAGE_ACTION = "com.termux.app.reload_style"
    }
}

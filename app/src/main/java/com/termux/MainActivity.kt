package com.termux

import android.app.Activity
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
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = client
            webChromeClient = chrome
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (url.startsWith("http")) download(url, contentDisposition, mimeType)
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
            if (url.host == OpenCodeHost.HOST && url.port == OpenCodeHost.PORT) return false
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
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
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
    }
}

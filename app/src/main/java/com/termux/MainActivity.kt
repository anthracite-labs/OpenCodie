package com.termux

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.widget.Button
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
    private lateinit var retry: Button
    private lateinit var onboarding: OnboardingView
    private enum class FirstRun { WELCOME, PERMISSIONS, CONTINUE }
    private var firstRun = FirstRun.WELCOME
    private var webUiReady = false
    private var webPageFailed = false
    @Volatile private var storageSetupStarted = false
    private val handler = Handler(Looper.getMainLooper())
    private var loadedUrl: String? = null
    private var observedServerPairing: String? = null
    private var nextPairingAttemptAtMs = 0L
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: String? = null
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
        updateWebViewFileAccess()
        updateOnboarding()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusTheme()
        webView.settings.textZoom = (resources.configuration.fontScale * 100).toInt()
    }

    // Indices are positional into the intArrayOf() above, not R.styleable refs.
    @SuppressLint("ResourceType")
    private fun themeColors(): Pair<Int, Int> {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground, android.R.attr.textColorPrimary))
        try {
            return attrs.getColor(0, Color.WHITE) to attrs.getColor(1, Color.BLACK)
        } finally {
            attrs.recycle()
        }
    }

    private fun applyStatusTheme() {
        if (!::status.isInitialized) return
        val (bg, fg) = themeColors()
        status.setBackgroundColor(bg)
        status.setTextColor(fg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDownload = savedInstanceState?.getString("pending_download_url")
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        val completed = getSharedPreferences("opencodie_onboarding", MODE_PRIVATE)
            .getBoolean("completed", false)
        firstRun = if (completed) FirstRun.CONTINUE else FirstRun.WELCOME
        val density = resources.displayMetrics.density
        val statusPad = (16 * density).toInt()

        status = TextView(this).apply {
            text = getString(R.string.starting)
            gravity = Gravity.CENTER
            setPadding(statusPad, statusPad, statusPad, statusPad)
            isClickable = true
            isFocusable = true
            defaultFocusHighlightEnabled = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // No static contentDescription: live region must announce dynamic
            // status text; clickable already yields "double-tap to activate".
            setOnClickListener { showDiagnostics() }
        }
        applyStatusTheme()
        retry = Button(this).apply {
            text = "Retry"
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            visibility = View.GONE
            setOnClickListener { retryCheckNow() }
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = (resources.configuration.fontScale * 100).toInt()
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Secure default: no file access until the user grants storage.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.safeBrowsingEnabled = true
            webViewClient = client
            webChromeClient = chrome
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                val uri = Uri.parse(url)
                if (uri.scheme == "http" && uri.host == OpenCodeHost.HOST &&
                    uri.port == OpenCodeHost.PORT
                ) download(url, contentDisposition, mimeType)
            }
        }
        updateWebViewFileAccess()
        val retryLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = (48 * density).toInt() }
        onboarding = OnboardingView(this, object : OnboardingView.Actions {
            override fun getStarted() {
                firstRun = FirstRun.PERMISSIONS
                updateOnboarding()
            }
            override fun requestStorage() {
                getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE).edit()
                    .putBoolean("storage_opt_in", true)
                    .putBoolean("storage_prompted", true)
                    .commit()
                requestStorageAccess()
                updateOnboarding()
            }
            override fun requestNotifications() {
                requestNotificationPermission()
                updateOnboarding()
            }
            override fun continueToWorkspace() {
                getSharedPreferences("opencodie_onboarding", MODE_PRIVATE).edit()
                    .putBoolean("completed", true).apply()
                firstRun = FirstRun.CONTINUE
                updateOnboarding()
            }
            override fun retry() = retryCheckNow()
            override fun diagnostics() = showDiagnostics()
        })
        webView.visibility = View.INVISIBLE
        status.visibility = View.GONE
        val root = FrameLayout(this).apply {
            addView(webView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(status, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(retry, retryLp)
            addView(onboarding, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        // Recovery is the only native overlay; keep it inside system insets.
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bottomInset = insets.systemWindowInsetBottom
            retryLp.bottomMargin = (48 * density).toInt() + bottomInset
            retry.layoutParams = retryLp
            insets
        }
        setContentView(root)

        startForegroundService(Intent(this, OpenCodeService::class.java))
        updateOnboarding()
        poll()
    }

    /** Content access follows explicit storage permission; file access stays off.
     *
     * WebView loads only the loopback HTTP UI; upload/download use native
     * pickers + backend. No verified feature needs file:// URLs, and
     * allowFileAccess also exposes app-private files — so it stays false.
     * allowContentAccess is gated because picker results are content:// URIs.
     */
    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun updateWebViewFileAccess() {
        if (!::webView.isInitialized) return
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = hasStoragePermission()
        // Never enable file-URL inheritance: blocks file:// exfiltration.
        webView.settings.allowFileAccessFromFileURLs = false
        webView.settings.allowUniversalAccessFromFileURLs = false
    }

    /** Onboarding is presentation only; the existing service owns execution. */
    private fun updateOnboarding() {
        if (!::onboarding.isInitialized) return
        if (OnboardingState.canRevealWebView(firstRun == FirstRun.CONTINUE, webUiReady)) {
            onboarding.visibility = View.GONE
            webView.visibility = View.VISIBLE
            status.visibility = View.GONE
            return
        }
        onboarding.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        status.visibility = View.GONE
        retry.visibility = View.GONE
        val screen = when (firstRun) {
            FirstRun.WELCOME -> OnboardingView.Screen.WELCOME
            FirstRun.PERMISSIONS -> OnboardingView.Screen.PERMISSIONS
            FirstRun.CONTINUE -> when (OnboardingState.phase(
                OpenCodeHost.status, OpenCodeHost.error, OpenCodeHost.pairingUrl != null,
            )) {
                SetupPhase.FAILED -> OnboardingView.Screen.FAILED
                SetupPhase.CONNECTING -> OnboardingView.Screen.CONNECTING
                SetupPhase.PREPARING -> OnboardingView.Screen.PREPARING
            }
        }
        onboarding.show(
            screen = screen,
            preparationTitle = OnboardingState.preparationTitle(OpenCodeHost.status),
            storageGranted = hasStoragePermission(),
            notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "http" && url.host == OpenCodeHost.HOST && url.port == OpenCodeHost.PORT) return false
            openExternally(url)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!webPageFailed &&
                url.startsWith("http://" + OpenCodeHost.HOST + ":" + OpenCodeHost.PORT + "/") &&
                !url.contains("/auth/connect/")
            ) {
                webUiReady = true
                updateOnboarding()
            }
            // Persist the browser-issued session cookie before the next Activity
            // instance needs it. Do the synchronous flush off the UI thread.
            if (url.startsWith("http://${OpenCodeHost.HOST}:${OpenCodeHost.PORT}/") &&
                !url.contains("/auth/connect/")
            ) {
                Thread { CookieManager.getInstance().flush() }.apply { isDaemon = true }.start()
            }
        }

        /** The supervisor restarts a dead server; keep the user informed meanwhile. */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            status.visibility = View.VISIBLE
            status.text = getString(R.string.reconnecting)
            webPageFailed = true
            webUiReady = false
            updateOnboarding()
            // An old WebView load may fail after a server restart; allow a new
            // session check without replaying any consumed pairing URL.
            observedServerPairing = null
            nextPairingAttemptAtMs = SystemClock.elapsedRealtime() + PAIR_RETRY_MS
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
                    if (File(filesDir, ".opencodie-ready").isFile) setupStorageLinks()
                    updateWebViewFileAccess()
                    updateOnboarding()
                } else {
                    updateWebViewFileAccess()
                    updateOnboarding()
                    show("Storage access not granted; you can allow it later")
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            updateOnboarding()
            return
        }
        if (requestCode != STORAGE_PERMISSION_REQUEST) return
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            if (File(filesDir, ".opencodie-ready").isFile) setupStorageLinks()
        } else show("Storage permission denied; you can allow it later")
        updateWebViewFileAccess()
        updateOnboarding()
    }

    /** Set up shared links only after explicit opt-in and a complete runtime install. */
    private fun maybeRequestBootstrapStorage() {
        if (!resumed || !File(filesDir, ".opencodie-ready").isFile) return
        val preferences = getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE)
        val optedIn = preferences.getBoolean("storage_opt_in", false)
        if (!optedIn) return
        if (OnboardingState.shouldPromptForStorage(
            runtimeReady = true,
            optedIn = optedIn,
            alreadyPrompted = preferences.getBoolean("storage_prompted", false),
        )) {
            if (preferences.edit().putBoolean("storage_prompted", true).commit()) {
                requestStorageAccess()
            }
            return
        }
        if (hasStoragePermission() &&
            !preferences.getBoolean("storage_links_ready", false) && !storageSetupStarted
        ) setupStorageLinks()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                if (File(filesDir, ".opencodie-ready").isFile) setupStorageLinks()
                updateOnboarding()
                return
            }
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (startForResultSafely(intent, STORAGE_SETTINGS_REQUEST)) return
            startForResultSafely(fallback, STORAGE_SETTINGS_REQUEST) {
                show("Android storage settings unavailable")
            }
        } else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            if (File(filesDir, ".opencodie-ready").isFile) setupStorageLinks()
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
        }
    }

    /** Idempotent Termux links. Never clear ~/storage or overwrite a user's files. */
    private fun setupStorageLinks() {
        if (storageSetupStarted) return
        storageSetupStarted = true
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
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    links["audiobooks"] = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS)
                }
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
                getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE).edit()
                    .putBoolean("storage_links_ready", true).apply()
                show("Termux storage ready: ~/storage")
            } catch (e: Exception) {
                Log.e("OpenCodieStorage", "Could not set up shared storage", e)
                show("Storage setup failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /** Asks the platform picker where to save, then streams the loopback response there. */
    private fun download(url: String, contentDisposition: String?, mimeType: String?) {
        pendingDownload = url
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, URLUtil.guessFileName(url, contentDisposition, mimeType))
        }
        startForResultSafely(intent, SAVE_FILE_REQUEST) {
            pendingDownload = null
            show("No file picker available")
        }
    }

    /** Stream a local download to the Android document picker without a blocking dialog. */
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
     * The server publishes one startup pairing link as a readiness marker.
     * It is single-use, so a new Activity must NOT load it directly. Instead,
     * validate persisted WebView cookies or mint a fresh pairing link.
     */
    private fun openWebUiForServer(serverPairingLink: String) {
        status.visibility = View.VISIBLE
        status.text = getString(R.string.reconnecting)
        Thread {
            val root = "http://${OpenCodeHost.HOST}:${OpenCodeHost.PORT}/"
            val destination = runCatching {
                WebUiSessionSelector(
                    rootUrl = root,
                    readCookies = { CookieManager.getInstance().getCookie(root) },
                    verifySession = { cookies -> OpenCodeHost.hasValidWebSession(cookies) },
                    newPairingLink = { OpenCodeHost.freshPairingUrl() },
                ).destinationUrl()
            }
            handler.post {
                // The server may have restarted while the background request
                // was in flight. Never load a link minted by an older server.
                if (isDestroyed || isFinishing || OpenCodeHost.pairingUrl != serverPairingLink) {
                    return@post
                }
                destination.onSuccess { target ->
                    loadedUrl = target
                    webUiReady = false
                    webPageFailed = false
                    webView.loadUrl(target)
                    updateOnboarding()
                }.onFailure {
                    observedServerPairing = null
                    nextPairingAttemptAtMs = SystemClock.elapsedRealtime() + PAIR_RETRY_MS
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.reconnecting)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Keep watching for server restarts without ever replaying a spent link.
     * A fresh Activity always performs its own session selection.
     */
    private fun poll() {
        handler.postDelayed({
            if (isDestroyed || isFinishing) return@postDelayed
            maybeRequestBootstrapStorage()
            val error = OpenCodeHost.error
            val serverPairingLink = OpenCodeHost.pairingUrl
            when {
                serverPairingLink != null -> {
                    if (serverPairingLink != observedServerPairing &&
                        SystemClock.elapsedRealtime() >= nextPairingAttemptAtMs
                    ) {
                        observedServerPairing = serverPairingLink
                        openWebUiForServer(serverPairingLink)
                    }
                }
                loadedUrl != null -> {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.reconnecting)
                }
                error != null -> {
                    status.visibility = View.VISIBLE
                    val code = RuntimeHealth.failureCode(error)
                    status.text = getString(R.string.failed, "[$code] $error")
                }
                else -> {
                    status.visibility = View.VISIBLE
                    status.text = OpenCodeHost.status
                }
            }
            refreshHealthPanel()
            updateOnboarding()
            poll()
        }, if (loadedUrl == null) POLL_MS else RECONNECT_POLL_MS)
    }

    /** Health slice: explicit retry + sanitized diagnostics, no raw secrets. */
    private fun refreshHealthPanel() {
        if (!::retry.isInitialized) return
        val state = RuntimeHealth.current(OpenCodeHost.status, OpenCodeHost.error)
        retry.visibility = if (state is HealthState.Failed) View.VISIBLE else View.GONE
    }

    /**
     * Retry failed startup through the supervisor; otherwise just refresh
     * a stale WebView pairing attempt.
     */
    private fun retryCheckNow() {
        // A failed bootstrap leaves no supervisor to retry automatically.
        if (OpenCodeHost.error != null) {
            Thread { OpenCodeHost.restart(applicationContext) }.apply { isDaemon = true }.start()
        }
        observedServerPairing = null
        nextPairingAttemptAtMs = SystemClock.elapsedRealtime()
        status.visibility = View.VISIBLE
        status.text = getString(R.string.reconnecting)
        refreshHealthPanel()
    }

    private fun showDiagnostics() {
        val error = OpenCodeHost.error ?: return
        val code = RuntimeHealth.failureCode(error)
        val details = RuntimeHealth.sanitize("[$code] $error\n\n${OpenCodeHost.sanitizedLog()}")
        AlertDialog.Builder(this)
            .setTitle("OpenCodie diagnostics")
            .setMessage(details.takeLast(2000))
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("opencodie-diagnostics", details))
                show("Diagnostics copied (secrets redacted)")
            }
            .setNegativeButton("Close", null)
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
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
        } catch (_: ActivityNotFoundException) {
            show(getString(R.string.failed, url.toString()))
        }
    }

    private fun startForResultSafely(intent: Intent, requestCode: Int, onMissing: () -> Unit = {}): Boolean {
        return try {
            startActivityForResult(intent, requestCode)
            true
        } catch (_: ActivityNotFoundException) {
            onMissing()
            false
        }
    }

    @Deprecated("WebView back navigation")
    override fun onBackPressed() {
        if (firstRun == FirstRun.PERMISSIONS) {
            firstRun = FirstRun.WELCOME
            updateOnboarding()
        } else if (webUiReady && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_download_url", pendingDownload)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        fileChooser?.onReceiveValue(null)
        fileChooser = null
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val POLL_MS = 400L
        const val RECONNECT_POLL_MS = 2_000L
        const val PAIR_RETRY_MS = 2_000L
        const val NOTICE_MS = 3_000L
        const val FILE_CHOOSER_REQUEST = 1
        const val SAVE_FILE_REQUEST = 2
        const val STORAGE_PERMISSION_REQUEST = 3
        const val STORAGE_SETTINGS_REQUEST = 4
        const val NOTIFICATION_PERMISSION_REQUEST = 5
        const val STORAGE_ACTION = "com.termux.app.reload_style"
    }
}

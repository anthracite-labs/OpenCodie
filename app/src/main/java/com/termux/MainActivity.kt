package com.termux

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.Parcel
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException

/**
 * Shows the OpenCode WebUI served by the bundled server on loopback.
 * The desktop is the server's own UI; this activity only hosts it, forwards
 * file uploads and implements file downloads through the platform picker.
 */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private var restoredWebHistory: Bundle? = null
    private var restoredWorkspaceUrl: String? = null
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var webRetryAttempts = 0
    private var webFailureReason: String? = null
    private var activeLoadFailed = false
    private var rendererCrashWindowMs = 0L
    private var rendererCrashCount = 0
    private val downloadCancelled = AtomicBoolean(false)
    private var downloadDialog: AlertDialog? = null
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var onboarding: OnboardingView
    private enum class FirstRun { WELCOME, PERMISSIONS, CONTINUE }
    private var firstRun = FirstRun.WELCOME
    private var webUiReady = false
    private var webPageFailed = false
    private val storageSetupStarted = AtomicBoolean(false)
    @Volatile private var storageSetupFailed = false
    private val handler = Handler(Looper.getMainLooper())
    private var loadedUrl: String? = null
    private var observedServerPairing: String? = null
    private var nextPairingAttemptAtMs = 0L
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: String? = null
    private var resumed = false
    private var permissionDecision = PermissionDecision()

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
        reviewRevokedPermissions()
        updateOnboarding()
    }

    override fun onPause() {
        if (permissionDecision.pending != null) permissionDecision.lostFocus()
        resumed = false
        super.onPause()
    }

    /** The legacy-target notification popup is OS-owned: no permission callback
     * fires when the channel is created. Window focus returns only after it closes.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::onboarding.isInitialized) return
        if (!hasFocus) {
            permissionDecision.lostFocus()
        } else {
            val resolved = permissionDecision.focusReturned()
            if (resolved != null) {
                commitPermissionResolution(resolved)
            }
            // A grant can also change while Settings/OS UI is open without
            // an active request, such as after an explicit Cancel.
            updateOnboarding()
        }
    }

    private fun finishPermissionRequest(kind: PermissionKind) {
        if (permissionDecision.finish(kind)) commitPermissionResolution(kind)
    }

    private fun commitPermissionResolution(kind: PermissionKind) {
        getSharedPreferences("opencodie_permissions", MODE_PRIVATE).edit()
            .putBoolean(
                if (kind == PermissionKind.NOTIFICATIONS) "notifications_attempted"
                else "storage_attempted", true,
            ).apply()
        updateOnboarding()
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
        val mode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (lastNightMode != mode) {
            lastNightMode = mode
            onboarding.refreshTheme()
        } else {
            onboarding.refreshContentWidth()
        }
        updateOnboarding()
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
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        restoredWebHistory = savedInstanceState?.getBundle("web_navigation")
        restoredWorkspaceUrl = savedInstanceState?.getString("workspace_url")?.takeIf {
            OnboardingState.isWorkspaceUrl(it, OpenCodeHost.HOST, OpenCodeHost.PORT)
        }
        pendingDownload = savedInstanceState?.getString("pending_download_url")
        val restoredKind = savedInstanceState?.getString("pending_permission")
            ?.let { runCatching { PermissionKind.valueOf(it) }.getOrNull() }
        permissionDecision = PermissionDecision(
            restoredKind,
            savedInstanceState?.getBoolean("pending_permission_focus_lost") == true,
        )
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        val onboardingPrefs = getSharedPreferences("opencodie_onboarding", MODE_PRIVATE)
        // Decide upgrade migration once, before the first bootstrap is started.
        if (!onboardingPrefs.contains("completed")) {
            onboardingPrefs.edit().putBoolean(
                "completed", File(filesDir, ".opencodie-ready").isFile,
            ).commit()
        }
        val completed = onboardingPrefs.getBoolean("completed", false)
        val permissionPrefs = getSharedPreferences("opencodie_permissions", MODE_PRIVATE)
        // Upgrades had no permission snapshot: seed from the actual current
        // grants rather than treating every existing user as a new denial.
        if (completed && !permissionPrefs.contains("storage_at_continue")) {
            val storageAllowed = hasStoragePermission()
            val notificationsAllowed = hasNotificationAccess()
            permissionPrefs.edit()
                .putBoolean("storage_at_continue", storageAllowed)
                .putBoolean("notifications_at_continue", notificationsAllowed)
                .putBoolean("notifications_attempted", notificationsAllowed)
                .commit()
        }
        val reviewRevoked = PermissionFlow.shouldReviewRevocation(
            completed = completed,
            previousStorage = permissionPrefs.getBoolean("storage_at_continue", false),
            storageNow = hasStoragePermission(),
            previousNotifications = permissionPrefs.getBoolean("notifications_at_continue", false),
            notificationsNow = hasNotificationAccess(),
        )
        firstRun = if (completed) {
            if (reviewRevoked) FirstRun.PERMISSIONS else FirstRun.CONTINUE
        } else {
            savedInstanceState?.getString("first_run_step")?.let {
                runCatching { FirstRun.valueOf(it) }.getOrNull()
            }?.takeUnless { it == FirstRun.CONTINUE } ?: FirstRun.WELCOME
        }
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
        webView = createWebView()
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
                if (permissionDecision.pending == PermissionKind.STORAGE) {
                    permissionDecision.finish(PermissionKind.STORAGE)
                    updateOnboarding()
                    return
                }
                if (!permissionDecision.begin(PermissionKind.STORAGE)) return
                // These are bootstrap *opt-in* flags; UI attempt state is committed
                // only after Android returns, in commitPermissionResolution().
                getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE).edit()
                    .putBoolean("storage_opt_in", true)
                    .putBoolean("storage_prompted", true)
                    .commit()
                updateOnboarding()
                if (hasStoragePermission() && !storageSetupFailed) {
                    openAllFilesAccessSettings()
                } else {
                    requestStorageAccess()
                }
            }
            override fun requestNotifications() {
                if (permissionDecision.pending == PermissionKind.NOTIFICATIONS) {
                    // Some Android builds may not show the first legacy-target
                    // dialog. Let the user cancel instead of inferring a
                    // denial from a timer or leaving the control stuck.
                    permissionDecision.finish(PermissionKind.NOTIFICATIONS)
                    updateOnboarding()
                    return
                }
                if (!permissionDecision.begin(PermissionKind.NOTIFICATIONS)) return
                updateOnboarding()
                requestNotificationPermission()
            }
            override fun continueToWorkspace() {
                permissionPrefs.edit()
                    .putBoolean("storage_at_continue", hasStoragePermission())
                    .putBoolean("notifications_at_continue", hasNotificationAccess())
                    .putBoolean("storage_attempted",
                        permissionPrefs.getBoolean("storage_attempted", false) || hasStoragePermission())
                    .putBoolean("notifications_attempted",
                        permissionPrefs.getBoolean("notifications_attempted", false) || hasNotificationAccess())
                    .commit()
                getSharedPreferences("opencodie_onboarding", MODE_PRIVATE).edit()
                    .putBoolean("completed", true).commit()
                firstRun = FirstRun.CONTINUE
                // The first notification channel is no longer created on Welcome:
                // legacy-target Android 13+ would otherwise prompt before the user acts.
                startForegroundService(Intent(this@MainActivity, OpenCodeService::class.java))
                updateOnboarding()
            }
            override fun retry() = retryCheckNow()
            override fun diagnostics() = showDiagnostics()
        })
        webView.visibility = View.INVISIBLE
        status.visibility = View.GONE
        root = FrameLayout(this).apply {
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

        if (PermissionFlow.shouldStartHost(completed)) {
            startForegroundService(Intent(this, OpenCodeService::class.java))
        }
        updateOnboarding()
        poll()
    }

    /** Reused after renderer termination; never reuse a WebView with a dead renderer. */
    private fun createWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.textZoom = (resources.configuration.fontScale * 100).toInt()
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
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

    private fun disposeWebView(view: WebView, rendererGone: Boolean = false) {
        (view.parent as? ViewGroup)?.removeView(view)
        if (!rendererGone) {
            view.stopLoading()
            view.clearHistory()
        }
        view.destroy()
    }

    /** WebView file:// access stays disabled. Android document-picker content://
     * URIs are separately authorized and need not require all-files access.
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
        // User-chosen content:// URIs from the system picker do NOT depend on
        // broad MANAGE_EXTERNAL_STORAGE access.
        webView.settings.allowContentAccess = true
        // Never enable file-URL inheritance: blocks file:// exfiltration.
        webView.settings.allowFileAccessFromFileURLs = false
        webView.settings.allowUniversalAccessFromFileURLs = false
    }

    /** Onboarding is presentation only; the existing service owns execution. */
    private fun updateOnboarding() {
        if (!::onboarding.isInitialized) return
        val startupSnapshot = OpenCodeHost.startup.snapshot()
        val hostReady = OpenCodeHost.error == null &&
            startupSnapshot.active in listOf(StartupStep.WEBVIEW, StartupStep.READY) &&
            OpenCodeHost.pairingUrl != null
        if (OnboardingState.canRevealWebView(
            firstRun == FirstRun.CONTINUE, webUiReady, hostReady, OpenCodeHost.error != null,
        )) {
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
            FirstRun.CONTINUE -> when {
                webPageFailed || startupSnapshot.failed || OpenCodeHost.error != null ->
                    OnboardingView.Screen.FAILED
                startupSnapshot.active == StartupStep.WEBVIEW ||
                    startupSnapshot.active == StartupStep.READY -> OnboardingView.Screen.CONNECTING
                else -> OnboardingView.Screen.PREPARING
            }
        }
        onboarding.show(
            screen = screen,
            preparationTitle = getString(R.string.onboarding_preparing_title),
            storagePermission = permissionDecision.presentation(
                PermissionKind.STORAGE,
                granted = hasStoragePermission(),
                everAsked = getSharedPreferences("opencodie_permissions", MODE_PRIVATE)
                    .getBoolean("storage_attempted", false) ||
                    getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE)
                        .getBoolean("storage_links_ready", false),
                retrySetup = hasStoragePermission() && storageSetupFailed,
            ),
            notificationsPermission = permissionDecision.presentation(
                PermissionKind.NOTIFICATIONS,
                granted = hasNotificationAccess(),
                everAsked = getSharedPreferences("opencodie_permissions", MODE_PRIVATE)
                    .getBoolean("notifications_attempted", false),
            ),
            failureMessage = if (screen == OnboardingView.Screen.FAILED) {
                val hostError = OpenCodeHost.error
                when {
                    hostError != null -> RuntimeHealth.sanitize(
                        getString(R.string.failed, "[${RuntimeHealth.failureCode(hostError)}] $hostError"),
                    )
                    webPageFailed -> getString(R.string.onboarding_web_failure)
                    else -> null
                }
            } else null,
            startup = startupSnapshot,
        )
    }

    private fun hasNotificationAccess(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.getNotificationChannel(OpenCodeService.CHANNEL_ID)
            ?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Only re-open the permission step when an actual previous grant was lost.
     * A user who continued while denied is never repeatedly interrupted.
     */
    private fun reviewRevokedPermissions() {
        if (!::onboarding.isInitialized || firstRun != FirstRun.CONTINUE) return
        val prefs = getSharedPreferences("opencodie_permissions", MODE_PRIVATE)
        val storageNow = hasStoragePermission()
        val notificationsNow = hasNotificationAccess()
        val storageWasAllowed = prefs.getBoolean("storage_at_continue", false)
        val notificationsWereAllowed = prefs.getBoolean("notifications_at_continue", false)
        if (PermissionFlow.shouldReviewRevocation(
            completed = true,
            previousStorage = storageWasAllowed,
            storageNow = storageNow,
            previousNotifications = notificationsWereAllowed,
            notificationsNow = notificationsNow,
        )) {
            firstRun = FirstRun.PERMISSIONS
        } else if ((storageNow && !storageWasAllowed) ||
            (notificationsNow && !notificationsWereAllowed)
        ) {
            // Capture grants changed outside the app, so later revocation can
            // be offered for review on a warm resume as well as a cold launch.
            prefs.edit()
                .putBoolean("storage_at_continue", storageNow)
                .putBoolean("notifications_at_continue", notificationsNow)
                .putBoolean("notifications_attempted",
                    notificationsNow || prefs.getBoolean("notifications_attempted", false))
                .apply()
        }
    }

    private fun requestNotificationPermission() {
        val manager = getSystemService(NotificationManager::class.java)
        val hasChannel = manager?.getNotificationChannel(OpenCodeService.CHANNEL_ID) != null
        when (PermissionFlow.notificationAction(
            androidApi = Build.VERSION.SDK_INT,
            granted = hasNotificationAccess(),
            channelExists = hasChannel,
        )) {
            NotificationAction.NONE -> openNotificationSettings()
            NotificationAction.CREATE_CHANNEL -> {
                // On targetSdk 28, Android 13+ shows the first notification
                // dialog on channel creation while an Activity is running.
                // Do not create it on Welcome or issue a competing request.
                manager?.createNotificationChannel(
                    NotificationChannel(
                        OpenCodeService.CHANNEL_ID,
                        getString(R.string.onboarding_notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                ) ?: openNotificationSettings()
            }
            NotificationAction.OPEN_SETTINGS -> openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        if (!startForResultSafely(intent, NOTIFICATION_SETTINGS_REQUEST)) {
            startForResultSafely(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")),
                NOTIFICATION_SETTINGS_REQUEST,
            ) { show(getString(R.string.onboarding_notification_settings_missing)) }
        }
    }

    /**
     * All failures hide the authenticated workspace until a new session is verified.
     * Temporary failures re-pair on bounded backoff; exhausted retries stay actionable.
     */
    private fun recordWebFailure(temporary: Boolean, message: String) {
        if (webUiReady) {
            restoredWorkspaceUrl = webView.url?.takeIf {
                OnboardingState.isWorkspaceUrl(it, OpenCodeHost.HOST, OpenCodeHost.PORT)
            }
        }
        webUiReady = false
        activeLoadFailed = true
        observedServerPairing = null
        webFailureReason = message
        webRetryAttempts++
        webPageFailed = !temporary || !WebRecoveryPolicy.canRetry(webRetryAttempts)
        nextPairingAttemptAtMs = SystemClock.elapsedRealtime() +
            WebRecoveryPolicy.delayMs(webRetryAttempts)
        if (webPageFailed) show(message)
        updateOnboarding()
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (view !== webView) return true
            val url = request.url
            if (url.scheme == "http" && url.host == OpenCodeHost.HOST &&
                url.port == OpenCodeHost.PORT
            ) return false
            if (request.isForMainFrame) openExternally(url)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (view !== webView || activeLoadFailed || webPageFailed ||
                !OnboardingState.isWorkspaceUrl(url, OpenCodeHost.HOST, OpenCodeHost.PORT)
            ) return
            val pairingAtCheck = OpenCodeHost.pairingUrl
            val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
            Thread {
                val result = OpenCodeHost.verifyWebSession(cookies)
                handler.post {
                    if (isDestroyed || isFinishing || view !== webView || activeLoadFailed ||
                        pairingAtCheck == null || pairingAtCheck != OpenCodeHost.pairingUrl ||
                        view.url != url
                    ) return@post
                    when (result) {
                        SessionValidation.VALID -> {
                            webUiReady = true
                            webRetryAttempts = 0
                            webPageFailed = false
                            webFailureReason = null
                            OpenCodeHost.startup.advance(StartupEvent.WEBVIEW_READY)
                            updateOnboarding()
                        }
                        SessionValidation.EXPIRED ->
                            recordWebFailure(true, getString(R.string.onboarding_session_expired))
                        SessionValidation.TRANSIENT ->
                            recordWebFailure(true, getString(R.string.onboarding_web_failure))
                    }
                }
            }.apply { isDaemon = true }.start()
            Thread { CookieManager.getInstance().flush() }.apply { isDaemon = true }.start()
        }

        override fun onReceivedHttpError(
            view: WebView, request: WebResourceRequest, response: WebResourceResponse,
        ) {
            if (view !== webView || !request.isForMainFrame || response.statusCode < 400 ||
                activeLoadFailed
            ) return
            val code = response.statusCode
            // A missing project route is a WebUI-level 404, not a broken server.
            if (code == 404 && webUiReady) return
            val recoverable = code == 401 || code == 403 ||
                WebRecoveryPolicy.isRecoverableHttp(code)
            recordWebFailure(recoverable, getString(R.string.onboarding_http_failure, code))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView || !request.isForMainFrame || activeLoadFailed) return
            recordWebFailure(true, getString(R.string.onboarding_web_failure))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view !== webView) {
                disposeWebView(view, rendererGone = true)
                return true
            }
            fileChooser?.onReceiveValue(null)
            fileChooser = null
            val now = SystemClock.elapsedRealtime()
            if (now - rendererCrashWindowMs > 60_000L) {
                rendererCrashWindowMs = now
                rendererCrashCount = 0
            }
            rendererCrashCount++
            Log.w("OpenCodieWeb", if (detail.didCrash()) "Renderer crashed" else "Renderer reclaimed")
            webUiReady = false
            disposeWebView(view, rendererGone = true)
            webView = createWebView()
            root.addView(webView, 0, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // After repeated crashes, never reload the same page in an endless loop.
            if (rendererCrashCount > 3) {
                recordWebFailure(false, getString(R.string.onboarding_renderer_failed))
            } else {
                recordWebFailure(true, getString(R.string.onboarding_renderer_restarting))
            }
            return true
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
            NOTIFICATION_SETTINGS_REQUEST -> {
                finishPermissionRequest(PermissionKind.NOTIFICATIONS)
                updateOnboarding()
            }
            STORAGE_SETTINGS_REQUEST -> {
                finishPermissionRequest(PermissionKind.STORAGE)
                if (hasStoragePermission()) {
                    if (File(filesDir, ".opencodie-ready").isFile) setupStorageLinks()
                } else {
                    show("Shared-file access is not enabled; you can try again later")
                }
                updateWebViewFileAccess()
                updateOnboarding()
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            finishPermissionRequest(PermissionKind.NOTIFICATIONS)
            updateOnboarding()
            return
        }
        if (requestCode != STORAGE_PERMISSION_REQUEST) return
        finishPermissionRequest(PermissionKind.STORAGE)
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
        if (OnboardingState.shouldAttemptStorageLinks(
            runtimeReady = true,
            permissionGranted = hasStoragePermission(),
            linksReady = preferences.getBoolean("storage_links_ready", false),
            inFlight = storageSetupStarted.get(),
            lastAttemptFailed = storageSetupFailed,
        )) setupStorageLinks()
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            // Prior to Android 11 this is an ordinary runtime permission;
            // once granted, the user changes it from the app details page.
            if (!startForResultSafely(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                STORAGE_SETTINGS_REQUEST,
            )) finishPermissionRequest(PermissionKind.STORAGE)
            return
        }
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        if (!startForResultSafely(specific, STORAGE_SETTINGS_REQUEST) &&
            !startForResultSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                STORAGE_SETTINGS_REQUEST)
        ) finishPermissionRequest(PermissionKind.STORAGE)
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                if (File(filesDir, ".opencodie-ready").isFile) setupStorageLinks()
                finishPermissionRequest(PermissionKind.STORAGE)
                updateOnboarding()
                return
            }
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (startForResultSafely(intent, STORAGE_SETTINGS_REQUEST)) return
            if (!startForResultSafely(fallback, STORAGE_SETTINGS_REQUEST)) {
                finishPermissionRequest(PermissionKind.STORAGE)
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
        if (!storageSetupStarted.compareAndSet(false, true)) return
        storageSetupFailed = false
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
                storageSetupFailed = false
                show(getString(R.string.onboarding_storage_connected))
            } catch (e: Exception) {
                storageSetupFailed = true
                getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE).edit()
                    .putBoolean("storage_links_ready", false).apply()
                Log.e("OpenCodieStorage", "Could not set up shared storage", e)
                show(getString(R.string.onboarding_storage_failed))
                handler.post {
                    if (!isDestroyed && !isFinishing && resumed && firstRun == FirstRun.CONTINUE) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.onboarding_storage_title)
                            .setMessage(R.string.onboarding_storage_failed)
                            .setPositiveButton(R.string.onboarding_retry_setup) { _, _ ->
                                if (hasStoragePermission()) setupStorageLinks()
                                else requestStorageAccess()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            } finally {
                storageSetupStarted.set(false)
                handler.post { if (!isDestroyed && !isFinishing) updateOnboarding() }
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
                        !webPageFailed &&
                        SystemClock.elapsedRealtime() >= nextPairingAttemptAtMs
                    ) {
                        // Invalidate an old page BEFORE the asynchronous session
                        // selector runs; a restarted host must never expose it.
                        webUiReady = false
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
        webPageFailed = false
        webUiReady = false
        nextPairingAttemptAtMs = SystemClock.elapsedRealtime()
        status.visibility = View.VISIBLE
        status.text = getString(R.string.reconnecting)
        refreshHealthPanel()
    }

    private fun showDiagnostics() {
        val error = OpenCodeHost.error ?: if (storageSetupFailed) {
            getString(R.string.onboarding_storage_failed)
        } else if (webPageFailed) {
            getString(R.string.onboarding_web_failure)
        } else OpenCodeHost.startup.snapshot().lastFailure ?: return
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
            if (isDestroyed || isFinishing) return@post
            if (onboarding.visibility == View.VISIBLE) {
                onboarding.setNotice(message)
            } else {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
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
        } else if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_download_url", pendingDownload)
        outState.putString("first_run_step", firstRun.name)
        permissionDecision.pending?.let {
            outState.putString("pending_permission", it.name)
            outState.putBoolean("pending_permission_focus_lost", permissionDecision.hadFocusLoss)
        }
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
        const val NOTIFICATION_SETTINGS_REQUEST = 6
        const val STORAGE_ACTION = "com.termux.app.reload_style"
    }
}

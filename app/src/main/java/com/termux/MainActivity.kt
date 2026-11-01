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
import android.webkit.WebResourceResponse
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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val storageSetupStarted = AtomicBoolean(false)
    @Volatile private var storageSetupFailed = false
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
        reviewRevokedPermissions()
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
        onboarding.refreshTheme()
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
        pendingDownload = savedInstanceState?.getString("pending_download_url")
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
                permissionPrefs.edit().putBoolean("notifications_attempted", true).apply()
                requestNotificationPermission()
                updateOnboarding()
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

        if (PermissionFlow.shouldStartHost(completed)) {
            startForegroundService(Intent(this, OpenCodeService::class.java))
        }
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
        val hostReady = OpenCodeHost.error == null &&
            OpenCodeHost.status == "Ready" && OpenCodeHost.pairingUrl != null
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
            FirstRun.CONTINUE -> if (webPageFailed) OnboardingView.Screen.FAILED else when (OnboardingState.phase(
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
            storageLinksReady = hasStoragePermission() &&
                getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE)
                    .getBoolean("storage_links_ready", false),
            storageSetupFailed = storageSetupFailed,
            storageAttempted = getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE)
                .getBoolean("storage_prompted", false),
            notificationsAttempted = getSharedPreferences("opencodie_permissions", MODE_PRIVATE)
                .getBoolean("notifications_attempted", false),
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
            notificationsGranted = hasNotificationAccess(),
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
            NotificationAction.NONE -> Unit
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

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "http" && url.host == OpenCodeHost.HOST && url.port == OpenCodeHost.PORT) return false
            openExternally(url)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            // A completed navigation does not establish an authenticated session.
            if (!webPageFailed && OnboardingState.isWorkspaceUrl(
                    url, OpenCodeHost.HOST, OpenCodeHost.PORT,
                )
            ) {
                val pairingAtCheck = OpenCodeHost.pairingUrl
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                Thread {
                    val validSession = runCatching {
                        OpenCodeHost.hasValidWebSession(cookies)
                    }.getOrDefault(false)
                    handler.post {
                        if (isDestroyed || isFinishing || webPageFailed ||
                            pairingAtCheck == null || pairingAtCheck != OpenCodeHost.pairingUrl ||
                            view.url != url
                        ) return@post
                        webUiReady = validSession
                        if (!validSession) {
                            webPageFailed = true
                            show(getString(R.string.onboarding_web_failure))
                        }
                        updateOnboarding()
                    }
                }.apply { isDaemon = true }.start()
                Thread { CookieManager.getInstance().flush() }.apply { isDaemon = true }.start()
            }
        }

        override fun onReceivedHttpError(
            view: WebView, request: WebResourceRequest, response: WebResourceResponse,
        ) {
            if (!request.isForMainFrame || response.statusCode < 400) return
            webPageFailed = true
            webUiReady = false
            observedServerPairing = null
            nextPairingAttemptAtMs = SystemClock.elapsedRealtime() + PAIR_RETRY_MS
            show(getString(R.string.onboarding_http_failure, response.statusCode))
            updateOnboarding()
        }

        /** The supervisor restarts a dead server; keep the user informed meanwhile. */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            status.visibility = View.VISIBLE
            status.text = getString(R.string.reconnecting)
            webPageFailed = true
            webUiReady = false
            show(getString(R.string.onboarding_web_failure))
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
            NOTIFICATION_SETTINGS_REQUEST -> updateOnboarding()
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
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) show(getString(R.string.onboarding_notification_denied))
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
        if (OnboardingState.shouldAttemptStorageLinks(
            runtimeReady = true,
            permissionGranted = hasStoragePermission(),
            linksReady = preferences.getBoolean("storage_links_ready", false),
            inFlight = storageSetupStarted.get(),
            lastAttemptFailed = storageSetupFailed,
        )) setupStorageLinks()
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
        } else return
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

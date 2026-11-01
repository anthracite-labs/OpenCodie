package com.termux

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Native onboarding only. The WebView and host remain independent.
 * Re-render only when the screen changes: state updates must not steal focus
 * or reset a partially scrolled permissions screen.
 */
internal class OnboardingView(context: Context, private val actions: Actions) : FrameLayout(context) {
    interface Actions {
        fun getStarted()
        fun requestStorage()
        fun requestNotifications()
        fun continueToWorkspace()
        fun retry()
        fun diagnostics()
    }

    enum class Screen { WELCOME, PERMISSIONS, PREPARING, CONNECTING, FAILED }
    private enum class TypeRole(val sp: Float) {
        HERO(30f), BRAND(18f), BODY(15f), ACTION(16f), LABEL(14f), SUPPORT(13f), CAPTION(12f)
    }

    private data class Presentation(
        val screen: Screen,
        val title: String,
        val storagePermission: PermissionPresentation,
        val notificationsPermission: PermissionPresentation,
        val failureMessage: String?,
        val startup: StartupSnapshot,
    )

    private val backgroundColor get() = context.getColor(R.color.onboarding_background)
    private val surfaceColor get() = context.getColor(R.color.onboarding_surface)
    private val accentColor get() = context.getColor(R.color.onboarding_accent)
    private val titleColor get() = context.getColor(R.color.onboarding_text)
    private val secondaryColor get() = context.getColor(R.color.onboarding_secondary)
    private val borderColor get() = context.getColor(R.color.onboarding_outline)
    private val buttonTextColor get() = context.getColor(R.color.onboarding_on_accent)
    private val noticeColor get() = context.getColor(R.color.onboarding_notice)

    private var previousScreen: Screen? = null
    private var lastPresentation: Presentation? = null
    private var headline: TextView? = null
    private var description: TextView? = null
    private var storageButton: Button? = null
    private var notificationsButton: Button? = null
    private var storageStatus: TextView? = null
    private var notificationStatus: TextView? = null
    private var startupDetail: TextView? = null
    private var startupRows: List<TextView> = emptyList()
    private var retryNotice: TextView? = null
    private var retryDiagnosticsButton: Button? = null
    private var noticeView: TextView? = null
    private var latestNotice: String? = null

    init {
        setBackgroundColor(backgroundColor)
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** uiMode is handled by MainActivity without recreating the WebView. */
    fun refreshTheme() {
        previousScreen = null
        lastPresentation?.let {
            show(it.screen, it.title, it.storagePermission, it.notificationsPermission,
                it.failureMessage, it.startup)
        }
    }

    /** Visible above the permission controls, and politely announced by TalkBack. */
    fun setNotice(message: String) {
        latestNotice = message
        noticeView?.apply {
            text = message
            visibility = View.VISIBLE
        }
        postDelayed({
            if (latestNotice == message) {
                latestNotice = null
                noticeView?.visibility = View.GONE
            }
        }, 6_000L)
    }

    fun show(
        screen: Screen,
        preparationTitle: String = context.getString(R.string.onboarding_preparing_title),
        storagePermission: PermissionPresentation =
            PermissionDecision().presentation(PermissionKind.STORAGE, false, false),
        notificationsPermission: PermissionPresentation =
            PermissionDecision().presentation(PermissionKind.NOTIFICATIONS, false, false),
        failureMessage: String? = null,
        startup: StartupSnapshot = StartupTimeline.describe(StartupEvent.PREPARING_RUNTIME),
    ) {
        val presentation = Presentation(screen, preparationTitle, storagePermission,
            notificationsPermission, failureMessage, startup)
        lastPresentation = presentation
        if (previousScreen == screen) {
            refreshDynamic(presentation)
            return
        }
        previousScreen = screen
        removeAllViews()
        setBackgroundColor(backgroundColor)
        headline = null
        description = null
        storageButton = null
        notificationsButton = null
        storageStatus = null
        notificationStatus = null
        startupDetail = null
        startupRows = emptyList()
        retryNotice = null
        retryDiagnosticsButton = null
        noticeView = null

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(mark(dp(38)))
        header.addView(text(context.getString(R.string.app_name), TypeRole.BRAND, titleColor, true).apply {
            setPaddingRelative(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(text(context.getString(when (screen) {
            Screen.WELCOME -> R.string.onboarding_welcome_tag
            Screen.PERMISSIONS -> R.string.onboarding_permissions_tag
            else -> R.string.onboarding_workspace_tag
        }), TypeRole.CAPTION, secondaryColor, true))
        column.addView(header)
        if (screen == Screen.PREPARING || screen == Screen.CONNECTING) {
            // Give real progress the space previously occupied by the duplicate logo.
            space(column, 28)
        } else {
            space(column, 32)
            column.addView(mark(dp(80)), LinearLayout.LayoutParams(dp(80), dp(80)))
            space(column, 28)
        }

        headline = text(titleFor(presentation), TypeRole.HERO, titleColor, true).apply {
            isAccessibilityHeading = true
            isFocusable = true
            setLineSpacing(dp(2).toFloat(), 1f)
        }.also { column.addView(it) }
        space(column, 12)
        description = text(descriptionFor(presentation), TypeRole.BODY, secondaryColor).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }.also { column.addView(it) }

        space(column, 16)
        noticeView = text("", TypeRole.SUPPORT, noticeColor).apply {
            background = rounded(surfaceColor, dp(12), borderColor)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }.also { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        latestNotice?.let {
            noticeView?.text = it
            noticeView?.visibility = View.VISIBLE
        }

        when (screen) {
            Screen.PERMISSIONS -> {
                space(column, 18)
                val storage = permissionItem(column,
                    context.getString(R.string.onboarding_storage_title),
                    context.getString(R.string.onboarding_storage_description),
                    actions::requestStorage)
                storageButton = storage.second.apply { tag = "permission-storage-action" }
                storageStatus = storage.first.apply { tag = "permission-storage-status" }
                space(column, 12)
                val notifications = permissionItem(column,
                    context.getString(R.string.onboarding_notification_title),
                    context.getString(R.string.onboarding_notification_description),
                    actions::requestNotifications)
                notificationsButton = notifications.second.apply { tag = "permission-notifications-action" }
                notificationStatus = notifications.first.apply { tag = "permission-notifications-status" }
            }
            Screen.PREPARING, Screen.CONNECTING -> {
                space(column, 22)
                val state = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(surfaceColor, dp(16), borderColor)
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                }
                startupDetail = text("", TypeRole.LABEL, titleColor, true).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }.also { state.addView(it) }
                space(state, 12)
                val activityIndicator = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(accentColor)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                state.addView(activityIndicator, LinearLayout.LayoutParams(-1, dp(4)))
                space(state, 20)
                val labels = listOf(
                    R.string.onboarding_step_runtime,
                    R.string.onboarding_step_packages,
                    R.string.onboarding_step_server,
                    R.string.onboarding_step_workspace,
                )
                startupRows = labels.map { label ->
                    text(context.getString(label), TypeRole.LABEL, secondaryColor).also {
                        state.addView(it)
                        space(state, 14)
                    }
                }
                retryNotice = text("", TypeRole.SUPPORT, noticeColor).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    visibility = View.GONE
                }.also { state.addView(it) }
                column.addView(state)
            }
            else -> Unit
        }

        space(column, 36)
        when (screen) {
            Screen.WELCOME -> primary(column, context.getString(R.string.onboarding_get_started), actions::getStarted)
            Screen.PERMISSIONS -> {
                primary(column, context.getString(R.string.onboarding_continue), actions::continueToWorkspace)
                space(column, 12)
                column.addView(text(context.getString(R.string.onboarding_settings_note),
                    TypeRole.CAPTION, secondaryColor).apply { gravity = Gravity.CENTER })
            }
            Screen.FAILED -> {
                column.addView(text(context.getString(
                    R.string.onboarding_stage_failed,
                    context.getString(when (presentation.startup.active) {
                        StartupStep.RUNTIME -> R.string.onboarding_step_runtime
                        StartupStep.PACKAGES -> R.string.onboarding_step_packages
                        StartupStep.SERVER -> R.string.onboarding_step_server
                        StartupStep.WEBVIEW, StartupStep.READY -> R.string.onboarding_step_workspace
                    }),
                ), TypeRole.SUPPORT, noticeColor))
                space(column, 20)
                primary(column, context.getString(R.string.onboarding_retry), actions::retry)
                space(column, 12)
                secondary(column, context.getString(R.string.onboarding_diagnostics), actions::diagnostics)
            }
            Screen.PREPARING, Screen.CONNECTING -> {
                column.addView(text(context.getString(R.string.onboarding_local_note),
                    TypeRole.CAPTION, secondaryColor).apply { gravity = Gravity.CENTER })
                space(column, 12)
                retryDiagnosticsButton = secondary(column,
                    context.getString(R.string.onboarding_diagnostics),
                    actions::diagnostics).apply { visibility = View.GONE }
            }
        }
        refreshDynamic(presentation)
        // Progress may move between PREPARING and CONNECTING without taking
        // screen-reader focus away from the person's current location.
        if (screen != Screen.PREPARING && screen != Screen.CONNECTING) {
            headline?.post {
                if (isAttachedToWindow && previousScreen == screen) headline?.requestFocus()
            }
        }
    }

    private fun titleFor(p: Presentation): String = when (p.screen) {
        Screen.WELCOME -> context.getString(R.string.onboarding_welcome_headline)
        Screen.PERMISSIONS -> context.getString(R.string.onboarding_permissions_headline)
        Screen.PREPARING -> p.title
        Screen.CONNECTING -> context.getString(R.string.onboarding_connecting_headline)
        Screen.FAILED -> context.getString(R.string.onboarding_failure_headline)
    }

    private fun descriptionFor(p: Presentation): String = p.failureMessage ?: context.getString(when (p.screen) {
        Screen.WELCOME -> R.string.onboarding_welcome_description
        Screen.PERMISSIONS -> R.string.onboarding_permissions_description
        Screen.PREPARING -> R.string.onboarding_preparing_description
        Screen.CONNECTING -> R.string.onboarding_connecting_description
        Screen.FAILED -> R.string.onboarding_failure_description
    })

    private fun refreshDynamic(p: Presentation) {
        val newTitle = titleFor(p)
        val newDescription = descriptionFor(p)
        if (headline?.text != newTitle) headline?.text = newTitle
        if (description?.text != newDescription) description?.text = newDescription

        fun updatePermission(
            statusView: TextView?, button: Button?, presentation: PermissionPresentation,
            actionDescription: Int,
        ) {
            val status = context.getString(when (presentation.status) {
                PermissionStatus.GRANT -> R.string.onboarding_not_configured
                PermissionStatus.REQUESTING -> R.string.onboarding_requesting
                PermissionStatus.ALLOWED -> R.string.onboarding_allowed
                PermissionStatus.NOT_ENABLED -> R.string.onboarding_not_enabled
            })
            if (statusView?.text != status) statusView?.text = status
            button?.apply {
                val nextLabel = context.getString(when (presentation.action) {
                    PermissionAction.GRANT -> R.string.onboarding_grant_access
                    PermissionAction.CHANGE -> R.string.onboarding_change_access
                    PermissionAction.RETRY_SETUP -> R.string.onboarding_retry_setup
                    PermissionAction.CANCEL -> R.string.onboarding_cancel_request
                })
                if (text != nextLabel) text = nextLabel
                contentDescription = context.getString(actionDescription, nextLabel)
                isEnabled = presentation.enabled
            }
        }
        updatePermission(storageStatus, storageButton, p.storagePermission,
            R.string.onboarding_storage_action)
        updatePermission(notificationStatus, notificationsButton, p.notificationsPermission,
            R.string.onboarding_notifications_action)

        val detail = context.getString(when (p.startup.event) {
            StartupEvent.PREPARING_RUNTIME -> R.string.onboarding_stage_preparing
            StartupEvent.VERIFYING_RUNTIME -> R.string.onboarding_stage_verifying
            StartupEvent.UNPACKING_RUNTIME -> R.string.onboarding_stage_unpacking
            StartupEvent.CONFIGURING_PACKAGES -> R.string.onboarding_stage_packages
            StartupEvent.VERIFYING_INSTALL -> R.string.onboarding_stage_checking
            StartupEvent.RUNTIME_READY, StartupEvent.STARTING_SERVER -> R.string.onboarding_stage_server
            StartupEvent.WAITING_FOR_SERVER -> R.string.onboarding_stage_waiting
            StartupEvent.SERVER_READY -> R.string.onboarding_stage_connecting
            StartupEvent.WEBVIEW_READY -> R.string.onboarding_stage_ready
        })
        val activeDetail = if (p.startup.retrying)
            context.getString(R.string.onboarding_retrying_server)
        else detail
        if (startupDetail?.text != activeDetail) startupDetail?.text = activeDetail
        retryNotice?.apply {
            val message = p.startup.lastFailure ?: context.getString(R.string.onboarding_server_stopped)
            val value = context.getString(R.string.onboarding_last_server_issue, message)
            if (text != value) text = value
            visibility = if (p.startup.retrying) View.VISIBLE else View.GONE
        }
        retryDiagnosticsButton?.visibility =
            if (p.startup.retrying) View.VISIBLE else View.GONE
        startupRows.forEachIndexed { index, row ->
            val label = context.getString(when (index) {
                0 -> R.string.onboarding_step_runtime
                1 -> R.string.onboarding_step_packages
                2 -> R.string.onboarding_step_server
                else -> R.string.onboarding_step_workspace
            })
            val glyph = when {
                index < p.startup.completedCount -> "✓"
                index == p.startup.completedCount && !p.startup.failed -> "●"
                index == p.startup.completedCount -> "!"
                else -> "○"
            }
            val value = "$glyph  $label"
            if (row.text != value) row.text = value
            row.setTextColor(if (index == p.startup.completedCount) accentColor else secondaryColor)
        }
    }

    private fun permissionItem(
        parent: LinearLayout,
        title: String,
        description: String,
        action: () -> Unit,
    ): Pair<TextView, Button> {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surfaceColor, dp(16), borderColor)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(title, TypeRole.ACTION, titleColor, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val control = Button(context).apply {
            textSize = TypeRole.SUPPORT.sp
            isAllCaps = false
            minHeight = dp(48)
            minWidth = dp(72)
            setTextColor(accentColor)
            background = RippleDrawable(ColorStateList.valueOf(0x22B9A4FF),
                rounded(surfaceColor, dp(12), borderColor), null)
            setOnClickListener { action() }
        }
        row.addView(control)
        box.addView(row)
        space(box, 8)
        val status = text(context.getString(R.string.onboarding_not_configured),
            TypeRole.SUPPORT, accentColor, true).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        box.addView(status)
        space(box, 4)
        box.addView(text(description, TypeRole.SUPPORT, secondaryColor))
        parent.addView(box)
        return status to control
    }

    private fun mark(size: Int): View = TextView(context).apply {
        text = ">_"
        textSize = if (size > dp(50)) 29f else 20f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(accentColor)
        background = rounded(surfaceColor, dp(16))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun primary(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(context).apply {
            text = label
            textSize = TypeRole.ACTION.sp
            isAllCaps = false
            minHeight = dp(54)
            setTextColor(buttonTextColor)
            background = RippleDrawable(ColorStateList.valueOf(0x33000000),
                rounded(accentColor, dp(14)), null)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, -2))
    }

    private fun secondary(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val button = Button(context).apply {
            text = label
            textSize = TypeRole.LABEL.sp
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(accentColor)
            background = rounded(surfaceColor, dp(14), borderColor)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, -2))
        return button
    }

    private fun text(value: String, role: TypeRole, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = role.sp
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = true
        }

    private fun rounded(color: Int, radius: Int, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (border != null) setStroke(dp(1), border)
        }

    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

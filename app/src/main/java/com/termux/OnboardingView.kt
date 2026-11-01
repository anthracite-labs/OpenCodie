package com.termux

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * A native, Kotlin-only first-launch experience. The WebView remains a separate
 * sibling; this screen never loads scripts, changes the OpenCode UI, or owns the
 * OpenCode process lifecycle.
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

    private val backgroundColor = Color.rgb(17, 17, 22)
    private val surfaceColor = Color.rgb(29, 29, 38)
    private val accentColor = Color.rgb(185, 164, 255)
    private val titleColor = Color.rgb(247, 246, 255)
    private val secondaryColor = Color.rgb(166, 164, 180)
    private val borderColor = Color.rgb(60, 57, 74)
    private var previousKey = ""

    init {
        setBackgroundColor(backgroundColor)
        isClickable = true // The user cannot interact with the hidden WebView.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun show(
        screen: Screen,
        preparationTitle: String = "Preparing your workspace",
        storageGranted: Boolean = false,
        notificationsGranted: Boolean = false,
    ) {
        val key = "$screen|$preparationTitle|$storageGranted|$notificationsGranted"
        if (previousKey == key) return
        previousKey = key
        removeAllViews()

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        scroll.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Header
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        top.addView(mark(dp(38)))
        top.addView(text("OpenCodie", 18f, titleColor, true).apply {
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text(when (screen) {
            Screen.WELCOME -> "WELCOME"
            Screen.PERMISSIONS -> "YOUR CHOICE"
            else -> "WORKSPACE"
        }, 11f, secondaryColor, true))
        column.addView(top)

        // Centre the main message on tall devices, but scroll naturally on small ones.
        column.addView(View(context), LinearLayout.LayoutParams(1, 0, 0.8f).apply {
            height = dp(30)
        })
        column.addView(mark(dp(80)), LinearLayout.LayoutParams(dp(80), dp(80)))
        space(column, 30)

        val headline = when (screen) {
            Screen.WELCOME -> "Code without limits."
            Screen.PERMISSIONS -> "Make it yours."
            Screen.PREPARING -> preparationTitle
            Screen.CONNECTING -> "Almost there."
            Screen.FAILED -> "Let's get you back on track."
        }
        column.addView(text(headline, 30f, titleColor, true).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        space(column, 14)
        val description = when (screen) {
            Screen.WELCOME -> "Your own AI-powered development workspace, right on your phone."
            Screen.PERMISSIONS -> "Choose how OpenCodie can work with your device. You can keep coding without shared-file access."
            Screen.PREPARING -> "We're setting up the tools your workspace needs. This can take longer the first time."
            Screen.CONNECTING -> "Your tools are ready. We're securely connecting the coding interface."
            Screen.FAILED -> "Your workspace didn't start. You can try again or inspect the diagnostics."
        }
        column.addView(text(description, 15f, secondaryColor).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        when (screen) {
            Screen.PERMISSIONS -> {
                space(column, 28)
                permissionItem(
                    column, "Shared device files",
                    "Optional. Read and save projects outside OpenCodie's private workspace.",
                    if (storageGranted) "Allowed" else "Allow",
                    storageGranted, actions::requestStorage,
                )
                space(column, 12)
                permissionItem(
                    column, "Notifications",
                    "Show status while your local coding server runs.",
                    if (notificationsGranted) "Allowed" else "Allow",
                    notificationsGranted, actions::requestNotifications,
                )
            }
            Screen.PREPARING, Screen.CONNECTING -> {
                space(column, 30)
                val state = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(surfaceColor, dp(16), borderColor)
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                }
                val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(accentColor)
                    contentDescription = "Workspace preparation in progress"
                }
                state.addView(spinner, LinearLayout.LayoutParams(dp(26), dp(26)))
                val phaseText = if (screen == Screen.CONNECTING) "Connecting to OpenCode" else "Setting up securely"
                state.addView(text(phaseText, 14f, titleColor, true).apply {
                    setPadding(dp(14), 0, 0, 0)
                })
                column.addView(state)
            }
            else -> Unit
        }

        column.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f).apply {
            height = dp(32)
        })
        when (screen) {
            Screen.WELCOME -> primary(column, "Get started", actions::getStarted)
            Screen.PERMISSIONS -> {
                primary(column, "Continue to workspace", actions::continueToWorkspace)
                space(column, 12)
                column.addView(text("Permissions can be changed later in Android Settings.", 12f, secondaryColor)
                    .apply { gravity = Gravity.CENTER })
            }
            Screen.FAILED -> {
                primary(column, "Try again", actions::retry)
                space(column, 12)
                secondary(column, "View diagnostics", actions::diagnostics)
            }
            Screen.PREPARING, Screen.CONNECTING -> {
                column.addView(text("Your files and existing work stay on your device.", 12f, secondaryColor)
                    .apply { gravity = Gravity.CENTER })
            }
        }
    }

    private fun permissionItem(
        parent: LinearLayout,
        title: String,
        description: String,
        actionLabel: String,
        granted: Boolean,
        action: () -> Unit,
    ) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surfaceColor, dp(16), borderColor)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(title, 16f, titleColor, true), LinearLayout.LayoutParams(0, -2, 1f))
        val control = text(actionLabel, 13f, if (granted) secondaryColor else accentColor, true).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            minimumWidth = dp(64)
            contentDescription = if (granted) "$title allowed" else "Allow $title"
            isEnabled = !granted
            isClickable = !granted
            isFocusable = !granted
            if (!granted) {
                background = RippleDrawable(ColorStateList.valueOf(0x22B9A4FF), rounded(0x302B41, dp(12)), null)
                setOnClickListener { action() }
            }
        }
        row.addView(control)
        box.addView(row)
        space(box, 4)
        box.addView(text(description, 13f, secondaryColor))
        parent.addView(box)
    }

    private fun mark(size: Int): View {
        return TextView(context).apply {
            text = ">_"
            textSize = if (size > dp(50)) 29f else 20f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(accentColor)
            background = rounded(0x292338, dp(16))
            contentDescription = "OpenCodie"
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun primary(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = text(label, 16f, backgroundColor, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(54)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(0x33000000),
                rounded(accentColor, dp(14)), null)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, dp(54)))
    }

    private fun secondary(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = text(label, 14f, accentColor, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size
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

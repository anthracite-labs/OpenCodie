package com.termux

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.hamcrest.CoreMatchers.`is`
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected Android regression tests. Run with a dedicated English-locale DEBUG
 * install (not production data). Notification-dialog tests require a clean install
 * and are run individually: the system displays the first legacy-target prompt
 * only once per newly installed package. CI compiles this suite, but does not run
 * it without an emulator/connected physical device.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingAndroidTest {
    private val app: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun showNativeFirstRun() {
        app.getSharedPreferences("opencodie_onboarding", Context.MODE_PRIVATE)
            .edit().putBoolean("completed", false).commit()
        app.getSharedPreferences("opencodie_permissions", Context.MODE_PRIVATE)
            .edit().putBoolean("notifications_attempted", false)
            .putBoolean("storage_attempted", false).commit()
    }

    private fun openPermissions(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        onView(withText(R.string.onboarding_get_started)).perform(click())
        onView(withText(R.string.onboarding_permissions_headline)).check(matches(isDisplayed()))
        return scenario
    }

    @Test fun firstRunLabelsAndRotationReflectRealState() {
        openPermissions().use { scenario ->
            val notifier = app.getSystemService(NotificationManager::class.java)
            val expected = if (notifier.areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 ||
                    app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED)
            ) R.string.onboarding_allowed else R.string.onboarding_not_configured
            onView(withTagValue(`is`("permission-notifications-status")))
                .check(matches(withText(expected)))
            scenario.recreate()
            onView(withText(R.string.onboarding_permissions_headline)).check(matches(isDisplayed()))
            onView(withTagValue(`is`("permission-notifications-status")))
                .check(matches(withText(expected)))
        }
    }

    @Test fun previouslyGrantedAndNowRevokedStorageReopensPermissionReview() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        assumeTrue(!android.os.Environment.isExternalStorageManager())
        app.getSharedPreferences("opencodie_onboarding", Context.MODE_PRIVATE)
            .edit().putBoolean("completed", true).commit()
        app.getSharedPreferences("opencodie_permissions", Context.MODE_PRIVATE)
            .edit().putBoolean("storage_at_continue", true)
            .putBoolean("storage_attempted", true)
            .putBoolean("notifications_at_continue", false).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(R.string.onboarding_permissions_headline))
                .check(matches(isDisplayed()))
            onView(withTagValue(`is`("permission-storage-status")))
                .check(matches(withText(R.string.onboarding_not_enabled)))
        }
    }

    @Test fun fileAccessSettingsReturnRefreshesNeutralStatus() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        assumeTrue(!android.os.Environment.isExternalStorageManager())
        openPermissions().use {
            onView(withTagValue(`is`("permission-storage-action"))).perform(click())
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.waitForIdle()
            device.pressBack()
            onView(withTagValue(`is`("permission-storage-status")))
                .check(matches(withText(R.string.onboarding_not_enabled)))
        }
    }

    @Test fun notificationPendingIsNeutralBeforeUserAnswers() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val manager = app.getSystemService(NotificationManager::class.java)
        assumeTrue(manager.getNotificationChannel(OpenCodeService.CHANNEL_ID) == null)
        assumeTrue(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_DENIED)

        openPermissions().use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val button = root.findViewWithTag<Button>("permission-notifications-action")
                val label = root.findViewWithTag<TextView>("permission-notifications-status")
                button.performClick()
                // Synchronous assertion BEFORE Android handles the dialog.
                assertEquals(activity.getString(R.string.onboarding_requesting), label.text.toString())
                assertEquals(false, button.isEnabled)
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assumeTrue("Requires the Android 13+ first-install notification prompt",
                device.wait(Until.hasObject(By.textContains("Allow OpenCodie")), 8_000L))
            device.pressBack() // System dismiss without Allow or Don't allow.
            onView(withTagValue(`is`("permission-notifications-status")))
                .check(matches(withText(R.string.onboarding_not_enabled)))
        }
    }

    @Test fun firstNotificationDenialUpdatesOnlyAfterDecision() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val manager = app.getSystemService(NotificationManager::class.java)
        assumeTrue(manager.getNotificationChannel(OpenCodeService.CHANNEL_ID) == null)
        assumeTrue(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_DENIED)
        openPermissions().use {
            onView(withTagValue(`is`("permission-notifications-action"))).perform(click())
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assumeTrue(device.wait(Until.hasObject(By.text("Don't allow")), 8_000L))
            device.findObject(By.text("Don't allow")).click()
            onView(withTagValue(`is`("permission-notifications-status")))
                .check(matches(withText(R.string.onboarding_not_enabled)))
            onView(withTagValue(`is`("permission-notifications-action")))
                .check(matches(withText(R.string.onboarding_grant_access)))
        }
    }

    @Test fun firstNotificationGrantIsAllowedAfterUserChoosesAllow() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val manager = app.getSystemService(NotificationManager::class.java)
        assumeTrue(manager.getNotificationChannel(OpenCodeService.CHANNEL_ID) == null)
        assumeTrue(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_DENIED)
        openPermissions().use {
            onView(withTagValue(`is`("permission-notifications-action"))).perform(click())
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assumeTrue(device.wait(Until.hasObject(By.text("Allow")), 8_000L))
            device.findObject(By.text("Allow")).click()
            onView(withTagValue(`is`("permission-notifications-status")))
                .check(matches(withText(R.string.onboarding_allowed)))
            onView(withTagValue(`is`("permission-notifications-action")))
                .check(matches(withText(R.string.onboarding_change_access)))
        }
    }

    @Test fun notificationRetryOpensSettingsAndRefreshesOnReturn() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val manager = app.getSystemService(NotificationManager::class.java)
        assumeTrue("Requires denied notifications and existing channel",
            manager.getNotificationChannel(OpenCodeService.CHANNEL_ID) != null &&
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_DENIED)
        openPermissions().use {
            onView(withTagValue(`is`("permission-notifications-action"))).perform(click())
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.waitForIdle()
            device.pressBack()
            onView(withTagValue(`is`("permission-notifications-status")))
                .check(matches(withText(R.string.onboarding_not_enabled)))
        }
    }
}

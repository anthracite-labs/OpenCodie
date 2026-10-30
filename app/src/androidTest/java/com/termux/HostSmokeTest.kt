package com.termux

import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup

/** Requires the genuine bundled runtime and an ARM64 Android device. */
@Suppress("DEPRECATION")
class HostSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun realServicePairsAndLoadsWebView() {
        assertTrue("ARM64 runtime requires an ARM64 device", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val deadline = System.nanoTime() + 240_000_000_000L
            while (OpenCodeHost.pairingUrl == null && OpenCodeHost.error == null && System.nanoTime() < deadline) {
                Thread.sleep(250)
            }
            assertNull(OpenCodeHost.error)
            assertNotNull("Local server must pair", OpenCodeHost.pairingUrl)
            Thread.sleep(3_000)
            instrumentation.runOnMainSync {
                val web = findWebView(activity.window.decorView)
                assertNotNull(web)
                assertTrue(web!!.url?.startsWith("http://127.0.0.1:4096/") == true)
                assertTrue(web.settings.javaScriptEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }
}

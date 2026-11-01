"""Contracts for the WebView-first Android shell restored after PR #26.

The overlay/first-run slices and PR #28's design assets should not return.
Runtime security and recovery improvements from later changes must remain.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "app/src/main/java/com/termux"


class MinimalShellContractTest(unittest.TestCase):
    def test_native_shell_does_not_cover_webui_navigation(self):
        activity = (ANDROID / "MainActivity.kt").read_text()
        self.assertIn("addView(webView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)", activity)
        self.assertNotIn('text = "Projects"', activity)
        self.assertNotIn('text = "About"', activity)
        self.assertNotIn("showProjectHub()", activity)
        self.assertNotIn("showSettings()", activity)
        self.assertNotIn("projectsLp", activity)
        self.assertNotIn("settingsLp", activity)

    def test_no_guided_onboarding_or_native_file_progress_dialogs(self):
        activity = (ANDROID / "MainActivity.kt").read_text()
        self.assertNotIn("maybeShowFirstRun()", activity)
        self.assertNotIn("showSavedDialog(", activity)
        self.assertNotIn("FileTransfer.", activity)
        self.assertIn("Intent.ACTION_CREATE_DOCUMENT", activity)
        self.assertIn("input.copyTo(output)", activity)

    def test_pr28_design_assets_and_ui_slice_modules_are_removed(self):
        self.assertFalse((ROOT / "design-system/opencodie").exists())
        self.assertFalse((ROOT / ".opencode/skills").exists())
        for name in ("FirstRun", "AppSettings", "FileTransfer", "ActivityCenter"):
            self.assertFalse((ANDROID / f"{name}.kt").exists())
            self.assertFalse(
                (ROOT / "app/src/test/java/com/termux" / f"{name}Test.kt").exists()
            )

    def test_security_and_recovery_are_preserved(self):
        activity = (ANDROID / "MainActivity.kt").read_text()
        host = (ANDROID / "OpenCodeHost.kt").read_text()
        self.assertIn("WebUiSessionSelector(", activity)
        self.assertIn("settings.allowFileAccess = false", activity)
        self.assertIn("settings.allowUniversalAccessFromFileURLs = false", activity)
        self.assertIn("OpenCodeHost.restart(applicationContext)", activity)
        self.assertIn("Build.VERSION_CODES.Q", activity)
        self.assertIn('outState.putString("pending_download_url", pendingDownload)', activity)
        self.assertIn("RuntimeHealth.sanitize(", activity)
        self.assertIn("startAfterSupervisorExit(", host)


if __name__ == "__main__":
    unittest.main()

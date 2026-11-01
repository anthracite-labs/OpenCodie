"""Source-level contracts for Android edge-case recovery paths (device tests remain necessary)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/termux"


def read(name):
    return (SRC / name).read_text()


class EdgeCaseRecoveryContractTest(unittest.TestCase):
    def test_delayed_supervisor_restart_cannot_be_dropped(self):
        host = read("OpenCodeHost.kt")
        self.assertIn("restartGeneration", host)
        self.assertIn("startAfterSupervisorExit(context)", host)
        self.assertIn("previous.join()", host)

    def test_retry_restarts_failed_supervisor(self):
        activity = read("MainActivity.kt")
        retry = activity.split("private fun retryCheckNow()", 1)[1].split("private fun showDiagnostics()", 1)[0]
        self.assertIn("OpenCodeHost.restart(applicationContext)", retry)

    def test_installer_status_is_allowlisted(self):
        host = read("OpenCodeHost.kt")
        self.assertIn("RuntimeHealth.installStatus(it)", host)
        self.assertNotIn("Log.i(TAG, line)", host)

    def test_git_output_is_drained_before_process_wait(self):
        activity = read("MainActivity.kt")
        runner = activity.split("private fun runGit(", 1)[1].split("private fun switchProject(", 1)[0]
        self.assertIn("drain.start()", runner)
        self.assertIn("Termux.environment(android.os.Process.myUid())", runner)
        self.assertLess(runner.index("drain.start()"), runner.index("proc.waitFor("))

    def test_pending_download_survives_activity_recreation(self):
        activity = read("MainActivity.kt")
        self.assertIn('savedInstanceState?.getString("pending_download_url")', activity)
        self.assertIn('outState.putString("pending_download_url", pendingDownload)', activity)
        self.assertIn("fileChooser?.onReceiveValue(null)", activity)

    def test_ready_marker_requires_executable_integrity(self):
        installer = read("RuntimeInstaller.kt")
        self.assertIn("verifyInstalledExecutables(prefix)", installer)
        self.assertIn("executable.canExecute()", installer)

    def test_readiness_deadline_is_monotonic(self):
        host = read("OpenCodeHost.kt")
        readiness = host.split("private fun awaitPairing(", 1)[1].split("fun freshPairingUrl(", 1)[0]
        self.assertIn("SystemClock.elapsedRealtime()", readiness)
        self.assertNotIn("System.currentTimeMillis()", readiness)


if __name__ == "__main__":
    unittest.main()

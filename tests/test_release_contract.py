"""Build-contract checks for the single Android release artifact."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BuildContractTest(unittest.TestCase):
    def test_version_tracks_opencode_runtime_lock(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('startsWith("OPENCODE_VERSION=")', gradle)
        self.assertIn("versionName = openCodeVersion", gradle)

    def test_single_signed_release_artifact(self):
        workflow = (ROOT / ".github/workflows/build-and-release.yml").read_text()
        self.assertIn("dist/OpenCodie.apk", workflow)
        self.assertIn("name: OpenCodie", workflow)
        self.assertIn("if: github.event_name != 'pull_request'", workflow)
        self.assertNotIn("path: app/build/outputs/apk/**/*.apk", workflow)
        self.assertIn('"$BT/apksigner" verify "$APK"', workflow)
        self.assertIn('gh release create "$tag"', workflow)
        self.assertIn('"$APK#OpenCodie.apk"', workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)

    def test_git_installed_with_bootstrap_and_safe_workspace(self):
        lock = (ROOT / "runtime.lock").read_text()
        host = (ROOT / "app/src/main/java/com/termux/OpenCodeHost.kt").read_text()
        self.assertIn("TERMUX_BOOTSTRAP_PACKAGES=ripgrep,libc++,git", lock)
        self.assertIn(".directory(workspace)", host)
        self.assertIn('File(Termux.HOME, "projects/workspace")', host)
        installer = (ROOT / "app/src/main/java/com/termux/RuntimeInstaller.kt").read_text()
        self.assertIn("selectDefaultMirror(prefix)", installer)
        self.assertIn('"etc/termux/chosen_mirrors"', installer)


if __name__ == "__main__":
    unittest.main()

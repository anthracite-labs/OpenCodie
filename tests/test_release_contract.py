"""Build-contract checks for the single Android release artifact."""
from pathlib import Path
import subprocess
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

    def test_mobile_shell_output_build_contract(self):
        build = (ROOT / "scripts/build-opencode.sh").read_text()
        patch = (ROOT / "patches/opencode-mobile-ui.patch").read_text()
        activity = (ROOT / "app/src/main/java/com/termux/MainActivity.kt").read_text()
        self.assertIn("apply_patch opencode-mobile-ui.patch", build)
        self.assertIn("function ConsoleOutput", patch)
        self.assertIn("Wrap: On", patch)
        self.assertIn("data-expanded", patch)
        self.assertIn("max-height: 75dvh", patch)
        self.assertIn('white-space: pre;', patch)
        self.assertIn("settings.textZoom =", activity)

    def test_storage_permission_is_requested_automatically_after_bootstrap(self):
        activity = (ROOT / "app/src/main/java/com/termux/MainActivity.kt").read_text()
        installer = (ROOT / "app/src/main/java/com/termux/RuntimeInstaller.kt").read_text()

        # Initial bootstrap's ready marker is written only after second-stage setup.
        self.assertIn('private const val READY_FILE = ".opencodie-ready"', installer)
        self.assertIn('File(filesDir, ".opencodie-ready").isFile', activity)
        self.assertIn('getSharedPreferences("opencodie_bootstrap", MODE_PRIVATE)', activity)
        self.assertIn('putBoolean("storage_prompted", true).commit()', activity)
        self.assertIn('if (!resumed || !File(filesDir, ".opencodie-ready").isFile) return', activity)
        self.assertIn('maybeRequestBootstrapStorage()', activity.split("private fun poll()", 1)[1])
        self.assertIn('requestStorageAccess()', activity.split("private fun maybeRequestBootstrapStorage()", 1)[1])

    def test_termux_setup_storage_bridge(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        activity = (ROOT / "app/src/main/java/com/termux/MainActivity.kt").read_text()
        env = (ROOT / "app/src/main/java/com/termux/Termux.kt").read_text()
        workflow = (ROOT / ".github/workflows/build-and-release.yml").read_text()

        self.assertIn('android.permission.WRITE_EXTERNAL_STORAGE', manifest)
        self.assertIn('android.permission.MANAGE_EXTERNAL_STORAGE', manifest)
        self.assertIn('android:requestLegacyExternalStorage="true"', manifest)
        self.assertIn('"com.termux.app.reload_style"', activity)
        self.assertIn('Context.RECEIVER_NOT_EXPORTED', activity)
        self.assertIn('Environment.isExternalStorageManager()', activity)
        self.assertIn('Manifest.permission.WRITE_EXTERNAL_STORAGE', activity)
        self.assertIn('File(Termux.HOME, "storage")', activity)
        self.assertIn('Files.isSymbolicLink', activity)
        self.assertIn('TERMUX__USER_ID" to (uid / 100_000).toString()', env)
        self.assertIn('"bin/am"', workflow)

    def test_scripts_with_shebang_are_executable_in_the_index(self):
        """A shebang script must be 100755 in git, not only in the worktree.

        A fresh clone restores the mode recorded in the index, so a 100644 entry
        turns every documented `scripts/foo.sh` invocation into "Permission
        denied" no matter how the file is chmodded locally.

        Regression: 0c15735 dropped scripts/build-opencode.sh from 100755 to
        100644 while changing its contents, and scripts/patch-opentui-loader.py
        was committed as 100644 from the start.
        """
        listing = subprocess.run(
            ["git", "ls-files", "-s", "--", "scripts/"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        self.assertTrue(listing.strip(), "expected tracked files under scripts/")

        offenders = []
        for line in listing.splitlines():
            meta, path = line.split("\t", 1)
            mode = meta.split()[0]
            script = ROOT / path
            if script.is_file() and script.read_bytes().startswith(b"#!"):
                if mode != "100755":
                    offenders.append(f"{path} is {mode}")

        self.assertEqual(offenders, [], "shebang scripts must be 100755 in git")

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

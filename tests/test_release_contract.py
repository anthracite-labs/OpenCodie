"""Build-contract checks for the single Android release artifact."""
from pathlib import Path
import json
import re
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


    def test_malformed_opencode_config_is_preserved_and_reported(self):
        """A corrupt opencode.json is kept for recovery, never silently dropped.

        OpenCode refuses to start on invalid JSON and the install log said
        nothing about why. A valid user-customised config (for example one with
        an mcp.servers block) must still be left completely untouched.
        """
        installer = (ROOT / "app/src/main/java/com/termux/RuntimeInstaller.kt").read_text()
        self.assertIn("import org.json.JSONObject", installer)
        # Valid configs merge missing defaults, never overwrite user values.
        self.assertIn("mergeMissingDefaults(config, log)", installer)
        self.assertIn("if (!current.has(key))", installer)
        # Malformed configs are copied aside before the default is restored.
        self.assertIn(".broken-", installer)
        self.assertIn("copyTo(backup, overwrite = false)", installer)
        self.assertIn("is not valid JSON", installer)

    def test_default_opencode_config_is_valid_json(self):
        """The embedded default must itself parse, or the installer writes the bug."""
        installer = (ROOT / "app/src/main/java/com/termux/RuntimeInstaller.kt").read_text()
        body = installer.split("private fun defaultOpenCodeConfig() =", 1)[1]
        literal = body.split('"""', 2)[1]
        text = literal.replace("${'$'}", "$").replace("${Termux.PREFIX}", "/data/data/com.termux/files/usr")
        parsed = json.loads(text)
        self.assertEqual(parsed["shell"], "/data/data/com.termux/files/usr/bin/bash")
        self.assertEqual(parsed["update"], "disable")
        self.assertEqual(parsed["permissions"][0]["effect"], "ask")


    def test_launcher_environment_defers_to_the_host(self):
        """The launcher fills gaps; the host's Termux.environment() always wins.

        The env contract lives in Termux.kt and every process the app starts
        inherits it. The launcher runs later, inside Termux, so it may add
        variables the host cannot know about -- but any variable both sides set
        must use the `${VAR:-default}` form, or a host value would be silently
        overridden and the two contracts would drift apart.
        """
        host = (ROOT / "app/src/main/java/com/termux/Termux.kt").read_text()
        launcher = (ROOT / "scripts/opencode-wrapper.sh").read_text()

        host_vars = set(re.findall(r'"([A-Z_][A-Z0-9_]*)" to', host))
        launcher_vars = set(
            re.findall(r"^(?:export )?([A-Z_][A-Z0-9_]*)=", launcher, re.MULTILINE)
        )
        self.assertTrue(host_vars, "expected variables in Termux.environment()")
        self.assertTrue(launcher_vars, "expected exports in the launcher")

        shared = sorted(host_vars & launcher_vars)
        self.assertTrue(shared, "launcher and host should agree on PREFIX at least")
        for name in shared:
            self.assertRegex(
                launcher,
                rf"(?m)^(?:export )?{name}=\"\$\{{{name}:-",
                f"{name} is set by both host and launcher, so it must defer",
            )

        # The launcher's fallback prefix must match the host prefix exactly.
        self.assertIn('const val PREFIX = "$ROOTFS/usr"', host)
        self.assertIn(
            'PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"',
            launcher,
        )

    def test_launcher_installs_as_the_sole_opencode_entrypoint(self):
        """The wrapper must stay the only way opencode is started.

        It supplies LD_LIBRARY_PATH and OPENCODE_DISABLE_FFF, which the host
        environment does not carry; launching opencode.bin directly would skip
        them and the bundled file watcher would fail to load libc++.
        """
        build = (ROOT / "scripts/build-opencode.sh").read_text()
        runtime = (ROOT / "scripts/build-runtime.sh").read_text()
        host = (ROOT / "app/src/main/java/com/termux/OpenCodeHost.kt").read_text()

        self.assertIn(
            'install -m 755 "$REPO_ROOT/scripts/opencode-wrapper.sh" "$DIST/opencode/opencode"',
            build,
        )
        self.assertIn('install -m 755 "$DIST/opencode/opencode" "$STAGE/bin/opencode"', runtime)
        # The host must exec the launcher, never the raw binary.
        self.assertIn("exec ${Termux.PREFIX}/bin/opencode serve", host)
        self.assertNotIn("opencode.bin", host)


    def test_webview_hardening_disables_file_access(self):
        activity = (ROOT / "app/src/main/java/com/termux/MainActivity.kt").read_text()
        # Secure default, content-only gating: file:// never enabled.
        self.assertIn("settings.allowFileAccess = false", activity)
        self.assertIn("settings.allowContentAccess = false", activity)
        self.assertIn("settings.allowFileAccessFromFileURLs = false", activity)
        self.assertIn("settings.allowUniversalAccessFromFileURLs = false", activity)
        self.assertIn("settings.safeBrowsingEnabled = true", activity)
        self.assertIn("private fun hasStoragePermission()", activity)
        self.assertIn("private fun updateWebViewFileAccess()", activity)
        self.assertIn("webView.settings.allowFileAccess = false", activity)
        self.assertIn("webView.settings.allowContentAccess = hasStoragePermission()", activity)
        self.assertIn("onMissing: () -> Unit = {}", activity)
        self.assertIn("updateWebViewFileAccess()", activity)
        self.assertIn("startForResultSafely", activity)

    def test_loopback_cleartext_only_with_manifest_permissions(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        config = (ROOT / "app/src/main/res/xml/network_security_config.xml").read_text()
        self.assertIn('android:networkSecurityConfig="@xml/network_security_config"', manifest)
        self.assertIn("FOREGROUND_SERVICE_DATA_SYNC", manifest)
        self.assertIn("POST_NOTIFICATIONS", manifest)
        self.assertIn('<domain includeSubdomains="false">127.0.0.1</domain>', config)
        self.assertIn('cleartextTrafficPermitted="false"', config)

    def test_shared_shell_helper_is_sourced(self):
        lib = (ROOT / "scripts/lib.sh").read_text()
        build = (ROOT / "scripts/build-opencode.sh").read_text()
        runtime = (ROOT / "scripts/build-runtime.sh").read_text()
        self.assertIn("checkout_pinned()", lib)
        self.assertIn('source "$REPO_ROOT/scripts/lib.sh"', build)
        self.assertIn('source "$REPO_ROOT/scripts/lib.sh"', runtime)
        self.assertIn('checkout_pinned "$TERMUX" "$TERMUX_BOOTSTRAP_COMMIT"', runtime)

    def test_session_leftovers_runtime_and_build(self):
        installer = (ROOT / "app/src/main/java/com/termux/RuntimeInstaller.kt").read_text()
        self.assertIn("private fun writeInstructions(", installer)
        self.assertIn("private fun writeDefaultConfig(", installer)
        host = (ROOT / "app/src/main/java/com/termux/OpenCodeHost.kt").read_text()
        self.assertIn("attempts++", host)
        self.assertIn("if (attempts < 20) 500 else 1_000", host)
        activity = (ROOT / "app/src/main/java/com/termux/MainActivity.kt").read_text()
        self.assertIn("}.apply { isDaemon = true }.start()", activity)
        self.assertIn("requestNotificationPermission()", activity)
        self.assertIn("NOTIFICATION_PERMISSION_REQUEST", activity)
        lock = (ROOT / "runtime.lock").read_text()
        self.assertIn("PTY_MUSL_SHA256=", lock)
        self.assertIn("PARCEL_WATCHER_SHA256=", lock)
        build = (ROOT / "scripts/build-opencode.sh").read_text()
        self.assertIn('expected_sha', build)
        self.assertIn('sha256sum "$tmp"', build)
        workflow = (ROOT / ".github/workflows/build-and-release.yml").read_text()
        self.assertIn("Cache Bun toolchain", workflow)
        self.assertIn("Cache Cargo registry", workflow)
        self.assertIn("Cache Android NDK", workflow)
        self.assertIn("scripts/lib.sh", workflow)
        self.assertIn("build-tools;37.0.0", workflow)
        self.assertNotIn("build-tools;36.0.0", workflow)


    def test_config_merge_preserves_mcp(self):
        installer = (ROOT / "app/src/main/java/com/termux/RuntimeInstaller.kt").read_text()
        self.assertIn("private fun mergeMissingDefaults(", installer)
        self.assertIn('for (key in listOf("\\$schema", "shell"))', installer)
        self.assertIn("private fun atomicWriteText(", installer)
        self.assertIn(".tmp-", installer)
        self.assertIn("renameTo(target)", installer)
        self.assertIn(".pre-merge-", installer)
        # Policy keys untouched: omission keeps upstream default.
        self.assertNotIn('"permissions"', installer.split("private fun mergeMissingDefaults(", 1)[1].split("private fun atomicWriteText(", 1)[0].replace('"$schema"', '').replace('"shell"', ''))
        # Malformed path still backs up separately.
        self.assertIn(".broken-", installer)

    def test_agents_md_single_source_locked(self):
        installer = (ROOT / "app/src/main/java/com/termux/RuntimeInstaller.kt").read_text()
        for bullet in [
            "Use pkg, never sudo",
            "pkg install -y PACKAGE",
            "never initialize Git in ~ or ~/projects",
            "verify its eventual outcome",
        ]:
            self.assertIn(bullet, installer)


if __name__ == "__main__":
    unittest.main()

"""Fast regression checks for the fixed Android build contract."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AndroidBuildContract(unittest.TestCase):
    def test_stable_compile_sdk_and_termux_identity(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn("compileSdk = 37", gradle)
        self.assertIn('applicationId = "com.termux"', gradle)
        self.assertIn("targetSdk = 28", gradle)
        self.assertIn("minSdk = 29", gradle)
        self.assertNotIn("OPENCODIE_COMPILE_SDK", gradle)

    def test_ci_installs_exact_sdk_instead_of_preview(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn('"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install "platforms;android-37"', workflow)
        self.assertIn('test -s "$ANDROID_HOME/platforms/android-37/android.jar"', workflow)
        self.assertIn('BT="$ANDROID_HOME/build-tools/36.0.0"', workflow)
        self.assertNotIn("sort -n | tail -1", workflow)


if __name__ == "__main__":
    unittest.main()

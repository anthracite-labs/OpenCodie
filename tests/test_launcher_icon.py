"""Verify the OpenCodie Android launcher branding resources are wired correctly."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class LauncherIconTest(unittest.TestCase):
    def test_manifest_references_adaptive_launcher(self):
        manifest = ET.parse(ROOT / "app" / "src" / "main" / "AndroidManifest.xml")
        application = manifest.getroot().find("application")
        self.assertIsNotNone(application)
        self.assertEqual(application.get(ANDROID + "icon"), "@mipmap/ic_launcher")
        self.assertEqual(application.get(ANDROID + "roundIcon"), "@mipmap/ic_launcher")

    def test_adaptive_icon_layers_and_artwork(self):
        icon = ET.parse(RES / "mipmap-anydpi-v26" / "ic_launcher.xml").getroot()
        self.assertEqual(icon.tag, "adaptive-icon")
        self.assertEqual(
            icon.find("background").get(ANDROID + "drawable"),
            "@drawable/opencodie_launcher_art",
        )
        self.assertEqual(
            icon.find("foreground").get(ANDROID + "drawable"),
            "@drawable/ic_launcher_foreground",
        )
        ET.parse(RES / "drawable" / "ic_launcher_foreground.xml")
        artwork = (RES / "drawable-nodpi" / "opencodie_launcher_art.webp").read_bytes()
        self.assertGreater(len(artwork), 4000)
        self.assertEqual(artwork[:4], b"RIFF")
        self.assertEqual(artwork[8:12], b"WEBP")


if __name__ == "__main__":
    unittest.main()

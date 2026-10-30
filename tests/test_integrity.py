"""Altered inputs must never replace a usable cached artifact."""
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from fetch_verified import download
from verify_termux_index import verify_index


class IntegrityTest(unittest.TestCase):
    def test_package_index_must_match_signed_release_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            index = Path(directory) / "Packages"
            release = "Header\nSHA256:\n ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad 3 main/binary-aarch64/Packages\n"
            index.write_bytes(b"abc")
            verify_index(index, release, "aarch64")
            index.write_bytes(b"bad")
            with self.assertRaisesRegex(ValueError, "integrity"):
                verify_index(index, release, "aarch64")

    def test_tampered_bytes_preserve_old_artifact_and_remove_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / "archive"
            dest.write_bytes(b"working")
            spec = {"url": "https://example.test/input", "sha256": "0" * 64}
            with self.assertRaisesRegex(ValueError, "integrity"):
                download(spec, dest, opener=lambda *a, **k: io.BytesIO(b"tampered"))
            self.assertEqual(dest.read_bytes(), b"working")
            self.assertEqual(list(Path(directory).iterdir()), [dest])

    def test_verified_download_uses_independent_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / "archive"
            spec = {"url": "https://example.test/input", "sha256":
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}
            download(spec, dest, opener=lambda *a, **k: io.BytesIO(b"abc"))
            self.assertEqual(dest.read_bytes(), b"abc")

    def test_unpinned_artifacts_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                download({"url": "https://example.test/input"}, Path(directory) / "archive")

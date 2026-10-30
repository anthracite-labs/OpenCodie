#!/usr/bin/env python3
"""Authenticate a Packages index using keys from the pinned Termux source commit."""
import hashlib
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path


def verify_index(index, release, architecture):
    section = release.split("\nSHA256:\n", 1)[1].split("\n\n", 1)[0]
    wanted = f"main/binary-{architecture}/Packages"
    entries = [line.split() for line in section.splitlines() if line.startswith(" ")]
    expected = next((row for row in entries if len(row) == 3 and row[2] == wanted), None)
    if expected is None or not re.fullmatch("[0-9a-f]{64}", expected[0]):
        raise ValueError("No signed SHA256 for package index")
    data = Path(index).read_bytes()
    if len(data) != int(expected[1]) or hashlib.sha256(data).hexdigest() != expected[0]:
        raise ValueError("Signed package index integrity mismatch")


if __name__ == "__main__":
    index, origin, architecture, keys, evidence = sys.argv[1:]
    with tempfile.TemporaryDirectory() as directory:
        signed = Path(directory) / "InRelease"
        verified = Path(directory) / "Release"
        with urllib.request.urlopen(origin + "/dists/stable/InRelease", timeout=30) as response:
            signed.write_bytes(response.read(4 * 1024 * 1024 + 1))
        if signed.stat().st_size > 4 * 1024 * 1024:
            raise ValueError("Signed metadata too large")
        keyrings = sorted(Path(keys).glob("*.gpg"))
        if not keyrings:
            raise ValueError("Pinned Termux keyring is absent")
        subprocess.run(["gpgv", "--homedir", directory,
                        *[arg for key in keyrings for arg in ("--keyring", str(key))],
                        "--output", str(verified), str(signed)], check=True)
        verify_index(index, verified.read_text(), architecture)
        target = Path(evidence)
        target.mkdir(parents=True, exist_ok=True)
        (target / "termux-InRelease").write_bytes(signed.read_bytes())
        (target / f"termux-Packages-{architecture}").write_bytes(Path(index).read_bytes())

#!/usr/bin/env python3
"""Download pinned input bytes; commit to the cache only after digest verification."""
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.request
from pathlib import Path


def download(spec, destination, opener=urllib.request.urlopen):
    algorithms = [name for name in ("sha256", "sha512", "sha1") if name in spec]
    if len(algorithms) != 1 or not spec["url"].startswith("https://"):
        raise ValueError("Artifact requires one independently pinned integrity digest and HTTPS")
    algorithm = algorithms[0]
    digest = hashlib.new(algorithm)
    if not re.fullmatch(r"[0-9a-f]{%d}" % (digest.digest_size * 2), spec[algorithm]):
        raise ValueError("Invalid pinned digest")
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".download-", dir=destination.parent)
    try:
        with os.fdopen(descriptor, "wb") as output, opener(spec["url"], timeout=60) as response:
            size = 0
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > 2 * 1024**3:
                    raise ValueError("Artifact size limit exceeded")
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != spec[algorithm]:
            raise ValueError("Artifact integrity mismatch; refusing activation")
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)


if __name__ == "__main__":
    manifest = json.loads(Path(__file__).with_name("source-integrity.json").read_text())
    download(manifest[sys.argv[1]], Path(sys.argv[2]))

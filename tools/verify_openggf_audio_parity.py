#!/usr/bin/env python3
"""Compile original pinned OpenGGF and extracted sources separately; compare playback.

Requires JDK 25, a local upstream Git clone containing the locked commit, and an
explicit disposable scratch directory. No game build, ROM, audio device or network
is used. The only reference-only stub resolves an unused AudioManager import;
the original upstream sources themselves are not patched.
"""

import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

from openggf_audio import SnapshotError, check, upstream_bytes


def run_probe(sources, output, probe):
    output.mkdir()
    subprocess.run(["javac", "--release", "25", "-d", str(output),
                    *map(str, sources), str(probe)], check=True)
    return subprocess.check_output(["java", "-cp", str(output), "S3kParityProbe"], text=True)


def verify(root, source, scratch):
    lock = check(root, source)
    probe = root / "tools/parity/S3kParityProbe.java"
    # Explicit scratch avoids RAM-backed /tmp. Context cleanup owns only this dir.
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="audio-parity-", dir=scratch) as temporary:
        workspace = Path(temporary)
        originals = []
        vendored = []
        for entry in lock["files"]:
            path = workspace / "original" / entry["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(upstream_bytes(source, lock["revision"], entry["path"]))
            originals.append(path)
            vendored.append(root / "openggf-audio" / entry["path"])
        stub = workspace / "original/com/openggf/audio/AudioManager.java"
        stub.parent.mkdir(parents=True, exist_ok=True)
        stub.write_text("package com.openggf.audio; public final class AudioManager {}\n")
        # Compile the same authored adapter against each independent core as well.
        adapter = root / "headless/src/main/java/com/opensmpsdeck/headless"
        adapters = [adapter / f"{name}.java" for name in ("S3kBinary", "S3kDemo", "S3kRenderer")]
        reference = run_probe(originals + [stub] + adapters, workspace / "reference-classes", probe)
        actual = run_probe(vendored + adapters, workspace / "vendored-classes", probe)
        if actual != reference:
            raise SnapshotError(f"playback parity mismatch\noriginal:\n{reference}\nvendored:\n{actual}")
        print(f"OpenGGF source-extraction parity PASS at {lock['revision']}")
        print(actual, end="")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    args = parser.parse_args()
    try:
        verify(Path(__file__).resolve().parents[1], args.source, args.scratch)
    except (SnapshotError, OSError, subprocess.CalledProcessError) as error:
        print(f"OpenGGF parity verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

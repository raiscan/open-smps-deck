#!/usr/bin/env python3
"""Compile original pinned OpenGGF and extracted sources separately; compare playback.

Requires JDK 25, a local upstream Git clone containing the locked commit, and an
explicit disposable scratch directory. No game build, ROM, audio device or network
is used. Reference-only stubs resolve non-audio types; the original upstream
sources themselves are not patched.
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
        services = workspace / "original/com/openggf/level/objects/ObjectServices.java"
        services.parent.mkdir(parents=True)
        services.write_text("package com.openggf.level.objects; public interface ObjectServices { "
                            "com.openggf.data.RomByteReader romReader() throws java.io.IOException; }\n")
        # The upstream PSG fallback is needed only to compile the independent
        # sources. The vendored loader reads envelopes from the user's ROM.
        fallback_source = "src/main/java/com/openggf/game/sonic2/audio/smps/Sonic2PsgEnvelopes.java"
        fallback = workspace / "original" / fallback_source
        fallback.write_bytes(upstream_bytes(source, lock["revision"], fallback_source))
        # Compile the same authored adapter against each independent core as well.
        adapter = root / "headless/src/main/java/com/opensmpsdeck/headless"
        adapters = [adapter / f"{name}.java" for name in ("S3kBinary", "S3kDemo", "S3kRenderer", "NativeBankAnalysis")]
        # Renderer resource records and the bounded envelope grammar use only
        # the JDK. Keep this independent bridge free of Gson/project persistence.
        project = root / "project-core/src/main/java/com/opensmpsdeck/project"
        adapters += [project / f"{name}.java" for name in (
            "AssetOrigin", "CompiledProject", "DacSample", "Envelope", "EnvelopeProgram",
            "NativeProgram", "StockRef", "Voice",
        )]
        reference = run_probe(originals + [stub, services, fallback] + adapters, workspace / "reference-classes", probe)
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

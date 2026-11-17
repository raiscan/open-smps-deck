#!/usr/bin/env python3
"""Exercise packaged native-DPI drawing and physical pointer picking in isolated Xvfb.

Build `mvn -pl studio -am package` first. No windows touch the shared desktop.
Screenshots remain in studio/target/qa; all mutable fixture files use --scratch.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scratch", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    screenshots = root / "studio/target/qa"
    screenshots.mkdir(parents=True, exist_ok=True)
    args.scratch.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(str(root / path) for path in (
        "studio/target/opensmpsdeck-studio-0.1.0-SNAPSHOT.jar", "studio/target/test-classes"))
    cases = [
        ("100-native", "1", "147456", ["-Dglass.gtk.uiScale=1"], {}),
        ("150-desktop", "1.5", "147456", [], {}),
        ("160-desktop", "1.6", "157286", [], {}),
        ("200-explicit-property", "2", "147456", ["-Dglass.gtk.uiScale=2"], {}),
        ("200-explicit-environment", "2", "147456", [], {"glass.gtk.uiScale": "2"}),
        ("200-explicit-gdk", "2", "147456", [], {"GDK_SCALE": "2"}),
    ]
    with tempfile.TemporaryDirectory(prefix="studio-dpi-", dir=args.scratch) as temporary:
        workspace = Path(temporary)
        for name, expected, dpi, flags, overrides in cases:
            case = workspace / name
            gtk = case / "gtk-3.0"
            gtk.mkdir(parents=True)
            (gtk / "settings.ini").write_text(f"[Settings]\ngtk-xft-dpi={dpi}\n")
            source = case / "song.json"
            shutil.copyfile(root / "examples/lantern-walk.smps.json", source)
            env = os.environ.copy()
            for key in ("GDK_SCALE", "GDK_DPI_SCALE", "glass.gtk.uiScale"):
                env.pop(key, None)
            env.update(overrides)
            env["XDG_CONFIG_HOME"] = str(case)
            print(f"Checking {name}", flush=True)
            subprocess.run([
                "xvfb-run", "-a", "-s", "-screen 0 1920x1080x24 -dpi 96", "java", *flags,
                "-cp", classpath, "com.opensmpsdeck.studio.DpiStudioSmoke", expected,
                str(source), str(screenshots / f"dpi-{name}.png"),
            ], env=env, check=True, timeout=25)
    print("Native DPI and pointer smoke: all six cases PASS")


if __name__ == "__main__":
    main()

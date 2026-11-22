"""Command-line scenario runner for the BizHawk capture executable."""
import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
from fractions import Fraction
from pathlib import Path


GAMEPLAY_MODES = {
    "s3k": {0x0C, 0x28, 0x38, 0x4C},  # OpenGGF docs/skdisasm/sonic3k.asm:5628-5658
    "s1": {0x0C},                       # docs/s1disasm/sonic.asm:446-449, 2273
    "s2": {0x0C, 0x1C, 0x24, 0x28},    # docs/s2disasm/s2.constants.asm:466-475
}
TITLE_MODE = 0x04


def input_hashes(path):
    sha1 = hashlib.sha1(usedforsecurity=False)
    sha256 = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha1.update(block)
            sha256.update(block)
    return {"name": path.name, "sha1": sha1.hexdigest(), "sha256": sha256.hexdigest()}


def nonnegative(value):
    number = int(value, 0)
    if number < 0:
        raise argparse.ArgumentTypeError("expected a nonnegative integer")
    return number


def press_arg(value):
    frame, separator, button = value.partition(":")
    if not separator or button not in ("Up", "Down", "Left", "Right", "A", "B", "C", "Start"):
        raise argparse.ArgumentTypeError("expected FRAME:BUTTON with a Genesis three-button pad input")
    return nonnegative(frame), button


def watch_arg(value):
    match = re.fullmatch(r"([A-Za-z][A-Za-z0-9_]*)=(68k|z80):([^:]+)(?::([124]))?", value)
    if not match:
        raise argparse.ArgumentTypeError("expected NAME=68k|z80:OFFSET[:1|2|4]")
    name, domain, address, width = match.groups()
    address, width = nonnegative(address), int(width or 1)
    if address + width > (0x10000 if domain == "68k" else 0x2000):
        raise argparse.ArgumentTypeError("watch falls outside the RAM domain")
    return name, domain, address, width


def pointer_arg(value):
    fields = value.split(":")
    if len(fields) != 3:
        raise argparse.ArgumentTypeError("expected TRACK:MIN:MAX")
    track, lower, upper = fields
    lower, upper = nonnegative(lower), nonnegative(upper)
    if not 0 < lower <= upper <= 0xFFFFFF:
        raise argparse.ArgumentTypeError("expected 0 < MIN <= MAX <= 0xFFFFFF")
    return track, lower, upper


def parse_args(description=__doc__, *, libretro=False):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--profile", required=True, choices=GAMEPLAY_MODES)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--reference", type=Path, help="hash a reference WAV for the report")
    if libretro:
        core = os.environ.get("LIBRETRO_CORE")
        parser.add_argument("--core", required=not core, default=core, type=Path, metavar="PATH")
        parser.add_argument("--ram-order", choices=("big", "word-native"),
                            help="override 68K work RAM byte order")
    else:
        parser.add_argument("--build", required=True, type=Path, metavar="DIR")
    parser.add_argument("--out", required=True, type=Path, metavar="DIR")
    parser.add_argument("--frames", required=True, type=nonnegative)
    parser.add_argument("--press", action="append", default=[], type=press_arg, metavar="FRAME:BUTTON")
    parser.add_argument("--watch", action="append", default=[], type=watch_arg, metavar="NAME=CPU:OFFSET[:SIZE]")
    parser.add_argument("--video", action="store_true", help="also capture video using ffmpeg")
    parser.add_argument("--title-song-from", type=nonnegative, metavar="FRAME")
    parser.add_argument("--bank", type=nonnegative)
    parser.add_argument("--song", type=nonnegative, help="S2 zCurSong ID")
    parser.add_argument("--pointer", type=pointer_arg, metavar="TRACK:MIN:MAX")
    parser.add_argument("--no-demo", nargs="?", type=float, const=60.0, metavar="SECONDS")
    parser.add_argument("--start-game", nargs=2, type=nonnegative, metavar=("PRESS_FRAME", "WINDOW"))
    args = parser.parse_args()

    if not 0 < args.frames < 2**31 - 1:
        parser.error("--frames must be positive and below the emulator frame counter limit")
    if any(frame >= args.frames for frame, _ in args.press):
        parser.error("--press frame must be inside the capture")
    if args.no_demo is not None and (not math.isfinite(args.no_demo) or args.no_demo <= 0):
        parser.error("--no-demo needs a positive finite duration")
    if args.start_game is not None:
        frame, window = args.start_game
        if (frame, "Start") not in args.press or window == 0:
            parser.error("--start-game requires a matching --press FRAME:Start and a positive window")

    tracks = ({"fm6_dac", "fm1", "fm2", "fm3", "fm4", "fm5"} if args.profile == "s3k"
              else {"dac", "fm1", "fm2", "fm3", "fm4", "fm5", "fm6"})
    tracks.update(("psg1", "psg2", "psg3"))
    if args.pointer and (args.pointer[0] not in tracks or
                         (args.profile != "s1" and args.pointer[2] > 0xFFFF)):
        parser.error("--pointer track or range is invalid for this profile")
    if args.bank is not None and (args.profile == "s1" or args.bank > 255):
        parser.error("--bank is a byte supported by S2 and S3K")
    if args.song is not None and (args.profile != "s2" or args.song > 255):
        parser.error("--song is a byte supported by S2")
    has_identity = any(value is not None for value in (args.bank, args.song, args.pointer))
    if (args.title_song_from is not None) != has_identity:
        parser.error("--title-song-from and at least one of --bank, --song, --pointer are required together")
    if args.title_song_from is not None and args.title_song_from >= args.frames:
        parser.error("--title-song-from must be inside the capture")
    reserved = {"frame", "buttons", "mode", "demo_flag", "demo_timer", "bank", "song",
                "sound_request", "voice_ptr"} | {track + "_ptr" for track in tracks}
    names = [watch[0] for watch in args.watch]
    if len(set(names)) != len(names) or reserved.intersection(names):
        parser.error("--watch names must be unique and cannot shadow built-in columns")
    return args


def read_log(path):
    with path.open() as log:
        fps = Fraction(log.readline().removeprefix("# fps=").strip())
        frames = [
            {key: int(value, 10 if key == "frame" else 16) if value else None
             for key, value in row.items() if key != "buttons"}
            for row in csv.DictReader(log)
        ]
    return fps, frames


def evaluate(args, frames, fps, unavailable=()):
    def timing(frame):
        return None if frame is None else {"frame": frame, "seconds": float(frame / fps)}

    def is_title(row):
        return row["mode"] == TITLE_MODE and not row["demo_flag"]

    first_title = next((row["frame"] for row in frames if is_title(row)), None)
    first_demo = next((row["frame"] for row in frames
                       if row["demo_flag"] or row["mode"] & 0x7F == 0x08), None)

    checks = {}
    first_song = None
    if args.title_song_from is not None:
        identity_fields = {"bank": args.bank, "song": args.song}
        if args.pointer:
            identity_fields[args.pointer[0] + "_ptr"] = args.pointer
        missing = sorted(key for key in unavailable if identity_fields.get(key) is not None)

        def matches(row):
            if args.bank is not None and row["bank"] != args.bank:
                return False
            if args.song is not None and row["song"] != args.song:
                return False
            if args.pointer:
                track, low, high = args.pointer
                pointer = row[track + "_ptr"]
                return pointer is not None and low <= pointer <= high
            return True

        start = args.title_song_from
        # The selected frame must be on title. Compare through that same title visit.
        end = next((row["frame"] for row in frames[start:] if not is_title(row)), len(frames))
        visit = start
        while visit > 0 and is_title(frames[visit - 1]):
            visit -= 1
        first_song = next((row["frame"] for row in frames[visit:end] if matches(row)), None)
        bad = next((row["frame"] for row in frames[start:end] if not matches(row)), None)
        if start == end:
            bad = start
            first_song = None
        checks["title_song"] = {
            "passed": not missing and bad is None,
            "expected": {key: value for key, value in (
                ("bank", args.bank), ("song", args.song), ("pointer", args.pointer),
            ) if value is not None},
            "from": timing(start),
            "through": timing(end - 1) if start < end else None,
            "first_match": timing(first_song),
            "first_failure": timing(bad) if not missing else None,
        }
        if missing:
            checks["title_song"].update(status="unavailable", unavailable_fields=missing)

    if args.no_demo is not None:
        idle_frames = 0
        if first_title is not None:
            first_input = min((frame for frame, _ in args.press if frame + 1 >= first_title),
                              default=len(frames))
            idle_end = next((row["frame"] for row in frames[first_title:] if not is_title(row)),
                            len(frames))
            idle_frames = max(0, min(first_input, idle_end) - first_title)
        idle_seconds = float(idle_frames / fps)
        checks["no_demo"] = {
            "passed": idle_seconds >= args.no_demo,
            "required_seconds": args.no_demo,
            "idle_title_frames": idle_frames,
            "idle_title_seconds": idle_seconds,
            "first_title": timing(first_title),
            "first_demo": timing(first_demo),
        }

    if args.start_game is not None:
        press, window = args.start_game
        exit_frame = None
        on_title = press > 0 and is_title(frames[press - 1])
        if on_title:
            exit_frame = next((row for row in frames[press:press + window + 1]
                               if not is_title(row)), None)
        passed = (exit_frame is not None and not exit_frame["demo_flag"]
                  and exit_frame["mode"] & 0x7F in GAMEPLAY_MODES[args.profile])
        checks["start_game"] = {
            "passed": passed,
            "press": timing(press),
            "window_frames": window,
            "on_title_before_press": on_title,
            "first_exit": timing(exit_frame["frame"]) if exit_frame else None,
            "exit_mode": exit_frame["mode"] if exit_frame else None,
        }

    return checks, {
        "first_title": timing(first_title), "first_demo": timing(first_demo),
        "first_title_song_match": timing(first_song),
    }


def prepare_run(args):
    output = args.out.resolve()
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("output directory must be new or empty")
    inputs = {"rom": input_hashes(args.rom)}
    if args.reference is not None:
        inputs["reference"] = input_hashes(args.reference)
    ffmpeg = shutil.which("ffmpeg") if args.video else None
    notes = []
    if args.video and ffmpeg is None:
        notes.append("Video skipped because ffmpeg was not found on PATH.")
        print(notes[-1], file=sys.stderr)

    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    return output, inputs, ffmpeg, notes


def run(args):
    exe = args.build.resolve() / "RomProbe.exe"
    if not exe.is_file():
        raise ValueError(f"{exe} is missing. Run build.sh first.")
    output, inputs, ffmpeg, notes = prepare_run(args)
    rom = args.rom.resolve()
    presses = ",".join(f"{frame}:{button}" for frame, button in args.press)
    watches = [f"{name}:{domain}:{address}:{width}" for name, domain, address, width in args.watch]
    # Waterbox diagnostics go to stdout. Keep errors on stderr and the probe log structured.
    subprocess.run(["mono", str(exe), str(rom), str(output), args.profile, str(args.frames),
                    presses, ffmpeg or "", *watches], cwd=output, stdout=subprocess.DEVNULL, check=True)
    return write_report(args, output, inputs, ffmpeg, notes, bizhawk_version="2.11", core="GPGX")


def write_report(args, output, inputs, ffmpeg, notes, *, unavailable=(), **emulator):
    fps, frames = read_log(output / "probe.log")
    checks, timings = evaluate(args, frames, fps, unavailable)
    report = {
        "profile": args.profile, **emulator,
        "frames": len(frames), "fps": str(fps), "inputs": inputs,
        "presses": [{"frame": frame, "button": button} for frame, button in args.press],
        "watches": [{"name": name, "domain": domain, "offset": address, "size": width}
                    for name, domain, address, width in args.watch],
        "timings": timings, "checks": checks, "notes": notes,
        "outputs": {"audio": "capture.wav", "log": "probe.log",
                    "video": "capture.mp4" if ffmpeg else None},
    }
    report_path = output / "report.json"
    with report_path.open("x") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    for name, check in checks.items():
        status = check.get("status", "PASS" if check["passed"] else "FAIL")
        print(f"{name}: {status.upper()}")
    print(report_path)
    return 0 if all(check["passed"] for check in checks.values()) else 1


def main():
    try:
        return run(parse_args())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"ROM probe: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Make an offline listen page with inlined MP3 controls and disagreement spans."""
import argparse
import base64
import html
import json
from pathlib import Path
import subprocess

import numpy as np

from evaluate import DRUMS, inside, matches, read_notes


RATE = 24000


def disagreements(suno, local, duration, seconds, count=3):
    ranked = []
    for start in np.arange(0., duration - seconds + .001, seconds / 2):
        end = start + seconds
        left, right = ([row for row in inside(notes, (start, end)) if not row[4]]
                       for notes in (suno, local))
        hits = len(matches(left, right))
        ranked.append({"start": float(start), "end": float(end), "suno": len(left),
                       "local": len(right), "matches": hits,
                       "unmatched": len(left) + len(right) - 2 * hits})
    chosen = []
    for window in sorted(ranked, key=lambda w: -w["unmatched"]):
        if all(window["start"] >= w["end"] or window["end"] <= w["start"] for w in chosen):
            chosen.append(window)
            if len(chosen) == count:
                break
    return chosen


def overlay_notes(notes, start, length):
    output = np.zeros(length, dtype=np.float32)
    for a, b, pitch, _, drum in notes:
        if drum:
            b = a + .09
        begin = int(round((a - start) * RATE))
        end = min(length, int(round((b - start) * RATE)))
        if end <= max(0, begin):
            continue
        t = np.arange(max(0, begin) - begin, end - begin) / RATE
        if drum:
            if pitch in DRUMS["kick"]:
                signal = np.sin(2 * np.pi * (52 * t + 1 - np.exp(-t * 45)))
            else:
                rng = np.random.default_rng(pitch)
                signal = rng.uniform(-1, 1, len(t))
            signal *= .5 * np.exp(-t * 50)
        else:
            hz = 440 * 2 ** ((pitch - 69) / 12)
            attack = np.minimum(1., t / .008)
            release = np.minimum(1., np.maximum(0., b - a - t) / .018)
            signal = np.sin(2 * np.pi * hz * t) * attack * release
        output[max(0, begin):end] += signal
    return output


def decode(path, start, seconds):
    raw = subprocess.check_output([
        "ffmpeg", "-nostdin", "-loglevel", "error", "-ss", str(start), "-i", str(path),
        "-t", str(seconds), "-ar", str(RATE), "-ac", "2", "-f", "f32le", "-"
    ])
    return np.frombuffer(raw, dtype="<f4").reshape(-1, 2)


def uri(stereo):
    mp3 = subprocess.run([
        "ffmpeg", "-nostdin", "-loglevel", "error", "-f", "f32le", "-ar", str(RATE),
        "-ac", "2", "-i", "-", "-c:a", "libmp3lame", "-b:a", "96k", "-f", "mp3", "-"
    ], input=np.asarray(stereo, dtype="<f4").tobytes(), capture_output=True, check=True).stdout
    return "data:audio/mpeg;base64," + base64.b64encode(mp3).decode("ascii")


def clip(mix_path, suno_notes, local_notes, start, end):
    mix = decode(mix_path, start, end - start)
    suno = overlay_notes(suno_notes, start, len(mix))
    local = overlay_notes(local_notes, start, len(mix))
    peak = max(np.max(abs(suno)), np.max(abs(local)), 1.)
    gain = .45 / peak
    return [uri(mix * .45), uri(mix * .45 + gain * suno[:, None]),
            uri(mix * .45 + gain * local[:, None])]


def listen(cases, output):
    report = {}
    with output.open("w", encoding="utf-8") as page:
        page.write("""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Local extraction listening test</title>
<style>
body{max-width:65rem;margin:3rem auto;padding:0 1.2rem;font:1rem/1.45 system-ui;background:#f6f4ef;color:#26372f}
section{border-top:1px solid #a8b6a9;margin:2rem 0}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(14rem,1fr));gap:1rem}
audio{width:100%;display:block;margin:.4rem 0 1rem}h3{margin-bottom:.3rem}p{max-width:72ch}
</style><body>
<h1>Local extraction listening test</h1>
<p>Hear the original mix, then the same mix with each MIDI transcription played over it.
Both overlays use the same sine tone, drum sounds, and gain. Pitch bends are omitted.
Times refer to the full mix.</p>
""")
        for case in cases:
            suno, local = (read_notes(case[which]) for which in ("suno", "local"))
            windows = disagreements(suno, local, case["duration"], case.get("clipSeconds", 8))
            report[case["title"]] = windows
            page.write(f"<section><h2>{html.escape(case['title'])}</h2>\n")
            spans = case.get("highlights", [])
            spans += [[w["start"], w["end"]] for w in windows[:2]]
            for start, end in spans:
                page.write(f'<h3>{start:.3f}–{end:.3f} s</h3><div class="grid">\n')
                for label, data in zip(("Mix", "Suno MIDI + mix", "Local MIDI + mix"),
                                       clip(case["mix"], suno, local, start, end), strict=True):
                    page.write(f'<div>{label}<audio controls preload="none" src="{data}"></audio></div>\n')
                page.write("</div>\n")
            page.write("</section>\n")
        page.write("""<script>
document.addEventListener('play', event => {
  for (const audio of document.querySelectorAll('audio')) {
    if (audio !== event.target) audio.pause()
  }
}, true)
</script></body></html>
""")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(listen(json.loads(args.manifest.read_text()), args.output), indent=2))


if __name__ == "__main__":
    main()

"""Assemble paired manifests and score MIDI notes against per-stem sequencer truth."""

import argparse
from collections import defaultdict, deque
from copy import deepcopy
import hashlib
import math
from pathlib import Path
from statistics import mean

import mido
import numpy as np
from mir_eval.transcription import match_notes

from transcribe import ROLES, read_json, write_json


def input_time(time, duration, drift):
    if not drift:
        return time
    return 2 * time / (1 + math.sqrt(1 + 2 * drift * time / (duration / (1 + drift / 2))))


def raw_truth(corpus, case, duration):
    target = corpus / case["truth"]["path"]
    groups = read_json(target.with_name("stems.json"))
    reverse = {lane: role for role, lanes in groups.items() for lane in lanes}
    grouped = {role: [] for role in ROLES}
    for channel in read_json(target)["channels"]:
        role = reverse[channel["kind"]]
        if role == "drums":
            continue
        for note in channel["notes"]:
            grouped[role].append((input_time(note["start"], duration, case.get("drift")),
                                  input_time(note["end"], duration, case.get("drift")), note["pitch"]))
    # Only the established native drum classifier knows the reference GM labels.
    grouped["drums"] = midi_notes(target.with_name("clean-stems.mid"))["drums"]
    return grouped


def midi_notes(path):
    midi = mido.MidiFile(path)
    result = {}
    for track in midi.tracks:
        if track.name not in ROLES:
            continue
        ticks = 0
        active = defaultdict(deque)
        notes = []
        for message in track:
            ticks += message.time
            if message.type not in ("note_on", "note_off"):
                continue
            key = (message.channel, message.note)
            if message.type == "note_on" and message.velocity:
                active[key].append(ticks)
            elif active[key]:
                start = active[key].popleft()
                if ticks > start:
                    scale = 2 * midi.ticks_per_beat
                    notes.append((start / scale, ticks / scale, message.note))
        result[track.name] = sorted(notes)
    return result


def note_f1(truth, predicted, offsets=False):
    if not truth or not predicted:
        return float(not truth and not predicted)

    def intervals(notes):
        return np.array([(start, end) for start, end, _ in notes])

    def frequencies(notes):
        return 440 * np.exp2((np.array([pitch for _, _, pitch in notes]) - 69) / 12)

    matched = match_notes(intervals(truth), frequencies(truth), intervals(predicted), frequencies(predicted),
                          onset_tolerance=0.05, pitch_tolerance=50, offset_ratio=0.2 if offsets else None)
    return 2 * len(matched) / (len(truth) + len(predicted))


def model_notes(corpus, case_id, model, role):
    return read_json(corpus / "transcriptions" / model / case_id / f"{role}.json")["notes"]


def note_track(role, notes, ticks_per_beat):
    track = mido.MidiTrack([mido.MetaMessage("track_name", name=role)])
    events = []
    for start, end, pitch in notes:
        onset = round(start * 2 * ticks_per_beat)
        offset = max(onset + 1, round(end * 2 * ticks_per_beat))
        events.extend(((onset, 1, pitch), (offset, 0, pitch)))
    last = 0
    for time, on, pitch in sorted(events):
        track.append(mido.Message("note_on" if on else "note_off", note=pitch, velocity=96 if on else 0,
                                  time=time - last))
        last = time
    return track


def materialize(corpus, case, source, tonal_model, tracker):
    folder = (corpus / case["truth"]["path"]).parent
    baseline = mido.MidiFile(folder / "suno-like-stems.mid")
    keys = {track.name: index for index, track in enumerate(baseline.tracks)}
    if source == "suno-like":
        return folder / "suno-like-stems.mid"
    if source == "clean":
        clean = mido.MidiFile(folder / "clean-stems.mid")
        for track in clean.tracks:
            if track.name in ROLES[:3]:
                baseline.tracks[keys[track.name]] = track
    else:
        replace = {}
        if source in ("basic-pitch", "muscriptor", "f0-lead"):
            replace.update(dict.fromkeys(ROLES[:3], tonal_model if source == "f0-lead" else source))
        if source in ("f0-lead", "hybrid"):
            replace["lead"] = tracker
        for role, model in replace.items():
            notes = model_notes(corpus, case["id"], model, role)
            baseline.tracks[keys[role]] = note_track(role, notes, baseline.ticks_per_beat)
    path = corpus / "midi" / source / f"{case['id']}.mid"
    path.parent.mkdir(parents=True, exist_ok=True)
    baseline.save(path)
    return path


def compare_models(corpora, models, roles):
    scores = {model: [] for model in models}
    for corpus in corpora:
        manifest = read_json(corpus / "manifest.json")
        for case in manifest["cases"]:
            truth = raw_truth(corpus, case, manifest["frames"] / 60)
            for model in models:
                scores[model].extend(note_f1(truth[role], model_notes(corpus, case["id"], model, role))
                                     for role in roles)
    return {model: mean(values) for model, values in scores.items()}


def assemble(corpus, sources, tonal_model, tracker):
    original = read_json(corpus / "manifest.json")
    duration = original["frames"] / 60
    saved = corpus / "raw-f1.json"
    raw = read_json(saved) if saved.is_file() else {}
    for source in sources:
        manifest = deepcopy(original)
        for case in manifest["cases"]:
            path = materialize(corpus, case, source, tonal_model, tracker)
            case["midi"] = {"path": str(path.relative_to(corpus)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            truth, actual = raw_truth(corpus, case, duration), midi_notes(path)
            if set(actual) != set(ROLES):
                raise ValueError(f"Expected four stem tracks in {path}")
            baseline = midi_notes((corpus / case["truth"]["path"]).with_name("suno-like-stems.mid"))
            if actual["drums"] != baseline["drums"]:
                raise ValueError(f"Drum events differ from the baseline in {path}")
            raw.setdefault(case["id"], {})[source] = {
                **{role: note_f1(truth[role], actual[role]) for role in ROLES},
                "leadWithOffset": note_f1(truth["lead"], actual["lead"], offsets=True),
            }
        write_json(corpus / f"manifest-{source}.json", manifest)
    for case in original["cases"]:
        truth = raw_truth(corpus, case, duration)
        for model in ("rmvpe", "torchcrepe"):
            if (corpus / "transcriptions" / model / case["id"] / "lead.json").is_file():
                notes = model_notes(corpus, case["id"], model, "lead")
                raw.setdefault(case["id"], {})[model] = {"lead": note_f1(truth["lead"], notes),
                                           "leadWithOffset": note_f1(truth["lead"], notes, offsets=True)}
    write_json(corpus / "raw-f1.json", raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpora", nargs="+", type=Path)
    parser.add_argument("--without-muscriptor", action="store_true")
    parser.add_argument("--sources", nargs="+",
                        choices=("suno-like", "clean", "basic-pitch", "muscriptor", "f0-lead", "hybrid"),
                        help="Prepare a subset of manifests while other sources run")
    args = parser.parse_args()
    tonal_models = ["basic-pitch"] if args.without_muscriptor else ["basic-pitch", "muscriptor"]
    sources = args.sources or ["suno-like", "clean", *tonal_models, "f0-lead", "hybrid"]
    tonal_model = tracker = None
    if set(sources) & {"f0-lead", "hybrid"}:
        tonal_scores = compare_models(args.corpora, tonal_models, ("bass", "backing")) if "f0-lead" in sources else {}
        lead_scores = compare_models(args.corpora, ("rmvpe", "torchcrepe"), ("lead",))
        tonal_model = max(tonal_scores, key=tonal_scores.get) if tonal_scores else None
        tracker = max(lead_scores, key=lead_scores.get)
        print(f"Tonal bass/backing onset F1: {tonal_scores}. Selected {tonal_model}.")
        print(f"Tracker lead onset F1: {lead_scores}. Selected {tracker}.")
        for corpus in args.corpora:
            write_json(corpus / "selection.json", {"tonal": tonal_model, "tracker": tracker,
                                                    "tonalScores": tonal_scores, "trackerScores": lead_scores})
    for corpus in args.corpora:
        assemble(corpus, sources, tonal_model, tracker)


if __name__ == "__main__":
    main()

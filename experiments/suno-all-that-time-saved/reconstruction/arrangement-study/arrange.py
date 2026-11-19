"""Reproduce the All That Time Saved voicing study."""
import argparse
import collections
import csv
import json
from pathlib import Path

FPS = 60

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("notes", type=Path)
parser.add_argument("previous_project", type=Path)
parser.add_argument("out", type=Path)
parser.add_argument("--seconds", choices=(20, 60), type=int, default=20)
args = parser.parse_args()
FRAMES = args.seconds * FPS

rows = []
with args.notes.open() as notes:
    for row in csv.DictReader(notes):
        i, pitch, velocity, start, end = map(int, row.values())
        t = round(start * FPS / 1e6)
        if t >= FRAMES or i not in (0, 1):
            continue
        if i == 0 and pitch == 64 and 8_525_000 <= start <= 8_870_000:
            # E4 appeared in the MIDI chord but measured well below Eb4 in the WAV.
            continue
        rows.append((i, pitch, velocity, t, min(FRAMES, max(t + 1, round(end * FPS / 1e6)))))

groups = collections.defaultdict(list)
for i, pitch, velocity, start, end in rows:
    groups[i, start].append((pitch, velocity, end))

def spans(attacks):
    return [(time, min(end, following), pitch) for (time, (pitch, _, end)), following
            in zip(attacks, [time for time, _ in attacks[1:]] + [FRAMES])]

lead_attacks = []
bass_attacks = []
for (part, tick), notes in sorted(groups.items()):
    if part == 1:
        bass_attacks.append((tick, max(notes, key=lambda n: (n[1], -n[0]))))
        continue
    candidates = [n for n in notes if (tick <= 64 and n[0] >= 65) or
                  (n[1] >= 70 and n[0] >= 60 and n[2] - tick <= 75)]
    if candidates:
        lead_attacks.append((tick, max(candidates, key=lambda n: (n[1], n[0]))))
lead = spans(lead_attacks)
bass = spans(bass_attacks)
bass_start = bass[0][0]
# Keep one bass register through the repeated opening F ostinato.
bass.insert(0, (64, bass_start, 41))

def at(lane,tick):
    return next((pitch for start, end, pitch in lane if start <= tick < end), None)

accent_ids = {(t, p) for t, (p, _, _) in lead_attacks}
harmony = [[] for _ in range(2)]
active = [None, None]
for tick in range(FRAMES):
    b, tune = at(bass,tick), at(lead,tick)
    avoid = {p % 12 for p in (b, tune) if p is not None}
    candidates = {p for i,p,v,t,e in rows
                  if i == 0 and (t,p) not in accent_ids and t<=tick<e and 53<=p<=74 and p%12 not in avoid}
    if tick < bass_start:
        # Retain the short A/Bb/C figures above the opening ostinato on one lane.
        candidates = {p for p in candidates if 57 <= p <= 60}
    previous = [n[2] if n else None for n in active]
    keep = set()
    limit = 1 if tick < bass_start else 2
    def distance(p):
        relative = ((p - b) % 12) if b is not None else 0
        return (p not in previous, relative not in (3, 4), abs(p - 62), p)
    for pitch in sorted(candidates, key=distance):
        if len(keep) >= limit:
            break
        if all(other % 12 != pitch % 12 and abs(pitch-other)>1 for other in keep):
            keep.add(pitch)
    for lane in range(2):
        if previous[lane] in keep:
            keep.remove(previous[lane])
        else:
            if active[lane]:
                start, _, pitch = active[lane]
                harmony[lane].append((start, tick, pitch))
            active[lane] = None
    for pitch in sorted(keep):
        lane = active.index(None)
        active[lane] = (tick, FRAMES, pitch)
for lane in range(2):
    if active[lane]:
        harmony[lane].append(active[lane])

previous = json.loads(args.previous_project.read_text())
def asset(prefix):
    return next(voice for voice in previous["voices"] if voice["name"].startswith(prefix))

lead_voice=asset("Lead")
bass_voice=asset("Electric Bass")
focused = dict(id="focused-lead", name="Focused additive lead",
    bytes=[7, 1, 2, 3, 4, 29, 31, 31, 31, 3, 14, 15, 18, 0, 0, 0, 0, 28, 88, 120, 136, 133, 154, 163, 179])
voices = [focused, lead_voice, bass_voice]
sequences, clips = [], []
def add(name, lane, pitches, voice, level):
    ident = lane.lower()
    sequences.append(dict(id=ident,name=name,type="FM",lengthTicks=FRAMES,
        voiceId=voice["id"],volume=level,notes=[
            dict(id=f"n{j}",tick=start,duration=end-start,pitch=pitch)
            for j,(start,end,pitch) in enumerate(pitches) if end>start],commands=[],
        repeatState="RESET",psgEnvelopeId=None,modulationEnvelopeId=None,triggers=[]))
    clips.append(dict(id="clip-"+ident,sequenceId=ident,lane=lane,startTick=0,lengthTicks=FRAMES))

add("Topline", "FM1", lead, focused, 121)
add("Bass", "FM2", bass, bass_voice, 115)
add("Harmony", "FM3", harmony[0], lead_voice, 98)
add("Harmony 2", "FM4", harmony[1], lead_voice, 92)

old_sequences={s["id"]:s for s in previous["sequences"]}
for clip in previous["clips"]:
    if clip["lane"] != "DAC" or clip["startTick"]>=FRAMES:
        continue
    seq = old_sequences[clip["sequenceId"]]
    length = min(clip["lengthTicks"],FRAMES-clip["startTick"])
    triggers=[tr for tr in seq["triggers"] if tr["tick"]<length]
    if triggers:
        sequences.append(dict(seq, lengthTicks=length, triggers=triggers))
        clips.append(dict(clip, lengthTicks=length))
used_samples={tr["sampleId"] for seq in sequences for tr in seq["triggers"]}
project=dict(formatVersion=4,name="All That Time Saved - voicing study",mode="S3K",
             ticksPerBeat=previous["ticksPerBeat"],tempo=0,voices=voices,sequences=sequences,clips=clips,
             envelopes=[],samples=[s for s in previous["samples"] if s["id"] in used_samples])
args.out.write_text(json.dumps(project,indent=2)+"\n")

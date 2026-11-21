#!/usr/bin/env python3
"""Stage bounded public evaluation subsets. All output belongs outside Git."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import io
import json
from pathlib import Path
import subprocess
import tempfile
from urllib.request import urlopen

HF = "https://huggingface.co"
MULTTIPOP = "gclef-cmu/multtipop"
SLAKH = "DreamyWanderer/Slakh2100-FLAC-Redux-Reduced"
MIR = ("https://raw.githubusercontent.com/york135/singing_transcription_ICASSP2021/"
       "master/MIR-ST500_20210206")


def read(url):
    with urlopen(url, timeout=30) as response:
        return response.read()


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-y", *map(str, args)], check=True)


def download_video(yt_dlp, url, temporary):
    subprocess.run([yt_dlp, "--ignore-config", "--no-playlist",
                    "--no-progress", "--socket-timeout", "15", "--retries", "1",
                    "-f", "140/251", "--max-filesize", "25M",
                    "-o", str(temporary / "audio.%(ext)s"), url], check=True)
    return next(temporary.glob("audio.*"))


def stage_pop(args):
    metadata = json.loads(read(f"{HF}/datasets/{MULTTIPOP}/raw/main/test.json"))
    rows = {row["id"]: row for row in metadata}

    def stage(song_id):
        row = rows[song_id]
        video = row["youtube"]
        if video["timestampType"] != "absolute":
            raise ValueError(f"{song_id} requires manual time mapping")
        folder = args.output / song_id
        folder.mkdir(parents=True, exist_ok=True)
        if not (folder / "mix.wav").exists():
            with tempfile.TemporaryDirectory(dir=args.output) as work:
                tmp = Path(work)
                source = download_video(args.yt_dlp,
                    f"https://www.youtube.com/watch?v={video['ytid']}", tmp)
                ffmpeg("-i", source, "-ac", 2, "-ar", 44100, tmp / "mix.wav")
                (tmp / "mix.wav").replace(folder / "mix.wav")
        (folder / "reference.mid").write_bytes(read(
            f"{HF}/datasets/{MULTTIPOP}/resolve/main/{row['labels'].removeprefix('./')}"))
        (folder / "source.json").write_text(json.dumps(row, indent=2) + "\n")
        return song_id

    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs = {pool.submit(stage, song_id): song_id for song_id in args.ids}
        failures = []
        for job, song_id in jobs.items():
            try:
                print(f"Staged {job.result()}", flush=True)
            except (OSError, ValueError, StopIteration, subprocess.CalledProcessError) as error:
                failures.append(song_id)
                print(f"{song_id}: {error}", flush=True)
    if failures:
        raise SystemExit(f"Failed downloads: {', '.join(failures)}")


def crop_pop(args):
    for source in sorted(args.full_mixes.glob("*/source.json")):
        video = json.loads(source.read_text())["youtube"]
        start, end = video["start"], video["end"]
        target = args.output / source.parent.name
        target.mkdir(parents=True, exist_ok=True)
        # Negative timestamps indicate a left-padded annotation timeline.
        delay = max(0, round(-start * 44100))
        ffmpeg("-ss", max(0, start), "-i", source.with_name("mix.wav"),
               "-af", f"adelay={delay}S:all=1", "-t", end - start,
               "-c:a", "pcm_f32le", target / "mix.wav")


def stage_slakh(args):
    import soundfile as sf

    index = json.loads(read(f"{HF}/api/datasets/{SLAKH}/tree/main/data/test"))
    tracks = sorted(Path(item["path"]).name for item in index if item["type"] == "directory")
    # Sample across the test split deterministically, without using model outputs.
    selected = tracks[::max(1, len(tracks) // args.count)][:args.count]

    def stage(song_id):
        folder = args.output / song_id
        folder.mkdir(parents=True, exist_ok=True)
        url = f"{HF}/datasets/{SLAKH}/resolve/main/data/test/{song_id}"
        if not (folder / "mix.wav").exists():
            with sf.SoundFile(io.BytesIO(read(f"{url}/mix.flac"))) as source:
                audio = source.read(int(args.seconds * source.samplerate), dtype="float32")
                sf.write(folder / "mix.wav", audio, source.samplerate, subtype="FLOAT")
        (folder / "reference.mid").write_bytes(read(f"{url}/all_src.mid"))
        print(f"Staged {song_id}", flush=True)

    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(stage, selected))


def stage_mir(args):
    from matrix import write_midi

    notes = json.loads(read(f"{MIR}/MIR-ST500_corrected.json"))
    urls = json.loads(read(f"{MIR}/MIR-ST500_link.json"))
    for ident in args.ids:
        target = args.output / ident
        target.mkdir(parents=True, exist_ok=True)
        start = max(0., notes[ident][0][0] - 2.)
        with tempfile.TemporaryDirectory(dir=args.output) as work:
            source = download_video(args.yt_dlp, urls[ident], Path(work))
            ffmpeg("-ss", start, "-i", source, "-t", args.seconds, "-ar", 44100,
                   "-ac", 2, "-c:a", "pcm_f32le", target / "mix.wav")
        rows = [(a - start, min(b - start, args.seconds), int(p), 53, False)
                for a, b, p in notes[ident] if start <= a < start + args.seconds]
        write_midi(rows, target / "reference.mid")
        (target / "origin.json").write_text(json.dumps({"start": start, "end": start + args.seconds}) + "\n")
        print(f"Staged {ident}", flush=True)


def stage_musdb(args):
    import pyarrow.parquet as pq
    import soundfile as sf

    parquet = pq.ParquetFile(args.parquet)
    groups = {}
    for index, path in enumerate(parquet.read(columns=["path"]).column(0).to_pylist()):
        track, stem = Path(path).parent.name, Path(path).stem
        groups.setdefault(track, {})[stem] = index
    for track, sources in groups.items():
        if not {"mixture", "bass", "drums", "other", "vocals"} <= sources.keys():
            continue
        folder = args.output / track
        folder.mkdir(parents=True, exist_ok=True)
        # The tested mirror stores one row per row group.
        if parquet.num_row_groups != parquet.metadata.num_rows:
            raise ValueError("Expected one row per row group")
        for stem, index in sources.items():
            row = parquet.read_row_group(index, columns=["audio"]).column(0)[0].as_py()
            with sf.SoundFile(io.BytesIO(row["bytes"])) as source:
                source.seek(round(args.start * source.samplerate))
                audio = source.read(round(args.seconds * source.samplerate), dtype="float32")
                sf.write(folder / ("mix.wav" if stem == "mixture" else f"{stem}.wav"),
                         audio, source.samplerate, subtype="FLOAT")
        print(f"Staged {track}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    commands = parser.add_subparsers(dest="source", required=True)
    pop = commands.add_parser("multtipop")
    pop.add_argument("ids", nargs="+")
    pop.add_argument("--yt-dlp", default="yt-dlp")
    clips = commands.add_parser("pop-clips")
    clips.add_argument("full_mixes", type=Path)
    slakh = commands.add_parser("slakh")
    slakh.add_argument("--count", type=int, default=18)
    slakh.add_argument("--seconds", type=float, default=30)
    mir = commands.add_parser("mir-st500")
    mir.add_argument("ids", nargs="+")
    mir.add_argument("--yt-dlp", default="yt-dlp")
    mir.add_argument("--seconds", type=float, default=28)
    musdb = commands.add_parser("musdb")
    musdb.add_argument("parquet", type=Path)
    musdb.add_argument("--start", type=float, default=45)
    musdb.add_argument("--seconds", type=float, default=25)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    {"multtipop": stage_pop, "pop-clips": crop_pop,
     "slakh": stage_slakh, "mir-st500": stage_mir, "musdb": stage_musdb}[args.source](args)


if __name__ == "__main__":
    main()

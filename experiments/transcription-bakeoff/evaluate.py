"""Run the unchanged native benchmark and measure FM1 lead coverage in source time."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import bisect
import fcntl
import hashlib
import json
import math
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import time

import numpy as np
from scipy.optimize import linear_sum_assignment

from assemble import raw_truth
from transcribe import read_json, write_json


WORK_ARTIFACTS = ("score.json", "arrangement.smps.json", "report.json")


def command(cli, args):
    return ["java", "-jar", str(cli), *map(str, args)]


def java(cli, args, **kwargs):
    return subprocess.run(command(cli, args), **kwargs)


def capture_work(output, snapshots, count):
    # BenchRun numbers work directories in manifest order and removes them
    # after scoring. The three export artifacts are sufficient for lead-gap.
    for bench in output.glob(".bench-*"):
        for work in bench.glob("*"):
            if not work.name.isdecimal() or int(work.name) >= count:
                continue
            dest = snapshots / work.name
            if dest.exists() or not (work / "report.json").is_file():
                continue
            try:
                artifacts = {name: (work / name).read_bytes() for name in WORK_ARTIFACTS}
                json.loads(artifacts["report.json"])  # It may still be open for writing.
            except (FileNotFoundError, json.JSONDecodeError):
                continue
            dest.mkdir()
            for name, contents in artifacts.items():
                (dest / name).write_bytes(contents)


def run_bench(cli, path, catalogue, output, jobs, snapshots, count):
    with (output / "bench.log").open("w") as log:
        args = command(cli, ["bench", "run", path, catalogue, output, "--jobs", jobs])
        with subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT) as run:
            while run.poll() is None:
                capture_work(output, snapshots, count)
                time.sleep(0.15)
        if run.returncode:
            raise subprocess.CalledProcessError(run.returncode, args)


def source_clock(work):
    report = read_json(work / "report.json")
    curve = report["tempoCurve"]
    if not curve["warped"]:
        return lambda time: time

    beats = read_json(work / "score.json")["beatMap"]["beats"]
    lower = min(len(beats) - 2, max(0, bisect.bisect_right(beats, 0) - 1))
    origin = lower - beats[lower] / (beats[lower + 1] - beats[lower])

    def map_time(time):
        beat = origin + time * curve["steadyBpm"] / 60
        index = min(len(beats) - 2, max(0, math.floor(beat)))
        return beats[index] + (beat - index) * (beats[index + 1] - beats[index])
    return map_time


def fm1_attacks(work):
    project = read_json(work / "arrangement.smps.json")
    sequences = {sequence["id"]: sequence for sequence in project["sequences"]}
    to_source = source_clock(work)
    attacks = []
    for clip in project["clips"]:
        if clip["lane"] != "FM1":
            continue
        for note in sequences[clip["sequenceId"]]["notes"]:
            if note["tick"] < clip["lengthTicks"]:
                attacks.append((to_source((clip["startTick"] + note["tick"]) / 60), note["pitch"]))
    return sorted(attacks)


def lead_attacks(truth, actual):
    expected_times = np.array([time for time, _, _ in truth])
    observed_times = np.array([time for time, _ in actual])
    distance = np.abs(expected_times[:, None] - observed_times[None, :])
    eligible = distance <= 0.05 + 1e-9
    # Unmatched pairs carry more cost than the sum of all eligible distances.
    rows, cols = linear_sum_assignment(np.where(eligible, distance, len(truth) + 1))
    pairs = [(a, b) for a, b in zip(rows, cols, strict=True) if eligible[a, b]]
    count = len(pairs)
    return {
        "leadAttackF1": 2 * count / (len(truth) + len(actual)) if truth or actual else 1.0,
        "leadPitchAccuracy": sum(truth[a][2] == actual[b][1] for a, b in pairs) / count if count else None,
    }


def supplement(cli, catalogue, corpus, output, duration, case, snapshot):
    case_id = case["id"]
    lead_path = output / "lead" / f"{case_id}.json"
    if lead_path.is_file():
        return read_json(lead_path)
    truth = sorted(raw_truth(corpus, case, duration)["lead"])
    with TemporaryDirectory(prefix="lead-", dir=output) as scratch:
        scratch = Path(scratch)
        midi = corpus / case["midi"]["path"]
        audio = corpus / case["audio"]["path"]
        work = snapshot
        if not work.is_dir():
            # Reproduce the same case when resuming a scorecard or if an
            # export was removed before the polling loop could copy it.
            work = scratch / "work"
            with (scratch / "reconstruct.log").open("w") as log:
                run = java(cli, ["reconstruct", "run", midi, audio, catalogue, work, "--name", "Benchmark"],
                           stdout=log, stderr=subprocess.STDOUT)
            if run.returncode:
                raise RuntimeError((scratch / "reconstruct.log").read_text())
        metrics = lead_attacks(truth, fm1_attacks(work))
        if truth:
            end = read_json(work / "report.json")["referenceUs"] / 1e6
            labels = [{"midi": pitch, "attack_s": start, "release_s": release, "voice": "lead"}
                      for start, release, pitch in truth]
            write_json(scratch / "labels.json", {"regions": [{"id": "lead", "start_s": 0,
                       "end_s": end, "notes": labels}]})
            write_json(scratch / "gold.json", {"reference": str(audio), "midi": str(midi),
                       "annotations": "labels.json"})
            gap = java(cli, ["bench", "lead-gap", work, "--gold", scratch / "gold.json"],
                       check=True, capture_output=True, text=True)
            metrics["leadGap"] = json.loads(gap.stdout)["leadGap"]
        else:
            metrics["leadGap"] = None
    write_json(lead_path, metrics)
    print(f"Measured FM1 {case_id}", flush=True)
    return metrics


def evaluate(args, source):
    corpus = args.corpus.resolve()
    output = corpus / "runs" / source
    output.mkdir(parents=True, exist_ok=True)
    path = corpus / f"manifest-{source}.json"
    with (output / ".evaluate.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        manifest = read_json(path)
        with TemporaryDirectory(prefix="lead-inputs-", dir=output) as scratch:
            score_source(args, source, corpus, output, path, manifest, Path(scratch))


def score_source(args, source, corpus, output, path, manifest, snapshots):
    score = output / "scorecard.json"
    cases = manifest["cases"]
    if not score.is_file():
        run_bench(args.cli, path, args.catalogue, output, args.jobs, snapshots, len(cases))
    card = read_json(score)
    if card["manifestSha256"] != hashlib.sha256(path.read_bytes()).hexdigest():
        raise ValueError(f"Stale scorecard in {output}; use a fresh output directory")
    baseline = corpus / "runs" / "suno-like" / "scorecard.json"
    if source != "suno-like" and baseline.is_file():
        comparison = java(args.cli, ["bench", "compare", baseline, score, "--alternate-midi",
                          corpus / "manifest-suno-like.json", path], capture_output=True, text=True)
        if comparison.returncode not in (0, 1):
            raise RuntimeError(comparison.stderr)
        (output / "compare.md").write_text(comparison.stdout)

    case_ids = [case["id"] for case in cases]
    native = {row["id"]: row["metrics"] for row in card["cases"]}
    if set(native) != set(case_ids) or any(metrics is None for metrics in native.values()):
        raise ValueError(f"Missing or failed benchmark cases in {score}")

    def measure(index_case):
        index, case = index_case
        return supplement(args.cli, args.catalogue, corpus, output, manifest["frames"] / 60,
                          case, snapshots / str(index))

    captured = sum(1 for folder in snapshots.iterdir() if folder.is_dir())
    print(f"{source}: captured {captured}/{len(cases)} native work directories", flush=True)
    with ThreadPoolExecutor(max_workers=args.lead_jobs) as pool:
        for case, metrics in zip(case_ids, pool.map(measure, enumerate(cases)), strict=True):
            native[case].update(metrics)
    write_json(output / "metrics.json", native)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cli", type=Path)
    parser.add_argument("catalogue", type=Path)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("sources", nargs="+")
    parser.add_argument("--jobs", type=int, default=8)
    parser.add_argument("--lead-jobs", type=int, default=4)
    args = parser.parse_args()
    for source in args.sources:
        evaluate(args, source)


if __name__ == "__main__":
    main()

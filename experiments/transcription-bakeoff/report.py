"""Render public-safe metric tables from completed private bakeoff results."""

import argparse
from pathlib import Path
from statistics import mean

from transcribe import ROLES, read_json

METRICS = ("melodyF1", "noteF1", "onsetCorrelation", "leadAttackF1", "leadPitchAccuracy", "leadGap")
RAW = (*ROLES, "leadWithOffset")
MODELS = ("basic-pitch", "muscriptor", "rmvpe", "torchcrepe")


def table(columns, rows):
    print("| " + " | ".join(columns) + " |")
    print("|" + "|".join("---" for _ in columns) + "|")
    for row in rows:
        print("| " + " | ".join(row) + " |")
    print()


def fmt(value, signed=False):
    if value is None:
        return "—"
    return f"{value:{'+' if signed else ''}.3f}"


def average(rows, name):
    valid = [row[name] for row in rows if row.get(name) is not None]
    return mean(valid) if valid else None


def delta(actual, baseline):
    return f"{fmt(actual)} ({fmt(actual - baseline, signed=True)})" if actual is not None and baseline is not None else fmt(actual)


def load_corpus(path, sources):
    manifest = read_json(path / "manifest.json")
    cases = manifest["cases"]
    raw = read_json(path / "raw-f1.json")
    results = {source: read_json(path / "runs" / source / "metrics.json") for source in sources}
    case_ids = {case["id"] for case in cases}
    for source, metrics in results.items():
        if set(metrics) != case_ids or any(set(METRICS) - row.keys() for row in metrics.values()):
            raise ValueError(f"{path.name} {source} is missing benchmark cases or metrics")
    expected_raw = {*sources, "rmvpe", "torchcrepe"}
    if set(raw) != case_ids or any(expected_raw - row.keys() for row in raw.values()):
        raise ValueError(f"{path.name} is missing raw transcription scores")
    return cases, raw, results


def native_summary(cases, results):
    averages = {s: {m: average(rows.values(), m) for m in METRICS} for s, rows in results.items()}
    table(("Source", *METRICS), [[source, *(delta(values[m], averages["suno-like"][m]) for m in METRICS)]
                                 for source, values in averages.items()])
    rows = []
    for source, metrics in results.items():
        if source == "suno-like":
            continue
        tallies = []
        for metric in METRICS:
            better = equal = worse = 0
            direction = -1 if metric == "leadGap" else 1
            for case in cases:
                key = case["id"]
                before, after = results["suno-like"][key].get(metric), metrics[key].get(metric)
                if before is None or after is None:
                    continue
                change = direction * (after - before)
                better += change > 1e-9
                worse += change < -1e-9
                equal += abs(change) <= 1e-9
            tallies.append(f"{better}/{equal}/{worse}")
        rows.append([source, *tallies])
    print("Case counts versus suno-like shown as wins / ties / losses. Undefined pairs are omitted.")
    print()
    table(("Source", *METRICS), rows)


def raw_summary(raw, sources):
    table(("Source", *RAW), [
        [source, *(fmt(average((row[source] for row in raw.values()), m)) for m in RAW)]
        for source in sources if all(source in row for row in raw.values())
    ])


def timing(path, cases):
    rows = []
    for model in MODELS:
        roles = ROLES[:3] if model in ("basic-pitch", "muscriptor") else ROLES[:1]
        paths = [path / "transcriptions" / model / case["id"] / f"{role}.json"
                 for case in cases for role in roles]
        if not all(item.is_file() for item in paths):
            continue
        data = [read_json(item) for item in paths]
        audio = sum(item["audioSeconds"] for item in data)
        wall = sum(item["wallSeconds"] for item in data)
        devices = {item.get("device", "cpu") for item in data}
        if len(devices) != 1:
            raise ValueError(f"Mixed devices for {model} in {path.name}")
        device = devices.pop()
        rows.append([model, "RX 9070 XT (ROCm)" if device == "rocm" else "Ryzen 9 9950X (CPU)",
                     str(len(paths)), f"{audio / 60:.2f}", f"{wall:.1f}", f"{60 * wall / audio:.2f}"])
    table(("Transcriber", "Device", "Stems", "Stem audio min", "Summed wall s", "Wall s/audio min"), rows)


def per_case(cases, raw, results):
    labels = {row["id"]: f'{row["id"].split("/")[0]} · {row["variant"]}' for row in cases}

    def values(row, names):
        return " / ".join(fmt(row.get(name)) for name in names)

    print("<details>\n<summary>All pipeline results</summary>\n")
    print("Values in each cell follow this order. " + " / ".join(METRICS) + ".\n")
    table(("Case", *results), [
        [label, *(values(result[key], METRICS) for result in results.values())]
        for key, label in labels.items()
    ])
    print("</details>\n")
    raw_sources = (*results, "rmvpe", "torchcrepe")
    print("<details>\n<summary>All raw transcription results</summary>\n")
    print("Values in each cell follow this order. " + " / ".join(RAW) + ".\n")
    table(("Case", *raw_sources), [
        [label, *(values(raw[key][source], RAW) for source in raw_sources)]
        for key, label in labels.items()
    ])
    print("</details>\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpora", type=Path, nargs="+")
    parser.add_argument("--without-muscriptor", action="store_true")
    parser.add_argument("--summary-only", action="store_true")
    args = parser.parse_args()
    sources = ["suno-like", "clean", "basic-pitch"]
    if not args.without_muscriptor:
        sources.append("muscriptor")
    sources.extend(("f0-lead", "hybrid"))

    for path in args.corpora:
        cases, raw, results = load_corpus(path, sources)
        title = "No drift" if not cases[0].get("drift") else f'{cases[0]["drift"]:.0%} BPM drift'
        print(f"## {title}\n")
        print(f"{len(cases)} paired cases. Values are means across cases. Parentheses show signed deltas from suno-like.\n")
        native_summary(cases, results)
        print("### Raw per-stem note F1\n")
        raw_summary(raw, [*sources, "rmvpe", "torchcrepe"])
        print("### Inference timings\n")
        timing(path, cases)
        if not args.summary_only:
            per_case(cases, raw, results)
    if not args.summary_only:
        print("## Songs\n")
        songs = {case["id"].split("/")[0]: case["songName"] for case in cases}
        table(("Song ID", "Name"), [list(pair) for pair in songs.items()])


if __name__ == "__main__":
    main()

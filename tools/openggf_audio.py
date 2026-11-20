#!/usr/bin/env python3
"""Reproduce or verify the reviewed OpenGGF audio snapshot from committed Git objects.

The lock is the reviewed allowlist, not a request to discover dependencies. To
re-pin, review and update its revision, paths, hashes and explicit patches first.
Checking an existing snapshot needs neither network access nor an engine clone.
"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


class SnapshotError(Exception):
    """The snapshot or its upstream provenance did not match the reviewed lock."""


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(value):
    if not isinstance(value, str) or not value:
        raise SnapshotError("unsafe empty manifest path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in (".", "..") for part in value.split("/")) or "\\" in value:
        raise SnapshotError(f"unsafe manifest path: {value}")
    return path


def load_manifest(root):
    try:
        lock = json.loads((root / "upstream/openggf-audio.lock.json").read_text(encoding="utf-8"))
        if lock["schemaVersion"] != 1 or not re.fullmatch(r"[a-f0-9]{40}", lock["revision"]):
            raise SnapshotError("unsupported lock schema or non-exact Git revision")
        sources = set()
        destinations = set()
        for entry in lock["files"] + lock["licenses"]:
            path = safe_path(entry["path"])
            if entry in lock["files"]:
                if not str(path).startswith("src/main/java/") or path.suffix != ".java":
                    raise SnapshotError(f"unsafe Java source path: {path}")
                destination = str(path)
            else:
                destination = str(safe_path(entry["destination"]))
                if destination.startswith("src/"):
                    raise SnapshotError(f"unsafe license destination: {destination}")
            if str(path) in sources or destination in destinations:
                raise SnapshotError(f"duplicate lock path: {path}")
            sources.add(str(path))
            destinations.add(destination)
            for field in ("sourceSha256", "vendoredSha256"):
                if not re.fullmatch(r"[a-f0-9]{64}", entry[field]):
                    raise SnapshotError(f"invalid {field} for {path}")
        patched = set()
        for patch in lock["patches"]:
            if patch["path"] not in sources or patch["path"] in patched:
                raise SnapshotError("patch references an absent or duplicate source")
            if not patch["removeExact"] or not patch["reason"]:
                raise SnapshotError("patch requires exact content and a reason")
            if "replaceWith" in patch and not isinstance(patch["replaceWith"], str):
                raise SnapshotError("patch replacement must be text")
            if "endBefore" in patch and (not isinstance(patch["endBefore"], str) or not patch["endBefore"]):
                raise SnapshotError("patch end marker must be nonempty text")
            patched.add(patch["path"])
        return lock
    except (OSError, KeyError, TypeError, ValueError) as error:
        raise SnapshotError(f"invalid lock: {error}") from error


def targets(lock):
    for entry in lock["files"]:
        yield entry, Path("openggf-audio") / entry["path"]
    for entry in lock["licenses"]:
        yield entry, Path("openggf-audio") / entry["destination"]


def upstream_bytes(source, revision, path):
    try:
        return subprocess.check_output(
            ["git", "-C", str(source), "show", f"{revision}:{path}"], stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise SnapshotError(f"Git could not read {revision}:{path}: {error}") from error


def materialize(lock, source):
    """Validate every object and patch before returning any bytes for writing."""
    result = []
    patches = {patch["path"]: patch for patch in lock["patches"]}
    for entry, destination in targets(lock):
        original = upstream_bytes(source, lock["revision"], entry["path"])
        if sha256(original) != entry["sourceSha256"]:
            raise SnapshotError(f"upstream hash mismatch: {entry['path']}")
        vendored = original
        if entry["path"] in patches:
            patch = patches[entry["path"]]
            marker = patch["removeExact"].encode("utf-8")
            if original.count(marker) != 1:
                raise SnapshotError(f"patch precondition requires exactly one occurrence: {entry['path']}")
            start = original.index(marker)
            end = start + len(marker)
            if "endBefore" in patch:
                limit = patch["endBefore"].encode("utf-8")
                if original.count(limit) != 1 or original.index(limit) < end:
                    raise SnapshotError(f"patch precondition requires one end marker after start: {entry['path']}")
                end = original.index(limit)
            replacement = patch.get("replaceWith", "").encode("utf-8")
            vendored = original[:start] + replacement + original[end:]
        if sha256(vendored) != entry["vendoredSha256"]:
            raise SnapshotError(f"patched hash mismatch: {entry['path']}")
        result.append((destination, vendored))
    return result


def reject_extra_files(root, lock):
    expected = {root / path for _, path in targets(lock)}
    for directory in ("openggf-audio/src/main/java", "openggf-audio/LICENSES"):
        tree = root / directory
        if tree.exists():
            unexpected = [path for path in tree.rglob("*") if (path.is_file() or path.is_symlink()) and path not in expected]
            if unexpected:
                raise SnapshotError("unexpected snapshot files: " + ", ".join(str(p.relative_to(root)) for p in unexpected))


def reject_symlink(root, path):
    while path != root:
        if path.is_symlink():
            raise SnapshotError(f"unsafe symlink destination: {path.relative_to(root)}")
        path = path.parent


def check(root, source=None):
    root = Path(root)
    lock = load_manifest(root)
    reject_extra_files(root, lock)
    for entry, destination in targets(lock):
        path = root / destination
        reject_symlink(root, path)
        if not path.is_file():
            raise SnapshotError(f"missing snapshot file: {destination}")
        if sha256(path.read_bytes()) != entry["vendoredSha256"]:
            raise SnapshotError(f"snapshot hash mismatch: {destination}")
    if source is not None:
        materialize(lock, source)
    return lock


def sync(root, source):
    root = Path(root)
    lock = load_manifest(root)
    reject_extra_files(root, lock)
    files = materialize(lock, source)
    # Never follow a symlink in the vendored destination, including a directory.
    for destination, _ in files:
        reject_symlink(root, root / destination)
    for destination, data in files:
        path = root / destination
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    check(root)
    return lock


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("sync", "check"))
    parser.add_argument("--source", type=Path, help="local OpenGGF Git repository; dirty files are ignored")
    args = parser.parse_args()
    if args.operation == "sync" and args.source is None:
        parser.error("sync requires --source")
    root = Path(__file__).resolve().parents[1]
    try:
        lock = sync(root, args.source) if args.operation == "sync" else check(root, args.source)
    except SnapshotError as error:
        print(f"OpenGGF snapshot verification failed: {error}", file=sys.stderr)
        return 1
    print(f"OpenGGF audio snapshot {args.operation}: {len(lock['files'])} sources at {lock['revision']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

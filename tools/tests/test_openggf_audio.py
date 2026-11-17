"""Exercise snapshot provenance and tamper detection with real Git objects."""

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "openggf_audio.py"
SPEC = importlib.util.spec_from_file_location("openggf_audio", SCRIPT)
snapshot = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(snapshot)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "deck"
        self.source = Path(self.temp.name) / "engine"
        self.root.mkdir()
        self.source.mkdir()
        self.run_git("init", "-q")
        self.path = "src/main/java/com/openggf/audio/smps/SmpsSequencer.java"
        self.original = b"package com.openggf.audio.smps;\nimport com.openggf.audio.AudioManager;\nclass SmpsSequencer {}\n"
        self.patched = self.original.replace(b"import com.openggf.audio.AudioManager;\n", b"")
        self.license = b"Upstream license fixture\n"
        self.files = {self.path: self.original, "LICENSE": self.license}
        for path, data in self.files.items():
            target = self.source / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.run_git("add", ".")
        tree = self.run_git("write-tree").strip()
        self.revision = self.run_git(
            "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
            "commit-tree", tree,
            input="Synthetic snapshot fixture\n\nCo-authored-by: OpenAI Codex GPT-6.1 <noreply@openai.com>\n",
        ).strip()
        self.manifest = {
            "schemaVersion": 1,
            "repository": "https://example.test/openggf.git",
            "revision": self.revision,
            "patches": [{
                "path": self.path,
                "removeExact": "import com.openggf.audio.AudioManager;\n",
                "reason": "Unused import would require the engine composition root.",
            }],
            "files": [{
                "path": self.path,
                "sourceSha256": digest(self.original),
                "vendoredSha256": digest(self.patched),
            }],
            "licenses": [{
                "path": "LICENSE",
                "destination": "LICENSE.upstream",
                "sourceSha256": digest(self.license),
                "vendoredSha256": digest(self.license),
            }],
        }
        self.write_manifest()

    def run_git(self, *args, input=None):
        return subprocess.check_output(
            ["git", "-C", str(self.source), *args], text=True, input=input,
            stderr=subprocess.PIPE,
        )

    def write_manifest(self):
        path = self.root / "upstream/openggf-audio.lock.json"
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(self.manifest), encoding="utf-8")

    def target(self):
        return self.root / "openggf-audio" / self.path

    def test_sync_reads_committed_objects_and_is_repeatable(self):
        (self.source / self.path).write_text("dirty unrelated edit", encoding="utf-8")
        snapshot.sync(self.root, self.source)
        self.assertEqual(self.patched, self.target().read_bytes())
        snapshot.check(self.root)
        snapshot.check(self.root, self.source)
        snapshot.sync(self.root, self.source)
        self.assertEqual(self.patched, self.target().read_bytes())
        self.assertEqual("dirty unrelated edit", (self.source / self.path).read_text())

    def test_offline_check_rejects_tampering_missing_and_extra_files(self):
        snapshot.sync(self.root, self.source)
        self.target().write_bytes(b"tampered")
        with self.assertRaisesRegex(snapshot.SnapshotError, "hash mismatch"):
            snapshot.check(self.root)
        self.target().unlink()
        with self.assertRaisesRegex(snapshot.SnapshotError, "missing"):
            snapshot.check(self.root)
        snapshot.sync(self.root, self.source)
        extra = self.target().parent / "Stale.java"
        extra.write_text("stale", encoding="utf-8")
        with self.assertRaisesRegex(snapshot.SnapshotError, "unexpected"):
            snapshot.check(self.root)
        with self.assertRaisesRegex(snapshot.SnapshotError, "unexpected"):
            snapshot.sync(self.root, self.source)
        self.assertTrue(extra.exists(), "Unknown files must never be deleted by sync")

    def test_license_tampering_is_detected_without_upstream(self):
        snapshot.sync(self.root, self.source)
        (self.root / "openggf-audio/LICENSE.upstream").write_bytes(b"changed")
        with self.assertRaisesRegex(snapshot.SnapshotError, "hash mismatch"):
            snapshot.check(self.root)

    def test_patch_requires_exactly_one_occurrence_before_writing(self):
        self.manifest["patches"][0]["removeExact"] = "missing import\n"
        self.write_manifest()
        with self.assertRaisesRegex(snapshot.SnapshotError, "patch precondition"):
            snapshot.sync(self.root, self.source)
        self.assertFalse(self.target().exists())

    def test_source_digest_mismatch_is_rejected_before_writing(self):
        self.manifest["files"][0]["sourceSha256"] = "0" * 64
        self.write_manifest()
        with self.assertRaisesRegex(snapshot.SnapshotError, "upstream hash mismatch"):
            snapshot.sync(self.root, self.source)
        self.assertFalse(self.target().exists())

    def test_symlinked_destination_is_neither_followed_nor_accepted(self):
        snapshot.sync(self.root, self.source)
        outside = Path(self.temp.name) / "outside.java"
        outside.write_bytes(self.patched)
        self.target().unlink()
        self.target().symlink_to(outside)
        with self.assertRaisesRegex(snapshot.SnapshotError, "symlink"):
            snapshot.check(self.root)
        with self.assertRaisesRegex(snapshot.SnapshotError, "symlink"):
            snapshot.sync(self.root, self.source)
        self.assertEqual(self.patched, outside.read_bytes())

    def test_invalid_manifest_cannot_escape_snapshot_directory(self):
        self.manifest["files"][0]["path"] = "src/main/java/../../outside.java"
        self.write_manifest()
        with self.assertRaisesRegex(snapshot.SnapshotError, "unsafe"):
            snapshot.sync(self.root, self.source)

    def test_missing_revision_fails_without_mutating_existing_snapshot(self):
        snapshot.sync(self.root, self.source)
        self.manifest["revision"] = "0" * 40
        self.write_manifest()
        with self.assertRaisesRegex(snapshot.SnapshotError, "Git"):
            snapshot.sync(self.root, self.source)
        self.assertEqual(self.patched, self.target().read_bytes())


if __name__ == "__main__":
    unittest.main()

# S3K Playback Foundation Implementation Plan

**Goal:** Establish reproducible playback through current OpenGGF before building
the new authoring model or interface.

**Architecture:** Add `openggf-audio` beside legacy `synth-core`, retaining
upstream namespaces and a hash manifest. A separate `headless` module exercises
the production session path without JavaFX or a sound device.

**Tech stack:** Java 25 for the new modules, Maven, Python 3 standard library for
source extraction and independent parity checks.

**Spec:** [Reboot design](../specs/2026-11-17-reimagined-design.md).

## Constraints

Work only on `codex/reimagined` in its existing isolated worktree. Read upstream
sources from committed objects at `a1c4aac7ae2708f5c487e8000da80502e854abe2`.
Do not modify the OpenGGF checkout or legacy playback. Preserve license notices.
First delivery is a raw playback proof, not a new music editor.

## Tasks

### 1. Reproducible extraction

- [x] Add `upstream/openggf-audio.lock.json`, `tools/openggf_audio.py` and
  extraction tests under `tools/tests/`.
- [x] Record original and vendored hashes for the reviewed dependency closure
  and licenses. Apply only the declared unused-import deletion.
- [x] Support offline integrity checks and exact-commit upstream provenance
  checks. Test altered/missing/extra files, dirty checkout isolation and patch
  preconditions. Package the snapshot in `openggf-audio`.

### 2. Headless S3K proof

- [x] Add `headless` with a runnable CLI, a synthetic FM/PSG demo, and bounded
  standalone `.bin` rendering. Use production music activation and per-frame
  service/render cadence with accurate chip settings.
- [x] Test deterministic PCM, non-silence, WAV structure and frame counts,
  malformed inputs, unsupported assets/commands and safe output behaviour.
- [x] Document the deliberately finite first input subset and command examples.

### 3. Evidence and PR

- [x] Add an independent parity probe compiled against original Git objects and
  vendored sources; compare PCM and chip writes for synthetic fixtures.
- [x] Run extraction tests, new Java tests, demo/CLI and parity checks. Run the
  existing suite and compare failure identities against baseline.
- [x] Review the authored changes and provenance, fix substantive issues and
  record evidence/limitations.

Delivery: push the verified task branch, create an open PR, and keep its worktree.

## Review focus

An upstream dirty checkout must not contaminate the snapshot. Unresolved sample
or envelope references must not quietly render a different song. Header pointers
and channel counts must be bounded. Frame-clock settings must match the chosen
upstream production path. Existing unrelated test failures must be identified
and compared, not hidden by reporting only the new modules.

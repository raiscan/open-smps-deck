# Timeline and live refresh implementation plan

**Goal:** A human-editable timeline and shared JSON source that refreshes from
external-agent edits and renders with the pinned S3K core.

**Spec:** [Design and exact interfaces](../specs/2026-11-17-timeline-live-refresh-design.md).
**Stack:** Java 25, JavaFX, Gson, Maven. Existing isolated `codex/reimagined`.

- [x] Add strict immutable project records, source codec, validated pure edits,
  demo and a finite S3K compiler; prove timing, repetitions, occupancy and bounds.
- [x] Add hashed file persistence, guarded atomic saves, dirty/conflict recovery
  and a closeable watcher; test real filesystem edits and replacements.
- [x] Add the new JavaFX studio: sequence browser, editable clip timeline,
  piano roll/inspector, playback/export, stable selection and live status.
- [x] Add headless source validate/compile/render commands, an example source,
  runnable studio packaging, documentation and focused CI.
- [x] Exercise actual UI editing/refresh under Xvfb, inspect its snapshot, run
  relevant tests and compare the legacy suite with its known nine failures.
- [x] Review and fix substantive issues; record local verification evidence.

Delivery: commit/push the verified task branch and update the open PR.

Review focus: preserve unsaved work and unrelated external content, atomic file
replacement notifications, invalid intermediate writes, JavaFX thread confinement,
repeat/partial-repeat timing, sparse hardware lanes, bounded long notes/output,
playback/watcher shutdown and packaged runtime behaviour.

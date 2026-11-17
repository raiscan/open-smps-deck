# Native S3K command integration plan

**Goal:** Add native command editing to the accepted Studio while tracking every
remaining requirement for full S3K support before import.
**Spec:** [Native command design](../specs/2026-11-17-s3k-native-commands-design.md).
**Stack:** Existing Java 25 modules, JavaFX and pinned OpenGGF.

- [x] Record the complete S3K coverage matrix, including driver limitations and
  the evidence needed before a feature is declared complete.
- [x] Add strict, backwards-compatible v2 source commands and repeat state;
  compile merged native stream units without retriggering sustained notes.
- [x] Extend bounded renderer validation and prove frame-stamped native command
  timing/state against handwritten reference streams.
- [x] Integrate command/source controls into the existing editor, preserving
  undo, live refresh, stable IDs, DPI and recovery behaviour.
- [x] Add a separate native-command example and agent/human documentation, run
  focused/native-pointer/regression checks, review and update the open PR.

Preserve the existing untracked user recovery project under examples/. Do not
stage it or change it. Keep the existing UI and legacy source behaviour. No
vendor source modifications and no importer in this slice.

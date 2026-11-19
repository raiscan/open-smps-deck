# Voices and assets implementation plan

**Goal:** Native FM voice editing and bounded PSG/modulation envelope assets through
source, UI, render and complete package export.
**Spec:** [Design](../specs/2026-11-17-voices-assets-design.md).
**Stack:** Existing Java25 project-core/headless/JavaFX Studio and unchanged pinned
OpenGGF audio.

- [x] Project-core: mask-preserving NativeFmVoice helper; immutable Envelope and
  bounded EnvelopeProgram; compatible v3 project/source records and strict JSON;
  pure asset edits; asset-aware compiler/reset and CompiledProject. Test literal
  masks, strict resource references, old-version bytes and copy preservation.
- [x] Headless: resource-aware validator/renderer/WAV, CLI and deterministic native
  ZIP package; extend independent extraction-parity bridge. Test real PCM and
  frame writes, missing/unsafe resources before output, package completeness and
  no overwrite.
- [x] Studio: native voice pane, envelope pane, source asset assignment and Assets
  tabs; preserve every new field in project copies; preview/WAV/package export;
  focus routing. Test real controls, linked edits, undo/save, stale/deleted drafts
  and existing high-DPI pointer flows.
- [x] Document safe profile, native package adapter, example and remaining S3K
  gaps; run focused/full/native checks, independent review and update PR#3.

Model and headless implementers own their module source/tests. Voice pane worker
owns only VoiceEditorPane and its helper tests. Root owns integration, envelope
pane, docs and real-scene tests. Run Maven sequentially to avoid target races.

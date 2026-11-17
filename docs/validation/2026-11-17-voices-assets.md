# Native voices and envelope assets: verification

Verified locally on 2026-11-17 in `codex/reimagined`, continuing the accepted
timeline. The pinned audio source remains unchanged at
`a1c4aac7ae2708f5c487e8000da80502e854abe2`.

## Delivered behavior

- FM voice create/duplicate/rename/edit/delete-unused; algorithm/feedback and
  four native stored slots, preserving unrelated bits and independent TL-bit7
  track-volume flags. Parameter/name and exact 25-byte hex drafts are separate.
- Strict v3 typed PSG-volume/modulation envelope assets and stable source
  assignments. V1/V2 field sets and compiled bytes remain compatible. All edit
  and project-copy paths preserve the new assets and source references.
- Resource-aware production rendering, Studio preview/WAV and headless commands.
  Every declared envelope, including unused resources, validates before output.
- Deterministic native ZIP export with baked track, all envelope bytes, source,
  native indices and hashes. Bare binary export refuses to drop declared assets.
- Linked asset UI, source-to-asset editing links, common undo/save and retained
  stale/deleted drafts that cannot overwrite an agent's newer file revision.

The [support matrix](../architecture/s3k-support.md) and
[package contract](../architecture/native-asset-package.md) record exact limits.
This is a bounded envelope profile, not complete S3K envelope/track support or
an existing OpenGGF ZIP loader. DAC and banked/shared/global voices remain work.

## Passing checks

- Isolated Xvfb `mvn -B -pl studio -am verify -Dstudio.ui.test=true
  -Dstudio.snapshot=<absolute PNG>`: **127 Java tests** pass (57 project-core,
  41 headless, 29 Studio), no failures/errors/skips. The final full-suite run
  executes these same new-path tests successfully.
- New model tests cover native masks/high bits/slot order, strict v3 round trips,
  unsafe programs, resource kinds/references/counts, edit/copy preservation,
  reset/carry bytes and unchanged legacy-version serialization/compilation.
- Eleven headless asset cases compare compiled projects against independently
  handwritten banks and explicitly installed pinned-core tables: PCM,
  presentation-stamped chip writes, PSG attenuation, modulation, productive
  resets, independent-clip clearing and header initialization. Native FM tests
  assert actual register placement and TL-bit7 track-volume effects. Missing,
  unsafe, unknown, unused-invalid and oversized resources fail before PCM/WAV.
- Four package cases check exact entries, every declared/unused resource, hashes,
  source round trips, native namespaces, path-safe names, fixed time/STORED entries,
  byte reproducibility, existing file/symlink preservation, interruption cleanup
  and CLI refusal of asset-losing bare exports.
- Actual JavaFX scenes cover voice controls/raw high bits, asset CRUD and
  reference protection, undo/save, envelope assignment and editor navigation,
  atomic external updates, retained drafts, removed assets and document reset.
- `python3 tools/verify_studio_dpi.py --scratch <disk scratch>`: **all six packaged
  native-pointer cases pass** at 100%, desktop 150%/160% and 200% property/environment/
  GDK precedence. Each edits clips/notes/commands, applies a voice, creates an
  envelope and clicks Save, then reads the shared file. Controls remain scrollable
  at native scale; snapshots were visually inspected in `studio/target/qa/`.
  One run read asset state before the fixed post-click pause had reliably
  established save completion; an isolated 200% reproduction passed. The harness
  now waits up to five seconds for the document to become clean before readback,
  still failing and capturing a scene if a physical Save click never completes.
- Packaged CLI validates the new
  [example](../../examples/voices-and-envelopes.smps.json) to **531 native bytes**,
  renders 1,800 presentations (30 seconds, 48 kHz, 16-bit stereo) and writes the five
  package entries. Manifest hashes match every resource. Example PCM SHA-256:
  `6a00445c2668c544e72b4baa01107767ce101db954373a4438fc52dbdd6f7bc1`.
- All 8 Python tooling tests and the 87-source extraction/provenance check pass.
  Independent original-source/extracted parity passes with the updated JDK-only
  asset bridge. The three raw PCM/write fixtures and production demo golden are
  unchanged. Both finished executable JARs retain every locked source and license.
- Actionlint, workflow YAML, local documentation links and whitespace checks pass.
  Fresh independent read-only review found a missing source-to-asset navigation
  route; it was added and covered by a real-scene test. No model/renderer safety
  finding remained. Parent inspection also moved declaration validation before
  building renderer resource maps.

## Full suite and remaining limits

Final isolated-display `mvn -B --fail-at-end test -Dstudio.ui.test=true
-Dstudio.snapshot=<absolute PNG>`: **826 tests, 9 failures, 0 errors, 2 skips**.
The failure identities/types/assertions match the
[recorded legacy baseline](2026-11-17-s3k-foundation.md): eight missing Drowning-rip
assertions and the DAC subdirectory parsing assertion. The same two legacy import
cases skip. No new or worsened failures were observed; all 127 new-path tests pass.

No ROM/song importer or physical-device audibility test is included. The safe
envelope profile rejects native branches, multipliers and negative modulation
offsets pending driver/native resolution. The ZIP describes an adapter contract;
OpenGGF does not load it directly yet. Hosted CI status for the pushed revision
is separate from these local results. Main master and the pre-existing untracked
user recovery project were preserved.

# Native bank and import readiness checks

The v5 project stores the complete bank, header location, explicit voice table
extent, and asset bindings. The synthetic tests cover analysis of calls and loops
that share data before the header, immediate-cycle rejection, exact package
preservation, playback timing, and Studio edits, undo and external refresh.

## Verified

- `xvfb-run -a mvn -B -pl studio -am verify -Dstudio.ui.test=true` passed
  174 tests (73 project-core, 63 headless, 38 Studio). The screenshot check
  ran in the full reactor with `-Dstudio.snapshot`.
- The packaged Studio opened the original native example at 200% scale.
  `python3 tools/verify_studio_dpi.py` passed all six packaged arrangements
  at 100–200%, including pointer edits and save.
- The packaged CLI inspected 27 events, rendered 360 presentations, and
  exported a schema 3 package with all 67 original bank bytes. The WAV contains
  288,000 stereo frames at 48 kHz, with PCM SHA-256
  `d1d601f2f480c769b1ea0d036436000af51411e6459e2885fc54f2ef4fb27672`.
- `python3 tools/openggf_audio.py check --source /path/to/OpenGGF` verified
  all 87 sources and retained licenses at
  `a1c4aac7ae2708f5c487e8000da80502e854abe2`. The eight extraction-tool tests,
  independent PCM/chip-write parity checks and workflow lint passed.
- `xvfb-run -a mvn -B --fail-at-end verify -Dstudio.ui.test=true` ran 874
  tests across the reactor. The legacy app's 647 outcomes matched the committed
  `42232926bac6229503d7149d01c5a98d4083c0e9` baseline by test identity,
  exception type and normalized message. There are nine existing failures
  and two existing skips. [Baseline details](2026-11-17-s3k-foundation.md)

## Locked-on S3&K catalogue

A read-only probe ran the current preflight on all 51 music IDs from the
combined S3&K ROM with SHA-1 `cfbf98c36c776677290a872547ac47c53d2761d6`.
It used the existing OpenGGF loader for the original banks and all dependency
declarations. The loader supplied 35 global voices, 39 PSG volume envelopes,
eight modulation envelopes and 68 DAC mappings to 47 distinct samples. The
probe inferred the minimum local voice extent from reached voice references.
It wrote no ROM-derived assets.

23 IDs passed preflight across 22 distinct headers. ID `2E` shares the
header of `18`. 28 distinct headers failed at the first blocker below.
Numbers in the ID column are hexadecimal.

| First preflight blocker | Count | Music IDs |
| --- | --- | --- |
| PSG envelope slot 2 outside the approved playback profile | 9 | `01 02 0B 0C 13 1C 22 23 2F` |
| HOLD with nonzero note fill | 7 | `07 08 1F 25 2C 2D 32` |
| DAC pan on FM6 | 5 | `03 04 14 15 33` |
| Modulation envelope slot 1 or 5 outside the playback profile | 4 | `0D 0E 1D 27` |
| FM level change before voice initialization | 1 | `1E` |
| FM voice command on a PSG track | 1 | `29` |
| Previous-song restoration via `E2 FF` | 1 | `2A` |

The first-blocker counts establish reachability and identify priorities.
Later blockers can appear when the first one is resolved. Playback fidelity
still requires native reference comparisons of PCM, writes and timing.
The import gate needs those comparisons and an OpenGGF pin with the required
semantics. [S3K coverage matrix](../architecture/s3k-support.md)

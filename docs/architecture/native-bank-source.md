# Address-preserving S3K bank projects

Version 5 is the native source destination for a future importer. One byte
array owns the complete bank, including the selected song header, shared
phrases, embedded voices, and bytes outside the reached instructions. This
preserves positional addresses and raw initial channel state. Versions 1–4
continue to use the arrangement model for musical notes and clips.

The source has the project name, S3K mode, ticks per beat, global voices,
envelopes and samples. The required `nativeProgram` object declares:

| Field | Meaning |
| --- | --- |
| `bankBase` | Base 0 for an explicitly normalized bank or 32768 for an absolute Z80 window |
| `headerOffset` | Byte offset of the selected song header inside this bank |
| `localVoiceCount` | Count of complete 25-byte voices at the header voice pointer. Zero selects global lookup using the retail table address or the synthetic zero-pointer convention |
| `previewFrames` | Number of 60 Hz output presentations for preview and default source WAV rendering |
| `bankBase64` | Exact bank bytes in canonical padded base64, at most 32 KiB |
| `envelopeSlots` | Asset ID and explicit native slot in its PSG-volume or modulation namespace |
| `dacSlots` | Exact native note byte, sample ID and rate byte for each bound note |

The ordered `voices` list supplies the optional global table. Local and
shared in-bank voices remain in the byte array at their native address.
Volume-envelope and DAC bindings can be sparse. Multiple DAC notes can point
to the same PCM at different native rates. These mappings never depend on
the arrangement's generated asset numbering.

The tempo field is derived from the header. The native project has no clip or
sequence arrays. A header can carry seven declared FM/DAC entries so the source
can preserve the retail inactive seventh entry. The playback profile only
accepts that shape with a PSG entry that overwrites the native RAM slot.

## Studio and CLI

Open an existing v5 source in Studio to read the reached event list and edit
bank bytes. An in-place edit preserves layout and length. Changes use project
undo, save and external refresh. A stale draft is kept and its Apply action
refuses to replace the newer disk revision. The preview length is explicit
because a native sequence can loop or change tempo during playback.

```sh
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar inspect-project examples/native-evening-clock.smps.json
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar render-project examples/native-evening-clock.smps.json /path/to/new-preview.wav
java -jar headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar compile-package examples/native-evening-clock.smps.json /path/to/new-bank.smps.zip
```

`inspect-project` runs the playback preflight and reports reached native addresses,
exact event bytes, and their meanings. The traversal follows calls, returns,
counted loops, and jumps, including targets before the header. It checks used
asset slots, operand/address bounds, return depth, and whether repeating control
flow lets time advance. An explicit duration byte can also be a separately
reached event. A pointer into a command operand fails preflight.

Validation, Studio preview, and WAV rendering use the same playback boundary.
If preflight finds unsupported semantics, Studio shows the exact reason and
keeps the stored byte array editable. Source save and schema 3 package export
preserve unsupported data, including envelope programs outside the supported
playback profile. See the [asset package contract](native-asset-package.md).

## Import readiness

A future importer can write an exact bank without first interpreting it as
piano-roll notes. The remaining gate is compatibility of shipped music and
native runtime dependencies. The following are concrete checks for that gate.

| Gate | Evidence needed |
| --- | --- |
| Bank layout and dependencies | Reachability from the active tracks in each S3K variant. Resolve shared phrases and instrument data, table and header addresses, original numeric envelope indices, and per-note DAC sample/rate descriptors. Preserve any inactive header entries. |
| Stream semantics | Native timing and register comparisons for explicit and reused durations, bare-duration replay, HOLD with fill, tempo changes, stack and loop counters, voice changes, PSG envelope/noise and DAC timing. |
| Engine blockers | Signed modulation envelope values used in Carnival Night and Launch Base, exact wrapped duration and fill state, song restoration for the 1-UP cue, and DAC rest replay and active stop semantics. Re-pin after fixes are backed by native playback evidence. |
| Whole-song acceptance | Run the strict traversal on every song in the selected retail variant with resolved dependencies. Use local user-supplied assets and record identity, coverage and timing evidence. Check silent lanes and cross-track timing as well as sound. |
| Additional defined commands | Track native cross-song voices, conditional loop exit, direct register writes, FM3 special mode, custom FM envelopes, speed changes and game/SFX context separately from reached retail uses. |

The synthetic example proves the v5 workflow, sparse mappings, tempo events and
shared control-flow paths. In a local check of the locked-on retail catalogue,
23 of 51 music IDs passed preflight, with one alias among the passes. The
remaining 28 have their first blocker recorded in the
[validation report](../validation/2026-11-17-native-import-target.md).
Whole-song native PCM and register parity remain open.

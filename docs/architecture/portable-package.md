# Portable SMPS song package

This normative contract covers the OpenSMPSDeck writer and reader, the
OpenSMPSDeck disassembly kit, and the OpenGGF `smpsPackage` track kind.
Record implementation deviations here and in the corresponding PR.

Keywords: MUST, MUST NOT and SHOULD as in RFC 2119.

## 0. Goals and policy

- A song package plays in OpenGGF on the player's machine. Stock game assets
  are **references**, resolved from the player's own legally obtained ROM and
  checked by SHA-256. Custom FM voices, PSG/modulation envelopes and DAC
  samples are embedded.
- Policy: **warn and record, never refuse on provenance.** Packages that embed
  ROM-derived assets or retail song data MAY be written in any mode. The
  writer MUST record this in `provenance` and warn the user (CLI stderr, MCP
  result `warnings`, Studio confirmation dialog). Readers and validators MUST
  surface the same warning when they load such a package. The portable
  reference mode is the recommended way to make a shareable package.
- The package declares one target **driver**: `s1`, `s2` or `s3k` (default
  `s3k`). Sonic CD's FM driver has no songs or DAC, so it is not a music target.

## 1. Container

- A ZIP file named `*.smps.zip`. Entry names are UTF-8, stored with no
  compression, every timestamp is 0, and there are no directories entries,
  duplicate names, absolute paths, `..` segments or backslashes. Asset IDs
  and display names MUST NOT become paths.
- Entry order:
  1. `manifest.json`
  2. `track.bin`
  3. optional `voices/global.bin`
  4. `psg/asset-NNN.bin`
  5. `mod/asset-NNN.bin`
  6. `dac/asset-NNN.pcm`
  7. optional `source.smps.json`
  8. optional `LICENSE.txt`

  `NNN` is a 1-based ordinal per kind for envelopes and a 0-based ordinal
  for DAC, zero-padded to 3 digits.
- Writing is deterministic: identical input produces identical bytes. The
  writer uses CREATE_NEW, never overwrites an existing file or symlink, and
  removes only its own partial output on failure.
- `manifest.json` is UTF-8 JSON, pretty-printed with 2-space indent, with
  keys in the order given in §2 and a trailing newline.
- Readers MUST reject unknown fields, missing required fields, unknown enum
  values, entries not named by the manifest, hash mismatches and limit
  violations (§8).
- When embedding a package inside an OpenGGF mod JAR, the same entries are
  stored **exploded** under `audio/smps/<track-id>/`, byte-identical to the
  ZIP entries (§9).

## 2. Manifest, schema 4

```json
{
  "schemaVersion": 4,
  "profile": "portable",
  "driver": "s3k",
  "package": {
    "id": "my-song",
    "name": "My Song",
    "version": "1.0.0",
    "authors": ["Author"],
    "license": null,
    "rights": {"composition": null, "notes": null}
  },
  "generator": {"tool": "OpenSMPSDeck", "version": "0.1.0-SNAPSHOT",
                "openggfAudio": "<40-hex pinned OpenGGF revision>"},
  "provenance": {
    "containsRomDerivedData": false,
    "containsRetailSongData": false,
    "derivedFromRetailSong": null,
    "warnings": []
  },
  "requires": {"stockAssets": true, "games": ["s3k"]},
  "driverOptions": {},
  "bank": {
    "form": "embedded",
    "path": "track.bin",
    "sha256": "<hex of stored bytes, holes zeroed>",
    "assembledSha256": "<hex after fills>",
    "base": 0,
    "headerOffset": 0,
    "localVoiceCount": 4,
    "previewFrames": 3600,
    "relocations": [2, 4, 6],
    "relocationsComplete": true,
    "fills": [{"offset": 812, "length": 25, "asset": "fm/0"}]
  },
  "assets": [],
  "envelopeBindings": [],
  "dacBindings": [],
  "globalVoices": null,
  "source": null
}
```

The fields are:

- `profile`: always `"portable"` in schema 4.
- `package.id`: matches `[a-z0-9][a-z0-9-]{0,63}`.
- `package.version`: strict `MAJOR.MINOR.PATCH`.
- `package.authors`: 0–32 nonblank strings.
- `package.license`: `null` (the default; no license granted) or an SPDX
  license expression from `--license`. When it is non-null, the writer MAY
  add `LICENSE.txt` containing only the expression and the authors.
- `package.rights`: free text supplied by the author, or null. It is never
  validated.
- `generator.version` and `generator.openggfAudio` are informational.
  `openggfAudio` is the pinned revision from `upstream/openggf-audio.lock.json`.
- `provenance`:
  - `containsRomDerivedData`: true if **any embedded byte** (an asset file,
    bank bytes, global voices or source) is a stock ROM payload or a
    transform of one.
  - `containsRetailSongData`: true if the embedded bank, or the embedded
    source, contains a retail song's bytecode or note data (imported with
    `import-rom-song`, or derived through `copy-arrangement`).
  - `derivedFromRetailSong`: `null` or
    `{"game": "s3k", "songId": 13, "bankSha256": "<hex>"}`. It is set
    whenever the song derives from a retail song, even when
    `containsRetailSongData` is false (patch form, §5.4).
  - `warnings`: the sorted unique warning codes from §7.
- `requires.stockAssets` is true if any asset has a `ref` without an
  embedded payload. `requires.games` is the sorted unique `ref.game` values.
- `driverOptions`: `{}` for s3k. For s1 and s2:
  `{"speedUpTempo": <0–255> | null}`, the target-native speed-shoes tempo byte. Null means
  speed shoes leave tempo unchanged. On activation with speed shoes, or when
  the effect turns on, a host MUST use this byte in place of the song's normal
  tempo. Turning the effect off restores the normal tempo. Hosts MUST NOT
  index the game's retail speed table by a replacement ID. The export inputs
  `--speed-up-tempo` and MCP `speedUpTempo` are S3K-clock bytes. Writers
  convert them before storing the native byte, using the same conversion
  and warning or strictness policy as for the normal tempo.
- `bank`: see §4.
- `assets`, `envelopeBindings`, `dacBindings`, `globalVoices`: see §3.
- `source`: `null`, or
  `{"path": "source.smps.json", "sha256": "<hex>", "redacted": true|false}`.
  It is included by default. `--no-source` omits it. A redacted source
  replaces each stock asset's bytes with its reference (§6).

## 3. Assets and bindings

Each `assets[]` entry:

```json
{"key": "dac/1", "kind": "DAC_SAMPLE", "id": "<project asset id>", "name": "<display>",
 "origin": {"type": "stock"},
 "ref": {"game": "s3k", "table": "dac", "note": 129, "sha256": "<hex of resolved payload>"},
 "transforms": [{"op": "gain", "factor": "0x1.ecfdbd6190364p0"}],
 "resultSha256": "<hex after transforms>"}
```

- `key` is `<kindprefix>/<n>`, where the prefix is `fm`, `psg`, `mod` or
  `dac` and `n` is a 0-based ordinal per prefix in project declaration order.
  Keys are the only cross-references inside the manifest.
- `kind`: `FM_VOICE`, `PSG_VOLUME`, `MODULATION` or `DAC_SAMPLE`.
- `origin.type`:
  - `original`: authored.
  - `source-audio`: sliced or derived from an input recording. Emits the
    warning `SOURCE_AUDIO`.
  - `third-party`: additionally requires `origin.license` (SPDX) and
    `origin.attribution` (string). Emits `THIRD_PARTY`.
  - `stock`: comes from a retail ROM.
- **The payload is exactly one of the following:**
  - Embedded: `path` + `sha256`, or for an FM voice inside the bank's local
    voice table, `bankOffset`. It is the final bytes, after any transform.
    An embedded global voice uses `path: "voices/global.bin"` and the
    SHA-256 of its own 25-byte slice. Its ordinal in `globalVoices.voices`
    selects the slice. The table's own hash covers the full stored file.
  - Reference: `ref` + `transforms` (possibly empty) + `resultSha256` when
    `transforms` is non-empty. There are no payload bytes in the package.
  - A `stock` asset with no known locator MUST be embedded. It sets
    `containsRomDerivedData`.
- In portable mode, every `stock` asset with a known locator MUST be a
  reference. The writer MUST also convert undeclared embedded payloads whose
  SHA-256 matches the public stock table (§6.3) into references, and report
  each conversion as an `UNDECLARED_STOCK_CONVERTED` warning naming the asset.

### 3.1 Stock locators (`ref`)

`ref.game` is the ROM family: `s1`, `s2` or `s3k`. In this version
`ref.game` MUST equal the package `driver`. `ref.sha256` is the SHA-256 of
the exact payload defined below, before transforms.

| driver | `table` | Locator fields | Payload as resolved |
|---|---|---|---|
| s3k | `global-fm` | `slot` | 25 bytes at `slot*25` of `Sonic3kSmpsLoader.getGlobalVoiceData()` (Universal Voice Bank) |
| s3k | `local-fm` | `songId`, `slot` | 25 bytes at `slot*25` from the voice pointer of the song's header, from the bank that `loadMusic(songId)` returns |
| s3k | `psg` | `index` | `getPsgEnvelopes().get(index)` |
| s3k | `mod` | `index` | `getPlaybackModEnvelopes().get(index)`: the playback view, with `82`/`84` operands resolved |
| s3k | `dac` | `note` (`0x81`–`0xC4`) | decoded unsigned 8-bit PCM for that note from `loadDacData()` |
| s1 | `local-fm` | `songId`, zero-based `slot` | Raw 25-byte slice of `Sonic1SmpsLoader.loadMusic(songId).getData()`, at the big-endian voice pointer plus `25*slot`. Do not use `getVoice(slot)`, which reorders operators |
| s2 | `local-fm` | `songId`, zero-based `slot` | Raw 25-byte slice of `Sonic2SmpsLoader.loadMusic(songId).getData()`, at the little-endian voice pointer minus `getZ80StartAddress()` plus `25*slot` |
| s1 | `psg` | `index` (1–9) | `Sonic1SmpsLoader.getPsgEnvelopes()[index - 1]`, including the `80` terminator |
| s2 | `psg` | `index` (1–13) | The ROM's own decompressed Z80 `zPSG_EnvTbl` entry, including the `80` terminator, extracted as specified below |
| s1 | `dac` | `note` (`81`–`83`, `88`–`8B` hex) | Decoded unsigned 8-bit PCM at `loadDacData().sample(mappingForNote(note).sampleId())`. Timpani aliases share PCM |
| s2 | `dac` | `note` (`81`–`91` hex) | Same lookup through `Sonic2SmpsLoader.loadDacData()`. Pitch aliases share PCM |

`songId` is the **engine music ID** passed to `loadMusic`. S1 uses
`Sonic1Music`. S2 uses `Sonic2Music.id`, including its sparse numbering.
For example, Emerald Hill is `81` hex in the engine table.
S3K uses the locked-on catalogue numbering. A resolver given a standalone
Sonic 3 or Sonic & Knuckles image maps the ID through
`Sonic3kAudioResourceProfile` and fails that asset if it is absent.

An S1/S2 zero voice pointer has no local table. Count complete 25-byte
slots to the loader view's end, except S1 `93` hex (Get Emerald, 2 slots)
and S2 `BD` hex (Credits, 38 slots). These loader views extend beyond the
voice table. The resolver MUST exclude the trailing bytes from the locator
space. The `BANK` hash in §6.3 still covers the entire loader view.

**S2 PSG extraction (REV00 and REV01).** Both revisions have these same
coordinates. Offsets refer to the unheadered, linear Mega Drive ROM.

| Field | REV00 | REV01 |
|---|---:|---:|
| `DecompressSoundDriver` (`LEA Snd_Driver(pc),A6`) | `0xEC04A` | `0xEC04A` |
| `MOVE.W #size,D7` immediate (BE word) | `0xEC050` | `0xEC050` |
| `Snd_Driver` payload start | `0xEC0E8` | `0xEC0E8` |
| Payload length | `0x0F64` | `0x0F64` |
| `zPSG_EnvTbl` after decompression | `0x1029` | `0x1029` |

Verify the `4DFA` and `3E3C` instructions and their operands. The signed
LEA displacement is relative to the address of its extension word at
`0xEC04C`. Decompress exactly the indicated raw Saxman payload with no
on-ROM size prefix (equivalent to `SaxmanDecompressor.decompressRaw`).
Read 13 consecutive little-endian 16-bit offsets into decompressed Z80 RAM
from `0x1029`. For index *i*, read the word at `0x1029 + 2*(i-1)`. Its
target MUST be at or after `0x1043` and inside the decompressed driver.
Starting at the target, read bytes below `0x80` until the first byte at or
above `0x80`. That byte MUST be `0x80` and is included in the payload.
Reject out-of-bounds pointers, missing terminators, other command bytes,
programs longer than 256 bytes, or an unrecognized driver layout. Verify
the resulting asset hash as usual. The pinned S2 loader's own PSG table
is not a fallback.

### 3.2 Transforms (closed set, applied in list order)

- `{"op": "gain", "factor": "<Double.toHexString>"}`, for DAC only. For each
  byte `u`: `clamp(Math.round((u - 128) * factor) + 128, 0, 255)`. This is
  exactly `DacSample.applyGain`, with Java `Math.round` on double.
- `{"op": "trim", "start": a, "end": b}`, for DAC only, with
  `0 ≤ a < b ≤ length`. The result is the bytes `[a, b)`.
- `{"op": "patch", "bytes": [{"offset": o, "value": v}]}`, for FM and
  envelopes. Offsets are sorted, unique and in range, and `v` is in 0–255.

An unknown `op` is an error. After transforms, the result MUST satisfy the
kind's validity rules (§3.4) and hash to `resultSha256`.

### 3.3 Bindings

- `envelopeBindings`: `[{"asset": "psg/0", "slot": 1}]`. PSG volume slots
  are 1–255 and modulation slots 1–127 (128 is reserved). The same
  semantics as schema 3 `envelopeSlots`. S1 and S2 bind PSG volume only.
- `dacBindings`: `[{"note": 129, "asset": "dac/0", "rate": 6}]`. One entry
  per note, sorted by note. `note` is in `0x81`–`0xDF` and `rate` is in
  0–255. A sample MAY appear in several bindings.
- `globalVoices`: `null`, or `{"path": "voices/global.bin", "sha256": …,
  "voices": ["fm/3", …]}` for a v5 bank's ordered global table. Stock
  entries are zero-filled 25-byte holes, filled at load time like bank fills.
  S1 and S2 MUST use null.

### 3.4 Asset validity

| Kind | Rules |
|---|---|
| FM_VOICE | Exactly 25 bytes in native order for the driver. Algorithm/feedback, then DT·MUL, RS·AR, AM·D1R, D2R, D1L·RR and TL, each for 4 operators. S1 and S3K store each group in 4,3,2,1 order. S2 stores 4,2,3,1. The S1 data parser performs its own playback swap |
| PSG_VOLUME | S3K uses the project-core playback profile. S1/S2 use 2–256 bytes consisting of attenuation bytes below `80` hex followed by one `80` hold terminator |
| MODULATION | S3K only, 1–256 bytes. The existing project-core playback profile applies |
| DAC_SAMPLE | Unsigned 8-bit mono PCM, midpoint `0x80`, 1–65,535 bytes. At most 95 samples and 512 KiB in total. Rate *r* plays nominally at 7,159,090 / (*baseCycles* + 26·(*r*−1)) Hz (r=0 means 256). Use 301 for S1, 295 for S2 and 297 for S3K. The binding rate is independent of the stock locator and its retail rate |

## 4. Bank

- `form`: `"embedded"`. The value `"patch"` is reserved (§5.4). Readers that
  do not implement it MUST reject it with a clear error.
- `track.bin` holds the complete bank. Every stock FM voice inside it (in
  the local voice table, or any other bank-resident voice) is stored as
  zeros and listed in `fills`.
- `fills` is sorted by offset, does not overlap, and lies within the bank.
  Each fill is `{offset, length: 25, asset}`, and the asset MUST be an
  `FM_VOICE` reference.
- Loading: resolve each fill's asset, apply its transforms, write it at
  `offset`, then check `assembledSha256`. If there are no fills,
  `assembledSha256` equals `sha256`.
- `base`, `headerOffset`, `localVoiceCount` and `previewFrames` have the
  meanings of schema 3. The header lives at `base + headerOffset`. For s3k,
  `base` is 0 (normalized) or 32768 (absolute Z80 window). For S1, both
  `base` and `headerOffset` MUST be 0. Its 16-bit big-endian header pointers
  are relative to the song start. For S2, `headerOffset` MUST be 0 and
  `base` is 0 (normalized) or 32768 (absolute Z80 window). Subtract `base`
  from its little-endian pointer words. OpenSMPSDeck compiles both to base
  zero. S1/S2 bank tracks precede the local 25-byte voice table, if any.
  The header is the voice pointer, FM/DAC count, PSG count, timing divisor,
  tempo, then four-byte FM/DAC entries and six-byte PSG entries. Slot zero
  is DAC. PSG entries end with a zero modulation byte and a PSG binding slot.
  The header tempo is target-native. Exporters convert the arrangement's S3K
  tempo to the closest target duration rate per the [driver matrix](driver-support.md#tempo-conversion).
  For an inexact rate, the writer MUST record `TEMPO_APPROXIMATED` and
  report the source byte, target byte and relative rate error to the caller.
  CLI `--strict-tempo` or MCP `strictTempo: true` MUST fail the export
  with that diagnostic before writing the archive.
- `relocations` is the sorted list of unique byte offsets of every 16-bit
  bank pointer word. Words are big-endian for S1 and little-endian for S2
  and S3K. Include track pointers and a nonzero voice pointer. Programs
  with control flow also list the pointer operands; native S1 branch
  displacements are relative, so moving the whole bank does not change them. `relocationsComplete` is
  false when the traversal cannot prove that it found every pointer, for
  example in unreached bytes of an imported bank.

## 5. Playback mapping (OpenGGF and OpenSMPSDeck)

1. Verify the manifest, the entries, the hashes and the limits.
2. For each reference, resolve the ROM payload as in §3.1 and check
   `ref.sha256`, apply the transforms, and check `resultSha256`. A missing
   ROM or a mismatch fails the whole package, with an error naming the asset
   key, the locator and the expected and actual hashes.
3. Assemble the bank by applying `fills` and check `assembledSha256`.
   Assemble `globalVoices` the same way.
4. For s3k, build the data from the assembled bank:

   ```java
   var data = new Sonic3kSmpsData(<header view at base + headerOffset>, base);
   data.setBankData(bank, base);
   data.setPsgEnvelopes(slot -> bytes);   // from envelopeBindings
   data.setModEnvelopes(slot -> bytes);
   if (globalVoices != null) data.setGlobalVoiceData(table);
   var dac = new DacData(sampleIndex -> pcm, note -> new DacEntry(sampleIndex, rate), 297);
   ```

   OpenGGF then calls `playSmps(data, dac)`. OpenSMPSDeck renders through
   `S3kRenderer`.

5. For S1, construct `new Sonic1SmpsData(bank)` and install an array whose
   index `slot - 1` contains the PSG binding bytes. For S2, construct
   `new Sonic2SmpsData(bank, base)` and pass a map keyed by the PSG binding's
   one-based slot to `setPsgEnvelopes`. Construct a fresh `DacData` from the
   package PCM and bindings using that game's base cycles. Resolve all
   stock references before constructing the data. Use the pinned game's
   `SmpsSequencerConfig`, physical compatibility policy and normal music
   activation, with immediate readiness for the assembled bank. The S2
   retail Saxman load delay does not apply. The headless preview is a fresh
   NTSC session with no preceding music, SFX or gameplay speed effects.

### 5.1 ROM acceptance

Checks happen per asset. The host MAY first identify the image, using
OpenGGF's ROM identity and header checks. A known image hash passes silently.
An unknown hash with a valid header gives the warning `UNKNOWN_ROM_IMAGE`, and
the per-asset hashes decide the result. Standalone S3 and S&K images work when
they contain every referenced asset. S1 and S2 use unheadered 512 KiB and
1 MiB images, respectively. Match the domestic or international title
through `RomHeaderName` and check fingerprints with `RomIdentityTable`.
REV00 and REV01 are known for both games. Both revisions of each game
produce the same committed locators and hashes. S1 relocates ROM tables
through `Sonic1RomProfile`. The S2 music and DAC load extents and the
compressed driver are identical across the two tested revisions.

### 5.4 Reserved: patch form for retail-derived banks

`bank.form: "patch"` will store the bank as a delta against the retail bank
named by `provenance.derivedFromRetailSong`. The package then carries no
original song bytes, so `containsRetailSongData` is false. The delta format
is defined later. Until then, writers MUST NOT emit this form.

## 6. Project-format provenance (OpenSMPSDeck)

### 6.1 Fields

- Asset-level `origin` on voices, envelopes and samples:
  `{"type": ..., "ref": {...}?, "transforms": [...]?, "license"?, "attribution"?}`.
  It has the same shapes as §3.
- Project-level `derivedFrom`: `null` or
  `{"type": "retail-song", "game", "songId", "bankSha256"}`.
- `import-rom-song` sets `derivedFrom` and `origin: stock` on every imported
  asset. `copy-arrangement` propagates it. Catalogue *Add to project*,
  `catalog apply` and reconstruction stamp each asset's `origin`, including
  the gain transform for DAC and `source-audio` for sliced stem samples.
- Files with no origin data MUST round-trip byte-identically and keep their
  version.

### 6.2 Redacted source

A stock-reference asset in a redacted source MAY omit its payload bytes. Such
a project MUST be resolved with a ROM before it compiles or renders, using
the same resolver as §5. The v5 `nativeProgram` remains an addressed bank
in source. This can retain bank-resident stock bytes even when `track.bin`
has fills for those voices. The writer flags the embedded bytes. Omit the
source with `--no-source` when the project does not need to be editable.

### 6.3 Public stock table

Committed to OpenSMPSDeck, the table contains **hashes only, never payloads**.
Each entry is `{game, kind, table, locator..., length, sha256}`, plus `BANK`
entries `{game, songId, length, sha256}` for retail banks, used to detect
retail song data. A command generates it from a local ROM, and the
generation is reproducible.

## 7. Warning codes

| Code | Meaning |
|---|---|
| `RETAIL_SONG_DATA` | The package contains copyrighted retail song data. It is for personal use and must not be redistributed |
| `ROM_DERIVED_DATA` | The package embeds bytes from the game ROM. It is for personal use and must not be redistributed |
| `DERIVED_FROM_RETAIL_SONG` | Patch form: no retail bytes, but the composition is still the game's |
| `UNDECLARED_STOCK_CONVERTED` | An embedded payload matched a stock asset and was converted to a reference |
| `SOURCE_AUDIO` | A sample was sliced from an input recording; check that you have the rights to it |
| `THIRD_PARTY` | Embedded third-party material; check its license |
| `UNKNOWN_ROM_IMAGE` | The ROM hash is unknown; asset hashes decide |
| `TEMPO_APPROXIMATED` | The normal or speed-shoes tempo differs in long-run tick rate after conversion from S3K to the target driver |
| `DPCM_INEXACT` | Report only: the sample will change when a disassembly build encodes it to DPCM |

Legacy packages (schemas 1–3) gain an additive `provenance` object with the
same four fields, but **only** when ROM-derived or retail data is detected.
Otherwise their bytes are unchanged.

## 8. Limits

| Item | Limit |
|---|---|
| `track.bin` | 32 KiB |
| `manifest.json` | 1 MiB |
| `source.smps.json` | 16 MiB |
| Whole package | 2 MiB without source, 20 MiB with source |
| Entries | At most 1 + 1 + 1 + 255 + 127 + 95 + 1 + 1 |

## 9. OpenGGF audio manifest v2 (`smpsPackage` track kind)

```yaml
formatVersion: 2
tracks:
  - id: my-song
    smpsPackage: audio/smps/my-song/
    gain: 1.0
    tempoEffects: true
sfx: []
```

- A v2 track has either `assetPath` (with the streamed fields of v1) or
  `smpsPackage`, never both. An `smpsPackage` track has no loop fields,
  because native SMPS loops itself.
- The path is a normalized lower-case directory under `audio/smps/`,
  containing an exploded package (§1).
- The mod's `baseGame` MUST equal the package driver's game. Stock
  references resolve from the base-game ROM the engine already loaded.
- `tempoEffects` follows the driver's own native speed-shoes behaviour: the
  s3k frame multiplier, or s1/s2 `driverOptions.speedUpTempo`.
- Any failure to validate or resolve disables that track, with a reported
  reason, and falls back to stock music. The engine MUST NOT stop.
- Packages whose `provenance` flags ROM-derived or retail data load, but the
  mod manager and log show the corresponding warning.
- Mod v1 manifests and streamed tracks are unchanged.

## OpenSMPSDeck implementation

The [headless guide](../../headless/README.md#portable-song-packages) lists
the CLI options and MCP tools. `validate-package` checks a package with stock
references structurally without a ROM. The report sets `playbackValidated`
after resolution and native preflight. `--render` requires any ROMs needed
by the package. Export and validation support S3K playback. Inspect can
read S1/S2 metadata without references.

Studio requires a resolved project. Use a ROM with the headless tools before
rendering or compiling redacted source. A Studio ROM picker is a follow-up.
The mod JAR tests verify the exploded layout and both manifests. OpenGGF
integration is tracked separately.

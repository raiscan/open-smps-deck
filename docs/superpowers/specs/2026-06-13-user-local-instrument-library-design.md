# User-Local Instrument Library Design

## Purpose

OpenSMPSDeck needs a user-local library for reusable FM voices, PSG volume envelopes, modulation envelopes, and DAC samples. Users should be able to point the app at one game folder or a large root such as `smps-rips-master`, recursively scrape useful assets, deduplicate exact matches, and deploy selected assets into any open song.

The first version focuses on building and using a durable personal asset library. It does not rewrite existing track bytecode when assets are deployed into a song.

## Existing Context

OpenSMPSDeck already stores instruments inside each `Song`:

- `Song.getVoiceBank()` contains `FmVoice` entries.
- `Song.getPsgEnvelopes()` contains PSG volume envelopes.
- `Song.getModEnvelopes()` contains modulation envelopes in the same byte-array shape as PSG envelopes.
- `Song.getDacSamples()` contains `DacSample` entries.

Existing I/O already provides useful pieces:

- `VoiceBankFile` serializes FM voices and PSG envelopes to `.ovm`.
- `OsmpsVoiceFile` serializes one FM voice preset.
- `SmpsImporter` imports current SMPS song formats and loads companion `PSG.lst`, `Modulat.lst`, `DAC.ini`, `DefDrum.txt`, and `DAC/` files from a song directory.
- `DacSampleExporter` writes SMPSPlay-style DAC companion files.
- `InstrumentRemapper` can detect and rewrite voice/PSG references, but this design does not use it for library deployment.

The external rip archive at `C:/Users/farre/OneDrive/Documents/smps-rips-master` is structured around driver families (`68k`, `Pico`, `preSMPS`, `Z80`). Its `README.md` states that extensions are conventional labels, while SMPSPlay v2 uses `config.ini` to define each format. The scanner therefore must treat `config.ini`, `DefDrv.txt`, and `DefCFlag.txt` as the dialect metadata source, not the filename extension alone.

## Data Format

The library is a single app-managed user-local collection.

Default storage:

- A `LibraryPaths` utility resolves the library root from Java user preferences.
- If unset, the root defaults to a per-user OpenSMPSDeck data directory.
- The root contains `library.json` and a `dac/` payload directory.

`library.json` is versioned, pretty-printed JSON. It stores metadata and small payloads directly:

- FM voice data as hex-encoded 25-byte blobs.
- PSG envelope data as hex.
- Modulation envelope data as hex.
- DAC metadata and the relative path to a raw unsigned 8-bit PCM payload file under `dac/`.

Asset kinds:

- `FM_VOICE`
- `PSG_ENVELOPE`
- `MOD_ENVELOPE`
- `DAC_SAMPLE`

Common asset metadata:

- stable id
- kind
- display name
- dedupe key
- created timestamp
- updated timestamp
- source references

FM voice metadata:

- 25-byte voice data
- algorithm
- feedback

Envelope metadata:

- envelope data
- step count before the terminator

DAC metadata:

- playback rate
- byte length
- payload file path
- original compression label when known
- optional original `pan`, `param1`, `param2`, and DAC id metadata from the rip config

Deduplication rules:

- FM voices dedupe by exact 25-byte data.
- PSG envelopes dedupe by exact bytes.
- Modulation envelopes dedupe by exact bytes and stay separate from PSG volume envelopes.
- DAC samples dedupe by exact sample bytes plus playback rate.

Source references preserve where an asset came from:

- scan root
- driver family path segment (`68k`, `Pico`, `preSMPS`, `Z80`)
- game name
- variant/prototype path below the game, when present
- config section extension
- source song file, when harvested through a song import
- source companion file
- original index or id
- driver definition summary

## Recursive Scanner

The scanner accepts one root directory and recurses through descendants.

Discovery behavior:

- Build a rip context for each directory containing `config.ini`.
- Parse each `config.ini` section such as `[.smy]`, `[.smz]`, `[.trs]`, `[.s3k]`, or `[.smp]`.
- Resolve configured companion files from keys such as `Driver`, `Commands`, `Drums`, `VolEnv`, `ModEnv`, `DAC`, `FMDrums`, `PSGDrums`, and `GlobalInsLib`.
- Derive driver family, game name, and variant/prototype path from the folder path relative to the selected root.
- Avoid treating arbitrary `.bin` files as song candidates in known rip trees. `.bin` files are usually driver dumps, DAC payloads, instrument sets, pan animation tables, or other data.

Primary harvesting:

- Parse configured `VolEnv` files such as `PSG.lst` using the mid2smps envelope-list format.
- Parse configured `ModEnv` files such as `Modulat.lst` into the separate modulation-envelope library kind.
- Parse configured DAC ini files, including variants like `DAC.ini`, `DAC_Voice.ini`, `DAC_Voice1.ini`, `DAC_Voice2.ini`, `DAC_SFX.ini`, and `NECPCM.ini`.
- Load referenced PCM/DPCM files, converting supported DPCM to unsigned 8-bit PCM using the existing DPCM delta logic.
- Parse configured `GlobalInsLib` and `InsSet*.bin` files as FM voice banks when their instrument mode is supported.

Song import harvesting:

- For formats the app can fully import, run song import and add all extracted FM voices, PSG envelopes, modulation envelopes, and DAC samples to the library.
- For formats not yet fully importable, still harvest companion assets directly.

Scan summary:

- files visited
- config directories found
- full song imports attempted
- full song imports succeeded
- asset-only folders harvested
- unsupported song dialects
- failed files grouped by reason
- new assets by kind
- duplicate assets merged by kind
- total library counts by kind

Rescanning the same root is idempotent. Existing assets are not duplicated; new source references are merged into existing entries.

## Dialect Capability Classification

The scanner must not decide support from extension alone. It parses `DefDrv.txt` and `DefCFlag.txt` and computes capability from their contents.

`SmpsDriverDefinition` parses at least:

- `PtrFmt`
- `TempoMode`
- `InsMode`
- `InsRegs`
- `FMChnOrder`
- `PSGChnOrder`
- `FMBaseNote`
- `FMBaseOctave`
- `VolMode`
- `FMFreqs`
- `PSGFreqs`
- `FM3Freqs`
- `DrumChMode`
- `DACChns`
- `preSMPSTrkHdr` presence and fields
- `EnvelopeCmds`

`CoordFlagDefinition` parses `DefCFlag.txt`:

- `[Main]` flag rows
- `[Meta]` sub-command rows
- command length from the `Len` column
- pointer parameter offsets from `JmpOfs`
- command type and subtype names for future display/debugging

Capability buckets:

- `FULL_IMPORT`: the song format can be imported into a `Song` model with current or immediately parameterized importer behavior.
- `ASSET_ONLY`: the scanner can harvest companion files, but full track decompilation is not supported yet.
- `UNSUPPORTED`: the file looks like a song, but required driver features are not implemented.
- `IGNORED`: driver/sample/data files that are not song or library inputs.

Likely `FULL_IMPORT` targets after adding parsed definitions:

- Non-preSMPS `PtrFmt=Z80`, `68k`, or `Rst`.
- `TempoMode=Timeout`, `Overflow`, or `Overflow2`.
- `InsMode=Default` or `Hardware`.
- `DefCFlag.txt` with parseable command lengths and jump offsets.

This covers more than the current hardcoded `.smp`, `.sm2`, and `.s3k` paths, including many `.smy`, `.smz`, `.mmw`, `.tra`, `.trs`, `.rst`, and `.spi` configs.

Special importer work remains for:

- preSMPS configs with `preSMPSTrkHdr`.
- `PtrFmt=Z80Rel` or `PtrFmt=Pre68k`.
- `InsMode=Custom` or `InsMode=Interleaved`.
- special FM/PSG drum track mappings.
- heavily modified game-specific drivers where `DefCFlag` includes semantics beyond command length and control-flow parsing.

Even when full import is not ready, companion asset harvesting should still work.

## UI Workflow

Add a top-level `Library` menu.

Menu items:

- `Open Library...`
- `Scan Folder...`
- `Library Location...`

`Open Library...` opens a browser dialog with filters or tabs:

- FM Voices
- PSG Envelopes
- Mod Envelopes
- DAC Samples

Each table shows enough metadata to choose assets:

- display name
- source game
- source variant/prototype
- source song or companion file
- driver family
- config section/extension
- source count
- asset-specific details

Asset-specific columns:

- FM: algorithm, feedback
- PSG/mod envelope: step count
- DAC: rate, byte length

Dialog actions:

- `Deploy` selected assets into the active song.
- `Preview` selected asset where existing preview players support it.
- `Reveal Sources` displays all source references for one asset.

`Scan Folder...` opens a directory chooser, runs a recursive scan, persists the updated library, and shows a scan summary.

Long scans should run on a JavaFX background task with progress text. The initial implementation will keep progress coarse: current directory/file and counts. The library file is saved after a scan finishes with a coherent in-memory result.

## Deployment Rules

Deploying assets into the active song is conservative.

- FM voices append to `Song.getVoiceBank()` unless identical data already exists in the song.
- PSG envelopes append to `Song.getPsgEnvelopes()` unless identical data already exists.
- Mod envelopes append to `Song.getModEnvelopes()` unless identical data already exists.
- DAC samples append to `Song.getDacSamples()` unless identical data plus rate already exists.
- Existing track bytecode is not rewritten.
- Deployment marks the song dirty and refreshes `InstrumentPanel`.
- Deployment reports appended and reused/skipped counts.

Names are source-informed and stable. Name collisions are allowed because identity is byte/data based, not name based.

DAC metadata that the current `DacSample` model cannot represent remains in the library. Deployment only writes the current model fields: name, data, and rate.

## Error Handling

Scanning is tolerant:

- One bad file does not abort the scan.
- Missing companion files are recorded in the summary.
- Malformed companion files are skipped with a reason.
- Unsupported dialects are reported separately from parse failures.
- Invalid DAC entries with no `File` are skipped.
- DAC entries whose referenced file is missing are skipped.
- Unknown compression labels are reported as unsupported unless they can be safely treated as raw PCM.

Library persistence is stricter:

- Invalid `library.json` causes a load error and does not silently discard data.
- Saving writes to a temporary file and then replaces the index to avoid corrupting the library on partial writes.
- DAC payload files are written before the index references them.

## Testing Strategy

Unit tests:

- `InstrumentLibraryFile` round-trips JSON and DAC payloads.
- Duplicate assets merge source references instead of adding entries.
- Deployment appends missing assets and reuses identical existing song assets.
- `SmpsRipConfigParser` parses config sections and companion file keys.
- `SmpsDriverDefinition` parses key `DefDrv.txt` properties.
- `CoordFlagDefinition` parses command lengths and jump offsets.
- DAC ini parser handles `DAC.ini`, `DAC_Voice.ini`, DPCM, PCM, `Rate`, `Param1`, `Param2`, and missing file entries.
- `InsSet*.bin` parser extracts 25-byte voices and honors supported operator ordering.

Scanner tests:

- A synthetic temp rip tree with one config, one song, one `PSG.lst`, one `Modulat.lst`, one DAC ini, one DAC payload, and one instrument library.
- Recursive scanning from the parent root discovers nested game folders.
- Re-running the scan is idempotent.
- Unsupported song dialect still harvests companion assets.

Integration tests:

- A small synthetic S2/S3K-style folder exercises full import plus companion harvesting.
- No tests depend on the external `smps-rips-master` path.

Manual verification:

- Scan one known game folder.
- Scan a root containing multiple games.
- Open the library browser.
- Deploy one FM voice, one PSG envelope, one modulation envelope, and one DAC sample into an active song.
- Save and reload the project to confirm deployed assets persist.

## Out Of Scope For First Version

- Rewriting existing track bytecode references on deployment.
- Editing library assets in place.
- Deleting or garbage-collecting library DAC payloads.
- Cloud sync or multiple named libraries.
- Full preSMPS track import.
- Full playback correctness for every SMPSPlay dialect.
- Importing PWM, NEC PCM semantics beyond storing deployable raw sample data where possible.

## Open Implementation Notes

The scanner should be structured so asset harvesting does not depend on full song import. This prevents unsupported dialect work from blocking useful library population.

The importer should evolve from `SmpsMode` hardcoding toward a parameterized `SmpsDriverDefinition`. The current `S1`, `S2`, and `S3K` modes can remain presets built from parsed or built-in definitions.

The first implementation plan should split the work into small deliverable layers:

1. Library model and JSON/DAC persistence.
2. Config, driver, and coord-flag parsing.
3. Companion asset harvesting.
4. Recursive scanner and summary.
5. Deployment into active song.
6. Library UI.
7. Parameterized full-import expansion beyond the three hardcoded modes.

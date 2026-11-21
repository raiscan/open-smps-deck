# CLAUDE.md

OpenSMPSDeck is an S3K-first SMPS studio and headless CLI. Both work with the
same `.smps.json` project format.

## Principles

- Native SMPS bytecode is the source of truth for playback and export. A native
  project preserves its addressed bank and asset bindings. An editable
  arrangement compiles to native bytecode, and views of a native bank are
  derived from it.
- The pinned OpenGGF driver, session and chip cores are the playback authority.
  See the [audio pin](upstream/openggf-audio.lock.json) and
  [provenance and parity checks](openggf-audio/README.md).
- Project files are the agent interface. Validate edits through the headless
  CLI. Studio adopts valid external changes to clean documents and preserves
  local work when an external change conflicts with unsaved edits.
- Keep private Suno and ROM data in a private data directory outside the repo.
  Never commit it to this public repository.

## Reconstruction entry point

Separate a local WAV or MP3 with `reconstruct separate` (decode FLAC to WAV
first), then check the separated folder with `reconstruct import` and add
`--run` to build. Review the audio-only baseline in Studio or over CLI/MCP.
A Suno MIDI and stem download is another import source. See the
[external-agent guide](docs/user-guide/agents.md#build-a-local-baseline).

## Maven modules

### openggf-audio

- `com.openggf.audio.smps.SmpsSequencer` interprets SMPS bytecode.
- `com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler` implements S3K coordination flags and their parameter lengths.
- `com.openggf.audio.session.SmpsDriverSession` manages playback sessions.
- `com.openggf.audio.synth` contains chip cores, synthesis and resampling.

### project-core

- `StudioProject` holds the shared arrangement or native project data.
- `ProjectFormat` reads, writes and validates the versioned JSON format.
- `NativeProgram` holds the addressed S3K bank and its asset bindings.
- `ProjectCompiler` compiles arrangements into finite S3K bytecode with assets.
- `ProjectFiles` handles revision-aware persistence.
- `ProjectDocument` manages local edits, disk revisions and recovery. `ProjectWatcher` detects external file changes.

### headless

- `Main` dispatches project validation, compilation, rendering, export and MCP.
- `ExternalSeparators` invokes configured separator commands and validates
  their aligned WAV outputs and `separation.json`. The BS-RoFormer SW
  adapter is in [tools/separators](tools/separators/bs-roformer/).
- `SunoImport` checks separated folders and downloads, then assigns stem
  roles for audio-only input or maps stems to MIDI parts.
- `ReconstructionAudioOnly` prepares labelled stems and the beat grid.
  `ExternalTranscribers` runs the configured tonal adapter.
  `AudioDrumDetector` supplies spectral drum onsets.
- `ReconstructionStages` builds the arrangement from the audio-only or MIDI
  score. `ReconstructionVoicePlan` and `ReconstructionSections` manage
  voices and form.
- `ProjectExport` writes audio and native formats. `McpServer` exposes the
  workflow over stdio.
- `S3kRenderer` renders through the pinned session without an audio device.
- `S3kRomImporter` imports music and assets from verified locked-on S3&K images.
- `NativeArrangement` derives a timeline from the pinned sequencer.
- `ProjectPackage` packages native bytes, declared resources and project source.
- `CatalogueCli` dispatches instrument catalogue commands.

### studio

- `StudioLauncher` starts JavaFX. `StudioWindow` hosts the project editors.
- `TimelineView` edits clip placement and repetition. `PianoRollView` edits linked notes.
- `NativeProgramPane` edits native bank bytes in place.
- `SunoImportPane` checks downloads. `ReconstructionPane` hosts Analysis and
  Proposals. `ExportDialog` exports arrangements and native banks.
- `StudioPlayback` plays project snapshots off the JavaFX thread.

## Build and test

Run from the repository root with JDK 25 and Maven.

```sh
mvn -B package
mvn -B test
mvn -B -pl headless -am verify
```

See the [Studio launch instructions](studio/README.md#run),
[Studio user guide](docs/user-guide/studio.md) and
[headless CLI guide](headless/README.md#run).

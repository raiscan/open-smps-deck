# Playback and Export

Play your song in real time, export it as audio, or compile it to SMPS binary for use in Sonic ROMs.

## Overview

The internal model of every song in OpenSMPS Deck is SMPS bytecode. The tracker grid is a decoded view over that raw data, so what you hear during playback is exactly what gets written when you export. There is no separate render pass or lossy conversion step -- the playback engine and the binary exporter both feed from the same compiled output.

## Playback Controls

Start, stop, and pause playback with keyboard shortcuts or the **Transport Bar** buttons.

### Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `Space` | Toggle play / stop. If the song is stopped, compile and start playback from the beginning. If it is playing, stop. |
| `Enter` | Play from the current cursor position. Playback starts at the selected order row and pattern row. |
| `Escape` | Stop playback. If the tracker is in edit mode, `Escape` exits edit mode first; press it again to stop. |

### Transport Bar Buttons

The **Transport Bar** provides three buttons that mirror the keyboard shortcuts:

| Button | Action |
|--------|--------|
| **Play** | Compile the song and start playback from the beginning. If paused, resume from the paused position instead. |
| **Stop** | Stop playback and silence all channels. Resets the pause state. |
| **Pause** | Pause playback at the current position. Press **Play** to resume. |

When you press **Play** (either the button or `Space`), the engine compiles the song model into an SMPS binary, loads it into the sequencer, and streams audio at 44.1 kHz through the system audio output. Pressing **Stop** silences all chip channels immediately.

## Mute and Solo During Playback

Click a channel header in the **Tracker Grid** to mute or unmute that channel. Hold `Ctrl` and click a channel header to solo it. These changes take effect immediately during playback -- you do not need to stop and restart.

| Action | Behavior |
|--------|----------|
| Click channel header | Toggle mute for that channel. Muted headers turn grey. |
| `Ctrl` + click channel header | Toggle solo for that channel. The soloed header turns gold; all other channels are silenced. Click the same header with `Ctrl` again to exit solo mode. |

When a solo is active, every channel except the soloed one is treated as muted. Toggling any individual mute while solo is active clears the solo first.

## Live Reload on Edit

When playback is active and you edit the song (change notes, instruments, order list, or voices), the engine automatically recompiles the song and resumes playback from the approximate current position. You do not need to stop and restart playback to hear your changes.

Mute and solo state is preserved across live reloads. The playback cursor continues tracking the current position.

## Playback Cursor

During playback, a teal highlight bar tracks the currently playing row in the **Tracker Grid**. This is separate from the blue edit cursor -- you can continue editing at one position while watching playback progress at another.

The playback cursor clears automatically when playback stops.

## WAV Export

Select **File > Export WAV...** to render the song to a standard WAV audio file.

After choosing the output file, a settings dialog appears with the following controls:

| Control | Type | Default | Range |
|---------|------|---------|-------|
| **Loop Count** | Spinner | 2 | 1--99 |
| **Enable Fade Out** | Checkbox | Checked | -- |
| **Fade Duration** | Spinner (seconds) | 3.0 | 0.1--30.0 |
| **Fade Mode** | Combo box | Extend | Extend / Inset |

### Render Format

| Setting | Value |
|---------|-------|
| Sample rate | 44,100 Hz |
| Bit depth | 16-bit |
| Channels | Stereo |
| Maximum duration | 600 seconds |

The exporter creates a dedicated playback engine instance and renders the song offline -- it does not play through speakers. A progress dialog appears while the render is running. The dialog closes automatically when the export finishes.

### Loop Count

The song is rendered through the SMPS sequencer for the configured number of loops. Songs that do not loop (those using STOP instead of JUMP) render once regardless of the setting.

### Fade Modes

When **Enable Fade Out** is checked, one of two fade modes applies:

- **Extend** -- After all loops complete, the engine continues rendering for the configured fade duration. A linear fade from full volume to silence runs over this extension. The total audio length equals all loops plus the fade duration. Use this for a clean transition to silence after the music finishes.

- **Inset** -- The fade runs over the last N seconds of the already-rendered audio. The total audio length equals all loops (no extension). The final portion fades to silence in place. Use this to trim endings without adding extra time.

When **Enable Fade Out** is unchecked, the fade duration and mode controls are disabled. The export produces clean audio with no fade applied.

### Muted Channels

The WAV exporter respects the current mute and solo state in the **Tracker Grid**. Any channel that is muted or excluded by solo at the time you start the export is silenced in the output file. Use this to render individual channel stems.

## SMPS Binary Export

Select **File > Export SMPS...** to compile the song and write a raw SMPS binary (`.bin`) file.

The exporter runs the same `PatternCompiler` that the playback engine uses. It compiles the full song -- header, track pointers, voice table, and all pattern bytecode -- into a single binary blob and writes it to disk. The resulting `.bin` file is ready for ROM injection or playback by an external SMPS driver such as SMPSPlay or the OpenGGF sonic-engine.

No additional dialog or settings are required. Choose a destination file and the export completes immediately.

## Voice Bank Export

Select **File > Export Voice Bank...** to save the active song's FM voices and PSG envelopes to an `.ovm` file.

The `.ovm` format is a JSON file containing:

| Field | Content |
|-------|---------|
| `name` | The song name |
| `voices` | Array of FM voices, each with a name and hex-encoded 25-byte SMPS voice data |
| `psgEnvelopes` | Array of PSG envelopes, each with a name and hex-encoded step data |

Use voice bank files to share instrument patches between projects or with other OpenSMPS Deck users. Import them back with **File > Import Voice Bank...**.

## Tips

- Mute channels before starting a WAV export to create isolated stems. For example, solo the DAC channel, export, then solo FM 1, export again. Import the resulting WAV files into an external DAW for further mixing.
- The SMPS binary export produces the exact same compiled data that the playback engine feeds to the sequencer. What you hear is what you get -- there is no hidden conversion or quality difference between playback and export.

# Interface Overview

The main window organizes your workspace into a menu bar, transport controls, and tabbed song editors.

## Overview

Every element of the interface maps directly to a part of the SMPS data model. The **Transport Bar** controls song-level settings (tempo, timing, driver mode), the **Tracker Grid** gives you a decoded view over raw SMPS bytecode, and the **Instrument Panel** manages FM voices and PSG envelopes. The **Song View**, **Chain Strip**, and **Breadcrumb Bar** provide hierarchical navigation through the song structure (Song -> Chain -> Phrase). You can open multiple songs at once -- each lives in its own tab.

## Main Window Layout

The window is organized top-to-bottom in three regions:

1. **Menu Bar** -- file operations, import, and export commands.
2. **Transport Bar** -- playback controls plus tempo, timing, and SMPS mode settings.
3. **Tab Pane** -- one tab per open song, plus a **[+]** tab to create new songs.

Each song tab contains panels arranged inside a border layout:

| Position | Panel | Purpose |
|----------|-------|---------|
| Left | [Song View](08-chains-and-phrases.md) | Per-channel chain overview showing the song structure |
| Center (top) | [Breadcrumb Bar](08-chains-and-phrases.md) | Navigation path (Song -> Chain -> Phrase) with click-to-navigate |
| Center (upper) | [Chain Strip](08-chains-and-phrases.md) | Horizontal strip of clickable phrase cells for the active channel |
| Center (main) | [Tracker Grid](07-tracker-grid.md) | Note entry and phrase editing across all 10 channels |
| Right | [Instrument Panel](04-fm-voice-editor.md) | FM voice bank and PSG envelope management |

The **Song View** on the left shows all 10 channels and their chains at a glance. Clicking a channel in the **Song View** updates the **Chain Strip** to show that channel's phrase entries. Clicking a phrase cell in the **Chain Strip** loads the corresponding phrase into the **Tracker Grid**. The **Breadcrumb Bar** shows your current position in the hierarchy and lets you click to navigate back up. Edits in any panel mark the tab as dirty, indicated by an asterisk in the tab title.

## Menu Bar

The **Menu Bar** contains a single **File** menu with the following items:

### Project Commands

| Item | Shortcut | Description |
|------|----------|-------------|
| **New** | `Ctrl+N` | Create a new empty song in a new tab |
| **Open...** | `Ctrl+O` | Open an `.osmpsd` project file in a new tab |
| **Save** | `Ctrl+S` | Save the active song to its current file, or prompt for a path if unsaved |
| **Save As...** | `Ctrl+Shift+S` | Save the active song to a new file path |

### Export Commands

| Item | Shortcut | Description |
|------|----------|-------------|
| **Export SMPS...** | -- | Compile the song and write a raw SMPS `.bin` file |
| **Export WAV...** | -- | Render the song to a `.wav` audio file (respects muted channels) |

### Import Commands

| Item | Shortcut | Description |
|------|----------|-------------|
| **Import Voices...** | -- | Browse SMPS binaries and select individual FM voices to add to the active song's voice bank |
| **Import SMPS...** | -- | Import a raw SMPS binary (`.bin`, `.s3k`, `.sm2`, `.smp`) as a new song tab |

### Voice Bank Commands

| Item | Shortcut | Description |
|------|----------|-------------|
| **Import Voice Bank...** | -- | Load FM voices and PSG envelopes from an `.ovm` or `.rym2612` file into the active song |
| **Export Voice Bank...** | -- | Save the active song's FM voices and PSG envelopes to an `.ovm` file |

## Transport Bar

The **Transport Bar** sits directly below the menu bar. Controls are arranged left-to-right:

### Playback Buttons

| Button | Action |
|--------|--------|
| **Play** | Start playback from the beginning, or resume if paused |
| **Stop** | Stop playback and reset the pause state |
| **Pause** | Pause playback at the current position (press **Play** to resume) |

### Song Settings

| Control | Type | Range | Description |
|---------|------|-------|-------------|
| **Tempo** | Spinner | `1` -- `255` | Frames per beat. Lower values produce faster playback. |
| **Div** | Spinner | `1` -- `8` | Dividing timing. Subdivides the tempo tick for finer rhythmic control. |
| **Mode** | ComboBox | `S1`, `S2`, `S3K` | Target SMPS driver variant. `S1` is Sonic 1 (68k), `S2` is Sonic 2 (Z80), `S3K` is Sonic 3 & Knuckles (Z80). See [SMPS Modes](12-smps-modes.md) for details on how each mode affects sequencer behavior. |

All three settings write directly to the Song model when changed. Changing any value marks the song as dirty and fires immediately -- you do not need to confirm or apply.

## Tabs

The **Tab Pane** fills the center of the window. Each open song gets its own tab.

### Creating Tabs

Click the **[+]** tab at the far right to create a new empty song. You can also use `Ctrl+N` from the menu or open/import a file, which each create a new tab automatically.

### Switching Tabs

Click any tab to switch to that song. When you switch tabs, the **Transport Bar** updates to reflect the selected song's tempo, dividing timing, and SMPS mode. The window title bar updates to show the active song's name.

### Tab Titles

Each tab displays its song's file name (if saved) or song name (if unsaved). When a song has unsaved changes, the title shows an asterisk suffix:

- `MySong.osmpsd` -- saved, no pending changes
- `MySong.osmpsd *` -- saved, with unsaved changes
- `Untitled *` -- new song with unsaved changes

### Closing Tabs

All tabs are closable. Click the close button on any tab to remove it from the workspace.

## Tips

- Changes to tempo and dividing timing in the **Transport Bar** apply immediately. Adjust them while playing to find the right feel before committing to a value.
- Use the **[+]** tab to keep multiple arrangements open side-by-side -- for example, a reference import next to your work-in-progress.
- The **Export WAV** command respects the **Tracker Grid** channel mute state. Solo a single channel before exporting to render isolated parts for mixing in an external DAW.

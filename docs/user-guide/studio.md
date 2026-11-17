# Timeline Studio: human and external-agent workflow

The new Studio opens shared `.smps.json` files. It is a separate editor from the
old tracker, with playback through the pinned OpenGGF S3&K core. Start with the
[original demo](../../examples/lantern-walk.smps.json); make a copy before editing
it if you want to keep the example unchanged.

## Arrange clips

The sequence browser on the left contains reusable musical sources. Drag one
onto an FM/PSG hardware lane, or choose it and click **Place on timeline**.
Placement via the button appends to the selected compatible lane. A sequence's
channel type must match the lane; overlapping clips are rejected.

Click a clip to select it. Drag its body to move it in time or to another
compatible lane. The snap is a quarter of the current beat, at least one tick.
Drag its right edge to repeat or trim it; a partial final repeat is allowed.
The inspector provides exact start/length and lane fields; press **Apply clip
changes** to commit those fields. Duplicate creates another linked instance.
Delete/Backspace removes the selected clip. Use Ctrl+Z/Ctrl+Shift+Z (Cmd on macOS)
for project undo/redo, outside text fields.

## Edit the source notes

Selecting a clip opens its source in the piano roll below. Notes are bars:
horizontal position is time, vertical position is pitch, width is duration.
Double-click empty space to add a note. Drag a note to change time/pitch; drag its
right edge for duration. Select a note to edit exact tick, duration and MIDI pitch
in the fields, then press **Apply note**. Pitch 60 is C4 on both chips. Delete with
the piano roll focused removes a selected note.

**These are linked sources:** changing a source changes all its instances.
Editing a clip's repeated length leaves the source unchanged. The header shows
how many instances use that source. **New sequence** creates an empty FM or PSG
source; a new project includes original starter FM voices. Custom voice editing
currently uses the documented JSON file, not an instrument editor.

## Save, external changes and recovery

**Save** writes the open project. **Save as** creates a new file, retaining the
original. An agent can edit the same source using a text editor or script; the
headless commands validate, compile and render without starting Studio.

Clean projects refresh automatically after valid writes or atomic replacement.
Selection is retained by ID, and zoom/scroll stay in place. A removed selection
is cleared. An in-progress drag is cancelled on external adoption, so it cannot
revert newer external data when released. Invalid/partially written/missing files
show a status message while the last valid project remains visible. Correct the
file and refresh resumes automatically.

With unsaved local edits, external changes produce a **CONFLICT**. Your local
work remains visible, and Save refuses to overwrite the external file.
**Reload file + keep local recovery** writes your local version to a unique
sibling recovery JSON, then opens the valid disk version. Save As can also keep a
separate working copy. Reload will not discard work if the disk file is invalid.
New/Open/Close ask about unsaved changes. Undo history resets on adopting an
external revision; undo never secretly crosses into another writer's version.

## Listen and export

**Play** previews from the beginning; Space toggles Play/Stop outside text fields.
Edits or adopted external changes stop the previous playback snapshot. Use **Song settings** to rename the project or change native tempo and ruler
resolution. The inspector shows approximate BPM for context. The native
S3K tempo byte controls delay frames: larger is slower, zero is valid. Tick ruler
resolution is in the project file. Playback and WAV export allow up to ten minutes
and use 48 kHz stereo at 60 NTSC presentations per second with upstream's hardware
frame budget. No audio device? The app reports the problem; WAV export still works.

**Export WAV** renders the complete clip arrangement plus a short tail.
**Export SMPS** writes the baked standalone binary. Choose a new output filename;
existing files are preserved. A stopped export cleans up its incomplete WAV.
The binary's voices are local, so this slice requires no external assets.

Nested multi-channel sequences, make-unique, DAC samples, envelopes and raw native
event editing are subsequent work. This first editor is an FM/PSG composition
slice, not a claim to expose the entire SMPS command set yet.

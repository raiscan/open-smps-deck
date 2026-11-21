# Timeline Studio: human and external-agent workflow

The new Studio opens shared `.smps.json` files. It is a separate editor from the
old tracker, with playback through the pinned OpenGGF S3&K core. Start with the
[original demo](../../examples/lantern-walk.smps.json); make a copy before editing
it if you want to keep the example unchanged.

Studio follows native display scaling, including Linux GTK/Xft preferences.
Restart after changing desktop scale. On a short logical screen, use the
**Arrangement** and **Notes** tabs to give each editor enough space. Double-click
a clip or source to open Notes. The inspector has its own scroll area.

## Import S3&K music

1. Click **Import S3&K…** and choose your 4 MiB locked-on S3&K cartridge image.
2. Select a song by title or hex ID.
3. Choose a new `.smps.json` destination. The song opens in a separate Studio
   window, with its **Arrangement** tab selected.

The pinned sequencer lays out FM and PSG notes and DAC hits in eight-second
clips. The ruler measures playback time, so tempo changes and loops stay
aligned with the playhead. Select a clip to see its piano roll or DAC hits.
Use **Edit in Native bank** to change the bank and refresh its arrangement.

Click **Save editable copy…** in the imported arrangement and choose a new
file. Studio opens that file in a separate window with the usual clip, piano
roll, and DAC editors. The copy rebuilds the preview interval as editable
notes and hits, including the FM voices and sample playback rates it uses.
Open **Commands** to edit imported modulation, envelope and voice changes,
PSG noise, pan, detune, level changes, SSG-EG and holds. The copy keeps the
envelope bytes as linked assets. Loop iterations appear where they played.

The editable timeline uses one tick per presentation. A song's original tempo
and divider can advance effect counters at a different rate. Commands outside
the arrangement format, such as raw frequency and global driver operations,
still require **Native bank** for exact playback and export. Reduce **Preview
frames** if a long preview exceeds the project or compiled bank size limit.

Imported projects carry the bank and native asset bindings, so they can be
opened and rendered without the cartridge. **Extra Life** restores previous
music and uses the headless renderer's `--previous-project` and
`--previous-frames` options for that context. [CLI import and rendering](../../headless/README.md#import-locked-on-s3k-music)

## Import a Suno folder

Choose **Import Suno folder…**, select the download, then select a separate
work directory. Studio lists the detected files, the mapped MIDI parts and
any gaps. A tick marks a passing check, a warning marks a fallback or
unmapped file, and a cross marks an error that blocks reconstruction.

Use a stem's **Override** dropdown to select a MIDI part, exclude the stem,
or restore automatic mapping. Choose **Re-check** to apply your changes and
refresh `import.json` in the work directory. With a usable mix and MIDI and
no import errors, choose **Start reconstruction** and select your instrument
catalogue. Studio shows progress and opens the completed arrangement in a
new window.

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
Delete/Backspace with the arrangement focused removes the selected clip. Use Ctrl+Z/Ctrl+Shift+Z (Cmd on macOS)
for project undo/redo, outside text fields.

## Group clips into a sequence

Try the original [Signal garden example](../../examples/signal-garden.smps.json).
It has two linked **Chorus** placements, each containing a nested **Rhythm bed**.

1. Click one clip, then Ctrl-click (Cmd-click on macOS) or Shift-click others
   across lanes. Choose **Groups → Group into sequence** or press Ctrl+G
   (Cmd+G). You can include existing group placements.
2. Drag a group's purple header to move all its children in time. Drag the
   right edge to trim or repeat the group. Vertical marks show the start of
   each repeat, and the last repeat can be partial. The inspector accepts
   exact start and length values.
3. Double-click a group header or choose **Edit inside**. Studio shows the
   children in place at that instance's first repeat, with the surrounding
   song dimmed. Edit child clips and nested groups with the usual controls.
   Inspector start ticks are relative to the open group. Every linked
   instance updates together. Press Esc to go out one level or click a
   breadcrumb to return to that level.
4. Drag a group from **Sequences** onto the timeline to reuse it. A group
   always keeps its authored hardware lanes, regardless of the drop lane.
   **Place on timeline** appends a selected group after the current arrangement.
   Use **Apply group source** in the inspector to rename the group or change
   the duration of one repeat. The length of each placement stays independent.
5. **Groups → Ungroup** or Ctrl+Shift+G (Cmd+Shift+G) restores the children of
   the selected instance at their expanded positions and lengths. Nested
   group children stay linked. The reusable source stays in the library.
   Grouping, ungrouping and placement changes use the usual undo/redo.

With groups, a hardware lane is occupied only where an expanded child clip
lies. Gaps remain usable. Studio refuses a move, extension or linked edit if
it would overlap another clip on the same lane in any instance. A cycle or
more than four group levels is also rejected. Clean external refreshes keep
your breadcrumb path by ID while it still exists, and return to the closest
surviving level if a placement disappears.

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
source; a new project includes original starter FM voices. Use **Voices & assets** to edit instruments and envelopes.

## Edit native commands

The source editor has **Notes** and **Commands** tabs. Choose **Commands**, or
**Source controls & commands** in the clip inspector. Gold markers in the piano
roll select events in the command table. These are linked source edits, affecting
every clip that uses the source.

Choose a command, enter a decimal tick and its raw **hex operand bytes**, then
**Add**. The selector supplies the opcode: for left pan, choose Pan and enter
`80`, not `E0 80`. Select an event to edit it and press **Apply**. Up/Down changes
authored row order, which determines execution order at the same tick. Delete
with the table focused removes an event. Commands use the same save and undo
history as notes and clips. Typing in a form alone does not change the project.

Commands include pan/AMS/FMS, detune, chip volume, transpose and custom
modulation. Version 4 also supports holds, PSG noise, SSG-EG and timed
voice or envelope selections. For an asset command, select its named voice
or envelope alongside any raw operands. Choose **Off** to disable an envelope.
Version 4 allows modulation setup during a hold. Older project versions
require the setup at a note attack. Transpose applies when the driver reads
a new pitch.

On a short window, **Browse** opens the command list with its reorder
controls. Choose a row and use **Edit command** to return to its fields.
Switching views keeps the current selection and draft.

**Linked source settings** selects an existing FM voice, base volume and repeat
policy. **RESET** restores supported state each repetition. **CARRY** resets at
clip entry, then lets relative changes accumulate across its repetitions.
Independent clips always reset. Press **Apply source** to commit these fields.
Try the [native controls example](../../examples/native-controls.smps.json).

A form draft survives external changes to its source. Applying an outdated event
is refused; use **Reload event** or **New form** to continue from the current
source. Source settings merge untouched fields and refuse conflicting edits.
These draft fields are not saved until applied.

## Voices and envelope assets

Choose **Voices & assets**, or the source editor's **Assets** tab. The **FM
voices** view lists linked local voices and their source usage. New creates a
native starting patch; Duplicate copies the applied voice. Delete refuses any
voice still assigned to a source. Assign voices in **Commands → Linked source
settings**, then **Apply source**.

The voice editor exposes algorithm/feedback and four native stored slots, labelled
with their YM register offsets. Values are decimal. Total level0 is loudest and
127 is quietest. **Apply track volume** is an independent native TL high-bit flag;
algorithm changes retain it. **Apply voice** commits those fields.
**Raw bytes** jumps to the second form. **Apply raw bytes** commits exactly 25 hex bytes in native order.
The two drafts are independent; Reload replaces both with the current voice.

The **Envelopes** view creates PSG volume or modulation envelopes. Give the asset
a name and raw hex bytes, then **Apply envelope**. Assign it through the two
envelope selectors in **Commands → Linked source settings**. None disables that
envelope on the next source reset. Envelopes are linked: editing one changes all
assigned sources and clips. Their steps advance with the driver, independently
of the piano-roll tick ruler.

Version 3 uses the basic envelope profile with PSG attenuation 00–0F,
modulation offsets 00–7F, and 80 / 81 / 83 controls. Version 4 also accepts
native signed modulation offsets and resolved 82 jump and 84 multiplier
operands. Validation rejects programs that can run outside their bytes or
cycle without advancing time. Try [voices-and-envelopes.smps.json](../../examples/voices-and-envelopes.smps.json).

Voice/envelope drafts survive external changes to the same asset. Applying a stale
or deleted asset is refused; Reload adopts its current definition. A removed
selection does not become a different asset. These forms use the shared project
undo, Save and recovery flow; typing alone does not save a draft.

## Browse the instrument catalogue

Under **Voices & assets → Catalogue**, choose **Open catalogue…** and select a
local index. Search or choose an asset kind. **View variant** shows its native
settings and source occurrences. **Audition** previews it through the same
chips as the project and uses the shared Stop control. **Add to project**
is undoable. Added assets are saved with the project. A PSG noise recipe
adds a linked PSG3 clip after your current arrangement.

Use the headless `catalog scan` command to create an index from a verified
S3&K image or compatible rips. [Catalogue guide](../architecture/instrument-catalogue.md#first-pass-in-use)

## Samples and the DAC lane

Open **Voices & assets → DAC samples** to import PCM WAV or raw unsigned 8-bit
mono PCM (`.pcm` / `.bin`). WAV import supports 8-bit and 16-bit PCM mono or
stereo. Studio downmixes stereo and resamples to a native S3K rate while
preserving duration approximately. Raw PCM imports unchanged at rate `0C`.
The sample library shows the waveform, native rate byte, and linked usage.
Rename, change rate, replace the audio or duplicate a sample. Apply commits
your changes. Clear a sample's hits before deleting it.

Create a **DAC** sequence and place it on the **DAC** lane. Double-click a
sample row in **DAC hits** to add a hit. Click a hit to edit its tick or sample.
Use **Add hit**, **Apply hit** and **Delete hit** for exact edits. Drag a clip's
right edge to repeat or trim its hits. Each hit starts a one-shot sample and
replaces the previously playing sample. A sample can finish after the clip
ends, and Studio includes that tail in preview and WAV export.

The DAC lane uses the FM6 hardware channel, with fixed stereo output and
sample level set by the PCM. Rate controls speed and pitch together. Rate
`01` is fastest and `00` is slowest. Use **Export package** to include the
PCM and native mapping with the track. Try the original
[DAC percussion example](../../examples/dac-percussion.smps.json).

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

## Inspect a reconstruction

Choose **Open reconstruction…** from the Studio toolbar and select the work
directory created by `reconstruct run`. Studio reads its stage artifacts and
opens a read-only workspace. It uses recorded WAV paths for the mix and mapped
stems, and reads `render.wav` for the chip spectrogram and playback. Reopen
the directory after a rebuild to inspect the updated artifacts.

- Choose a **Source** to change the spectrogram and playback together. Use
  **Time zoom** and the horizontal scrollbar to inspect a passage. **Fit song**
  restores the overview. The ruler starts in source seconds. Toggle
  **Project time** to see the steady project clock on the same aligned view.
- Adjust the **Spectrum** dB range to bring out quiet evidence or reduce
  background detail. **Ridges** marks the three strongest spectral peaks in
  each analysis column. The bands above show sections and bars from
  `structure.json`.
- Filter notes by **role** and **Lanes**. Zoom or hover to read pitch, lane and
  patch. Dashed bars mark dropped or merged notes. Hover for the fate and any
  competing note IDs. Click the pitch overlay or a note in the chip lane
  strip to read its full trace. For a bend, the curve shows the applied FM
  pitch when available and the stage pitch curve otherwise.
- Click the ruler or a note to seek. Press **Space** in the plot or click
  **Play**. Press **R** to switch between the chip and the last mix or stem
  at the same aligned position. On tempo-warped projects, reference playback
  follows the project clock. Shift-drag across the plot to loop a range.
  Click **Clear loop** or press **Esc** to remove the loop.
- Click a lane or a sounding note, then choose **Solo lane** or **Solo note**
  to audition that lane through the chip renderer. **Solo note** loops the
  selected gate. Turn it off to return to your previous loop selection.

## Review a reconstruction

1. Open the shared project and choose **Review → Add reference…**. Pick a
   16-, 24-, or 32-bit PCM or 32-bit float WAV with one or two channels and
   up to 256 MiB of audio.
   For several guides or stems,
   use the reference picker to choose the visible and audible WAV. The
   waveform shares the arrangement's horizontal scroll, zoom and playhead.
   Drag it to align the guide, or focus it and use Left/Right to move it by
   10 ms. Studio saves the alignment with the project.
2. Click the ruler to position the playhead. Select **Render**, **Reference**
   or **A/B**, then press Play. In A/B, press **R** to switch sources at
   the current position. **R** also enters A/B from either source. On the
   render lanes, click **S** to solo or **M** to mute. Multiple solos are
   allowed and mute takes precedence. These controls affect Studio audition.
3. Shift-drag the ruler to loop a range, or select a clip and click **Loop
   selection**. Clear it with **Clear loop**.
4. Press **N** or click **Add note**. The note takes its time from the
   active range, selected clip, or playhead. Click an empty lane to attach
   lane context, or click the ruler to place a note without a lane.
   Enter the feedback and click **Add**, then **Save** to share it.
5. Click an open note or its ruler/lane marker to jump and set a loop with
   1.5 seconds of context on each side. Agent-authored notes use violet
   markers and text. After reviewing a note, select it and click **Resolve
   selected**, then save.

Review edits use the same undo, save and conflict behavior as arrangement
edits. A clean window picks up notes written by the CLI, and a draft in the
composer stays available across a refresh. The
[review protocol](../architecture/review-loop.md) covers the agent side.

## Listen and export

Open the [native bank example](../../examples/native-evening-clock.smps.json)
to inspect a v5 project. Select an event to highlight its bytes and address
in **Native bank**. Edit the hex and use **Apply bank** to commit an
in-place change. Use **Preview frames** to set playback and WAV length.
Edits use the usual undo, save and external refresh. An unsupported
instruction appears as a playback diagnostic. **Export package** still
preserves the exact bank and asset bindings.

**Play** previews from the playhead. Space toggles Play/Stop outside text fields.
Edits or adopted external changes stop the previous playback snapshot. Use **Song settings** to rename the project or change native tempo and ruler
resolution. The inspector shows approximate BPM for context. The native
S3K tempo byte controls delay frames: larger is slower, zero is valid. Tick ruler
resolution is in the project file. Playback and WAV export allow up to ten minutes
and use 48 kHz stereo at 60 NTSC presentations per second with upstream's hardware
frame budget. No audio device? The app reports the problem; WAV export still works.

**Export WAV** renders the complete clip arrangement plus a short tail, or the
selected number of presentation frames for a native bank.
**Export SMPS** writes the baked standalone binary. Choose a new output filename;
existing files are preserved. A stopped export cleans up its incomplete WAV.
Local voices are baked into the binary. Projects declaring envelopes or samples require
**Assets → Export package**, which writes native track/resource bytes plus their
manifest and source JSON into a new `.smps.zip`. This adapter package is documented
for engine integration; OpenGGF does not yet load the ZIP directly. Bare binary
export refuses to drop those dependencies. Preview and WAV export use them.

Make-unique, remaining DAC commands, FM6 switching, envelope semantics and other
native commands are subsequent work. The [S3K support matrix](../architecture/s3k-support.md)
records coverage for the retail importer and the remaining driver gaps.

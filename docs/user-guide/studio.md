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

## Open a local audio reconstruction

Start from a local mix using the headless
[separation and audio-only build](../../headless/README.md#external-stem-separation).
Choose **Open reconstruction…** and select the generated
`import-work/reconstruction` directory to inspect the aligned stems,
audition the result and make or review edits.

## Import a Suno folder

Choose **Import Suno folder…**, select the download, then select a separate
work directory. Studio lists the detected files, the mapped MIDI parts and
any gaps. A tick marks a passing check, a warning marks a fallback or
unmapped file, and a cross marks an error that blocks reconstruction.
Choose the **Target game** before starting. The default is Sonic 3 & Knuckles
(S3K). Sonic 1 and Sonic 2 reconstruction are in progress, and assembly can
stop if the selected driver cannot play an envelope or command used by the
arrangement. S1 and S2 builds place notes on their respective driver clocks
(about 59.76 ticks per second), accounting for the frames where each driver
holds durations. The project keeps its target and native tempo for export.
For an S1 or S2 song with DAC drums, register the clean cartridge in the
ROM library before starting. Reconstruction fits the game's stock kit.
The [headless drum kit options](06-dac-samples.md#drum-sources-in-headless-reconstruction)
also support custom PCM for portable projects.

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
the piano roll focused removes a selected note. Press **+** or **=** (or
**Numpad Add**) to raise its pitch one semitone, or **−** (or **Numpad
Subtract**) to lower it. Hold **Shift** with any of those keys to move by an
octave. Each move is undoable. The status line reports any move outside the
chip's pitch range or skipped PSG noise note.

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
settings and source occurrences. **Audition** plays it with the variant's driver and uses the shared Stop
control. **Add to project**
is undoable. Added assets are saved with the project. A PSG noise recipe
adds a linked PSG3 clip after your current arrangement.

To build an index for Sonic 1 or Sonic 2, use your own verified REV01 ROM.
From a private working directory, set `jar` to the path to the headless JAR
and run the commands for your game.

```sh
java -jar "$jar" rom set s1 "$s1_rom"
java -jar "$jar" catalog scan s1-instruments.json --game s1
java -jar "$jar" rom set s2 "$s2_rom"
java -jar "$jar" catalog scan s2-instruments.json --game s2
```

You can also pass `--rom PATH` to scan directly. Open the resulting JSON
in Studio. Each variant auditions with its own driver. Use the matching
game when adding catalogue instruments to a project or starting a
reconstruction. Keep ROM-derived indexes local. The same workflow
supports a verified locked-on S3&K ROM with `--game s3k`.
[Catalogue guide](../architecture/instrument-catalogue.md#first-pass-in-use)

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
`01` is fastest and `00` is slowest. Use **Export… → SMPS package** to include the
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

## Inspect and edit a reconstruction

Choose **Open reconstruction…** from the Studio toolbar and select the
stage work directory created by `reconstruct import --run` (the
`reconstruction` subdirectory) or `reconstruct run`. Studio reads the stage
artifacts and opens an editing workspace. It uses recorded WAV paths for the
mix and mapped stems, and reads `render.wav` for the chip spectrogram and
playback.

- Choose a **Source** to change the spectrogram and playback together. Use
  **Time zoom** and the horizontal scrollbar to inspect a passage. **Fit song**
  restores the overview. **Fit pitch** follows the tonal notes in the visible
  time window with a small margin. It starts enabled. Use the **Pitch zoom**
  buttons and the vertical scrollbar to set a manual range. The ruler starts
  in source seconds. Toggle
  **Project time** to see the steady project clock on the same aligned view.
- Adjust the **Spectrum** dB range to bring out quiet evidence or reduce
  background detail. **Ridges** marks the three strongest spectral peaks in
  each analysis column. **Note opacity** controls the fill. Press **O** to
  toggle outlines. The bands above show sections, periods and phrases.
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

- Choose the target's preset under **Lane policies** (**S1 layering**,
  **S2 layering**, or **S3K layering**) to rebuild with octave-up PSG doubling
  and a 14-tick FM echo on available channels. The echo uses
  11 carrier TL steps of attenuation relative to the dry note by default.
  Choose **Off** to clear the lane policies. Both choices replace any
  custom lane policies. [Adjust the echo level](../architecture/reconstruction-stages.md#optional-lane-policies)

### Review agent proposals

Agent proposals appear as magenta dashed outlines on the pitch plot. Hover
to read the reason. Open **Proposals** to see the dry-run results and review
individual edits or a whole batch. **Preview A/B** builds a sibling preview
and plays the same span twice, built first and proposal second. The playhead
returns to the start of the span for the second pass. **Accept** stages an
edit for **Apply**, and undo can bring the proposal back before Apply.
**Reject** removes it from the shared journal immediately. Studio picks up
new proposals from agents while the reconstruction window is open.

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

- Choose **Insert** and a MIDI part, then click an empty pitch row. The initial
  gate ends at the next beat. Drag either end to resize it, or drag its body
  to move it in time or pitch. Switch between **Beat** and **Bar** snapping,
  or hold **Alt** while placing or dragging to work freely. Zoom in to grab
  short note edges.
- In **Select**, drag from empty space to box-select. Hold **Shift** to add
  or **Ctrl** to toggle notes, including when the box starts on an overlay.
  A plain drag on a note still moves or resizes it. In **Selection**, filter
  the current selection by role, identified voice or lane. Click a colored
  role name above the plot to select that role across the whole song.
  Shift-click, Ctrl-click or Cmd-click adds the role to the selection.
  The checkbox next to each role name controls its visibility. Shift-drag
  on the ruler still sets a loop.
- With the reconstruction plot focused, press **+**, **=** or **Numpad
  Add** for one semitone up, and **−** or **Numpad Subtract** for one down.
  Hold **Shift** to move by an octave. The **Selection** tab also has
  **−1**, **+1**, **−Oct** and **+Oct** buttons. Each step previews immediately
  and can be undone separately. Studio skips drums, noise and generated
  notes without a score attack. If a pitched note would leave its chip's
  range, Studio refuses the move and
  explains why in the status line. Use **Apply** to rebuild with the new pitches.
- Use **Selection** to set roles, move notes to a voice, create a named voice,
  choose FM, PSG tone, PSG noise or DAC, pin an exact lane, or set or scale
  velocity. An incompatible chip choice shows the reason. **This voice**
  sets the instrument for the whole identified voice. **These notes only**
  applies it to the selected attacks. Search the catalogue and use **Audition
  selection** to hear the chosen instrument on the selected allocated notes.
- In **Selection → Retrigger**, select a single tonal note, enter the interval
  and gate in milliseconds and the number of attacks, then choose
  **Queue retrigger** and **Apply**. The first attack keeps its note ID.
- In **Selection → Layer**, choose **Echo**, **Chorus**, **Double** or
  **Off**. Pick a spare FM or PSG target and set the level. Echo shows the
  delay in frames and milliseconds. The default is 14 frames (233.3 ms)
  and a −3 dB trim from the original 7-TL echo, for 11 TL below dry.
  Clear **Trim from 7 TL echo** to enter dB relative to the dry note.
  With that setting, 10 frames is 166.7 ms and −9 dB is 12 TL.
  Use **Preview A/B** to hear the built span and then the selected setting.
  Choose **Queue layer** to stage the edit, then **Apply**. The plot draws
  the copy beneath the pitch in its lane color. A small × marks a skipped
  copy. Hover over it to see competing note IDs and lanes, or open the
  **Note** tab for details. With **Off**, check the policies to mask. Leave
  them all clear to suppress every layer and policy for the selected notes.
- **Collapse repeats** sustains same-pitch attacks from one part. Select
  two notes of different pitches for **Merge with glide**. Select
  co-attacking chord tones from one part for **Arpeggio** or **Chord instrument**.
- In **Cut**, click inside a scored note. Select the note first and
  **Alt-click** at the desired pitch within the note's time span to retune
  the second half. You can also Alt-drag from the cut time to the new pitch.
- In **Bend / slide**, grab the point where the slide should begin and
  drag up or down. Drag farther right for a longer slide. Hold **Shift**
  to snap to semitones. Drag a handle on the selected curve to revise it.
  The driver plays authored bends on FM.
- In **Vibrato**, drag right to increase rate and up to increase depth
  on an FM note. The readout shows the achievable Hz and cents. Drag down
  to zero depth to turn modulation off for that note.
- Select a scored note to set its velocity or delete it with **Delete**.
  Set a role or pin a lane from the **Note** tab. Delete on a note generated
  during role assignment records a drop. The trace shows the fate and reason,
  role, lane, instrument, both clocks, competing note IDs, and applied edits.
  Expand **Raw trace JSON** for detailed evidence.
- Gold outlines mark pending edits. **Undo** and **Redo** change the draft,
  including removals from the **Edits** tab. Press **Apply** to save the
  shared `edits.json` and rebuild from the first affected stage. Cancel before
  publication to keep the draft. The **Trace diff** tab summarizes changed
  notes, lane permutations, compiled events and PCM outside the edited span.
- The **Edits** tab lists the shared intents with their author, reason and
  last build outcome. Old entries with no author are shown as agent edits.
  Studio adopts updates to `edits.json` and `sections.json` when the draft
  is clean. When pending edits conflict, **Resolve conflict…** can reload
  the disk version and discard the draft, or keep the draft visible.
- Click a section, period or phrase to loop it. Drag a boundary or right-click
  a band to rename, split at the pointer or merge with the next sibling.
  These changes go directly to `sections.json` as human edits.


## Listen and export

Open the [native bank example](../../examples/native-evening-clock.smps.json)
to inspect a v5 project. Select an event to highlight its bytes and address
in **Native bank**. Edit the hex and use **Apply bank** to commit an
in-place change. Use **Preview frames** to set playback length and the export fallback for banks with no detected end or loop.
Edits use the usual undo, save and external refresh. An unsupported
instruction appears as a playback diagnostic. **Export… → SMPS package** still
preserves the exact bank and asset bindings.

**Play** previews from the playhead. Space toggles Play/Stop outside text fields.
Edits or adopted external changes stop the previous playback snapshot. Use **Song settings** to rename the project or change native tempo and ruler
resolution. The inspector shows approximate BPM for context. The native
S3K tempo byte controls delay frames: larger is slower, zero is valid. Tick ruler
resolution is in the project file. Playback and WAV export allow up to ten minutes
and use 48 kHz stereo at 60 NTSC presentations per second with upstream's hardware
frame budget. No audio device? The app reports the problem; WAV export still works.

Choose **Export…** in Studio or the reconstruction window. WAV and MP3 use
ffmpeg. Enable **Limit to a span** to enter project seconds, set a fade-out
duration, or choose separate channel stems with an optional ZIP. A looping
native bank supports an explicit loop count. VGM captures the full native
chip-write stream with DAC writes and a loop point when one is found. The
export runs in the background and reports progress.

For a driver-ready export choose **Raw S3K SMPS** for an arrangement with
local voices, or **SMPS package** to keep native bank addresses, envelope
and DAC bindings, resources and editable source. OpenGGF can use the native
contents via an adapter. OpenGGF currently has no direct ZIP loader.

Choose **Portable SMPS package** for a package that references eligible stock
assets from the player's ROM. Choose **OpenGGF mod** for a music replacement
JAR and enter the decimal music IDs to replace. Both use the project driver,
include redacted source by default and accept an optional SPDX license. For a
native S3K retail bank, **Store changes as a retail song patch** omits source.
An edited bank needs a matching ROM to build the delta. OpenGGF's current
package reader supports the embedded form.

Choose **Disassembly kit** for an SMPS2ASM ZIP targeting the project's driver.
**DPCM closed** quantises the exported WAV samples for DPCM playback. Optional
replacement DAC notes use hexadecimal. A ROM materialises stock files for
personal use.

Use **Choose ROM…** to verify stock references against a local cartridge image.
Studio remembers the path per game in preferences. **Clear** removes the
selection. The confirmation lists matched stock assets and provenance warnings
before writing. A mismatch identifies the slot that failed. Keep embedded
retail data and ROM-materialised kits for personal use. When you open redacted
package source from Studio, the export dialog resolves its stock assets with
the same picker.

To hear the song on the Sonic 3 & Knuckles title screen, register a verified
locked-on ROM under **ROM settings…** and choose **Export… → Test in game…**.
[Test in game setup and options](14-test-in-game.md)

In the reconstruction window, choose **Open as arrangement** to edit the
final sequence in the Studio timeline, piano roll and command editor. The
first hand edit marks the arrangement as **Detached from reconstruction**.
Save the project to persist that state. Returning to Analysis and pressing
**Apply** opens a warning for both saved and unsaved hand edits. Cancel,
keep a sibling copy and apply, save a separate project and apply, or
explicitly overwrite the arrangement.

Make-unique, remaining DAC commands, FM6 switching, envelope semantics and other
native commands are subsequent work. The [S3K support matrix](../architecture/s3k-support.md)
records coverage for the retail importer and the remaining driver gaps.

### Classify notes in Analysis

Switch between **Detail** and **Roles** in the Analysis toolbar. Both views
share the time and pitch viewport over the spectrogram. Roles uses voice
colors and patterns that match the swatches in **Voices**. Notes show short
tags when space allows. Hover for the full voice name and the note's role.
Unassigned notes are grey with a **?** tag. Gold dots mark notes with detail
edits.

Drum hits are hidden when Roles opens. Turn on **Show drums** in **Voices**
to see small, unlabelled hits in a separate strip beneath the pitch plot.
Kick, snare and hat use different shapes. Drum hits stay available in Detail's
chip lanes.

Click a voice name in **Voices** to select all its notes. The checkbox hides
or shows the voice, and **S** highlights it against the other voices.
Drag from empty space to box-select notes. Use **Shift** to add and **Ctrl**
to toggle. To assign the selection, click the destination voice's **→**
button or drag the selected notes from the Roles plot onto its legend entry.

Use **New voice** to create an empty tonal or drum voice or create a voice
from selected notes. Use **⋯** to rename, split or merge a voice. Choose
**Edit tag…** in the same menu to change its label, or clear the field to
restore the automatic tag. Studio saves tags and swatches locally for the
reconstruction. Use
**Selection** to change a role or chip lane type in bulk. Studio checks
whether a classification change would orphan a detail edit target before
queuing the change.

Both views share the selection, playhead, loop, zoom and undo history.
Double-click a note in Roles to focus its selection in Detail. Choose
**Apply** in either view to rebuild with the shared edits.

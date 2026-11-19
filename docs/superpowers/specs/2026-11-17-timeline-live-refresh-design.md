# Timeline and live project refresh

## Scope and intent

Continue the approved reboot direction with an editable native timeline over a
shared text project. An external agent can author/change the same file while a
human sees the result. This slice is a usable small composition workflow, not a
complete DAW or arbitrary SMPS importer.

Use a new JavaFX `studio` module instead of changing the old tracker. This is an
implementation choice: reuse the existing UI runtime and JVM audio integration,
while giving the reboot a separate scene and interaction model. A browser app
would add a server/bridge and packaging surface; patching the old tracker would
retain its navigation and stale playback. The new editor uses the pinned S3K
core through `headless`.

## Shared source and compiler

New Java-25 `project-core`, package `com.opensmpsdeck.project`, has no JavaFX or
audio-device dependency. The first format is JSON version 1, mode S3K. Time is
integer sequencer ticks; `ticksPerBeat` controls the musical ruler. `tempo` is the
native S3K tempo byte, not a misleading BPM value. Dividing timing is 1.

Public immutable records, using defensive list copies:

- `StudioProject(int formatVersion, String name, String mode, int ticksPerBeat,
  int tempo, List<Voice> voices, List<Sequence> sequences, List<Clip> clips)`
- `Voice(String id, String name, List<Integer> bytes)` — 25 native-order bytes.
- `Sequence(String id, String name, String type, int lengthTicks, String voiceId,
  int volume, List<NoteEvent> notes)` — type FM or PSG; absolute volume 0..127.
- `NoteEvent(String id, int tick, int duration, int pitch)` — MIDI pitch (FM 12..106, PSG 45..117);
  monophonic, inside the sequence.
- `Clip(String id, String sequenceId, String lane, int startTick, int lengthTicks)`
  — FM1..FM5 or PSG1..PSG3, type matches the sequence. Duration repeats source
  content and may end partway through its final repetition. No overlap per lane.

`ProjectFormat.read(String):StudioProject` strictly parses/validates unknown and
missing fields, integral/range values, IDs/references, hardware occupancy and
monophony with actionable errors. `ProjectFormat.write(StudioProject):String`
pretty-prints a deterministic document. `ProjectCompiler.compile(StudioProject):
byte[]` validates, expands repeated clips with exact final truncation, emits
silence for gaps, positional stopped channel stubs and native local voices,
resetting voice/absolute volume/pan/transpose at sequence boundaries. Long notes
use ties, rests split into native durations. No implicit clipping of pitch or
16-bit pointers; reject output beyond one 32 KiB bank. The output fits the
existing bounded renderer's finite linear subset. Reuse is linked in the source;
compiler output is baked inline in this slice, not a promise of CALL reuse.
`ProjectEdits` provides pure add/move/resize/delete clip and note-edit operations
with validation. `DemoProject.create():StudioProject` supplies a non-ROM example.

This slice deliberately omits nested multi-channel sequences, DAC/envelope
packages and arbitrary raw-command editing. Its schema must reject unrecognized
fields so edits cannot silently discard unsupported native features. These
remain follow-on work; do not portray FM/PSG notes as the complete SMPS spec.

## Persistence and refresh contract

`ProjectFiles.load(Path):LoadedProject`, where
`LoadedProject(StudioProject project,String revision)` identifies the exact bytes
by SHA-256. Bounded reads reject oversized input. `save(Path, StudioProject,
String expectedRevision):LoadedProject` protects against stale saves, writes a
sibling temporary file and atomically replaces the destination where supported.
A null expectedRevision means create-new, never overwrite an existing file.
Document that uncooperative external writers cannot be made transactionally
atomic with an optimistic filesystem comparison alone.

`ProjectDocument.open(Path)` owns the current project, baseline revision, dirty
state, external pending project and error text. Public synchronized methods:
`project()`, `path()`, `dirty()`, `conflicted()`, `message()`, `edit(StudioProject)`,
`refresh():boolean`, `save()`, `reloadKeepingRecovery():Path`.
Valid external changes replace a clean document; dirty changes become a visible
conflict and retain local edits. Bad/temporarily missing files retain the last
valid state and report errors; later valid changes recover. A conflicting save
fails. Reloading from disk first preserves dirty local work in a unique recovery
file, then adopts the external state. Never silently overwrite either version.

`ProjectWatcher(Path,Runnable):AutoCloseable` observes changes including atomic
replacement and invokes the callback off the UI thread; the editor marshals to
the FX thread before adopting project revisions or changing controls, serializing
adoption with complete read/transform/edit operations. Close must release its thread/resources.

## Human editor

A dark, readable workspace: sequence browser left, horizontal hardware lanes
centre, clip inspector right, note editor below. Show a musical ruler, clip name,
repeat boundaries and note previews. A selected source is linked: note changes
affect every instance. Add clips from the browser, drag clips to move (snap),
drag the right edge to repeat/trim, delete selected clips; inspector fields also
provide precise keyboard-accessible edits. A piano roll displays notes and
supports selection and pitch/time/duration changes, with explicit numeric fields.

Preserve selected clip/note by ID, zoom and scroll when reloading. Clear a missing
selection rather than selecting an unrelated row. Live status distinguishes
saved, unsaved edits, external refresh, invalid input and conflict, and offers
Save or Reload with recovery. Open/New/Save As use native dialogs. Play/Stop uses
pinned-core PCM on a background worker; lack of an audio device must be a clear
recoverable error. Refresh/edit during playback stops that old playback snapshot.
Export WAV/binary uses current validated project. Close prompts for dirty edits
and disposes watcher/playback resources.

## Verification and delivery

Meaningful tests cover compiler timing/repeats/stubs/overflow and strict source
validation, actual file replacement, malformed-then-valid writes, stale saves,
local/external conflicts and recovery. Exercise the actual JavaFX scene under an
isolated Xvfb display: drag/resize/edit/save, external atomic replacement, invalid
input retention and recovery, selection/viewport preservation. Capture a scene
snapshot for visual inspection. Ordinary headless runs may explicitly skip the
UI-only smoke when no display is available; CI runs it separately under Xvfb.
Keep existing source-extraction parity and baseline failure comparison. Continue
on `codex/reimagined`, update PR #3, leave it open, and keep its worktree.

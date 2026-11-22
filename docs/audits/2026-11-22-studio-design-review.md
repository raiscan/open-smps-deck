# Studio design review and hill climb (2026-11-22)

Goal: a first-time user can open a reconstruction, see where to start, and move
between assigning roles, editing notes on the spectrogram, listening to a
section or a selection, and exporting, without hunting through panels. The
arrangement, asset and native views follow the same design language.

The method follows the OpenGGF menu design review. Each surface class is scored
1–10 on seven criteria from captures. A change is kept only if its target class
improves and no other class regresses.

## Owner feedback that drives the scoring

After opening a real four-minute reconstruction, the owner said the UI is
"confusing as all hell":

- "too many panels at once, I didn't know where to start"
- "options, tools and buttons everywhere"
- "wasn't clear how I could switch between assigning roles, viewing
  spectrogram, playing back individual sections or sequences"

Clarity and hierarchy therefore weigh most in the overall scores. Each round
also reports whether these three complaints still hold.

## Capture method

`tools/capture_studio.sh --output DIR`, plus `--work-dir` or `--project` for a
private song, captures every surface at 1920×1080 and 1366×768 logical work
areas. Captures stay outside the repository. Baseline scoring used both the
committed synthetic fixture and a private four-minute audio-only reconstruction
with five parts, about 4,000 notes and 50 detected sections.

## Surface taxonomy

| Class | Surfaces |
| --- | --- |
| A. Shell and start | Launch with no arguments, opened project, global toolbar, project header, status bar |
| B. Arrangement editing | Timeline, sequences list, clip inspector, piano roll, DAC triggers, commands |
| C. Asset editors | FM voices, envelopes, DAC samples, catalogue |
| D. Dialogs | Export (all formats), Test in game, ROM settings, song settings, new sequence |
| E. Processing | Suno and local stem import reports |
| F. Analysis | Reconstruction window: Detail and Roles views, tools, voices, Note/Selection/Edits/Proposals/Trace tabs, layers, transport, unsaved state |
| G. Native bank | Native arrangement, bank event table and bytes, native assets (expert view) |

## Rubric

Each criterion is scored 1–10: 5 = functional but visibly amateur, 7 = good
indie quality, 9 = production polish. Overall is a holistic score, not an
average. The criteria are typography (Type), hierarchy (Hier.), spacing
(Space), colour, icons, consistency (Consist.) and clarity.

## Round 0 baseline

| Class | Type | Hier. | Space | Color | Icons | Consist. | Clarity | **Overall** |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A. Shell and start | 6 | 4 | 6 | 6 | 3 | 5 | 3 | **4** |
| B. Arrangement | 6 | 5 | 5 | 6 | 3 | 5 | 5 | **5** |
| C. Asset editors | 6 | 5 | 5 | 6 | 2 | 5 | 5 | **5** |
| D. Dialogs | 6 | 5 | 6 | 6 | 2 | 5 | 5 | **5** |
| E. Processing | 5 | 4 | 5 | 4 | 4 | 4 | 4 | **4** |
| F. Analysis | 5 | 2 | 3 | 5 | 2 | 3 | 2 | **2.5** |
| G. Native bank | 6 | 5 | 5 | 5 | 2 | 4 | 5 | **4.5** |

### Evidence by class

**F. Analysis (owner's main complaint).**

- *Controls before content.* Four full-width rows of about 35 controls sit
  above the canvas, with no grouping by task:
  - Row 1: Play, Source, Solo lane, Solo note, Clear loop, the instruction
    "Shift-drag to loop" styled as a label, and the Detail/Roles view switch.
  - Row 2: Apply, Undo, Redo, Lane policies, five tool modes, an unlabelled
    voice combo ("1 · bass") and an unlabelled snap combo ("BEAT").
  - Row 3: view settings (time and pitch zoom, spectrum dB range, Ridges,
    Project time).
  - Row 4: role filters, each drawn twice as a checkbox and a coloured chip,
    plus Lanes, Note opacity and Outlines.
- *No sense of task.* The view switch that changes the whole job (Detail vs
  Roles) is a small pair of buttons in the playback row. Nothing says what to do
  first. The header shows an internal run hash instead of the song name.
- *Right column.*
  - The Voices list is unlabelled: each row has a tick, an S, an arrow and an
    ellipsis.
  - Below it, a five-tab inspector (Note, Selection, Edits, Proposals, Trace
    diff) overflows its strip and clips the first tab to "e".
  - At 1366×768 the inspector body almost disappears below the voices.
  - The Note tab shows a page of disabled controls when nothing is selected.
- *Playback scope is invisible.*
  - Sections, periods and phrases are drawn above the canvas, but nothing says
    that they can be played.
  - Playing one voice, one section or the selection relies on Solo buttons,
    shift-drag loops and a Source combo whose effect isn't shown.
  - The status bar shows two clocks.
- *Internal vocabulary.* Note labels read "G6 · FM5 · other / Local FM 02" and
  are truncated. Proposals show op names and IDs ("insertNote ·
  vocal-4-39130000-missingSyllableNote") and file names ("score.json"), and
  repeat the same sentence twice.
- *Two colour systems.* Role chips and voice swatches use different palettes
  for overlapping concepts.

**A. Shell and start.**

- Two rows of global buttons sit beside the project row:
  - global: New, Open, Import S3&K, Import Suno folder, Open reconstruction,
    ROM settings, Save, Save as;
  - project row: Song settings, Play, Stop, Export.
- There's no start screen or recent files. The three ways in (arrangement,
  reconstruction, ROM import) look equal, and nothing says which one a user
  with an MP3 needs.
- "Reload file + keep local recovery" is always visible.

**B. Arrangement.**

- Sequences from a reconstruction all share part names ("vocals" repeated).
- Units are ticks.
- Notes, Commands and Assets nest a second tab row (FM voices, Envelopes, DAC
  samples, Catalogue) inside the editor pane.
- "Voices & assets" is also a sidebar button.

**C. Asset editors.** The operator grid is tidy, but voice names wrap in a
narrow list. Raw register terms carry no help, and there are no icons.

**D. Dialogs.** Export mixes format options with ROM settings and a Test in game
launcher. "Span values use the project clock" assumes knowledge of two clocks.

**E. Processing.**

- The header is an absolute temporary path.
- The table uses a washed grey-blue that breaks the dark theme.
- "Mapped part" is empty for audio-only imports, and the reason "Start
  reconstruction" is disabled is cut off.

**G. Native bank.** Light table rows clash with the dark theme. This is an
expert view, scored for consistency only.

## Proposed targets

| Class | **Overall target** |
| --- | --- |
| F. Analysis | **8** |
| A. Shell and start | **8** |
| E. Processing | **7.5** |
| B, C, D | **7.5** |
| G. Native bank | **7** |

## Hill-climb protocol

1. Pick the change with the largest expected gain, favouring shared components
   over per-screen fixes. Information architecture comes before polish.
2. Implement, recapture at both sizes on the fixture and a private song, and
   rescore every class.
3. Keep a change only if its target class improves and no other class
   regresses. Otherwise revert it and record why.
4. Also record whether each owner complaint still holds.

Planned levers, in expected-gain order:

1. **Analysis workspaces.** One prominent switch (Roles · Notes · Review ·
   Export) replaces the four toolbar rows:
   - Each workspace shows only its own tools and inspector.
   - Selection, zoom and playback scope survive switching.
   - View settings move into a View popover.
   - Role filters merge into the voice/role legend, with eye, solo and select
     controls that have icons and tooltips.
2. **One transport with explicit scope:**
   - What plays: Song, Section (click a section band), Selection, Voice or
     Lane.
   - What you hear: Original, Chip or A/B.
   - One clock.
   - Loop shown as a visible range, not a hidden shift-drag.
3. **Start and next step.**
   - A start screen with recent files and three clear entry points: "Rebuild a
     song from audio", "Continue a reconstruction" and "Open an arrangement".
     ROM import sits under more options.
   - The reconstruction header shows the song name, a phase stepper (Import ·
     Review · Export) and the suggested next step.
4. **Plain language.**
   - Human names for voices, roles and channels.
   - Patch names only on hover or high zoom.
   - Proposals described in words, with IDs and files in a details disclosure.
5. **Shell tidy-up.**
   - A File menu for global actions.
   - Recovery actions only when a conflict exists.
   - One colour system for roles and voices.
   - Consistent dark tables in Processing and the native view.
6. **Design-language skill** (`skills/studio-design-language`) recording the
   rules, as OpenGGF's `ui-design-language` does.

## Rounds

| Round | Change | Commit | Scores | Kept? |
| --- | --- | --- | --- | --- |
| 0 | Baseline | master | above | — |
| 1 | Reconstruction workspaces, header, transport and scoped inspectors | Round 1 PR | below | Yes |

### Round 1 layout sketches

Each sketch allocates the same 1366 × 768 work area.

```text
A · Top workspace switch, bottom transport
┌ Song name · phase                  Apply & rebuild ┐
│ Suggested next step                                │
├ Workspace  [Roles] [Notes] [Review] [Export]   View ┤
│ Task tools                                          │
│ Section / Period / Phrase        │ Task inspector   │
│ Canvas                           │ Scrolls          │
├ Play    Play range ▾    Hear ▾    Loop    00:00      ┤
└─────────────────────────────────────────────────────┘

B · Left workspace rail, bottom transport
┌ Song name · phase                  Apply & rebuild ┐
│ Suggested next step                                 │
├ Roles   │ Task tools             │ Task inspector   │
│ Notes   │ Section bands          │ Scrolls          │
│ Review  │ Canvas                 │                  │
│ Export  │                        │                  │
├ Play    Play range ▾    Hear ▾    Loop    00:00      ┤
└─────────────────────────────────────────────────────┘

C · Top switch and transport
┌ Song name · phase                  Apply & rebuild ┐
│ Suggested next step                                 │
├ Workspace  [Roles] [Notes] [Review] [Export]   View ┤
├ Play    Play range ▾    Hear ▾    Loop    00:00      ┤
│ Task tools                                          │
│ Section bands                    │ Task inspector   │
└ Canvas                           │ Scrolls          ┘
```

**Choose A.** First-time readers encounter song identity and the next action,
then four labelled destinations. Playback stays in the same place as the work
changes. B takes width from the time axis and the inspector where both need
space. C puts playback beside the mode and editing controls, repeating the
baseline's concentration of controls before the canvas. Use one shared canvas
and one playback state so changing workspaces never resets where the user is.

### Round 1 scores

| Class | Type | Hier. | Space | Color | Icons | Consist. | Clarity | **Overall** |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A. Shell and start | 6 | 4 | 6 | 6 | 3 | 5 | 3 | **4** |
| B. Arrangement | 6 | 5 | 5 | 6 | 3 | 5 | 5 | **5** |
| C. Asset editors | 6 | 5 | 5 | 6 | 2 | 5 | 5 | **5** |
| D. Dialogs | 6 | 5 | 6 | 6 | 2 | 5 | 5 | **5** |
| E. Processing | 5 | 4 | 5 | 4 | 4 | 4 | 4 | **4** |
| F. Analysis | 7 | 8 | 7 | 7 | 6 | 7 | 8 | **7.5 (+5)** |
| G. Native bank | 6 | 5 | 5 | 5 | 2 | 4 | 5 | **4.5** |

A–E and G were recaptured at both sizes. Matching fixture frames outside Analysis
were compared with the baseline. Differences in the arrangement focus, selected
native chooser frame, and temporary import paths did not change the layouts.
The four-minute input has no arrangement reference or pending vocal-review
group. Those two states were scored from the fixture. The private work directory
was read-only during capture.

The laptop Roles canvas uses the full height when the drum strip is hidden.
Notes puts transpose and layers ahead of the less frequent routing, instrument,
and retrigger controls. Review summarizes each proposal with its action, the
affected voice and time when known, and its preview status. The Details
disclosure retains the original reason and provenance. Long role summaries in
the voice legend can ellipsize. Hover shows the full summary.

### Round 1 self-review

| Owner feedback | Result | Capture evidence at 1366×768 |
| --- | --- | --- |
| Too many panels and no clear start | Roles opens with the song or work-folder name, a suggested next action and a short assignment hint. The inspector belongs to the selected workspace. | Private `analysis-roles.png`, fixture `analysis-detail.png` |
| Options, tools and buttons everywhere | The Notes row carries note tools and snap. View settings open on demand. The inspector exposes Layers and other tasks as disclosures. Undo and redo live under Changes. | Fixture `analysis-tool-select.png`, `analysis-view.png`, `analysis-dirty.png` |
| Unclear how to switch tasks or play sections and sequences | Four labelled workspaces stay above the canvas. The fixed transport identifies the play range, listening source and loop. A section click selects and cues that band. | Fixture `analysis-proposals.png`, `analysis-export-workspace.png`, `analysis-transport-section.png`, `analysis-transport-selection.png`, `analysis-transport-voice.png`, `analysis-transport-lane.png`, `analysis-transport-loop.png` |

The scores are a visual self-review. The owner has yet to try the new layout.
The JavaFX tests exercise workspace and loop persistence, transpose, role
selection and the existing edit gestures. A synthetic playback probe sent
all five scopes to an ALSA null device and observed each end position.
Original playback for a voice uses a mapped source stem where available.
That stem may include other voices. The original side of a chip lane uses
the reference for comparison. The start screen, arrangement shell, and
global design language remain outside this round.

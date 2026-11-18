# Studio design review and hill climb

Studio needs to make arranging notes, editing S3&K effects, and switching
between a bank and its editable copy easy to follow at desktop scale. This
review adapts the OpenGGF menu audit of 2026-10-27. Separate reviewers score
the same production scenes before discussing changes. Each score has a
visible issue or a workflow behind it.

## Rubric

Score each dimension from 1 to 10. A 5 is functional, a 7 is good indie
quality, and a 9 is production polish. Overall is a holistic judgement.

| Dimension | What to inspect |
| --- | --- |
| Typography | Text size, legibility at desktop scale, and useful truncation |
| Hierarchy | Project and selection context, primary actions, and a clear next step |
| Space | Room for the timeline and editor, scroll behaviour, dense and empty states |
| Color | Contrast and clear distinctions among editable, selected, and warning states |
| Icons | Readable transport, clip, note, and DAC marks |
| Consistency | Shared controls, labels, focus, and workflow between views |
| Clarity | Can someone find the action and tell if it worked? |

Every round starts with the same projects, window scales, and tasks. Pick
one change with a likely gain across screens. Give a worker ownership of
its files and ask the reviewers to score the recaptures. Keep the change
when the intended screens improve and the covered neighbouring workflows
still pass. Write down any trade-off, missing capture, or test result. A
number without evidence does not determine whether to keep a change.

## Round 0

The initial scorecards used the production captures made at `bdcbd8d`.
The long-title fixture was then captured at 100%, 160%, and 200% to isolate
the effect of the project bar. Command and asset views use the same
1440 × 900 examples for scoring each round.

| View | Type | Hier. | Space | Color | Icons | Consist. | Clarity | Overall |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Editable arrangement and piano roll, 100% | 7 | 6.5 | 6 | 7 | 5.5 | 7 | 6.5 | **6.5** |
| Editable arrangement, long title at 160–200% | 6.5 | 5.5 | 4.5 | 7 | 5.5 | 6 | 5.5 | **5.5** |
| Native arrangement, long title at 160–200% | 6.5 | 5 | 4 | 7 | 5.5 | 6 | 5 | **5** |
| Native bank, long title at 160–200% | 6 | 5 | 4.5 | 4 | 5 | 4.5 | 5 | **5** |
| Commands, 100% | 7 | 5 | 4 | 7 | 5 | 6 | 5 | **5.5** |
| FM voices, 100% | 6 | 5 | 5 | 7 | 5 | 6 | 5 | **5.5** |
| Envelopes, 100% | 6 | 6 | 5 | 7 | 5 | 6 | 6 | **6** |
| DAC hits, 100% | 7 | 7 | 7 | 7 | 6 | 7 | 7 | **7** |
| DAC hits, 160–200% | 6.5 | 5.5 | 4.5 | 7 | 5.5 | 6 | 5.5 | **5.5** |

At 200%, the long native project title pushed Play, Stop, and Export onto
three rows. The native arrangement showed the ruler and no lanes. The bank
showed one event row and its slate table body stood out against the file
editor. A single command filled the first row of a 220 px table while Add
and Apply were below the viewport. The voices list scrolled horizontally
and the generated ID competed with the voice name. DAC's inline controls
kept the tick, sample and hit actions together at normal scale.

## Round 1 · Keep playback and export on one row

The shared project bar now keeps Play, Stop, and exports together. Play uses
the existing accent colour. The title yields space to the actions. New,
Open, Import, Save, and the file watcher are still present above and below it.

| Matched view | Type | Hier. | Space | Color | Icons | Consist. | Clarity | Overall |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Editable arrangement, long title at 160–200% | 6.5 | 7 | 6.5 | 7.5 | 5.5 | 7 | 6.5 | **6.5** |
| Native arrangement, long title at 160–200% | 6.5 | 7 | 6.5 | 7.5 | 5.5 | 7 | 7 | **6.5** |
| Native bank, long title at 160–200% | 6 | 6 | 6.5 | 4.5 | 5 | 5 | 6 | **6** |
| Commands, 100% | 7 | 6 | 5 | 7 | 5 | 6 | 5 | **6** |
| FM voices, 100% | 6 | 6 | 6 | 7 | 5 | 6 | 5 | **6** |
| Envelopes, 100% | 6 | 7 | 6 | 7 | 5 | 6 | 6 | **6.5** |
| DAC hits, 100% | 7 | 8 | 7 | 7 | 6 | 7 | 7 | **7.5** |

Kept. At 200%, the editable bar went from 132 to 48 logical pixels and the
native bar from 174 to 48. The editable view gained the FM3 clip and FM4
lane. The native view gained a complete FM1 clip and the FM2 lane. The bank
shows five complete event rows. At 100% the shared bar freed 50 pixels
above the editors. Commands now shows the Add and Apply labels, though
the bottoms of the buttons remain below the viewport. All normal-scale
DAC controls fit.

The long fixture titles truncate sooner and retain the project names. The
compact sequence browser and bank table still need attention. The six
packaged DPI checks exercised clip and note edits, command edits, voice
Apply, envelope creation, DAC hit placement, Save, and window bounds.
The isolated Studio tests exercised import handoff, editable-copy Save,
live refresh, and assigning and disabling a timed envelope. No real
cartridge picker or high-DPI playback session was captured in this round.

## Round 2 · Put the command form before the event table

The command, tick, operands or linked asset, and Apply now sit together.
Commands with no raw operands hide that field. The table uses up to six
visible rows and scrolls for longer lists.

| Matched view | Type | Hier. | Space | Color | Icons | Consist. | Clarity | Overall |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Single event, round 1 | 6.5 | 5 | 4.5 | 7 | 5.5 | 6.5 | 5 | **5.5** |
| Single event, round 2 | 6.5 | 7 | 6.5 | 7 | 5.5 | 7 | 7 | **7** |
| Dense same-tick events, round 1 | 6.5 | 5.5 | 5 | 7 | 5.5 | 6.5 | 5.5 | **5.5** |
| Dense same-tick events, round 2 | 6.5 | 6.5 | 6 | 7 | 5.5 | 7 | 6.5 | **6.5** |

Kept for the editing gain at 100% and 200%. At 200%, Apply went from
387 logical pixels below the viewport to 24 pixels inside it. At 100%, the
single-event table uses 64 pixels, down from 220. The dense table still shows
six complete rows in the normal window. At 200%, browsing the event table now
starts with a scroll, where the previous view showed its first rows immediately.
This is the trade-off for bringing the form and Apply into view.

The matching arrangement, bank, and DAC images are pixel-identical.
Voice and envelope layouts match. The Studio suite covered named envelope
assignment, save and reopen, event order, and stale drafts. All six packaged
DPI pointer checks passed again.

## Repeat the captures

Build Studio and run its existing optional captures for commands and assets.
Choose a writable scratch directory for Java and the test fixtures.

```sh
mkdir -p studio/target/qa
xvfb-run -a -s '-screen 0 1920x1080x24' \
  mvn -B -pl studio -am verify -Dstudio.ui.test=true \
  -Dstudio.snapshot="$PWD/studio/target/qa/studio.png"
```

Use the test-scope `StudioDesignCapture` for the long-title comparison.
It opens a copy of the supplied project in the real editor, captures the
native bank when present, and reports bar height and action positions. Run
it for `lantern-walk` and `native-evening-clock` with scales 1, 1.6, and 2.
Use a fresh destination filename for every invocation.

```sh
studio_cp=studio/target/opensmpsdeck-studio-0.1.0-SNAPSHOT.jar:studio/target/test-classes
xvfb-run -a -s '-screen 0 1920x1080x24' \
  java -Dglass.gtk.uiScale=2 -cp "$studio_cp" \
  com.opensmpsdeck.studio.StudioDesignCapture examples/native-evening-clock.smps.json \
  "$capture_scratch/native-2.smps.json" "$capture_scratch/native-2.png"
python3 tools/verify_studio_dpi.py --scratch "$capture_scratch"
```

To capture Commands, use `lantern-walk` and append `commands` or
`dense-commands` after the image path. Run both at 100% and 200%.

Local images and measurement logs for all three rounds are in
`studio/target/qa/design-review/`. The compact captures use 903 logical
pixels at 200%. To compare bars, use matching fixture names. Capture on
both sides of the change, with no other layout edits between runs.

## Next candidate

Check compact event browsing with the same dense fixture. The desired state
keeps Apply visible and makes the event table easy to find by mouse or keyboard.
The asset editors are another candidate. FM voices can lead with the voice
name, keep the technical identifier secondary, and keep Apply reachable while
preserving every operator value. Recapture Notes, DAC, and both asset editors
after either change.

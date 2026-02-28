# Tutorial: Your First Song

This tutorial walks you through creating a complete song from scratch -- building instruments, writing patterns, arranging them, and exporting the result. By the end you will have a short loop with FM bass, FM lead, and PSG rhythm.

The tutorial assumes you have completed the [Quick Start](01-quick-start.md) and have the application open with an empty song. If you need to create a fresh song at any point, use **File > New** (`Ctrl+N`).

Every step tells you exactly what to press or click. At the end of each section, a confirmation tells you what you should see or hear so you can verify you are on track before moving on.

---

## 1. Create a Bass Voice

*Goal: create a simple low-frequency FM voice suitable for a bass line.*

The YM2612 FM chip needs a voice patch to produce sound. A voice defines how the four operators are routed and configured -- it determines the timbre. You will create a minimal voice that produces a clean, sustained tone suitable for bass.

1. Look at the **Instrument Panel** on the right side of the window. Under the **Voice Bank** heading, click **+**.

2. The **FM Voice Editor** dialog opens with a blank voice (all parameters at zero). Click **Init** to load a clean starting patch. **Init** sets sensible defaults: the carrier gets full volume and instant attack, while all modulators are silenced.

3. Set **Algorithm** to `7` using the combo box in the top row of the editor. Algorithm `7` routes all four operators as independent carriers -- the simplest topology. Each operator produces its own output with no modulation between them. This is the easiest algorithm to understand and a good starting point for basic tones.

4. At algorithm `7`, all four operators are carriers. However, after **Init**, only operator 4 has audible output -- the other three have their **TL** set to `127` (silent). Leave operators 1, 2, and 3 alone for now. You only need to configure operator 4.

5. On **Op 4**, set the following parameters using the sliders in the rightmost operator column:

   - **MUL** to `1` -- this is the frequency multiplier. `1` means the operator plays at the fundamental frequency, matching the note you enter in the tracker grid.
   - **AR** to `31` -- attack rate. `31` is the maximum, producing an instant attack with no fade-in. The sound reaches full volume immediately when a note triggers.
   - **D1R** to `0` -- first decay rate. `0` means no decay after the attack. The sound stays at peak volume indefinitely while the note is held.
   - **D2R** to `0` -- second decay rate. `0` means no further decay during sustain. Combined with D1R `0`, the note sustains at full volume.
   - **RR** to `8` -- release rate. `8` provides a moderate fade-out when the note ends. Not too abrupt, not too long.

6. Type `Bass` in the **Name** field at the top left of the editor. This name appears in the voice bank list so you can identify the voice later.

7. Click **Preview**. You should hear a simple, clean tone at middle C for about half a second. It should sound like a pure sine wave -- smooth, with no buzz or overtones.

8. Click **OK** to save the voice and close the editor.

**What you should see:** The voice bank in the **Instrument Panel** now shows one entry: `00: Bass`. The `00` is the hex index that you will use when assigning this voice to a channel.

> **Reference:** [FM Voice Editor](04-fm-voice-editor.md) covers all eight algorithms, operator parameters, and envelope shaping in full detail.

---

## 2. Create a Lead Voice

*Goal: create a brighter, richer FM voice for a melody line.*

This voice uses a more complex algorithm with actual FM modulation between operators. The result is a two-layered sound that is distinctly different from the simple bass voice.

1. Click **+** again in the **Voice Bank** section of the **Instrument Panel**.

2. The **FM Voice Editor** opens with another blank voice. Click **Init** to start from a clean patch.

3. Set **Algorithm** to `4`. This creates two independent modulator-carrier pairs: Op 1 modulates Op 2, and Op 3 modulates Op 4. Because there are two carriers (Op 2 and Op 4), the voice produces two layers of sound that mix together.

4. Configure **Op 2** (a carrier in algorithm `4` -- its column should have a cyan border):

   - **MUL** `2` -- one octave above the fundamental. This makes Op 2 output at twice the note frequency, adding brightness and presence.
   - **TL** `0` -- full volume. This is the primary output of the voice.
   - **AR** `28` -- fast but not quite instant. The slight softness on the attack gives the note a more natural onset.
   - **D1R** `5` -- gentle first decay. The volume drops slightly after the initial attack.
   - **D1L** `3` -- the first decay stops near the top of the volume range, so the note sustains loudly after the initial dip.
   - **RR** `10` -- moderately fast release. The sound cuts off fairly quickly when the note ends.

5. Configure **Op 4** (the other carrier -- also cyan-bordered):

   - **MUL** `1` -- fundamental frequency. This grounds the voice with the actual pitch of the note.
   - **TL** `10` -- slightly quieter than Op 2. This carrier blends underneath, adding body without dominating.
   - **AR** `31` -- instant attack. Combined with Op 2's slightly softer attack, this creates a punchy onset.
   - **D1R** `3` -- very slight decay.
   - **D1L** `2` -- sustains near peak volume.
   - **RR** `8` -- moderate release.

6. Configure **Op 1** (modulator for Op 2 -- its column should have a gray border):

   - **MUL** `3` -- third harmonic. This means Op 1 oscillates at three times the note frequency, introducing bright overtones into Op 2's output.
   - **TL** `80` -- moderate modulation depth. Lower TL values on modulators mean stronger modulation. `80` is roughly in the middle of the range, enough to add character without making the sound harsh or metallic.
   - **AR** `31` -- instant attack.
   - **RR** `15` -- fast release so the modulation does not linger after the note ends.

7. Leave **Op 3** (modulator for Op 4) at its **Init** defaults. **TL** `127` means it is completely silent and applies no modulation to Op 4. This means Op 4 outputs a clean, unmodulated sine tone -- providing a warm foundation while Op 2 carries the brighter, modulated character.

8. Type `Lead` in the **Name** field.

9. Click **Preview**. You should hear a brighter, two-layered tone -- noticeably richer and more interesting than the Bass voice. The Op 1 modulation adds harmonic complexity to the upper layer while the lower layer stays clean.

10. Click **OK** to save and close.

**What you should see:** The voice bank now has two entries: `00: Bass` and `01: Lead`.

> **Reference:** [FM Voice Editor](04-fm-voice-editor.md) explains carrier vs. modulator roles, how TL controls modulation depth on modulators, and provides common envelope recipes.

---

## 3. Create a PSG Envelope

*Goal: create a short percussive envelope for a rhythmic tick sound.*

The SN76489 PSG chip produces simple square-wave tones. Unlike FM voices with complex multi-operator synthesis, PSG channels shape their sound entirely through volume envelopes -- a list of volume steps that play out frame by frame.

1. In the **Instrument Panel**, look below the voice bank for the **PSG Envelopes** section. Click **+**.

2. The **PSG Envelope Editor** opens as a dialog with a bar graph display. It starts with 2 default steps.

3. Click **+Step** twice to add two more steps, giving you 4 steps total. The **Steps** label between the buttons should read `4`.

4. Click on each bar in the graph to set its volume. Remember that volume `0` is the loudest and `7` is the quietest:

   - Step 0: click near the bottom of the bar to set volume `0` (loudest -- tallest bar).
   - Step 1: click slightly higher to set volume `2` (slightly quieter).
   - Step 2: click near the middle to set volume `5` (noticeably quieter).
   - Step 3: click near the top to set volume `7` (near silence -- shortest bar).

5. The bar graph should now show a staircase descending from left to right -- loud on the left, quiet on the right. This creates a fast decay from full volume to near-silence over four frames, producing a short percussive tick.

6. Type `Tick` in the **Name** field at the top of the dialog.

7. Click **Preview**. You should hear a brief, sharp click at middle C -- a quick buzz that decays almost instantly.

8. Click **OK** to save and close.

**What you should see:** The PSG envelope list in the **Instrument Panel** now shows `00: Tick`.

> **Reference:** [PSG Envelopes](05-psg-envelopes.md) covers volume levels, step management, longer envelope designs, and percussion imitation techniques.

---

## 4. Enter Bass Notes on FM1

*Goal: write an eight-row bass pattern on the first FM channel.*

Now that you have instruments, it is time to write music. The tracker grid uses a piano-style keyboard layout: the lower row of letter keys (`Z` through `M`) maps to notes C through B in the current octave.

1. Click on the **Tracker Grid** in the center of the window. The cursor should be on FM1, row `00`. If it is not, click on the FM1 column header and use the arrow keys to navigate to row `00`.

2. Press `F3` to select octave 3. This is a good range for bass -- low enough to feel heavy and grounded without becoming muddy or indistinct. The current octave affects all subsequent note entry.

3. Enter the following notes using the keyboard. After each note entry, the cursor advances down one row automatically:

   - Press `Z` -- enters **C-3** on row `00`. (`Z` is the C key on the lower keyboard row.)
   - Press `Z` -- enters **C-3** on row `01`. (Repeating the root note for emphasis.)
   - Press `.` (period) -- enters a rest (`---`) on row `02`. (A gap before the next chord tone.)
   - Press `V` -- enters **F-3** on row `03`. (`V` is the F key.)
   - Press `V` -- enters **F-3** on row `04`.
   - Press `.` -- enters a rest on row `05`.
   - Press `B` -- enters **G-3** on row `06`. (`B` is the G key.)
   - Press `B` -- enters **G-3** on row `07`.

4. Your FM1 channel should now show eight rows of data:

   ```
   00  C-3
   01  C-3
   02  ---
   03  F-3
   04  F-3
   05  ---
   06  G-3
   07  G-3
   ```

5. Press `Space` to play it back. You should hear the notes played with the default FM voice -- a simple repeating phrase that moves from C up to F, then to G. Press `Escape` to stop.

**What you should hear:** A short, repeating bass phrase. The notes will sound plain because you have not assigned your custom Bass voice yet -- that comes in the next section. The harmonic movement is C (root), F (fourth), G (fifth). This I-IV-V progression is one of the most fundamental patterns in music.

> **Reference:** [Tracker Grid](07-tracker-grid.md) explains the full note entry keyboard layout, including the upper row for sharps and the octave+1 row.

---

## 5. Assign the Bass Voice

*Goal: assign voice `00` (Bass) to the FM1 channel so the notes use your custom timbre.*

Each channel needs an instrument assignment to know which voice (FM) or envelope (PSG) to use. You set this in the instrument column of the tracker grid.

1. Use the `Up` arrow key to move the cursor back to row `00` on FM1. Make sure you are in the FM1 channel -- the channel header above should read **FM1**.

2. Press `Right` to move from the **Note** column to the **Instrument** column. The cursor underline shifts to the two-digit hex field next to the note.

3. Type `0` -- the first hex digit. It appears in yellow as a pending value with an underscore placeholder: `0_`. This visual indicator tells you the entry is incomplete.

4. Type `0` -- the second hex digit. The full value `00` is committed, and the instrument column on row `00` now reads `00` in green. The tracker writes a SET_VOICE coordination flag into the bytecode.

5. Press `Space` to play. The bass line now uses your custom Bass voice -- you should hear the clean, sustained tone you designed in the voice editor. It should sound warmer and more defined than the default voice. Press `Escape` to stop.

**What you should hear:** The same C-F-G bass phrase, but now with the smooth sine tone of your Bass voice. Each note attacks instantly and sustains cleanly, with a moderate fade-out at the end.

You only need to set the instrument once at the beginning of the channel. Every note that follows on FM1 will use voice `00` until you place a different instrument change on a later row.

> **Reference:** [Tracker Grid](07-tracker-grid.md) details the two-stage hex entry process and explains how instrument changes map to SMPS coordination flags.

---

## 6. Enter Lead Melody on FM2

*Goal: write a melody on the second FM channel and assign the Lead voice.*

With the bass in place, you need a melody that sits above it. You will use a higher octave and a different voice to make the two channels clearly distinct.

1. Press `Tab` to move the cursor to the **Note** column of FM2. `Tab` always jumps to the note column of the next channel, so you land on FM2 row `00`.

2. Press `F5` to select octave 5. This puts the melody two octaves above the bass, giving clear separation between the two parts.

3. Enter the following melody. Each key press enters a note and advances the cursor:

   - Press `Z` -- **C-5** on row `00`.
   - Press `X` -- **D-5** on row `01`. (`X` is the D key.)
   - Press `C` -- **E-5** on row `02`. (`C` is the E key.)
   - Press `X` -- **D-5** on row `03`.
   - Press `Z` -- **C-5** on row `04`.
   - Press `.` -- rest on row `05`.
   - Press `F4` to drop the octave to 4, then press `B` -- **G-4** on row `06`. The octave drop lets you reach down for the lower G.
   - Press `F5` to return to octave 5, then press `Z` -- **C-5** on row `07`. The melody resolves back to the root.

4. Your FM2 channel should now show:

   ```
   00  C-5
   01  D-5
   02  E-5
   03  D-5
   04  C-5
   05  ---
   06  G-4
   07  C-5
   ```

5. Use the `Up` arrow to move the cursor back to row `00` on FM2. Press `Right` to reach the **Instrument** column.

6. Type `0` then `1` to assign voice `01` (your Lead patch). The instrument column reads `01` in green.

7. Press `Space` to play. You should hear both channels together -- the bass line on FM1 and the melody on FM2, each with its own distinct voice. The Lead voice should be noticeably brighter and richer than the Bass. Press `Escape` to stop.

**What you should hear:** A two-channel arrangement. The bass provides a steady low foundation while the melody moves above it with a stepping-down-and-back-up contour. The two voices are clearly distinguishable thanks to their different algorithms and operator configurations.

> **Reference:** [Tracker Grid](07-tracker-grid.md) covers channel navigation with `Tab` and `Shift+Tab`, and octave selection with `F1` through `F8`.

---

## 7. Add PSG Rhythm on PSG1

*Goal: add rhythmic texture using the PSG chip with your percussive Tick envelope.*

The PSG channels produce square-wave tones. Combined with a short decay envelope, they make excellent rhythmic accents that cut through the FM mix.

1. Press `Tab` repeatedly until the cursor reaches **PSG1** (channel index 6). You will pass through FM3, FM4, FM5, and DAC on the way. Watch the channel headers to confirm you have arrived at **PSG1**. You can also use `Shift+Tab` to wrap backward if you overshoot.

2. Press `F6` to select octave 6. PSG square waves sound best at higher pitches. At octave 6, the buzzy timbre reads as a crisp tick rather than a low, droning buzz.

3. Enter a pattern of alternating notes and rests to create a steady rhythmic pulse. This pattern fills all eight rows:

   - Press `Z` -- **C-6** on row `00`.
   - Press `.` -- rest on row `01`.
   - Press `Z` -- **C-6** on row `02`.
   - Press `.` -- rest on row `03`.
   - Press `Z` -- **C-6** on row `04`.
   - Press `.` -- rest on row `05`.
   - Press `Z` -- **C-6** on row `06`.
   - Press `.` -- rest on row `07`.

4. Your PSG1 channel should show alternating notes and rests:

   ```
   00  C-6
   01  ---
   02  C-6
   03  ---
   04  C-6
   05  ---
   06  C-6
   07  ---
   ```

5. Move the cursor back to row `00` on PSG1 using the `Up` arrow. Press `Right` to reach the **Instrument** column.

6. Type `0` then `0` to assign PSG envelope `00` (your Tick patch). On PSG channels, the instrument column sets the PSG envelope index via the PSG_INSTRUMENT coordination flag, rather than the FM voice index.

7. Press `Space` to play. You should hear all three channels together: the FM bass, the FM lead melody, and the PSG rhythmic tick. Press `Escape` to stop.

**What you should hear:** A three-channel arrangement. The bass and lead carry the harmony and melody, while the PSG tick adds a steady pulse that drives the rhythm forward. The four-step decay envelope makes each PSG hit a brief click that does not clash with the FM tones.

> **Reference:** [PSG Envelopes](05-psg-envelopes.md) explains how step count and volume curves shape the character of PSG sounds.

---

## 8. Create a Second Pattern

*Goal: add a second pattern with variation to make the song more interesting.*

A song that plays the same pattern forever is monotonous. You will create a second pattern by copying the first and modifying the melody to add variety.

1. Look at the **Order List Panel** at the bottom of the window. It currently shows one row (row `00`) referencing pattern `00` across all 10 channels.

2. Click the **+** button in the order list toolbar to add a new row. Row `01` appears, initially referencing pattern `00` on all channels. Clicking **+** also creates a new empty pattern for the new row.

3. Click row `00` in the order list to select it and load the original pattern into the tracker grid.

4. Press `Ctrl+A` to select all rows across all channels. The entire pattern highlights with a semi-transparent blue overlay.

5. Press `Ctrl+C` to copy the selection to the clipboard.

6. Click row `01` in the order list. The tracker grid switches to show the new, empty pattern.

7. Press `Ctrl+V` to paste. All your note data, instrument assignments, and rests from pattern `00` are now duplicated into the second pattern. The grid should look identical to the original.

8. Now create a variation on the melody. Click on the FM2 channel header or navigate to FM2 row `00`.

9. Select the melody notes: place the cursor on FM2 row `00`, then hold `Shift` and press `Down` seven times to extend the selection through row `07`. The eight melody rows should highlight in blue.

10. Press `Shift+=` to transpose the selection up by one octave (12 semitones). The notes jump from octave 5 to octave 6 -- the melody is now higher and brighter.

11. Optionally, change a few individual notes to make the second pattern feel even more different. Navigate to any note on FM2 and press a different key to replace it. For example, change the `D-6` on row `01` to an `E-6` by pressing `C` (the E key in octave 6).

12. Click row `01` in the order list if it is not already selected. Press `Space` to hear the second pattern in isolation. It should sound like a recognizable variation of the first -- same bass and rhythm, but with the melody pitched higher. Press `Escape` to stop.

**What you should see:** The order list has two rows. Clicking each row loads the corresponding pattern into the tracker grid. The second pattern has the same bass and PSG rhythm as the first, but the FM2 melody is transposed up.

> **Reference:** [Patterns and Orders](08-patterns-and-orders.md) covers the order list, the **Dup** button for duplicating rows, and per-channel pattern assignment.

---

## 9. Arrange and Loop

*Goal: arrange the two patterns into a looping song structure.*

The order list controls the playback sequence. Each row plays in order from top to bottom. When the last row finishes, the song jumps back to the loop point.

1. The **Order List Panel** now has two rows:
   - Row `00` -- your original pattern (bass, melody at octave 5, and tick).
   - Row `01` -- the variation (melody transposed to octave 6).

2. Playback proceeds top to bottom: row `00` plays first, then row `01`, then the song loops back.

3. Click row `00` in the order list to select it, then click the **Loop** button in the toolbar. A loop arrow indicator appears next to row `00`. This tells the SMPS sequencer that after row `01` finishes, playback jumps back to row `00`. Both patterns repeat indefinitely: `00` -> `01` -> `00` -> `01` -> ...

4. Try a different arrangement: click row `01`, then click **Loop**. Now row `00` acts as a one-time intro. It plays once on the first pass, then the song loops at row `01` and only the variation repeats. This is a common technique for creating songs with intros.

5. For this tutorial, set the loop point back to row `00` so both patterns repeat.

6. Press `Space` to hear the full arrangement from the beginning. You should hear your original pattern followed by the variation, then the song loops back and both patterns play again. Press `Escape` to stop.

**What you should hear:** A two-section song that loops. The first section establishes the theme; the second section varies it by moving the melody higher. The loop is seamless -- after the second section, the original returns without any gap.

The loop point maps directly to the SMPS `F4` jump command in the exported binary. Every SMPS song must loop -- there is no "play once and stop" option in the Mega Drive sound driver.

> **Reference:** [Patterns and Orders](08-patterns-and-orders.md) explains loop points, row management, and how per-channel pattern indices enable flexible arrangements.

---

## 10. Set Tempo and Timing

*Goal: adjust the speed and feel of the song.*

The tempo controls how fast the sequencer advances through the pattern data. Finding the right speed makes the difference between a song that feels energetic and one that drags.

1. Look at the **Transport Bar** at the top of the window, directly below the menu bar.

2. Find the **Tempo** spinner. The default value depends on the SMPS mode. In `S3K` mode, higher tempo values produce slower playback (the tempo value is added to an accumulator, and overflows cause the sequencer to skip ticks). Try these values to hear the difference:

   - `6` for a moderate, comfortable pace.
   - `4` for a faster, more energetic feel.
   - `8` for a slower, more relaxed groove.

3. Adjust the spinner and press `Space` to hear the difference. Changes apply immediately -- you do not need to stop playback or confirm. You can even adjust the tempo while the song is playing to find the right speed in real time.

4. The **Div** spinner controls **Dividing Timing**, which defaults to `1`. Leave it at `1` for this tutorial. Higher values subdivide the tempo tick for finer rhythmic control, useful for complex patterns with sixteenth notes or triplets, but unnecessary for this simple arrangement.

5. The **Mode** dropdown defaults to `S3K` (Sonic 3 & Knuckles). Leave it unless you are targeting a specific Sonic game for ROM injection. S3K is the most capable SMPS variant and the best choice for general-purpose composition.

6. Settle on a tempo that makes your bass line groove and your melody flow naturally.

**What you should notice:** Lower tempo values make the song play faster; higher values slow it down. The relationship between the tempo number and actual speed depends on the SMPS mode, which is why the same number sounds different between S1, S2, and S3K.

> **Reference:** [SMPS Modes](12-smps-modes.md) explains how each mode interprets the tempo value differently and when to choose each variant.

---

## 11. Listen and Export

*Goal: play through the finished song, save the project, and export it.*

You have built a complete song: two FM voices, a PSG envelope, two patterns, an arrangement with a loop, and a tempo setting. Now it is time to review, save, and export.

### Review

1. Click row `00` in the order list to start from the beginning.

2. Press `Space` to play the full song. Listen through at least one complete loop and verify:
   - The bass on FM1 provides a steady harmonic foundation (C-F-G movement).
   - The lead on FM2 sits above the bass with a distinct, brighter timbre.
   - The PSG tick on PSG1 adds a regular rhythmic pulse.
   - The second pattern sounds like a clear variation of the first, with the melody pitched higher.
   - The loop transition is smooth -- no awkward silence or glitch between the end of row `01` and the restart at row `00`.

3. If anything sounds wrong, press `Escape` to stop. Navigate to the problem and fix it. Use `Ctrl+Z` to undo any mistakes. Then play again to verify.

### Save

4. Save the project with **File > Save** (`Ctrl+S`). If this is a new, unsaved song, a file dialog prompts you for a location and filename. Choose a location and save it as an `.osmpsd` file.

5. The `.osmpsd` format is the project file -- it is a JSON document that preserves everything: song name, SMPS mode, tempo, dividing timing, all FM voices, all PSG envelopes, all patterns, and the full order list with loop point. You can close the application and reopen the project later to continue editing.

### Export WAV

6. Select **File > Export WAV...** from the menu bar. Choose a destination filename.

7. The exporter renders the song offline through the synth engine at 44,100 Hz, 16-bit stereo. By default it plays the song through two full loops, applying a linear fade-out over the entire final loop. A progress dialog shows the render status.

8. When the export finishes, you have a standard `.wav` file that you can play in any media player, share online, or import into an external audio editor.

### Export SMPS Binary

9. Select **File > Export SMPS...** from the menu bar. Choose a destination filename.

10. This produces a raw `.bin` file containing the compiled SMPS bytecode -- the header, track pointers, voice table, and all pattern data packed into a single binary blob. This is the same data that the playback engine feeds to the sequencer during real-time playback.

11. The `.bin` file is ready for injection into a Sonic ROM using a hex editor or ROM hacking tool, or for playback by an external SMPS driver such as SMPSPlay or the OpenGGF sonic-engine.

### Done

Congratulations -- you have composed a complete Mega Drive song from scratch. You built two FM voices with different algorithms and timbres, designed a PSG percussion envelope, wrote patterns with bass, melody, and rhythm, arranged them into a looping structure, and exported the result in three formats: project file, audio, and SMPS binary.

> **Reference:** [Playback and Export](09-playback-and-export.md) covers WAV render settings, mute/solo behavior during export, voice bank export, and binary format details.

---

## Where to Go Next

Now that you have completed the tutorial, explore these topics to deepen your skills:

- **Shape richer FM voices.** The [FM Voice Editor](04-fm-voice-editor.md) chapter covers modulation depth, feedback, detune, and common envelope recipes for leads, pads, and percussion.
- **Design more PSG envelopes.** The [PSG Envelopes](05-psg-envelopes.md) chapter explains longer sustained envelopes, hi-hat imitations, and volume curve techniques.
- **Use DAC samples.** The [DAC Samples](06-dac-samples.md) chapter shows how to import drum samples from WAV files and trigger them from the DAC channel for real sampled percussion.
- **Master the tracker grid.** The [Tracker Grid](07-tracker-grid.md) chapter details selection, transposition, copy/paste across songs, undo/redo, and channel mute/solo.
- **Build complex arrangements.** The [Patterns and Orders](08-patterns-and-orders.md) chapter covers per-channel pattern assignment, the **Dup** button, and strategies for efficient song structure.
- **Import existing music.** The [Importing](10-importing.md) chapter explains how to bring in SMPS binaries from Sonic ROMs, import individual FM voices, and load voice bank files.
- **Learn every shortcut.** The [Keyboard Reference](11-keyboard-reference.md) is a complete cheat sheet of every key binding in the application.

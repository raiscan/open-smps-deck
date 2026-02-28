# FM Voice Editor

The FM voice editor creates timbres for the six FM channels by configuring four operators and their routing.

## Overview

The YM2612 FM chip generates sound by combining four sine-wave oscillators called operators. Each operator can either produce output directly (as a **carrier**) or modulate the frequency of another operator (as a **modulator**). The FM voice editor gives you full control over how these four operators are routed and configured.

The editor opens as a modal dialog that works on a copy of the selected voice. Adjust the algorithm, feedback, and all four operator parameters using sliders and combo boxes. Click **OK** to save your changes back to the voice bank, or **Cancel** to discard them. Nothing changes in your song until you confirm with **OK**.

## Opening the Editor

Open the editor from the **Instrument Panel** on the right side of the song tab:

- Click **+** in the **Voice Bank** section to create a new voice and open it in the editor immediately.
- Select an existing voice in the list and click **Edit** to modify it.
- Double-click any voice in the list to open it directly.

When you click **+**, a blank voice is created with all parameters at zero. The editor opens so you can configure it before it enters the voice bank. If you click **Cancel**, the voice is not added.

When you edit an existing voice, the editor works on a copy. The original voice in the bank is untouched until you press **OK**.

### Voice Name

The **Name** field at the top left of the editor lets you give the voice a descriptive label. This name appears in the **Voice Bank** list in the instrument panel and is saved with the project file. Choose something meaningful -- "Bright Lead", "Slap Bass", "Soft Pad" -- so you can identify voices quickly when assigning them to channels.

### Editor Layout

The dialog is organized top-to-bottom:

1. **Top row** -- **Name** text field, **Algorithm** combo box, **Feedback** combo box.
2. **Algorithm diagram** -- a canvas showing the current operator topology.
3. **Button bar** -- **Copy**, **Paste**, **Init**, and **Preview** buttons.
4. **Operator columns** -- four side-by-side columns (Op 1 through Op 4), each containing parameter sliders and an **AM** checkbox.

The operator columns scroll horizontally if the dialog is too narrow to show all four at once.

## Algorithms

The **Algorithm** combo box (`0`--`7`) selects how the four operators are wired together. This is the most important setting in the voice -- it determines the fundamental character of the sound.

Operators acting as **carriers** produce the final audio output. Their TL parameter controls output volume. Operators acting as **modulators** feed into other operators instead of producing direct output. Their TL parameter controls how strongly they affect the sound.

### Algorithm Table

| Algorithm | Carriers | Modulators | Topology |
|-----------|----------|------------|----------|
| `0` | Op 4 | Op 1, Op 2, Op 3 | Op 1 -> Op 2 -> Op 3 -> Op 4 -> OUT |
| `1` | Op 4 | Op 1, Op 2, Op 3 | (Op 1 + Op 2) -> Op 3 -> Op 4 -> OUT |
| `2` | Op 4 | Op 1, Op 2, Op 3 | Op 1 -> Op 2 -> Op 4, Op 3 -> Op 4 -> OUT |
| `3` | Op 4 | Op 1, Op 2, Op 3 | Op 1 -> Op 2 -> Op 4, Op 3 -> Op 4 -> OUT |
| `4` | Op 2, Op 4 | Op 1, Op 3 | Op 1 -> Op 2 -> OUT, Op 3 -> Op 4 -> OUT |
| `5` | Op 2, Op 3, Op 4 | Op 1 | Op 1 -> Op 2, Op 1 -> Op 3, Op 1 -> Op 4, all -> OUT |
| `6` | Op 2, Op 3, Op 4 | Op 1 | Op 1 -> Op 2 -> OUT, Op 3 -> OUT, Op 4 -> OUT |
| `7` | All | None | Op 1 -> OUT, Op 2 -> OUT, Op 3 -> OUT, Op 4 -> OUT |

### Algorithm Descriptions

**Algorithm 0** -- Four operators in series. Each operator modulates the next in a long chain, producing thick, harmonically rich tones. Excellent for electric guitar, distorted leads, and aggressive bass. This is the most "FM-sounding" algorithm because every operator contributes to modulation depth. With three modulators shaping a single carrier, you have maximum control over harmonic complexity.

**Algorithm 1** -- Parallel input to a serial chain. Operators 1 and 2 both feed into operator 3, which feeds operator 4. The parallel modulator inputs give the attack a punchy, layered quality. Good for brass, punchy bass, and synth leads. Adjust the TL balance between Op 1 and Op 2 to blend two different modulation characters into one sound.

**Algorithm 2** -- Mixed routing. Operators 1 and 2 form a serial pair feeding into operator 4, while operator 3 independently also feeds operator 4. This produces bell-like, metallic tones with an inharmonic shimmer. Set Op 3 to a different MUL from the Op 1/2 chain to create clangorous, inharmonic partials. Useful for bells, marimbas, and metallic sound effects.

**Algorithm 3** -- Alternative mixed routing. Similar structure to algorithm 2 but with different internal modulation paths, giving a subtly different modulation character. The distinction is in how the two modulation sources interact at Op 4. Try this when algorithm 2 is close but not quite right.

**Algorithm 4** -- Two independent pairs. Operator 1 modulates operator 2, and operator 3 modulates operator 4. The two pairs output independently and mix together. This creates organ-like, two-layer sounds where each pair contributes a distinct timbre. Set different MUL values on each pair to build rich two-part tones. Great for organs, layered pads, and dual-tone patches.

**Algorithm 5** -- One modulator feeds three carriers. Operator 1 modulates all three remaining operators, each of which outputs independently. This produces rich pads, choir-like sounds, and ensemble textures where a single modulation source colors three output voices simultaneously. Set different MUL values on the three carriers to spread them across the harmonic series.

**Algorithm 6** -- One modulator pair plus two independent carriers. Operator 1 modulates operator 2, while operators 3 and 4 output on their own with no modulation. This combines one FM timbre (the Op 1/2 pair) with two pure sine (or feedback-modified) tones for complex, layered textures. The unmodulated carriers add a stable tonal foundation under the FM component.

**Algorithm 7** -- All four operators are independent carriers with no FM modulation between them. This is pure additive synthesis -- each operator produces its own sine tone at its own frequency multiple. Use it for organ-like pure tones, stacked harmonics, and building custom waveforms by hand. Control the volume of each harmonic independently with its TL slider.

Changing the algorithm immediately updates the diagram and the operator column borders to reflect the new carrier/modulator assignments.

### Choosing an Algorithm

As a general guide:

- For **lead and solo instruments**, start with algorithm `0` or `1`. The full modulation chain gives maximum timbral control.
- For **bass sounds**, try algorithm `0` (distorted, aggressive) or `1` (punchy, rounded).
- For **bells and metallic percussion**, try algorithm `2` or `3`. The mixed routing naturally produces inharmonic partials.
- For **organs and pads**, try algorithm `4` (two independent layers) or `5` (one modulation source, three output tones).
- For **additive / soft tones**, use algorithm `7`. Build the harmonic spectrum manually using MUL and TL on each carrier.

You can change the algorithm at any time while editing. The operator parameters are preserved -- only the routing and carrier/modulator roles change.

## Algorithm Diagram

Below the top row, a canvas displays the current algorithm topology in real time. Each operator is drawn as a numbered rounded rectangle. Lines between operators show the modulation routing, and carrier outputs are drawn as arrows pointing to the right.

The diagram uses color to distinguish operator roles:

- **Carriers** appear in cyan with a cyan border and a cyan output arrow.
- **Modulators** appear in gray with a gray border and no output arrow.

The diagram redraws instantly when you change the **Algorithm** combo box, so you always have a visual reference of the current routing. Use it to verify which operators are carriers before adjusting TL values -- raising TL on a carrier mutes output, while raising TL on a modulator reduces modulation depth.

The operator columns below the diagram also reflect carrier/modulator status. Carrier columns are outlined in cyan; modulator columns are outlined in gray. Both the diagram and the column borders update together when you switch algorithms.

## Operator Parameters

Each of the four operators has its own column of sliders. The column header shows the operator number (**Op 1** through **Op 4**). Carrier columns have a cyan border and cyan header text; modulator columns have a gray border and gray header text.

Every slider snaps to integer values. The current numeric value appears to the right of each slider. Changes apply to the working copy immediately -- you hear the effect the next time you press **Preview**.

### Parameter Reference

| Parameter | Range | What It Does |
|-----------|-------|--------------|
| **MUL** | `0`--`15` | Frequency multiplier. `0` = half the base frequency. `1` = the fundamental (same as the note you play). `2` = one octave up. `3` = an octave and a fifth up. Higher values produce harmonics at integer multiples of the base pitch. |
| **DT** | `0`--`7` | Detune. Slightly shifts the operator's pitch away from the exact frequency multiple. Values `1`--`3` detune upward by increasing amounts. Values `5`--`7` detune downward. `0` and `4` apply no detune. Use small amounts for chorus and thickness. |
| **TL** | `0`--`127` | Total level (volume). `0` = loudest, `127` = silent. On carriers, this controls the output volume heard in the mix. On modulators, this controls modulation depth -- how strongly this operator shapes the timbre of the operators it feeds. |
| **AR** | `0`--`31` | Attack rate. How fast the volume rises when a note begins. Higher values produce a faster attack. `31` = instant (no fade-in). `0` = extremely slow attack. |
| **D1R** | `0`--`31` | First decay rate. How fast the volume drops from the attack peak toward the first decay level. Higher values produce a faster drop. `0` = no decay (holds at peak). |
| **D2R** | `0`--`31` | Second decay rate. The rate of continued volume decay during the sustain phase, after the first decay reaches its target level. `0` = the sound sustains indefinitely at the D1L level. |
| **D1L** | `0`--`15` | First decay level. The volume target where the first decay phase ends and the second decay phase begins. `0` = loudest (decay target is near peak volume). `15` = quietest (decay target is near silence). |
| **RR** | `0`--`15` | Release rate. How fast the sound fades to silence after a key release. Higher values produce a faster fade-out. `15` = near-instant cutoff. `0` = very long release tail. |
| **RS** | `0`--`3` | Rate scaling. Makes higher notes attack and decay faster than lower notes, simulating the natural behavior of acoustic instruments. `0` = no scaling (all notes behave the same). `3` = maximum scaling. |
| **AM** | on/off | Amplitude modulation enable. When checked, the LFO tremolo effect applies to this operator. This is a checkbox below the sliders rather than a slider. |

### Envelope Shape

The AR, D1R, D1L, D2R, and RR parameters together define the volume envelope of each operator. The envelope follows this path:

1. **Attack** -- Volume rises from zero to peak at the AR rate.
2. **First decay** -- Volume falls from peak toward the D1L level at the D1R rate.
3. **Second decay** -- Volume continues to fall from D1L toward silence at the D2R rate. If D2R is `0`, the sound sustains at D1L indefinitely.
4. **Release** -- When the key is released, volume falls to silence at the RR rate, regardless of which phase the envelope was in.

### Common Envelope Recipes

**Sustaining tone** (organ, pad, held lead): Set AR to `31`, D1R to `0`, D1L to `0`, D2R to `0`, RR to `8`. The sound attacks instantly, sustains at full volume for as long as the key is held, and fades out at a moderate rate on release.

**Plucky / percussive** (bass pluck, marimba, staccato): Set AR to `31`, D1R to `20`--`28`, D1L to `10`--`15`, D2R to `5`--`10`, RR to `12`. The sound peaks instantly and decays quickly, creating a sharp transient.

**Slow attack** (strings, swells): Set AR to `8`--`15`, D1R to `0`, D1L to `0`, D2R to `0`, RR to `6`. The sound fades in gradually and sustains until release.

**Punchy with sustain** (brass, synth lead): Set AR to `31`, D1R to `15`--`20`, D1L to `3`--`6`, D2R to `0`, RR to `8`. The sound hits hard, decays to a moderate sustain level, and holds there until release.

Remember that each operator has its own independent envelope. On modulators, the envelope shapes the modulation intensity over time rather than the volume. A modulator with a fast D1R creates a bright attack that mellows quickly -- a classic FM technique for realistic instrument timbres.

### Carriers vs. Modulators

The distinction between carriers and modulators is the single most important concept in FM voice editing. The algorithm determines which operators are which, but understanding the difference affects how you use every parameter:

- **Carrier parameters** directly affect the audible output. TL controls volume. The envelope (AR/D1R/D1L/D2R/RR) shapes the volume over time. MUL sets which harmonic of the note the carrier outputs.

- **Modulator parameters** indirectly affect the timbre. TL controls modulation depth (how much the operator changes the sound). The envelope shapes how the modulation evolves over time. MUL determines which harmonic ratio the modulation operates at, controlling the character of the resulting harmonics.

A common beginner mistake is setting all operators to the same values. Instead, think of carriers as "what you hear" and modulators as "how it sounds." Shape carriers for volume and envelope. Shape modulators for timbral complexity.

## Feedback

The **Feedback** combo box (`0`--`7`) in the top row controls self-feedback on **operator 1 only**. Operator 1's output is routed back into its own input, scaled by the feedback amount. This turns the pure sine wave of operator 1 into a richer waveform without needing another operator to modulate it.

| Feedback | Effect |
|----------|--------|
| `0` | No feedback. Operator 1 produces a pure sine wave. |
| `1`--`2` | Subtle warmth. Adds slight harmonic content, similar to a soft sawtooth. |
| `3`--`4` | Moderate harmonics. Noticeable buzz and overtone presence. |
| `5`--`6` | Strong distortion. Aggressive, buzzy character useful for leads and bass. |
| `7` | Maximum feedback. Harsh, almost noise-like output from operator 1. |

Feedback is independent of the algorithm setting. It always applies to operator 1 regardless of whether operator 1 is a carrier or a modulator in the current algorithm.

When operator 1 is a modulator (algorithms 0--6), feedback changes the shape of the modulation waveform, which indirectly affects the timbre of the carriers it feeds. This is an efficient way to add harmonic richness without spending another operator on modulation.

When operator 1 is a carrier (algorithm 7), feedback directly changes the audible waveform of that output. At feedback `5`--`6`, operator 1 approximates a sawtooth wave. At `7`, it approaches noise.

A good starting approach: set feedback to `0` while designing the voice, then increase it at the end to add bite or grit as a finishing touch.

## Voice Operations

The button bar below the algorithm diagram provides four operations:

| Button | Action |
|--------|--------|
| **Copy** | Copy all current voice parameters (algorithm, feedback, and all operator data) to an internal clipboard. |
| **Paste** | Replace all parameters in the current voice with the clipboard contents. The voice name is preserved. The diagram and operator borders update immediately to reflect the pasted algorithm. |
| **Init** | Reset the voice to a minimal starting patch. Sets algorithm to `0`, feedback to `0`. All modulators get TL `127` (silent), AR `31`, and RR `15`. The carrier (Op 4) gets MUL `1`, TL `0` (full volume), AR `31`, and RR `15`. All other parameters go to `0`. |
| **Preview** | Play a short test note using the current voice. Sends middle C through FM channel 1 for 500 milliseconds. Stops any active song playback first to free the channel. |

### Clipboard Behavior

The clipboard is shared across all FM voice editor instances within the current session. You can:

1. Open voice A and click **Copy**.
2. Close the dialog with **Cancel** (or **OK**).
3. Open voice B and click **Paste**.

The pasted data replaces all parameters but keeps voice B's name.

### Preview Behavior

**Preview** plays through the synth engine's FM channel 1, panned center. It keys on all four operators for 500 ms, then keys off automatically. If the playback engine is not available (for example, if audio output failed to initialize), the **Preview** button does nothing.

You can click **Preview** repeatedly as you adjust sliders. Each press stops any previous preview and plays a fresh note with the current settings.

## Tips

- Start from **Init** and change one parameter at a time. This is the fastest way to understand what each parameter does and to build voices methodically rather than tweaking randomly.

- On carriers, **TL** controls the output volume directly. Set it to `0` for full volume, or raise it to mix that carrier quieter relative to the other carriers in multi-carrier algorithms (4, 5, 6, 7).

- On modulators, **TL** controls how much that operator affects the sound. Start at `127` (silent, no modulation) and decrease gradually to hear the modulation appear. Even small decreases from `127` can produce dramatic timbral changes -- move the slider slowly.

- Use **Preview** after each change to hear the result immediately. The preview always reflects the current state of every slider, so you get instant feedback without leaving the editor.

- When building a voice from scratch, get the carrier envelope right first (AR, D1R, D1L, D2R, RR on Op 4 for algorithms 0--3). Once the volume shape sounds correct, start bringing in modulation by lowering modulator TL values.

- Use **MUL** on modulators to control the harmonic character of the modulation. MUL `1` produces fundamental-frequency modulation (warm, smooth). MUL `2` or higher adds upper harmonics (brighter, more metallic). Non-integer ratios (odd MUL values like `3`, `5`, `7`) tend to produce more bell-like, inharmonic timbres.

- Compare your voice to known patches by using **Copy** and **Paste**. Import a voice bank from a Sonic ROM (via **File > Import Voice Bank...**), copy an existing voice, then paste it into your working voice to use as a starting point.

- The **DT** parameter is subtle but important for realism. Apply slightly different DT values to two operators at the same MUL to create a natural detuned chorus effect, similar to two oscillators slightly out of tune.

- For multi-carrier algorithms (4--7), balance the carrier TL values against each other. If one carrier is much louder than the others, it will dominate the mix. Use **Preview** and adjust TL values to find a balanced blend.

- Give every voice a descriptive name. When you have 20 or more voices in the bank, names like "Voice 12" are useless. "Bright Brass", "Soft Pad", or "Kick Drum" will save you time during composition.

- If a voice sounds good but is too quiet or too loud in context, adjust the carrier TL values rather than changing the pattern volume commands. This keeps the voice consistent across the song.

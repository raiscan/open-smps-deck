# Phase 6: Instrument Editors — Design

## Goal

Add FM voice editor, PSG envelope editor, and instrument panel to OpenSMPSDeck, enabling visual creation and editing of SMPS instruments with real-time audio preview.

## Components

### 1. FmVoiceEditor (Dialog<FmVoice>)

Modal JavaFX dialog editing a working copy of 25-byte SMPS FM voice data.

**Layout:**
- Top: Name TextField, Algorithm ComboBox (0-7), Feedback ComboBox (0-7)
- Middle-left: Algorithm diagram Canvas (8 YM2612 topologies, carrier highlighting)
- Middle-right: 4 operator columns with sliders for MUL, DT, TL, AR, D1R, D2R, D1L, RR, RS + AM checkbox
- Bottom: Preview button (direct YM2612 register writes, no song compilation)

**SMPS operator byte packing:**
- byte[0]: DT (bits 4-6) | MUL (bits 0-3)
- byte[1]: TL (0-127)
- byte[2]: RS (bits 6-7) | AR (bits 0-4)
- byte[3]: AM (bit 7) | D1R (bits 0-4)
- byte[4]: D1L (bits 4-7) | RR (bits 0-3)

Operators stored in SMPS order (1,3,2,4). FmVoice helper methods handle bit-packing so the editor doesn't duplicate it.

**Carrier highlighting:** Algorithm determines which operators are carriers (output to DAC). Carrier TL controls output volume; modulator TL controls modulation depth. Carrier columns get a visually distinct border/background.

**Preview:** Direct YM2612 register writes via SmpsDriver — key-on FM channel 1, apply voice data, key-off after ~1 second.

### 2. PsgEnvelopeEditor (Dialog<PsgEnvelope>)

Modal dialog editing a working copy of PSG volume envelope data.

**Layout:**
- Top: Name TextField
- Center: Canvas bar graph (click/drag to set volume 0-7 per step, 0=tallest=loudest)
- Bottom: +Step/-Step buttons, step count label, Preview button

**Data flow:** Editor maintains mutable int[] working copy. On OK, converts back to byte[] + 0x80 terminator.

**Preview:** Direct PSG register writes — test tone with envelope applied.

### 3. InstrumentPanel (VBox)

Right-side docked panel replacing MainWindow's placeholder.

**Layout:**
- Voice Bank section: Label header, ListView<FmVoice>, [+] [Edit] [Delete] buttons
- PSG Envelopes section: same pattern with ListView<PsgEnvelope>
- Double-click or Edit opens corresponding editor dialog
- Selected item sets "current instrument" index for TrackerGrid note entry

**TrackerGrid integration:** Current instrument index from InstrumentPanel encodes as SET_VOICE (0xEF) for FM channels or PSG_INSTRUMENT (0xF5) for PSG channels when entering notes.

## Testing

- FmVoice bit-field accessor tests (new helper methods)
- PsgEnvelope step add/remove tests
- InstrumentPanel selection state tests (headless, no JavaFX needed)
- Editors tested manually (visual dialogs)

## Files

- Create: `FmVoiceEditor.java`, `PsgEnvelopeEditor.java`, `InstrumentPanel.java`
- Modify: `FmVoice.java` (add field accessors), `PsgEnvelope.java` (add/remove steps), `MainWindow.java` (replace placeholder), `TrackerGrid.java` (instrument selection wiring)

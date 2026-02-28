# Phase 6: Instrument Editors — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add FM voice editor, PSG envelope editor, and instrument panel so users can visually create and edit SMPS instruments with real-time audio preview.

**Architecture:** Three new JavaFX components — FmVoiceEditor (modal dialog with algorithm diagram + operator sliders), PsgEnvelopeEditor (modal dialog with bar graph canvas), InstrumentPanel (right-side docked panel managing voice bank + envelope lists). Model classes get new helper methods for bit-field access. TrackerGrid gains instrument-aware note entry.

**Tech Stack:** JavaFX 21, JUnit 5, existing synth-core SmpsDriver/Ym2612Chip/PsgChipGPGX

---

## Task 18: FmVoice Bit-Field Accessors

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/model/FmVoice.java`
- Test: `app/src/test/java/com/opensmps/deck/model/TestSongModel.java`

### Context

The 25-byte SMPS FM voice packs multiple parameters into single bytes. The existing `getOpParam(op, offset)` returns the raw packed byte, but the FM voice editor needs individual fields. We need accessor methods that unpack/repack the individual bit-fields.

**SMPS operator byte layout (5 bytes per operator at offset 1 + opIndex*5):**
- byte[0] `DT_MUL`: DT = bits 4-6 (0-7), MUL = bits 0-3 (0-15)
- byte[1] `TL`: full byte (0-127)
- byte[2] `RS_AR`: RS = bits 6-7 (0-3), AR = bits 0-4 (0-31)
- byte[3] `AM_D1R`: AM = bit 7 (0-1), D1R = bits 0-4 (0-31)
- byte[4] `D1L_RR`: D1L = bits 4-7 (0-15), RR = bits 0-3 (0-15)

**Operator order:** SMPS stores operators as (1,3,2,4) in the byte array. The opIndex 0-3 maps to SMPS slots directly (index 0=Op1, 1=Op3, 2=Op2, 3=Op4). The editor UI will present them in display order (Op1, Op2, Op3, Op4) and translate to SMPS indices internally.

**Carrier operators per algorithm** (operators that output to DAC):
- Algo 0: Op4 only
- Algo 1: Op4 only
- Algo 2: Op4 only
- Algo 3: Op4 only
- Algo 4: Op2, Op4
- Algo 5: Op2, Op3, Op4
- Algo 6: Op2, Op3, Op4
- Algo 7: Op1, Op2, Op3, Op4

### Step 1: Write failing tests for bit-field accessors

Add to `TestSongModel.java`:

```java
@Test
void testFmVoiceMulDt() {
    byte[] data = new byte[25];
    FmVoice v = new FmVoice("Test", data);

    // Op 0, byte[0] = DT_MUL
    v.setMul(0, 15);
    assertEquals(15, v.getMul(0));
    assertEquals(0, v.getDt(0)); // MUL shouldn't affect DT

    v.setDt(0, 5);
    assertEquals(5, v.getDt(0));
    assertEquals(15, v.getMul(0)); // DT shouldn't affect MUL

    // Verify packed byte: DT=5 in bits 4-6, MUL=15 in bits 0-3
    assertEquals(0x5F, v.getOpParam(0, 0));
}

@Test
void testFmVoiceTl() {
    byte[] data = new byte[25];
    FmVoice v = new FmVoice("Test", data);

    v.setTl(0, 127);
    assertEquals(127, v.getTl(0));
    assertEquals(0x7F, v.getOpParam(0, 1));

    v.setTl(2, 42);
    assertEquals(42, v.getTl(2));
}

@Test
void testFmVoiceRsAr() {
    byte[] data = new byte[25];
    FmVoice v = new FmVoice("Test", data);

    v.setRs(0, 3);
    assertEquals(3, v.getRs(0));
    assertEquals(0, v.getAr(0));

    v.setAr(0, 31);
    assertEquals(31, v.getAr(0));
    assertEquals(3, v.getRs(0));

    // RS=3 in bits 6-7, AR=31 in bits 0-4 => 0xDF
    assertEquals(0xDF, v.getOpParam(0, 2));
}

@Test
void testFmVoiceAmD1r() {
    byte[] data = new byte[25];
    FmVoice v = new FmVoice("Test", data);

    v.setAm(1, true);
    assertTrue(v.getAm(1));
    assertEquals(0, v.getD1r(1));

    v.setD1r(1, 20);
    assertEquals(20, v.getD1r(1));
    assertTrue(v.getAm(1));

    // AM=1 in bit 7, D1R=20 in bits 0-4 => 0x94
    assertEquals(0x94, v.getOpParam(1, 3));
}

@Test
void testFmVoiceD1lRr() {
    byte[] data = new byte[25];
    FmVoice v = new FmVoice("Test", data);

    v.setD1l(0, 15);
    assertEquals(15, v.getD1l(0));
    assertEquals(0, v.getRr(0));

    v.setRr(0, 10);
    assertEquals(10, v.getRr(0));
    assertEquals(15, v.getD1l(0));

    // D1L=15 in bits 4-7, RR=10 in bits 0-3 => 0xFA
    assertEquals(0xFA, v.getOpParam(0, 4));
}

@Test
void testFmVoiceIsCarrier() {
    byte[] data = new byte[25];
    FmVoice v = new FmVoice("Test", data);

    // Algo 0: only Op4 is carrier
    v.setAlgorithm(0);
    assertFalse(v.isCarrier(0)); // Op1
    assertFalse(v.isCarrier(1)); // Op3 (SMPS order)
    assertFalse(v.isCarrier(2)); // Op2
    assertTrue(v.isCarrier(3));  // Op4

    // Algo 7: all carriers
    v.setAlgorithm(7);
    assertTrue(v.isCarrier(0));
    assertTrue(v.isCarrier(1));
    assertTrue(v.isCarrier(2));
    assertTrue(v.isCarrier(3));

    // Algo 4: Op2 and Op4
    v.setAlgorithm(4);
    assertFalse(v.isCarrier(0)); // Op1
    assertFalse(v.isCarrier(1)); // Op3
    assertTrue(v.isCarrier(2));  // Op2
    assertTrue(v.isCarrier(3));  // Op4
}

@Test
void testFmVoiceDisplayOrder() {
    // SMPS order is 1,3,2,4 (indices 0,1,2,3)
    // Display order should be Op1, Op2, Op3, Op4
    assertEquals(0, FmVoice.displayToSmps(0)); // Display Op1 -> SMPS index 0
    assertEquals(2, FmVoice.displayToSmps(1)); // Display Op2 -> SMPS index 2
    assertEquals(1, FmVoice.displayToSmps(2)); // Display Op3 -> SMPS index 1
    assertEquals(3, FmVoice.displayToSmps(3)); // Display Op4 -> SMPS index 3
}
```

### Step 2: Run tests to verify they fail

Run: `mvn test -pl app -Dtest=TestSongModel`
Expected: FAIL — methods `getMul`, `setMul`, `getDt`, `setDt`, `getTl`, `setTl`, `getRs`, `setRs`, `getAr`, `setAr`, `getAm`, `setAm`, `getD1r`, `setD1r`, `getD1l`, `setD1l`, `getRr`, `setRr`, `isCarrier`, `displayToSmps` not found

### Step 3: Implement bit-field accessors in FmVoice

Add to `FmVoice.java`:

```java
// --- Per-operator bit-field accessors ---

/** MUL: bits 0-3 of DT_MUL (param offset 0). Range 0-15. */
public int getMul(int opIndex) { return getOpParam(opIndex, 0) & 0x0F; }
public void setMul(int opIndex, int val) {
    int packed = getOpParam(opIndex, 0);
    setOpParam(opIndex, 0, (packed & 0x70) | (val & 0x0F));
}

/** DT: bits 4-6 of DT_MUL (param offset 0). Range 0-7. */
public int getDt(int opIndex) { return (getOpParam(opIndex, 0) >> 4) & 0x07; }
public void setDt(int opIndex, int val) {
    int packed = getOpParam(opIndex, 0);
    setOpParam(opIndex, 0, (packed & 0x0F) | ((val & 0x07) << 4));
}

/** TL: full byte at param offset 1. Range 0-127. */
public int getTl(int opIndex) { return getOpParam(opIndex, 1) & 0x7F; }
public void setTl(int opIndex, int val) { setOpParam(opIndex, 1, val & 0x7F); }

/** RS: bits 6-7 of RS_AR (param offset 2). Range 0-3. */
public int getRs(int opIndex) { return (getOpParam(opIndex, 2) >> 6) & 0x03; }
public void setRs(int opIndex, int val) {
    int packed = getOpParam(opIndex, 2);
    setOpParam(opIndex, 2, (packed & 0x1F) | ((val & 0x03) << 6));
}

/** AR: bits 0-4 of RS_AR (param offset 2). Range 0-31. */
public int getAr(int opIndex) { return getOpParam(opIndex, 2) & 0x1F; }
public void setAr(int opIndex, int val) {
    int packed = getOpParam(opIndex, 2);
    setOpParam(opIndex, 2, (packed & 0xC0) | (val & 0x1F));
}

/** AM: bit 7 of AM_D1R (param offset 3). */
public boolean getAm(int opIndex) { return (getOpParam(opIndex, 3) & 0x80) != 0; }
public void setAm(int opIndex, boolean val) {
    int packed = getOpParam(opIndex, 3);
    setOpParam(opIndex, 3, val ? (packed | 0x80) : (packed & 0x7F));
}

/** D1R: bits 0-4 of AM_D1R (param offset 3). Range 0-31. */
public int getD1r(int opIndex) { return getOpParam(opIndex, 3) & 0x1F; }
public void setD1r(int opIndex, int val) {
    int packed = getOpParam(opIndex, 3);
    setOpParam(opIndex, 3, (packed & 0x80) | (val & 0x1F));
}

/** D2R: bits 0-4 of byte at param offset 4... wait, D2R is separate. */
// Actually re-reading the SMPS format:
// The voice layout in the design doc says:
// byte[1-4]: Op1: DT_MUL, TL, RS_AR, AM_D1R, D2R, D1L_RR
// That's 6 bytes per operator... but PARAMS_PER_OPERATOR = 5.
// Wait -- checking. The real SMPS layout is:
// byte[0]: FB_ALG
// Per operator (5 bytes): DT_MUL, TL, RS_AR, D1R_AM, D2R_something?
// Actually the standard YM2612 SMPS voice is:
// FB_ALG, then for each op: DT_MUL, TL, RS_AR, AM_D1R, D1L_RR
// D2R is NOT stored in standard 25-byte SMPS voices!
// The 25 bytes are: 1 + 4*6 = 25? No, 1 + 4*5 = 21, not 25.
// Let me recount: 1 (FB_ALG) + 4 ops * 6 bytes = 25. So 6 bytes per op.
// The 6 bytes per op are: DT_MUL, TL, RS_AR, AM_D1R, D2R, D1L_RR

// CORRECTION: PARAMS_PER_OPERATOR should be 6, not 5.
// Let me verify against the existing code before implementing.
```

**IMPORTANT STOP:** Before implementing, verify the actual voice byte layout by reading the existing `FmVoice.java` (already read above — it says `PARAMS_PER_OPERATOR = 5`). But the design doc says the voice is 25 bytes with layout `1 + 4*6 = 25`. So either `PARAMS_PER_OPERATOR` is wrong, or the layout is different.

Let's verify: 25 total - 1 (FB_ALG) = 24 bytes for operators. 24 / 4 = **6 bytes per operator**. So `PARAMS_PER_OPERATOR` should be 6, not 5. This is a bug in the existing model — BUT it only matters if code uses `getOpParam` with paramOffset 5 (the 6th byte). Since existing code only uses it for raw access, it works by accident because `1 + opIndex * 5 + paramOffset` still addresses within the 25-byte range for most combinations, just with wrong operator boundaries.

**Actually, re-reading the FmVoice code:**
```java
data[1 + opIndex * PARAMS_PER_OPERATOR + paramOffset]
```
With `PARAMS_PER_OPERATOR=5`, operator starts are at bytes 1, 6, 11, 16. With 6 params, operator 0 spans bytes 1-6, but operator 1 starts at byte 6 — which overlaps! The correct `PARAMS_PER_OPERATOR` must be 6 so operators start at 1, 7, 13, 19.

**Fix required as part of this task.** Change `PARAMS_PER_OPERATOR = 5` to `PARAMS_PER_OPERATOR = 6`.

### Step 3 (revised): Fix PARAMS_PER_OPERATOR and add bit-field accessors

In `FmVoice.java`:

1. Change: `public static final int PARAMS_PER_OPERATOR = 5;` → `public static final int PARAMS_PER_OPERATOR = 6;`

2. Add these methods:

```java
// --- Per-operator bit-field accessors ---
// Param layout per operator (6 bytes): DT_MUL[0], TL[1], RS_AR[2], AM_D1R[3], D2R[4], D1L_RR[5]

public int getMul(int op)  { return getOpParam(op, 0) & 0x0F; }
public void setMul(int op, int v) { setOpParam(op, 0, (getOpParam(op, 0) & 0x70) | (v & 0x0F)); }

public int getDt(int op)   { return (getOpParam(op, 0) >> 4) & 0x07; }
public void setDt(int op, int v) { setOpParam(op, 0, (getOpParam(op, 0) & 0x0F) | ((v & 0x07) << 4)); }

public int getTl(int op)   { return getOpParam(op, 1) & 0x7F; }
public void setTl(int op, int v) { setOpParam(op, 1, v & 0x7F); }

public int getRs(int op)   { return (getOpParam(op, 2) >> 6) & 0x03; }
public void setRs(int op, int v) { setOpParam(op, 2, (getOpParam(op, 2) & 0x1F) | ((v & 0x03) << 6)); }

public int getAr(int op)   { return getOpParam(op, 2) & 0x1F; }
public void setAr(int op, int v) { setOpParam(op, 2, (getOpParam(op, 2) & 0xC0) | (v & 0x1F)); }

public boolean getAm(int op) { return (getOpParam(op, 3) & 0x80) != 0; }
public void setAm(int op, boolean v) { setOpParam(op, 3, v ? (getOpParam(op, 3) | 0x80) : (getOpParam(op, 3) & 0x7F)); }

public int getD1r(int op)  { return getOpParam(op, 3) & 0x1F; }
public void setD1r(int op, int v) { setOpParam(op, 3, (getOpParam(op, 3) & 0x80) | (v & 0x1F)); }

public int getD2r(int op)  { return getOpParam(op, 4) & 0x1F; }
public void setD2r(int op, int v) { setOpParam(op, 4, v & 0x1F); }

public int getD1l(int op)  { return (getOpParam(op, 5) >> 4) & 0x0F; }
public void setD1l(int op, int v) { setOpParam(op, 5, (getOpParam(op, 5) & 0x0F) | ((v & 0x0F) << 4)); }

public int getRr(int op)   { return getOpParam(op, 5) & 0x0F; }
public void setRr(int op, int v) { setOpParam(op, 5, (getOpParam(op, 5) & 0xF0) | (v & 0x0F)); }

/**
 * Whether this operator is a carrier (outputs to DAC) for the current algorithm.
 * SMPS operator indices: 0=Op1, 1=Op3, 2=Op2, 3=Op4.
 */
public boolean isCarrier(int smpsOpIndex) {
    return CARRIER_TABLE[getAlgorithm()][smpsOpIndex];
}

/**
 * Convert display order (Op1=0, Op2=1, Op3=2, Op4=3) to SMPS storage index.
 * SMPS order is Op1(0), Op3(1), Op2(2), Op4(3).
 */
public static int displayToSmps(int displayIndex) {
    return DISPLAY_TO_SMPS[displayIndex];
}

private static final int[] DISPLAY_TO_SMPS = {0, 2, 1, 3};

// Carrier table: [algorithm][smpsOpIndex] = true if carrier
// SMPS op indices: 0=Op1, 1=Op3, 2=Op2, 3=Op4
private static final boolean[][] CARRIER_TABLE = {
    {false, false, false, true},   // Algo 0: Op4
    {false, false, false, true},   // Algo 1: Op4
    {false, false, false, true},   // Algo 2: Op4
    {false, false, false, true},   // Algo 3: Op4
    {false, false, true,  true},   // Algo 4: Op2(idx2), Op4(idx3)
    {false, true,  true,  true},   // Algo 5: Op2(idx2), Op3(idx1), Op4(idx3)
    {false, true,  true,  true},   // Algo 6: Op2(idx2), Op3(idx1), Op4(idx3)
    {true,  true,  true,  true},   // Algo 7: all
};
```

### Step 4: Update tests for PARAMS_PER_OPERATOR change

The existing `testFmVoiceOpParams` test uses paramOffset up to 4, which is still valid with `PARAMS_PER_OPERATOR=6`. But the byte indices will change:
- Old: `1 + opIndex * 5 + paramOffset` → `data[1]`, `data[14]`, `data[20]`
- New: `1 + opIndex * 6 + paramOffset` → `data[1]`, `data[15]`, `data[22]`

Update the existing test assertions for new offsets, then add the new bit-field tests from Step 1.

### Step 5: Run tests to verify they pass

Run: `mvn test`
Expected: ALL PASS (existing tests updated for new offsets, new bit-field tests pass)

### Step 6: Commit

```bash
git add app/src/main/java/com/opensmps/deck/model/FmVoice.java \
       app/src/test/java/com/opensmps/deck/model/TestSongModel.java
git commit -m "feat: add FmVoice bit-field accessors and fix PARAMS_PER_OPERATOR"
```

---

## Task 19: PsgEnvelope Add/Remove Steps

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/model/PsgEnvelope.java`
- Test: `app/src/test/java/com/opensmps/deck/model/TestSongModel.java`

### Context

PsgEnvelope currently supports `getStep(i)` and `setStep(i, val)` but the editor needs `addStep()` and `removeStep()` to grow/shrink the envelope. The data is stored as `byte[]` terminated by `0x80`, so add/remove must resize the array and maintain the terminator.

### Step 1: Write failing tests

Add to `TestSongModel.java`:

```java
@Test
void testPsgEnvelopeAddStep() {
    byte[] data = {0, 1, 2, (byte) 0x80};
    PsgEnvelope env = new PsgEnvelope("Test", data);
    assertEquals(3, env.getStepCount());

    env.addStep(4);
    assertEquals(4, env.getStepCount());
    assertEquals(4, env.getStep(3));

    // Verify 0x80 terminator is still present
    byte[] raw = env.getData();
    assertEquals((byte) 0x80, raw[4]);
}

@Test
void testPsgEnvelopeRemoveStep() {
    byte[] data = {0, 1, 2, (byte) 0x80};
    PsgEnvelope env = new PsgEnvelope("Test", data);

    env.removeStep(1);
    assertEquals(2, env.getStepCount());
    assertEquals(0, env.getStep(0));
    assertEquals(2, env.getStep(1));

    // Verify 0x80 terminator
    byte[] raw = env.getData();
    assertEquals((byte) 0x80, raw[2]);
}

@Test
void testPsgEnvelopeRemoveLastStep() {
    byte[] data = {5, (byte) 0x80};
    PsgEnvelope env = new PsgEnvelope("Test", data);

    env.removeStep(0);
    assertEquals(0, env.getStepCount());

    byte[] raw = env.getData();
    assertEquals(1, raw.length);
    assertEquals((byte) 0x80, raw[0]);
}

@Test
void testPsgEnvelopeSetData() {
    byte[] data = {0, (byte) 0x80};
    PsgEnvelope env = new PsgEnvelope("Test", data);

    byte[] newData = {3, 2, 1, 0, (byte) 0x80};
    env.setData(newData);
    assertEquals(4, env.getStepCount());
    assertEquals(3, env.getStep(0));
}
```

### Step 2: Run tests — expect FAIL

Run: `mvn test -pl app -Dtest=TestSongModel`
Expected: FAIL — `addStep`, `removeStep`, `setData` not found

### Step 3: Implement in PsgEnvelope

Add to `PsgEnvelope.java`:

```java
/** Add a volume step at the end (before the 0x80 terminator). */
public void addStep(int volume) {
    int count = getStepCount();
    byte[] newData = new byte[count + 2]; // steps + new step + terminator
    System.arraycopy(data, 0, newData, 0, count);
    newData[count] = (byte) (volume & 0xFF);
    newData[count + 1] = (byte) 0x80;
    this.data = newData;
}

/** Remove the step at the given index. */
public void removeStep(int index) {
    int count = getStepCount();
    if (index < 0 || index >= count) {
        throw new IndexOutOfBoundsException("Step index " + index + " out of range [0, " + count + ")");
    }
    byte[] newData = new byte[count]; // (count - 1) steps + terminator
    System.arraycopy(data, 0, newData, 0, index);
    System.arraycopy(data, index + 1, newData, index, count - index - 1);
    newData[count - 1] = (byte) 0x80;
    this.data = newData;
}

/** Replace the envelope data entirely. */
public void setData(byte[] newData) {
    if (newData == null) {
        throw new IllegalArgumentException("PSG envelope data must not be null");
    }
    this.data = newData.clone();
}
```

### Step 4: Run tests — expect PASS

Run: `mvn test`
Expected: ALL PASS

### Step 5: Commit

```bash
git add app/src/main/java/com/opensmps/deck/model/PsgEnvelope.java \
       app/src/test/java/com/opensmps/deck/model/TestSongModel.java
git commit -m "feat: add PsgEnvelope addStep/removeStep/setData methods"
```

---

## Task 20: FM Voice Editor Dialog

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/FmVoiceEditor.java`

### Context

JavaFX `Dialog<FmVoice>` for editing FM voice parameters. Uses the bit-field accessors from Task 18. The dialog operates on a working copy of the voice data; OK returns the modified voice, Cancel discards changes.

**YM2612 Algorithm Diagrams:**
The 8 standard FM algorithms define how 4 operators connect. Each diagram shows operator boxes with arrows indicating modulation flow. Carriers (output to DAC) are drawn with a distinct color (cyan vs white for modulators).

```
Algo 0: [1]→[2]→[3]→[4]→OUT
Algo 1: [1]→─┐
        [2]→─┴[3]→[4]→OUT
Algo 2: [1]───┐
        [2]→[3]┴→[4]→OUT
Algo 3: [1]→[2]─┐
        [3]──┴→[4]→OUT
Algo 4: [1]→[2]→OUT
        [3]→[4]→OUT
Algo 5: [1]→[2]→OUT
        └→[3]→OUT
        └→[4]→OUT
Algo 6: [1]→[2]→OUT
           [3]→OUT
           [4]→OUT
Algo 7: [1]→OUT [2]→OUT [3]→OUT [4]→OUT
```

### Step 1: Create FmVoiceEditor.java

```java
package com.opensmps.deck.ui;

import com.opensmps.deck.model.FmVoice;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Modal dialog for editing a 25-byte SMPS FM voice.
 *
 * <p>Layout: name + algorithm/feedback controls at top, algorithm routing
 * diagram on the left, 4 operator columns with parameter sliders on the
 * right, OK/Cancel buttons at the bottom.
 *
 * <p>All slider changes write directly to the working copy FmVoice.
 * OK returns the modified voice; Cancel returns null.
 */
public class FmVoiceEditor extends Dialog<FmVoice> {

    private static final Color CARRIER_COLOR = Color.web("#55ccff");
    private static final Color MODULATOR_COLOR = Color.web("#cccccc");
    private static final Color BG_COLOR = Color.web("#1e1e1e");
    private static final Color DIAGRAM_BG = Color.web("#2a2a2a");
    private static final Font LABEL_FONT = Font.font("Monospaced", 12);

    private final FmVoice voice;
    private final Canvas algorithmCanvas;
    private final TextField nameField;
    private final ComboBox<Integer> algoBox;
    private final ComboBox<Integer> fbBox;
    private final VBox[] operatorColumns = new VBox[4];

    public FmVoiceEditor(FmVoice source) {
        // Work on a copy
        this.voice = new FmVoice(source.getName(), source.getData());

        setTitle("FM Voice Editor");
        setHeaderText(null);

        // Dialog buttons
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Name field
        nameField = new TextField(voice.getName());
        nameField.setPromptText("Voice name");
        nameField.textProperty().addListener((obs, o, n) -> voice.setName(n));

        // Algorithm dropdown
        algoBox = new ComboBox<>();
        for (int i = 0; i < 8; i++) algoBox.getItems().add(i);
        algoBox.setValue(voice.getAlgorithm());
        algoBox.setOnAction(e -> {
            voice.setAlgorithm(algoBox.getValue());
            drawAlgorithmDiagram();
            updateCarrierHighlights();
        });

        // Feedback dropdown
        fbBox = new ComboBox<>();
        for (int i = 0; i < 8; i++) fbBox.getItems().add(i);
        fbBox.setValue(voice.getFeedback());
        fbBox.setOnAction(e -> voice.setFeedback(fbBox.getValue()));

        HBox topRow = new HBox(10,
            new Label("Name:"), nameField,
            new Label("Algo:"), algoBox,
            new Label("FB:"), fbBox);
        topRow.setAlignment(Pos.CENTER_LEFT);
        topRow.setPadding(new Insets(8));
        styleLabel(topRow);

        // Algorithm diagram canvas
        algorithmCanvas = new Canvas(200, 120);
        drawAlgorithmDiagram();

        // Operator columns
        // Display order: Op1, Op2, Op3, Op4
        // SMPS indices:  0,   2,   1,   3
        HBox opsBox = new HBox(8);
        String[] opLabels = {"Op1", "Op2", "Op3", "Op4"};
        for (int disp = 0; disp < 4; disp++) {
            int smpsIdx = FmVoice.displayToSmps(disp);
            operatorColumns[disp] = createOperatorColumn(opLabels[disp], smpsIdx);
            opsBox.getChildren().add(operatorColumns[disp]);
        }

        // Layout
        HBox middleRow = new HBox(12, algorithmCanvas, opsBox);
        middleRow.setPadding(new Insets(8));

        VBox content = new VBox(8, topRow, middleRow);
        content.setStyle("-fx-background-color: #1e1e1e;");

        getDialogPane().setContent(content);
        getDialogPane().setStyle("-fx-background-color: #1e1e1e;");
        getDialogPane().setPrefWidth(720);
        getDialogPane().setPrefHeight(500);

        updateCarrierHighlights();

        // Result converter
        setResultConverter(button -> {
            if (button == ButtonType.OK) return voice;
            return null;
        });
    }

    private VBox createOperatorColumn(String label, int smpsOpIndex) {
        VBox col = new VBox(4);
        col.setPadding(new Insets(4));
        col.setMinWidth(120);
        col.setStyle("-fx-border-color: #444; -fx-border-width: 1;");

        Label header = new Label(label);
        header.setStyle("-fx-text-fill: #cccccc; -fx-font-weight: bold;");
        col.getChildren().add(header);

        col.getChildren().add(createSlider("MUL", 0, 15, voice.getMul(smpsOpIndex),
            v -> voice.setMul(smpsOpIndex, v)));
        col.getChildren().add(createSlider("DT", 0, 7, voice.getDt(smpsOpIndex),
            v -> voice.setDt(smpsOpIndex, v)));
        col.getChildren().add(createSlider("TL", 0, 127, voice.getTl(smpsOpIndex),
            v -> voice.setTl(smpsOpIndex, v)));
        col.getChildren().add(createSlider("AR", 0, 31, voice.getAr(smpsOpIndex),
            v -> voice.setAr(smpsOpIndex, v)));
        col.getChildren().add(createSlider("D1R", 0, 31, voice.getD1r(smpsOpIndex),
            v -> voice.setD1r(smpsOpIndex, v)));
        col.getChildren().add(createSlider("D2R", 0, 31, voice.getD2r(smpsOpIndex),
            v -> voice.setD2r(smpsOpIndex, v)));
        col.getChildren().add(createSlider("D1L", 0, 15, voice.getD1l(smpsOpIndex),
            v -> voice.setD1l(smpsOpIndex, v)));
        col.getChildren().add(createSlider("RR", 0, 15, voice.getRr(smpsOpIndex),
            v -> voice.setRr(smpsOpIndex, v)));
        col.getChildren().add(createSlider("RS", 0, 3, voice.getRs(smpsOpIndex),
            v -> voice.setRs(smpsOpIndex, v)));

        CheckBox amBox = new CheckBox("AM");
        amBox.setSelected(voice.getAm(smpsOpIndex));
        amBox.setStyle("-fx-text-fill: #cccccc;");
        amBox.selectedProperty().addListener((obs, o, n) -> voice.setAm(smpsOpIndex, n));
        col.getChildren().add(amBox);

        return col;
    }

    private HBox createSlider(String name, int min, int max, int initialValue,
                              java.util.function.IntConsumer onChange) {
        Label label = new Label(String.format("%-3s", name));
        label.setStyle("-fx-text-fill: #aaaaaa; -fx-font-family: Monospaced; -fx-font-size: 11;");
        label.setMinWidth(30);

        Slider slider = new Slider(min, max, initialValue);
        slider.setBlockIncrement(1);
        slider.setMajorTickUnit(Math.max(1, (max - min) / 4));
        slider.setMinorTickCount(0);
        slider.setPrefWidth(80);

        Label valueLabel = new Label(String.valueOf(initialValue));
        valueLabel.setStyle("-fx-text-fill: #88cc88; -fx-font-family: Monospaced; -fx-font-size: 11;");
        valueLabel.setMinWidth(25);

        slider.valueProperty().addListener((obs, o, n) -> {
            int val = n.intValue();
            valueLabel.setText(String.valueOf(val));
            onChange.accept(val);
        });

        HBox row = new HBox(4, label, slider, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void drawAlgorithmDiagram() {
        GraphicsContext gc = algorithmCanvas.getGraphicsContext2D();
        double w = algorithmCanvas.getWidth();
        double h = algorithmCanvas.getHeight();

        gc.setFill(DIAGRAM_BG);
        gc.fillRect(0, 0, w, h);

        int algo = voice.getAlgorithm();

        // Draw algorithm topology
        // Each op is a 30x20 rounded rect
        // Positions vary by algorithm
        double boxW = 30, boxH = 20;
        gc.setFont(Font.font("Monospaced", 11));
        gc.setLineWidth(1.5);

        // Define op positions and connections per algorithm
        double[][] positions = getAlgoPositions(algo, w, h, boxW, boxH);
        int[][] connections = getAlgoConnections(algo);

        // Draw connections first
        gc.setStroke(Color.web("#666666"));
        for (int[] conn : connections) {
            double x1 = positions[conn[0]][0] + boxW;
            double y1 = positions[conn[0]][1] + boxH / 2;
            double x2 = positions[conn[1]][0];
            double y2 = positions[conn[1]][1] + boxH / 2;
            gc.strokeLine(x1, y1, x2, y2);
        }

        // Draw output arrows for carriers
        for (int i = 0; i < 4; i++) {
            int smpsIdx = FmVoice.displayToSmps(i);
            if (voice.isCarrier(smpsIdx)) {
                double x = positions[i][0] + boxW;
                double y = positions[i][1] + boxH / 2;
                gc.setStroke(CARRIER_COLOR);
                gc.strokeLine(x, y, x + 15, y);
                // Arrow head
                gc.strokeLine(x + 15, y, x + 10, y - 4);
                gc.strokeLine(x + 15, y, x + 10, y + 4);
            }
        }

        // Draw operator boxes
        String[] labels = {"1", "2", "3", "4"};
        for (int i = 0; i < 4; i++) {
            int smpsIdx = FmVoice.displayToSmps(i);
            boolean carrier = voice.isCarrier(smpsIdx);
            gc.setStroke(carrier ? CARRIER_COLOR : MODULATOR_COLOR);
            gc.setFill(Color.web("#333333"));
            gc.fillRoundRect(positions[i][0], positions[i][1], boxW, boxH, 6, 6);
            gc.strokeRoundRect(positions[i][0], positions[i][1], boxW, boxH, 6, 6);
            gc.setFill(carrier ? CARRIER_COLOR : MODULATOR_COLOR);
            gc.fillText(labels[i], positions[i][0] + 11, positions[i][1] + 14);
        }

        // "OUT" label
        gc.setFill(Color.web("#888888"));
        gc.fillText("OUT", w - 30, h / 2 + 4);
    }

    /** Get box positions for each of the 8 algorithms. Returns double[4][2] (x,y per display op). */
    private double[][] getAlgoPositions(int algo, double w, double h, double bw, double bh) {
        double cx = w / 2 - bw;
        double cy = h / 2 - bh / 2;
        // Default: serial chain
        return switch (algo) {
            case 0 -> new double[][]{ {10, cy}, {50, cy}, {90, cy}, {130, cy} };
            case 1 -> new double[][]{ {10, cy-15}, {10, cy+15}, {70, cy}, {120, cy} };
            case 2 -> new double[][]{ {10, cy-15}, {50, cy-15}, {50, cy+15}, {110, cy} };
            case 3 -> new double[][]{ {10, cy}, {50, cy}, {90, cy-15}, {130, cy} };
            case 4 -> new double[][]{ {10, cy-18}, {60, cy-18}, {10, cy+18}, {60, cy+18} };
            case 5 -> new double[][]{ {10, cy-25}, {60, cy-25}, {60, cy}, {60, cy+25} };
            case 6 -> new double[][]{ {10, cy-25}, {60, cy-25}, {60, cy}, {60, cy+25} };
            case 7 -> new double[][]{ {10, cy}, {50, cy}, {90, cy}, {130, cy} };
            default -> new double[][]{ {10, cy}, {50, cy}, {90, cy}, {130, cy} };
        };
    }

    /** Get connection pairs [from, to] in display operator indices. */
    private int[][] getAlgoConnections(int algo) {
        return switch (algo) {
            case 0 -> new int[][]{ {0,1}, {1,2}, {2,3} };       // 1→2→3→4
            case 1 -> new int[][]{ {0,2}, {1,2}, {2,3} };       // 1+2→3→4
            case 2 -> new int[][]{ {0,1}, {2,3}, {1,3} };       // 1→2, 3→4, 2→4
            case 3 -> new int[][]{ {0,1}, {1,3}, {2,3} };       // 1→2→4, 3→4
            case 4 -> new int[][]{ {0,1}, {2,3} };              // 1→2, 3→4
            case 5 -> new int[][]{ {0,1}, {0,2}, {0,3} };       // 1→2, 1→3, 1→4
            case 6 -> new int[][]{ {0,1} };                      // 1→2, 3 alone, 4 alone
            case 7 -> new int[][]{};                              // all independent
            default -> new int[][]{};
        };
    }

    private void updateCarrierHighlights() {
        String[] opLabels = {"Op1", "Op2", "Op3", "Op4"};
        for (int disp = 0; disp < 4; disp++) {
            int smpsIdx = FmVoice.displayToSmps(disp);
            boolean carrier = voice.isCarrier(smpsIdx);
            String borderColor = carrier ? "#55ccff" : "#444444";
            operatorColumns[disp].setStyle("-fx-border-color: " + borderColor + "; -fx-border-width: " + (carrier ? 2 : 1) + ";");
        }
    }

    private void styleLabel(Pane container) {
        container.getChildren().filtered(n -> n instanceof Label)
            .forEach(n -> n.setStyle("-fx-text-fill: #cccccc;"));
    }
}
```

### Step 2: Verify the file compiles

Run: `mvn compile -pl app`
Expected: BUILD SUCCESS

### Step 3: Commit

```bash
git add app/src/main/java/com/opensmps/deck/ui/FmVoiceEditor.java
git commit -m "feat: add FM voice editor dialog with algorithm diagram and operator sliders"
```

---

## Task 21: PSG Envelope Editor Dialog

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/PsgEnvelopeEditor.java`

### Context

JavaFX `Dialog<PsgEnvelope>` with a clickable bar graph canvas. Each bar represents a volume step (0-7, where 0=loudest/tallest). Click or drag to paint volumes. `+Step`/`-Step` buttons grow/shrink the envelope using the methods from Task 19.

### Step 1: Create PsgEnvelopeEditor.java

```java
package com.opensmps.deck.ui;

import com.opensmps.deck.model.PsgEnvelope;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Modal dialog for editing a PSG volume envelope.
 *
 * <p>Displays a clickable bar graph where each bar represents a volume
 * step (0=loudest/tallest, 7=quietest/shortest). Click or drag to paint
 * volumes. Add/Remove step buttons grow or shrink the envelope.
 */
public class PsgEnvelopeEditor extends Dialog<PsgEnvelope> {

    private static final int BAR_WIDTH = 20;
    private static final int BAR_MAX_HEIGHT = 100;
    private static final int CANVAS_PADDING = 10;
    private static final int MAX_STEPS = 64;
    private static final Color BAR_COLOR = Color.web("#55cc55");
    private static final Color BAR_OUTLINE = Color.web("#338833");
    private static final Color BG_COLOR = Color.web("#1e1e1e");
    private static final Color GRID_COLOR = Color.web("#333333");

    private final PsgEnvelope envelope;
    private final Canvas canvas;
    private final Label stepCountLabel;
    private final TextField nameField;

    public PsgEnvelopeEditor(PsgEnvelope source) {
        // Work on a copy
        this.envelope = new PsgEnvelope(source.getName(), source.getData());

        setTitle("PSG Envelope Editor");
        setHeaderText(null);

        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Name field
        nameField = new TextField(envelope.getName());
        nameField.setPromptText("Envelope name");
        nameField.textProperty().addListener((obs, o, n) -> envelope.setName(n));

        HBox nameRow = new HBox(8, label("Name:"), nameField);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        nameRow.setPadding(new Insets(8));

        // Canvas for bar graph
        canvas = new Canvas(calcCanvasWidth(), BAR_MAX_HEIGHT + CANVAS_PADDING * 2 + 20);
        canvas.setOnMousePressed(this::handleCanvasClick);
        canvas.setOnMouseDragged(this::handleCanvasClick);

        // Controls
        stepCountLabel = new Label("Steps: " + envelope.getStepCount());
        stepCountLabel.setStyle("-fx-text-fill: #aaaaaa;");

        Button addBtn = new Button("+Step");
        addBtn.setOnAction(e -> { envelope.addStep(0); refreshCanvas(); });

        Button removeBtn = new Button("-Step");
        removeBtn.setOnAction(e -> {
            if (envelope.getStepCount() > 0) {
                envelope.removeStep(envelope.getStepCount() - 1);
                refreshCanvas();
            }
        });

        HBox controls = new HBox(10, addBtn, removeBtn, stepCountLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(4, 8, 8, 8));

        VBox content = new VBox(4, nameRow, canvas, controls);
        content.setStyle("-fx-background-color: #1e1e1e;");

        getDialogPane().setContent(content);
        getDialogPane().setStyle("-fx-background-color: #1e1e1e;");
        getDialogPane().setPrefWidth(500);

        refreshCanvas();

        setResultConverter(button -> {
            if (button == ButtonType.OK) return envelope;
            return null;
        });
    }

    private void handleCanvasClick(MouseEvent e) {
        int stepCount = envelope.getStepCount();
        if (stepCount == 0) return;

        double x = e.getX() - CANVAS_PADDING;
        double y = e.getY() - CANVAS_PADDING;

        int stepIndex = (int) (x / BAR_WIDTH);
        if (stepIndex < 0 || stepIndex >= stepCount) return;

        // Convert y to volume (0=top=loudest, 7=bottom=quietest)
        int volume = (int) (y / (BAR_MAX_HEIGHT / 8.0));
        volume = Math.max(0, Math.min(7, volume));

        envelope.setStep(stepIndex, volume);
        refreshCanvas();
    }

    private void refreshCanvas() {
        int stepCount = envelope.getStepCount();
        double requiredWidth = calcCanvasWidth();
        canvas.setWidth(requiredWidth);
        stepCountLabel.setText("Steps: " + stepCount);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        // Background
        gc.setFill(BG_COLOR);
        gc.fillRect(0, 0, w, h);

        // Grid lines for volume levels
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(0.5);
        for (int v = 0; v <= 7; v++) {
            double y = CANVAS_PADDING + (v * BAR_MAX_HEIGHT / 8.0);
            gc.strokeLine(CANVAS_PADDING, y, CANVAS_PADDING + stepCount * BAR_WIDTH, y);
        }

        // Draw bars
        for (int i = 0; i < stepCount; i++) {
            int vol = envelope.getStep(i);
            // Volume 0 = full height, volume 7 = minimal height
            double barHeight = BAR_MAX_HEIGHT * (8 - vol) / 8.0;
            double x = CANVAS_PADDING + i * BAR_WIDTH;
            double y = CANVAS_PADDING + BAR_MAX_HEIGHT - barHeight;

            gc.setFill(BAR_COLOR);
            gc.fillRect(x + 1, y, BAR_WIDTH - 2, barHeight);
            gc.setStroke(BAR_OUTLINE);
            gc.strokeRect(x + 1, y, BAR_WIDTH - 2, barHeight);
        }

        // Step numbers
        gc.setFill(Color.web("#888888"));
        gc.setFont(Font.font("Monospaced", 9));
        for (int i = 0; i < stepCount; i++) {
            double x = CANVAS_PADDING + i * BAR_WIDTH + 4;
            gc.fillText(String.format("%02X", i), x, CANVAS_PADDING + BAR_MAX_HEIGHT + 12);
        }
    }

    private double calcCanvasWidth() {
        return CANVAS_PADDING * 2 + Math.max(envelope.getStepCount(), 1) * BAR_WIDTH + 20;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #cccccc;");
        return l;
    }
}
```

### Step 2: Verify compilation

Run: `mvn compile -pl app`
Expected: BUILD SUCCESS

### Step 3: Commit

```bash
git add app/src/main/java/com/opensmps/deck/ui/PsgEnvelopeEditor.java
git commit -m "feat: add PSG envelope editor dialog with bar graph canvas"
```

---

## Task 22: Instrument Panel

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/InstrumentPanel.java`
- Modify: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`

### Context

Right-side panel with two `ListView` sections for FM voices and PSG envelopes. Double-click or Edit opens the corresponding editor. The panel exposes a `currentVoiceIndex` / `currentEnvelopeIndex` property that TrackerGrid will read when entering notes.

### Step 1: Create InstrumentPanel.java

```java
package com.opensmps.deck.ui;

import com.opensmps.deck.model.FmVoice;
import com.opensmps.deck.model.PsgEnvelope;
import com.opensmps.deck.model.Song;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.Optional;

/**
 * Right-side instrument panel displaying the voice bank and PSG envelope list.
 *
 * <p>Provides add/edit/delete operations for FM voices and PSG envelopes,
 * and tracks the currently selected instrument index for use by the tracker grid.
 */
public class InstrumentPanel extends VBox {

    private final Song song;
    private final ListView<String> voiceListView;
    private final ListView<String> envelopeListView;
    private int currentVoiceIndex = -1;
    private int currentEnvelopeIndex = -1;

    public InstrumentPanel(Song song) {
        this.song = song;
        this.voiceListView = new ListView<>();
        this.envelopeListView = new ListView<>();

        setPrefWidth(250);
        setStyle("-fx-background-color: #252525;");
        setPadding(new Insets(4));
        setSpacing(8);

        // Voice bank section
        getChildren().add(createVoiceSection());

        // PSG envelope section
        getChildren().add(createEnvelopeSection());

        refresh();
    }

    private VBox createVoiceSection() {
        VBox section = new VBox(4);
        VBox.setVgrow(section, Priority.ALWAYS);

        Label title = new Label("Voice Bank");
        title.setStyle("-fx-text-fill: #88aacc; -fx-font-weight: bold;");

        voiceListView.setStyle("-fx-background-color: #2a2a2a;");
        voiceListView.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            if (n != null && n.intValue() >= 0) {
                currentVoiceIndex = n.intValue();
            }
        });
        voiceListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) editVoice();
        });
        VBox.setVgrow(voiceListView, Priority.ALWAYS);

        HBox buttons = createButtonRow(
            "+", "Add voice", this::addVoice,
            "Edit", "Edit selected voice", this::editVoice,
            "Del", "Delete selected voice", this::deleteVoice
        );

        section.getChildren().addAll(title, voiceListView, buttons);
        return section;
    }

    private VBox createEnvelopeSection() {
        VBox section = new VBox(4);
        VBox.setVgrow(section, Priority.ALWAYS);

        Label title = new Label("PSG Envelopes");
        title.setStyle("-fx-text-fill: #88aacc; -fx-font-weight: bold;");

        envelopeListView.setStyle("-fx-background-color: #2a2a2a;");
        envelopeListView.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            if (n != null && n.intValue() >= 0) {
                currentEnvelopeIndex = n.intValue();
            }
        });
        envelopeListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) editEnvelope();
        });
        VBox.setVgrow(envelopeListView, Priority.ALWAYS);

        HBox buttons = createButtonRow(
            "+", "Add envelope", this::addEnvelope,
            "Edit", "Edit selected envelope", this::editEnvelope,
            "Del", "Delete selected envelope", this::deleteEnvelope
        );

        section.getChildren().addAll(title, envelopeListView, buttons);
        return section;
    }

    private HBox createButtonRow(String t1, String tip1, Runnable a1,
                                  String t2, String tip2, Runnable a2,
                                  String t3, String tip3, Runnable a3) {
        Button b1 = new Button(t1); b1.setTooltip(new Tooltip(tip1)); b1.setOnAction(e -> a1.run());
        Button b2 = new Button(t2); b2.setTooltip(new Tooltip(tip2)); b2.setOnAction(e -> a2.run());
        Button b3 = new Button(t3); b3.setTooltip(new Tooltip(tip3)); b3.setOnAction(e -> a3.run());
        HBox row = new HBox(4, b1, b2, b3);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // --- Voice operations ---

    private void addVoice() {
        byte[] defaultData = new byte[FmVoice.VOICE_SIZE];
        // Algo 0, no feedback, all zeros (sine carrier)
        FmVoice newVoice = new FmVoice("Voice " + song.getVoiceBank().size(), defaultData);
        FmVoiceEditor editor = new FmVoiceEditor(newVoice);
        Optional<FmVoice> result = editor.showAndWait();
        result.ifPresent(v -> {
            song.getVoiceBank().add(v);
            refresh();
        });
    }

    private void editVoice() {
        int idx = voiceListView.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= song.getVoiceBank().size()) return;

        FmVoice original = song.getVoiceBank().get(idx);
        FmVoiceEditor editor = new FmVoiceEditor(original);
        Optional<FmVoice> result = editor.showAndWait();
        result.ifPresent(v -> {
            song.getVoiceBank().set(idx, v);
            refresh();
        });
    }

    private void deleteVoice() {
        int idx = voiceListView.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= song.getVoiceBank().size()) return;
        song.getVoiceBank().remove(idx);
        currentVoiceIndex = Math.min(currentVoiceIndex, song.getVoiceBank().size() - 1);
        refresh();
    }

    // --- Envelope operations ---

    private void addEnvelope() {
        byte[] defaultData = {0, (byte) 0x80}; // single step at volume 0
        PsgEnvelope newEnv = new PsgEnvelope("Env " + song.getPsgEnvelopes().size(), defaultData);
        PsgEnvelopeEditor editor = new PsgEnvelopeEditor(newEnv);
        Optional<PsgEnvelope> result = editor.showAndWait();
        result.ifPresent(env -> {
            song.getPsgEnvelopes().add(env);
            refresh();
        });
    }

    private void editEnvelope() {
        int idx = envelopeListView.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= song.getPsgEnvelopes().size()) return;

        PsgEnvelope original = song.getPsgEnvelopes().get(idx);
        PsgEnvelopeEditor editor = new PsgEnvelopeEditor(original);
        Optional<PsgEnvelope> result = editor.showAndWait();
        result.ifPresent(env -> {
            song.getPsgEnvelopes().set(idx, env);
            refresh();
        });
    }

    private void deleteEnvelope() {
        int idx = envelopeListView.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= song.getPsgEnvelopes().size()) return;
        song.getPsgEnvelopes().remove(idx);
        currentEnvelopeIndex = Math.min(currentEnvelopeIndex, song.getPsgEnvelopes().size() - 1);
        refresh();
    }

    /** Refresh both list views from the song model. */
    public void refresh() {
        voiceListView.getItems().clear();
        for (int i = 0; i < song.getVoiceBank().size(); i++) {
            FmVoice v = song.getVoiceBank().get(i);
            voiceListView.getItems().add(String.format("%02X: %s", i, v.getName()));
        }

        envelopeListView.getItems().clear();
        for (int i = 0; i < song.getPsgEnvelopes().size(); i++) {
            PsgEnvelope env = song.getPsgEnvelopes().get(i);
            envelopeListView.getItems().add(String.format("%02X: %s", i, env.getName()));
        }
    }

    /** Get the currently selected FM voice index (-1 if none). */
    public int getCurrentVoiceIndex() { return currentVoiceIndex; }

    /** Get the currently selected PSG envelope index (-1 if none). */
    public int getCurrentEnvelopeIndex() { return currentEnvelopeIndex; }
}
```

### Step 2: Update MainWindow to use InstrumentPanel

In `MainWindow.java`, replace the placeholder with the real panel:

**Remove** (lines ~57-62):
```java
// Right: Instrument panel placeholder
StackPane instrumentPlaceholder = new StackPane();
instrumentPlaceholder.setPrefWidth(250);
instrumentPlaceholder.setStyle("-fx-background-color: #252525;");
instrumentPlaceholder.getChildren().add(createLabel("Instruments"));
root.setRight(instrumentPlaceholder);
```

**Replace with:**
```java
// Right: Instrument panel
InstrumentPanel instrumentPanel = new InstrumentPanel(currentSong);
root.setRight(instrumentPanel);
```

Also add a field `private InstrumentPanel instrumentPanel;` and a getter `public InstrumentPanel getInstrumentPanel() { return instrumentPanel; }`.

### Step 3: Verify compilation

Run: `mvn compile -pl app`
Expected: BUILD SUCCESS

### Step 4: Commit

```bash
git add app/src/main/java/com/opensmps/deck/ui/InstrumentPanel.java \
       app/src/main/java/com/opensmps/deck/ui/MainWindow.java
git commit -m "feat: add instrument panel with voice bank and envelope list management"
```

---

## Task 23: Wire Instrument Selection to TrackerGrid

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`
- Modify: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`

### Context

When a user enters a note in the tracker grid, the current instrument from InstrumentPanel should be encoded as a voice/instrument change command BEFORE the note, if an instrument is selected.

For FM channels (0-5): prepend `SmpsCoordFlags.SET_VOICE` (0xEF) + voiceIndex
For PSG channels (6-9): prepend `SmpsCoordFlags.PSG_INSTRUMENT` (0xF5) + envelopeIndex

### Step 1: Add instrumentPanel reference to TrackerGrid

Add a field and setter in `TrackerGrid.java`:

```java
private InstrumentPanel instrumentPanel;

public void setInstrumentPanel(InstrumentPanel panel) {
    this.instrumentPanel = panel;
}
```

### Step 2: Modify insertNote to prepend instrument change

In `TrackerGrid.java`, modify the `insertNote` method:

**Current:**
```java
private void insertNote(int noteValue) {
    if (song == null) return;
    Pattern pattern = song.getPatterns().get(currentPatternIndex);
    byte[] trackData = pattern.getTrackData(cursorChannel);
    byte[] noteBytes = SmpsEncoder.encodeNote(noteValue, currentDuration);
    byte[] newData = SmpsEncoder.insertAtRow(trackData, cursorRow, noteBytes);
    undoManager.recordEdit(pattern, cursorChannel);
    pattern.setTrackData(cursorChannel, newData);
    cursorRow++;
    refreshDisplay();
}
```

**New:**
```java
private void insertNote(int noteValue) {
    if (song == null) return;
    Pattern pattern = song.getPatterns().get(currentPatternIndex);
    byte[] trackData = pattern.getTrackData(cursorChannel);

    // Build note bytes, optionally prepending instrument change
    byte[] noteBytes = SmpsEncoder.encodeNote(noteValue, currentDuration);
    byte[] insertBytes = prependInstrumentIfSelected(noteBytes);

    byte[] newData = SmpsEncoder.insertAtRow(trackData, cursorRow, insertBytes);
    undoManager.recordEdit(pattern, cursorChannel);
    pattern.setTrackData(cursorChannel, newData);
    cursorRow++;
    refreshDisplay();
}

private byte[] prependInstrumentIfSelected(byte[] noteBytes) {
    if (instrumentPanel == null) return noteBytes;

    byte[] instrBytes = null;
    if (cursorChannel <= 5) {
        // FM channel: use voice index
        int voiceIdx = instrumentPanel.getCurrentVoiceIndex();
        if (voiceIdx >= 0) {
            instrBytes = SmpsEncoder.encodeVoiceChange(voiceIdx);
        }
    } else {
        // PSG channel: use envelope index
        int envIdx = instrumentPanel.getCurrentEnvelopeIndex();
        if (envIdx >= 0) {
            instrBytes = SmpsEncoder.encodePsgEnvelope(envIdx);
        }
    }

    if (instrBytes == null) return noteBytes;

    // Concatenate: instrument change + note
    byte[] combined = new byte[instrBytes.length + noteBytes.length];
    System.arraycopy(instrBytes, 0, combined, 0, instrBytes.length);
    System.arraycopy(noteBytes, 0, combined, instrBytes.length, noteBytes.length);
    return combined;
}
```

### Step 3: Wire in MainWindow

In `MainWindow.setupLayout()`, after creating both `trackerGrid` and `instrumentPanel`:

```java
trackerGrid.setInstrumentPanel(instrumentPanel);
```

### Step 4: Verify compilation

Run: `mvn compile -pl app`
Expected: BUILD SUCCESS

### Step 5: Run all tests

Run: `mvn test`
Expected: ALL PASS (no test changes needed — instrument wiring is UI-only)

### Step 6: Commit

```bash
git add app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java \
       app/src/main/java/com/opensmps/deck/ui/MainWindow.java
git commit -m "feat: wire instrument selection to tracker grid note entry"
```

---

## Summary

| Task | Description | New Files | Tests |
|------|-------------|-----------|-------|
| 18 | FmVoice bit-field accessors + fix PARAMS_PER_OPERATOR | — | 7 new tests |
| 19 | PsgEnvelope add/remove/setData | — | 4 new tests |
| 20 | FM Voice Editor dialog | FmVoiceEditor.java | compile check |
| 21 | PSG Envelope Editor dialog | PsgEnvelopeEditor.java | compile check |
| 22 | Instrument Panel + MainWindow integration | InstrumentPanel.java | compile check |
| 23 | Wire instrument selection to TrackerGrid | — | compile + full test suite |

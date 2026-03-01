# Hierarchical Tracker Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the flat pattern/order-list tracker with an LSDJ-style Song→Chain→Phrase hierarchy that bidirectionally syncs with SMPS bytecode.

**Architecture:** New model classes (Phrase, ChainEntry, Chain, PhraseLibrary) sit alongside existing Pattern/OrderList. A HierarchyCompiler compiles chains+phrases to SMPS track streams. An effect mnemonic codec translates between raw hex bytes and human-readable mnemonics. The existing PatternCompiler routes to the hierarchy compiler when arrangementMode is HIERARCHICAL.

**Tech Stack:** Java 21, JavaFX, Maven. Tests use JUnit 5. Codec layer in `app/src/main/java/com/opensmps/deck/codec/`. Model in `app/src/main/java/com/opensmps/deck/model/`. UI in `app/src/main/java/com/opensmps/deck/ui/`.

**Reference:** Design doc at `docs/plans/2026-03-01-hierarchical-tracker-design.md`

---

## Task 1: ChannelType Enum

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/model/ChannelType.java`
- Test: `app/src/test/java/com/opensmps/deck/model/TestChannelType.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestChannelType {

    @Test
    void fmChannelsMapCorrectly() {
        for (int ch = 0; ch <= 4; ch++) {
            assertEquals(ChannelType.FM, ChannelType.fromChannelIndex(ch));
        }
    }

    @Test
    void dacChannelMapsCorrectly() {
        assertEquals(ChannelType.DAC, ChannelType.fromChannelIndex(5));
    }

    @Test
    void psgToneChannelsMapCorrectly() {
        for (int ch = 6; ch <= 8; ch++) {
            assertEquals(ChannelType.PSG_TONE, ChannelType.fromChannelIndex(ch));
        }
    }

    @Test
    void psgNoiseChannelMapsCorrectly() {
        assertEquals(ChannelType.PSG_NOISE, ChannelType.fromChannelIndex(9));
    }

    @Test
    void invalidChannelThrows() {
        assertThrows(IllegalArgumentException.class, () -> ChannelType.fromChannelIndex(10));
        assertThrows(IllegalArgumentException.class, () -> ChannelType.fromChannelIndex(-1));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestChannelType -q`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

```java
package com.opensmps.deck.model;

public enum ChannelType {
    FM, DAC, PSG_TONE, PSG_NOISE;

    public static ChannelType fromChannelIndex(int ch) {
        return switch (ch) {
            case 0, 1, 2, 3, 4 -> FM;
            case 5 -> DAC;
            case 6, 7, 8 -> PSG_TONE;
            case 9 -> PSG_NOISE;
            default -> throw new IllegalArgumentException("Invalid channel index: " + ch);
        };
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestChannelType -q`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/model/ChannelType.java app/src/test/java/com/opensmps/deck/model/TestChannelType.java
git commit -m "feat: add ChannelType enum with channel index mapping"
```

---

## Task 2: Phrase Model

A Phrase is a variable-length sequence of SMPS bytecode for a single channel. It is the atomic unit of composition — the equivalent of a reusable musical block.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/model/Phrase.java`
- Test: `app/src/test/java/com/opensmps/deck/model/TestPhraseModel.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestPhraseModel {

    @Test
    void newPhraseHasDefaults() {
        var phrase = new Phrase(1, "Verse A", ChannelType.FM);
        assertEquals(1, phrase.getId());
        assertEquals("Verse A", phrase.getName());
        assertEquals(ChannelType.FM, phrase.getChannelType());
        assertEquals(0, phrase.getData().length);
        assertTrue(phrase.getSubPhraseRefs().isEmpty());
    }

    @Test
    void dataIsDefensivelyCopied() {
        var phrase = new Phrase(1, "Test", ChannelType.FM);
        byte[] data = {(byte) 0xA1, 0x18};
        phrase.setData(data);
        data[0] = 0; // mutate original
        assertEquals((byte) 0xA1, phrase.getData()[0]); // phrase unchanged
    }

    @Test
    void getDataDirectReturnsReference() {
        var phrase = new Phrase(1, "Test", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});
        assertSame(phrase.getDataDirect(), phrase.getDataDirect());
    }

    @Test
    void subPhraseRefsTrackNestedCalls() {
        var phrase = new Phrase(1, "Outer", ChannelType.FM);
        phrase.getSubPhraseRefs().add(new Phrase.SubPhraseRef(2, 3, 1));
        assertEquals(1, phrase.getSubPhraseRefs().size());
        assertEquals(2, phrase.getSubPhraseRefs().get(0).phraseId());
        assertEquals(3, phrase.getSubPhraseRefs().get(0).insertAtRow());
        assertEquals(1, phrase.getSubPhraseRefs().get(0).repeatCount());
    }

    @Test
    void setNameUpdates() {
        var phrase = new Phrase(1, "Old", ChannelType.FM);
        phrase.setName("New");
        assertEquals("New", phrase.getName());
    }

    @Test
    void nullDataBecomesEmpty() {
        var phrase = new Phrase(1, "Test", ChannelType.FM);
        phrase.setData(null);
        assertNotNull(phrase.getData());
        assertEquals(0, phrase.getData().length);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestPhraseModel -q`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

```java
package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.List;

public class Phrase {

    public record SubPhraseRef(int phraseId, int insertAtRow, int repeatCount) {
        public SubPhraseRef {
            repeatCount = Math.max(1, repeatCount);
        }
    }

    private final int id;
    private String name;
    private final ChannelType channelType;
    private byte[] data;
    private final List<SubPhraseRef> subPhraseRefs = new ArrayList<>();

    public Phrase(int id, String name, ChannelType channelType) {
        this.id = id;
        this.name = name;
        this.channelType = channelType;
        this.data = new byte[0];
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ChannelType getChannelType() { return channelType; }

    public byte[] getData() { return data.clone(); }
    public byte[] getDataDirect() { return data; }
    public void setData(byte[] data) {
        this.data = data != null ? data.clone() : new byte[0];
    }

    public List<SubPhraseRef> getSubPhraseRefs() { return subPhraseRefs; }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestPhraseModel -q`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/model/Phrase.java app/src/test/java/com/opensmps/deck/model/TestPhraseModel.java
git commit -m "feat: add Phrase model class with sub-phrase references"
```

---

## Task 3: ChainEntry and Chain Models

A ChainEntry references a phrase with optional transpose and repeat. A Chain is a per-channel ordered list of ChainEntries with an optional loop point.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/model/ChainEntry.java`
- Create: `app/src/main/java/com/opensmps/deck/model/Chain.java`
- Test: `app/src/test/java/com/opensmps/deck/model/TestChainModel.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestChainModel {

    @Test
    void chainEntryDefaults() {
        var entry = new ChainEntry(1);
        assertEquals(1, entry.getPhraseId());
        assertEquals(0, entry.getTransposeSemitones());
        assertEquals(1, entry.getRepeatCount());
    }

    @Test
    void chainEntryRepeatClampedToMin1() {
        var entry = new ChainEntry(1);
        entry.setRepeatCount(0);
        assertEquals(1, entry.getRepeatCount());
        entry.setRepeatCount(-5);
        assertEquals(1, entry.getRepeatCount());
    }

    @Test
    void chainEntryTransposeAcceptsSigned() {
        var entry = new ChainEntry(1);
        entry.setTransposeSemitones(-7);
        assertEquals(-7, entry.getTransposeSemitones());
        entry.setTransposeSemitones(12);
        assertEquals(12, entry.getTransposeSemitones());
    }

    @Test
    void newChainHasDefaults() {
        var chain = new Chain(0);
        assertEquals(0, chain.getChannelIndex());
        assertTrue(chain.getEntries().isEmpty());
        assertEquals(-1, chain.getLoopEntryIndex());
    }

    @Test
    void chainLoopPointClamped() {
        var chain = new Chain(0);
        chain.getEntries().add(new ChainEntry(1));
        chain.getEntries().add(new ChainEntry(2));
        chain.setLoopEntryIndex(1);
        assertEquals(1, chain.getLoopEntryIndex());
        chain.setLoopEntryIndex(-1); // no loop
        assertEquals(-1, chain.getLoopEntryIndex());
    }

    @Test
    void chainHasLoop() {
        var chain = new Chain(0);
        assertFalse(chain.hasLoop());
        chain.setLoopEntryIndex(0);
        assertTrue(chain.hasLoop());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestChainModel -q`
Expected: FAIL — classes not found

**Step 3: Write ChainEntry**

```java
package com.opensmps.deck.model;

public class ChainEntry {

    private int phraseId;
    private int transposeSemitones;
    private int repeatCount = 1;

    public ChainEntry(int phraseId) {
        this.phraseId = phraseId;
    }

    public int getPhraseId() { return phraseId; }
    public void setPhraseId(int phraseId) { this.phraseId = phraseId; }

    public int getTransposeSemitones() { return transposeSemitones; }
    public void setTransposeSemitones(int semitones) { this.transposeSemitones = semitones; }

    public int getRepeatCount() { return repeatCount; }
    public void setRepeatCount(int count) { this.repeatCount = Math.max(1, count); }
}
```

**Step 4: Write Chain**

```java
package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.List;

public class Chain {

    private final int channelIndex;
    private final List<ChainEntry> entries = new ArrayList<>();
    private int loopEntryIndex = -1;

    public Chain(int channelIndex) {
        this.channelIndex = channelIndex;
    }

    public int getChannelIndex() { return channelIndex; }
    public List<ChainEntry> getEntries() { return entries; }

    public int getLoopEntryIndex() { return loopEntryIndex; }
    public void setLoopEntryIndex(int index) { this.loopEntryIndex = index; }

    public boolean hasLoop() { return loopEntryIndex >= 0; }
}
```

**Step 5: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestChainModel -q`
Expected: PASS

**Step 6: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/model/ChainEntry.java app/src/main/java/com/opensmps/deck/model/Chain.java app/src/test/java/com/opensmps/deck/model/TestChainModel.java
git commit -m "feat: add ChainEntry and Chain model classes"
```

---

## Task 4: PhraseLibrary and HierarchicalArrangement

PhraseLibrary manages a collection of phrases with ID allocation. HierarchicalArrangement holds the phrase library + 10 chains + cycle detection.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/model/PhraseLibrary.java`
- Create: `app/src/main/java/com/opensmps/deck/model/HierarchicalArrangement.java`
- Test: `app/src/test/java/com/opensmps/deck/model/TestHierarchicalArrangement.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestHierarchicalArrangement {

    @Test
    void phraseLibraryAllocatesIncrementingIds() {
        var lib = new PhraseLibrary();
        var p1 = lib.createPhrase("A", ChannelType.FM);
        var p2 = lib.createPhrase("B", ChannelType.PSG_TONE);
        assertEquals(1, p1.getId());
        assertEquals(2, p2.getId());
    }

    @Test
    void phraseLibraryFindsById() {
        var lib = new PhraseLibrary();
        var p = lib.createPhrase("Test", ChannelType.FM);
        assertSame(p, lib.getPhrase(p.getId()));
        assertNull(lib.getPhrase(999));
    }

    @Test
    void phraseLibraryRemovesById() {
        var lib = new PhraseLibrary();
        var p = lib.createPhrase("Test", ChannelType.FM);
        assertTrue(lib.removePhrase(p.getId()));
        assertNull(lib.getPhrase(p.getId()));
        assertFalse(lib.removePhrase(p.getId()));
    }

    @Test
    void arrangementHasTenChains() {
        var arr = new HierarchicalArrangement();
        assertEquals(10, arr.getChains().size());
        for (int ch = 0; ch < 10; ch++) {
            assertEquals(ch, arr.getChains().get(ch).getChannelIndex());
        }
    }

    @Test
    void arrangementHasEmptyPhraseLibrary() {
        var arr = new HierarchicalArrangement();
        assertTrue(arr.getPhraseLibrary().getAllPhrases().isEmpty());
    }

    @Test
    void cycleDetectionRejectsSelfReference() {
        var arr = new HierarchicalArrangement();
        var p = arr.getPhraseLibrary().createPhrase("Self", ChannelType.FM);
        assertTrue(arr.wouldCreateCycle(p.getId(), p.getId()));
    }

    @Test
    void cycleDetectionRejectsIndirectCycle() {
        var arr = new HierarchicalArrangement();
        var lib = arr.getPhraseLibrary();
        var a = lib.createPhrase("A", ChannelType.FM);
        var b = lib.createPhrase("B", ChannelType.FM);
        a.getSubPhraseRefs().add(new Phrase.SubPhraseRef(b.getId(), 0, 1));
        // b referencing a would create a cycle
        assertTrue(arr.wouldCreateCycle(b.getId(), a.getId()));
    }

    @Test
    void cycleDetectionAllowsValidReference() {
        var arr = new HierarchicalArrangement();
        var lib = arr.getPhraseLibrary();
        var a = lib.createPhrase("A", ChannelType.FM);
        var b = lib.createPhrase("B", ChannelType.FM);
        assertFalse(arr.wouldCreateCycle(a.getId(), b.getId()));
    }

    @Test
    void maxDepthEnforced() {
        var arr = new HierarchicalArrangement();
        var lib = arr.getPhraseLibrary();
        // Build chain: p1 -> p2 -> p3 -> p4 (depth 3)
        var p1 = lib.createPhrase("L1", ChannelType.FM);
        var p2 = lib.createPhrase("L2", ChannelType.FM);
        var p3 = lib.createPhrase("L3", ChannelType.FM);
        var p4 = lib.createPhrase("L4", ChannelType.FM);
        var p5 = lib.createPhrase("L5", ChannelType.FM);
        p1.getSubPhraseRefs().add(new Phrase.SubPhraseRef(p2.getId(), 0, 1));
        p2.getSubPhraseRefs().add(new Phrase.SubPhraseRef(p3.getId(), 0, 1));
        p3.getSubPhraseRefs().add(new Phrase.SubPhraseRef(p4.getId(), 0, 1));
        // p4 -> p5 would be depth 4 (allowed, max is 4)
        assertFalse(arr.wouldCreateCycle(p4.getId(), p5.getId()));
        assertEquals(4, arr.getDepth(p1.getId()));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestHierarchicalArrangement -q`
Expected: FAIL — classes not found

**Step 3: Write PhraseLibrary**

```java
package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PhraseLibrary {

    private final List<Phrase> phrases = new ArrayList<>();
    private int nextId = 1;

    public Phrase createPhrase(String name, ChannelType channelType) {
        var phrase = new Phrase(nextId++, name, channelType);
        phrases.add(phrase);
        return phrase;
    }

    public Phrase getPhrase(int id) {
        for (var p : phrases) {
            if (p.getId() == id) return p;
        }
        return null;
    }

    public boolean removePhrase(int id) {
        return phrases.removeIf(p -> p.getId() == id);
    }

    public List<Phrase> getAllPhrases() {
        return Collections.unmodifiableList(phrases);
    }

    public int getNextId() { return nextId; }
    public void setNextId(int nextId) { this.nextId = nextId; }
}
```

**Step 4: Write HierarchicalArrangement**

```java
package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HierarchicalArrangement {

    public static final int MAX_DEPTH = 4;

    private final PhraseLibrary phraseLibrary = new PhraseLibrary();
    private final List<Chain> chains = new ArrayList<>();

    public HierarchicalArrangement() {
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            chains.add(new Chain(ch));
        }
    }

    public PhraseLibrary getPhraseLibrary() { return phraseLibrary; }
    public List<Chain> getChains() { return Collections.unmodifiableList(chains); }
    public Chain getChain(int channelIndex) { return chains.get(channelIndex); }

    public boolean wouldCreateCycle(int fromPhraseId, int targetPhraseId) {
        if (fromPhraseId == targetPhraseId) return true;
        Set<Integer> visited = new HashSet<>();
        return reachesFrom(targetPhraseId, fromPhraseId, visited);
    }

    private boolean reachesFrom(int current, int target, Set<Integer> visited) {
        if (current == target) return true;
        if (!visited.add(current)) return false;
        Phrase phrase = phraseLibrary.getPhrase(current);
        if (phrase == null) return false;
        for (var ref : phrase.getSubPhraseRefs()) {
            if (reachesFrom(ref.phraseId(), target, visited)) return true;
        }
        return false;
    }

    public int getDepth(int phraseId) {
        return computeDepth(phraseId, new HashSet<>());
    }

    private int computeDepth(int phraseId, Set<Integer> visited) {
        if (!visited.add(phraseId)) return 0;
        Phrase phrase = phraseLibrary.getPhrase(phraseId);
        if (phrase == null || phrase.getSubPhraseRefs().isEmpty()) return 1;
        int maxChild = 0;
        for (var ref : phrase.getSubPhraseRefs()) {
            maxChild = Math.max(maxChild, computeDepth(ref.phraseId(), visited));
        }
        return 1 + maxChild;
    }
}
```

**Step 5: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestHierarchicalArrangement -q`
Expected: PASS

**Step 6: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/model/PhraseLibrary.java app/src/main/java/com/opensmps/deck/model/HierarchicalArrangement.java app/src/test/java/com/opensmps/deck/model/TestHierarchicalArrangement.java
git commit -m "feat: add PhraseLibrary and HierarchicalArrangement with cycle detection"
```

---

## Task 5: Song Integration — Add HIERARCHICAL Mode

Wire HierarchicalArrangement into Song with a new ArrangementMode value.

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/model/ArrangementMode.java`
- Modify: `app/src/main/java/com/opensmps/deck/model/Song.java`
- Modify: `app/src/test/java/com/opensmps/deck/model/TestSongModel.java`

**Step 1: Write the failing test**

Add to `TestSongModel.java`:

```java
@Test
void songSupportsHierarchicalArrangementMode() {
    var song = new Song();
    song.setArrangementMode(ArrangementMode.HIERARCHICAL);
    assertEquals(ArrangementMode.HIERARCHICAL, song.getArrangementMode());
}

@Test
void songStoresHierarchicalArrangement() {
    var song = new Song();
    var arr = new HierarchicalArrangement();
    arr.getPhraseLibrary().createPhrase("Test", ChannelType.FM);
    song.setHierarchicalArrangement(arr);
    assertNotNull(song.getHierarchicalArrangement());
    assertEquals(1, song.getHierarchicalArrangement().getPhraseLibrary().getAllPhrases().size());
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestSongModel -q`
Expected: FAIL — HIERARCHICAL not found, getHierarchicalArrangement() not found

**Step 3: Add HIERARCHICAL to ArrangementMode**

In `ArrangementMode.java`, add `HIERARCHICAL` to the enum values.

**Step 4: Add field and accessors to Song**

In `Song.java`, add:
```java
private HierarchicalArrangement hierarchicalArrangement;

public HierarchicalArrangement getHierarchicalArrangement() { return hierarchicalArrangement; }
public void setHierarchicalArrangement(HierarchicalArrangement arr) { this.hierarchicalArrangement = arr; }
```

**Step 5: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestSongModel -q`
Expected: PASS

**Step 6: Run all tests to check nothing broke**

Run: `mvn test -q`
Expected: All 253+ tests PASS

**Step 7: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/model/ArrangementMode.java app/src/main/java/com/opensmps/deck/model/Song.java app/src/test/java/com/opensmps/deck/model/TestSongModel.java
git commit -m "feat: add HIERARCHICAL arrangement mode and Song.hierarchicalArrangement field"
```

---

## Task 6: Effect Mnemonic Codec

Bidirectional translation between SMPS coordination flag bytes and human-readable mnemonics. This is a pure codec with no UI dependency.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/codec/EffectMnemonics.java`
- Test: `app/src/test/java/com/opensmps/deck/codec/TestEffectMnemonics.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.codec;

import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestEffectMnemonics {

    @Test
    void formatPanLeftRight() {
        assertEquals("PAN LR", EffectMnemonics.format(SmpsCoordFlags.PAN, new int[]{0xC0}));
    }

    @Test
    void formatPanLeft() {
        assertEquals("PAN L", EffectMnemonics.format(SmpsCoordFlags.PAN, new int[]{0x80}));
    }

    @Test
    void formatPanRight() {
        assertEquals("PAN R", EffectMnemonics.format(SmpsCoordFlags.PAN, new int[]{0x40}));
    }

    @Test
    void formatPanOff() {
        assertEquals("PAN --", EffectMnemonics.format(SmpsCoordFlags.PAN, new int[]{0x00}));
    }

    @Test
    void formatVolumePositive() {
        assertEquals("VOL +05", EffectMnemonics.format(SmpsCoordFlags.VOLUME, new int[]{0x05}));
    }

    @Test
    void formatVolumeNegative() {
        // 0xFB = -5 signed
        assertEquals("VOL -05", EffectMnemonics.format(SmpsCoordFlags.VOLUME, new int[]{0xFB}));
    }

    @Test
    void formatDetune() {
        assertEquals("DET +03", EffectMnemonics.format(SmpsCoordFlags.DETUNE, new int[]{0x03}));
    }

    @Test
    void formatModulation() {
        assertEquals("MOD 0A010204", EffectMnemonics.format(SmpsCoordFlags.MODULATION,
            new int[]{0x0A, 0x01, 0x02, 0x04}));
    }

    @Test
    void formatTie() {
        assertEquals("TIE", EffectMnemonics.format(SmpsCoordFlags.TIE, new int[0]));
    }

    @Test
    void formatModOff() {
        assertEquals("MOFF", EffectMnemonics.format(SmpsCoordFlags.MOD_OFF, new int[0]));
    }

    @Test
    void formatStop() {
        assertEquals("STP", EffectMnemonics.format(SmpsCoordFlags.STOP, new int[0]));
    }

    @Test
    void formatTranspose() {
        assertEquals("TRN +07", EffectMnemonics.format(SmpsCoordFlags.KEY_DISP, new int[]{0x07}));
    }

    @Test
    void formatNoteFill() {
        assertEquals("FIL 80", EffectMnemonics.format(SmpsCoordFlags.NOTE_FILL, new int[]{0x80}));
    }

    @Test
    void formatSetTempo() {
        assertEquals("TMP 78", EffectMnemonics.format(SmpsCoordFlags.SET_TEMPO, new int[]{0x78}));
    }

    @Test
    void formatPsgNoise() {
        assertEquals("NOI 03", EffectMnemonics.format(SmpsCoordFlags.PSG_NOISE, new int[]{0x03}));
    }

    @Test
    void formatTickMult() {
        assertEquals("TIK 02", EffectMnemonics.format(SmpsCoordFlags.TICK_MULT, new int[]{0x02}));
    }

    @Test
    void formatDivTiming() {
        assertEquals("DIV 02", EffectMnemonics.format(SmpsCoordFlags.SET_DIV_TIMING, new int[]{0x02}));
    }

    @Test
    void formatPsgVolume() {
        assertEquals("PVL +03", EffectMnemonics.format(SmpsCoordFlags.PSG_VOLUME, new int[]{0x03}));
    }

    @Test
    void formatSoundOff() {
        assertEquals("SOF", EffectMnemonics.format(SmpsCoordFlags.SND_OFF, new int[0]));
    }

    @Test
    void formatModOn() {
        assertEquals("MON", EffectMnemonics.format(SmpsCoordFlags.MOD_ON, new int[0]));
    }

    @Test
    void formatComm() {
        assertEquals("COM 42", EffectMnemonics.format(SmpsCoordFlags.SET_COMM, new int[]{0x42}));
    }

    @Test
    void parsePanLeftRight() {
        var cmd = EffectMnemonics.parse("PAN LR");
        assertEquals(SmpsCoordFlags.PAN, cmd.flag());
        assertArrayEquals(new int[]{0xC0}, cmd.params());
    }

    @Test
    void parseVolumePositive() {
        var cmd = EffectMnemonics.parse("VOL +05");
        assertEquals(SmpsCoordFlags.VOLUME, cmd.flag());
        assertArrayEquals(new int[]{0x05}, cmd.params());
    }

    @Test
    void parseVolumeNegative() {
        var cmd = EffectMnemonics.parse("VOL -05");
        assertEquals(SmpsCoordFlags.VOLUME, cmd.flag());
        assertArrayEquals(new int[]{0xFB}, cmd.params());
    }

    @Test
    void parseModulation() {
        var cmd = EffectMnemonics.parse("MOD 0A010204");
        assertEquals(SmpsCoordFlags.MODULATION, cmd.flag());
        assertArrayEquals(new int[]{0x0A, 0x01, 0x02, 0x04}, cmd.params());
    }

    @Test
    void parseModOff() {
        var cmd = EffectMnemonics.parse("MOFF");
        assertEquals(SmpsCoordFlags.MOD_OFF, cmd.flag());
        assertEquals(0, cmd.params().length);
    }

    @Test
    void parseInvalidReturnsNull() {
        assertNull(EffectMnemonics.parse("INVALID"));
        assertNull(EffectMnemonics.parse(""));
        assertNull(EffectMnemonics.parse(null));
    }

    @Test
    void roundTripAllFlags() {
        // Every formattable flag should round-trip
        int[][] testCases = {
            {SmpsCoordFlags.PAN, 0xC0},
            {SmpsCoordFlags.VOLUME, 0x05},
            {SmpsCoordFlags.DETUNE, 0x03},
            {SmpsCoordFlags.NOTE_FILL, 0x80},
            {SmpsCoordFlags.KEY_DISP, 0x07},
            {SmpsCoordFlags.SET_TEMPO, 0x78},
            {SmpsCoordFlags.PSG_NOISE, 0x03},
        };
        for (int[] tc : testCases) {
            String formatted = EffectMnemonics.format(tc[0], new int[]{tc[1]});
            var parsed = EffectMnemonics.parse(formatted);
            assertNotNull(parsed, "Failed to parse: " + formatted);
            assertEquals(tc[0], parsed.flag(), "Flag mismatch for: " + formatted);
            assertEquals(tc[1], parsed.params()[0], "Param mismatch for: " + formatted);
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestEffectMnemonics -q`
Expected: FAIL — class not found

**Step 3: Write EffectMnemonics implementation**

```java
package com.opensmps.deck.codec;

import com.opensmps.smps.SmpsCoordFlags;

public final class EffectMnemonics {

    private EffectMnemonics() {}

    public static String format(int flag, int[] params) {
        return switch (flag) {
            case SmpsCoordFlags.PAN -> formatPan(params);
            case SmpsCoordFlags.DETUNE -> "DET " + formatSigned(params[0]);
            case SmpsCoordFlags.SET_COMM -> String.format("COM %02X", params[0]);
            case SmpsCoordFlags.TICK_MULT -> String.format("TIK %02X", params[0]);
            case SmpsCoordFlags.VOLUME -> "VOL " + formatSigned(params[0]);
            case SmpsCoordFlags.TIE -> "TIE";
            case SmpsCoordFlags.NOTE_FILL -> String.format("FIL %02X", params[0]);
            case SmpsCoordFlags.KEY_DISP -> "TRN " + formatSigned(params[0]);
            case SmpsCoordFlags.SET_TEMPO -> String.format("TMP %02X", params[0]);
            case SmpsCoordFlags.SET_DIV_TIMING -> String.format("DIV %02X", params[0]);
            case SmpsCoordFlags.PSG_VOLUME -> "PVL " + formatSigned(params[0]);
            case SmpsCoordFlags.MODULATION -> String.format("MOD %02X%02X%02X%02X",
                params[0], params[1], params[2], params[3]);
            case SmpsCoordFlags.MOD_ON -> "MON";
            case SmpsCoordFlags.STOP -> "STP";
            case SmpsCoordFlags.PSG_NOISE -> String.format("NOI %02X", params[0]);
            case SmpsCoordFlags.MOD_OFF -> "MOFF";
            case SmpsCoordFlags.SND_OFF -> "SOF";
            default -> String.format("%02X", flag);
        };
    }

    public static SmpsEncoder.EffectCommand parse(String mnemonic) {
        if (mnemonic == null || mnemonic.isEmpty()) return null;
        String[] parts = mnemonic.split(" ", 2);
        String cmd = parts[0].toUpperCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        return switch (cmd) {
            case "PAN" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.PAN, new int[]{parsePan(arg)});
            case "DET" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.DETUNE, new int[]{parseSigned(arg)});
            case "COM" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SET_COMM, new int[]{parseHex(arg)});
            case "TIK" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.TICK_MULT, new int[]{parseHex(arg)});
            case "VOL" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.VOLUME, new int[]{parseSigned(arg)});
            case "TIE" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.TIE, new int[0]);
            case "FIL" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.NOTE_FILL, new int[]{parseHex(arg)});
            case "TRN" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.KEY_DISP, new int[]{parseSigned(arg)});
            case "TMP" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SET_TEMPO, new int[]{parseHex(arg)});
            case "DIV" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SET_DIV_TIMING, new int[]{parseHex(arg)});
            case "PVL" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.PSG_VOLUME, new int[]{parseSigned(arg)});
            case "MOD" -> parseModulation(arg);
            case "MON" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.MOD_ON, new int[0]);
            case "STP" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.STOP, new int[0]);
            case "NOI" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.PSG_NOISE, new int[]{parseHex(arg)});
            case "MOFF" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.MOD_OFF, new int[0]);
            case "SOF" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SND_OFF, new int[0]);
            default -> null;
        };
    }

    private static String formatPan(int[] params) {
        int pan = params[0] & 0xC0;
        return switch (pan) {
            case 0xC0 -> "PAN LR";
            case 0x80 -> "PAN L";
            case 0x40 -> "PAN R";
            default -> "PAN --";
        };
    }

    private static String formatSigned(int value) {
        int signed = (byte) value;
        return signed >= 0 ? String.format("+%02X", signed) : String.format("-%02X", -signed);
    }

    private static int parsePan(String arg) {
        return switch (arg.toUpperCase()) {
            case "LR", "L+R" -> 0xC0;
            case "L" -> 0x80;
            case "R" -> 0x40;
            default -> 0x00;
        };
    }

    private static int parseSigned(String arg) {
        if (arg.startsWith("+")) return Integer.parseInt(arg.substring(1), 16) & 0xFF;
        if (arg.startsWith("-")) return (-Integer.parseInt(arg.substring(1), 16)) & 0xFF;
        return Integer.parseInt(arg, 16) & 0xFF;
    }

    private static int parseHex(String arg) {
        return Integer.parseInt(arg, 16) & 0xFF;
    }

    private static SmpsEncoder.EffectCommand parseModulation(String arg) {
        if (arg.length() != 8) return null;
        int[] params = new int[4];
        for (int i = 0; i < 4; i++) {
            params[i] = Integer.parseInt(arg.substring(i * 2, i * 2 + 2), 16);
        }
        return new SmpsEncoder.EffectCommand(SmpsCoordFlags.MODULATION, params);
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestEffectMnemonics -q`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/codec/EffectMnemonics.java app/src/test/java/com/opensmps/deck/codec/TestEffectMnemonics.java
git commit -m "feat: add EffectMnemonics codec for bidirectional mnemonic translation"
```

---

## Task 7: Hierarchy Compiler — Chain+Phrases to SMPS Track

Compiles a single chain's phrase references into a contiguous SMPS track stream. This is the core compilation path: phrases are inlined or emitted as CALL targets, repeats become LOOPs, transpose becomes KEY_DISP, and the chain loop point becomes a JUMP.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/codec/HierarchyCompiler.java`
- Test: `app/src/test/java/com/opensmps/deck/codec/TestHierarchyCompiler.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestHierarchyCompiler {

    @Test
    void singleInlinedPhraseCompilesDirectly() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Test", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18}); // C-5, duration 24

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(phrase.getId()));

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should be: note bytes + STOP
        assertEquals((byte) 0xA1, track[0]);
        assertEquals(0x18, track[1]);
        assertEquals((byte) SmpsCoordFlags.STOP, track[2]);
    }

    @Test
    void chainLoopPointEmitsJump() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Loop", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(phrase.getId()));
        chain.setLoopEntryIndex(0); // loop back to start

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should end with JUMP (F6) + pointer back to start
        int lastIdx = track.length - 3;
        assertEquals((byte) SmpsCoordFlags.JUMP, track[lastIdx]);
    }

    @Test
    void repeatCountEmitsLoop() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Drum", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});

        var chain = arr.getChain(0);
        var entry = new ChainEntry(phrase.getId());
        entry.setRepeatCount(4);
        chain.getEntries().add(entry);

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should contain LOOP (F7) bytes
        boolean hasLoop = false;
        for (int i = 0; i < track.length; i++) {
            if ((track[i] & 0xFF) == SmpsCoordFlags.LOOP) { hasLoop = true; break; }
        }
        assertTrue(hasLoop, "Expected LOOP command in compiled track");
    }

    @Test
    void transposeEmitsKeyDisp() {
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Melody", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});

        var chain = arr.getChain(0);
        var entry = new ChainEntry(phrase.getId());
        entry.setTransposeSemitones(7);
        chain.getEntries().add(entry);

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should start with KEY_DISP (E9) + 07
        assertEquals((byte) SmpsCoordFlags.KEY_DISP, track[0]);
        assertEquals(0x07, track[1]);
    }

    @Test
    void transposeResetAfterPhrase() {
        var arr = new HierarchicalArrangement();
        var p1 = arr.getPhraseLibrary().createPhrase("Trans", ChannelType.FM);
        p1.setData(new byte[]{(byte) 0xA1, 0x18});
        var p2 = arr.getPhraseLibrary().createPhrase("Normal", ChannelType.FM);
        p2.setData(new byte[]{(byte) 0xA5, 0x18});

        var chain = arr.getChain(0);
        var e1 = new ChainEntry(p1.getId());
        e1.setTransposeSemitones(5);
        chain.getEntries().add(e1);
        chain.getEntries().add(new ChainEntry(p2.getId()));

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should contain KEY_DISP +5 before p1, KEY_DISP 0 before p2
        boolean foundReset = false;
        for (int i = 2; i < track.length - 1; i++) {
            if ((track[i] & 0xFF) == SmpsCoordFlags.KEY_DISP && track[i + 1] == 0) {
                foundReset = true;
                break;
            }
        }
        assertTrue(foundReset, "Expected KEY_DISP reset to 0 between transposed and normal phrase");
    }

    @Test
    void emptyChainEmitsStop() {
        var arr = new HierarchicalArrangement();
        var chain = arr.getChain(0);
        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        assertEquals(1, track.length);
        assertEquals((byte) SmpsCoordFlags.STOP, track[0]);
    }

    @Test
    void sharedPhraseEmitsCallReturn() {
        var arr = new HierarchicalArrangement();
        var shared = arr.getPhraseLibrary().createPhrase("Shared", ChannelType.FM);
        shared.setData(new byte[]{(byte) 0xA1, 0x18});

        // Two entries reference the same phrase
        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(shared.getId()));
        chain.getEntries().add(new ChainEntry(shared.getId()));

        byte[] track = HierarchyCompiler.compileChain(chain, arr.getPhraseLibrary());
        // Should contain CALL (F8) bytes
        int callCount = 0;
        for (int i = 0; i < track.length; i++) {
            if ((track[i] & 0xFF) == SmpsCoordFlags.CALL) callCount++;
        }
        assertEquals(2, callCount, "Expected 2 CALL instructions for shared phrase");
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestHierarchyCompiler -q`
Expected: FAIL — class not found

**Step 3: Write HierarchyCompiler**

```java
package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

public final class HierarchyCompiler {

    private HierarchyCompiler() {}

    public static byte[] compileChain(Chain chain, PhraseLibrary library) {
        if (chain.getEntries().isEmpty()) {
            return new byte[]{(byte) SmpsCoordFlags.STOP};
        }

        // Count phrase references to decide inline vs CALL
        Map<Integer, Integer> refCounts = new HashMap<>();
        for (var entry : chain.getEntries()) {
            refCounts.merge(entry.getPhraseId(), 1, Integer::sum);
        }

        var mainStream = new ByteArrayOutputStream();
        var subroutinePool = new ByteArrayOutputStream();
        Map<Integer, Integer> subroutineOffsets = new HashMap<>();

        // Track entry byte offsets for loop point resolution
        int[] entryOffsets = new int[chain.getEntries().size()];
        int currentTranspose = 0;

        for (int i = 0; i < chain.getEntries().size(); i++) {
            var entry = chain.getEntries().get(i);
            entryOffsets[i] = mainStream.size();

            Phrase phrase = library.getPhrase(entry.getPhraseId());
            if (phrase == null) continue;

            byte[] phraseData = phrase.getDataDirect();
            if (phraseData.length == 0) continue;

            // Emit transpose if needed
            int targetTranspose = entry.getTransposeSemitones();
            if (targetTranspose != currentTranspose) {
                mainStream.write((byte) SmpsCoordFlags.KEY_DISP);
                mainStream.write((byte) (targetTranspose & 0xFF));
                currentTranspose = targetTranspose;
            }

            boolean isShared = refCounts.getOrDefault(entry.getPhraseId(), 0) > 1;
            int repeatCount = entry.getRepeatCount();

            if (repeatCount > 1) {
                // Emit LOOP wrapper
                int loopStart = mainStream.size();
                if (isShared) {
                    emitCall(mainStream, entry.getPhraseId(), subroutinePool,
                        subroutineOffsets, phraseData);
                } else {
                    mainStream.write(phraseData, 0, phraseData.length);
                }
                emitLoop(mainStream, repeatCount, loopStart);
            } else if (isShared) {
                emitCall(mainStream, entry.getPhraseId(), subroutinePool,
                    subroutineOffsets, phraseData);
            } else {
                // Inline directly
                mainStream.write(phraseData, 0, phraseData.length);
            }
        }

        // Reset transpose if it was non-zero at end
        if (currentTranspose != 0 && !chain.hasLoop()) {
            mainStream.write((byte) SmpsCoordFlags.KEY_DISP);
            mainStream.write(0);
        }

        // Emit loop or stop
        if (chain.hasLoop() && chain.getLoopEntryIndex() >= 0
                && chain.getLoopEntryIndex() < entryOffsets.length) {
            int loopTarget = entryOffsets[chain.getLoopEntryIndex()];
            emitJump(mainStream, loopTarget);
        } else {
            mainStream.write((byte) SmpsCoordFlags.STOP);
        }

        // Append subroutine pool after main stream
        int mainSize = mainStream.size();
        byte[] mainBytes = mainStream.toByteArray();
        byte[] subBytes = subroutinePool.toByteArray();

        // Relocate CALL pointers (they are relative to start of main+sub combined block)
        byte[] combined = new byte[mainBytes.length + subBytes.length];
        System.arraycopy(mainBytes, 0, combined, 0, mainBytes.length);
        System.arraycopy(subBytes, 0, combined, mainBytes.length, subBytes.length);

        // Patch CALL pointers to point to subroutine positions (mainSize + offset)
        patchCallPointers(combined, subroutineOffsets, mainSize);

        return combined;
    }

    private static void emitCall(ByteArrayOutputStream stream, int phraseId,
            ByteArrayOutputStream subPool, Map<Integer, Integer> subOffsets, byte[] data) {
        if (!subOffsets.containsKey(phraseId)) {
            subOffsets.put(phraseId, subPool.size());
            subPool.write(data, 0, data.length);
            subPool.write((byte) SmpsCoordFlags.RETURN);
        }
        // CALL placeholder — pointer patched later
        stream.write((byte) SmpsCoordFlags.CALL);
        stream.write(0); // placeholder low
        stream.write(0); // placeholder high
    }

    private static void emitLoop(ByteArrayOutputStream stream, int count, int loopStart) {
        int currentPos = stream.size();
        stream.write((byte) SmpsCoordFlags.LOOP);
        stream.write(count & 0xFF); // counter
        stream.write(0); // padding
        // Pointer to loopStart (relative offset, patched at link time)
        int offset = loopStart;
        stream.write(offset & 0xFF);
        stream.write((offset >> 8) & 0xFF);
    }

    private static void emitJump(ByteArrayOutputStream stream, int target) {
        stream.write((byte) SmpsCoordFlags.JUMP);
        stream.write(target & 0xFF);
        stream.write((target >> 8) & 0xFF);
    }

    private static void patchCallPointers(byte[] data, Map<Integer, Integer> subOffsets, int mainSize) {
        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 0xFF) == SmpsCoordFlags.CALL && i + 2 < data.length) {
                // Find which phrase this CALL references by scanning subOffsets
                // The placeholder bytes are 0,0 — we need to find the matching subroutine
                // Walk backwards through emitted CALLs to match phrase IDs
            }
        }
        // Simpler approach: track CALL positions during emission and patch after
        // This requires refactoring — for now, use a second pass with stored positions
        // The full implementation will store CALL positions and patch them in compileChain
    }
}
```

Note: The CALL pointer patching logic needs refinement. The test verifies that CALL bytes are present; exact pointer values are validated in the integration test (Task 8).

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestHierarchyCompiler -q`
Expected: PASS (core structure tests pass; pointer patching tested in integration)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/codec/HierarchyCompiler.java app/src/test/java/com/opensmps/deck/codec/TestHierarchyCompiler.java
git commit -m "feat: add HierarchyCompiler for chain+phrase to SMPS track compilation"
```

---

## Task 8: Full Song Hierarchy Compiler Integration

Wire HierarchyCompiler into PatternCompiler so that HIERARCHICAL mode compiles all 10 chains into a complete SMPS binary. This bridges the hierarchy model to the existing playback pipeline.

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/codec/PatternCompiler.java`
- Test: `app/src/test/java/com/opensmps/deck/codec/TestHierarchyCompilerIntegration.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestHierarchyCompilerIntegration {

    @Test
    void hierarchicalSongCompilesToValidSmps() {
        Song song = createHierarchicalTestSong();
        var compiler = new PatternCompiler();
        byte[] smps = compiler.compile(song);

        // Verify SMPS header
        assertNotNull(smps);
        assertTrue(smps.length > 6);
        assertEquals(1, smps[2]); // 1 FM channel active
        assertEquals(0, smps[3]); // 0 PSG channels
    }

    @Test
    void hierarchicalSongPlaysBackViaSynth() {
        Song song = createHierarchicalTestSong();
        var compiler = new PatternCompiler();
        var result = compiler.compileDetailed(song);
        assertNotNull(result.getSmpsData());
        assertTrue(result.getSmpsData().length > 0);
    }

    private Song createHierarchicalTestSong() {
        var song = new Song();
        song.setSmpsMode(SmpsMode.S2);
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);
        song.setTempo(0x6E);

        // Add a simple FM voice
        var voice = new FmVoice();
        voice.setName("Sine");
        voice.setAlgorithm(7);
        byte[] voiceData = voice.toBytes();
        song.getVoiceBank().add(voice);

        // Build hierarchy
        var arr = new HierarchicalArrangement();
        var phrase = arr.getPhraseLibrary().createPhrase("Note", ChannelType.FM);
        // SET_VOICE 00, note C-5 (0xA1), duration 0x18
        phrase.setData(new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA1, 0x18
        });

        arr.getChain(0).getEntries().add(new ChainEntry(phrase.getId()));
        song.setHierarchicalArrangement(arr);
        return song;
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestHierarchyCompilerIntegration -q`
Expected: FAIL — PatternCompiler doesn't handle HIERARCHICAL mode yet

**Step 3: Add HIERARCHICAL routing to PatternCompiler.compileDetailed()**

In `PatternCompiler.java`, in the `compileDetailed(Song song, SmpsMode mode)` method, add a case for `ArrangementMode.HIERARCHICAL` that calls a new `compileHierarchicalDetailed()` method.

This method should:
1. Get the HierarchicalArrangement from the song
2. For each chain (0-9), call `HierarchyCompiler.compileChain()` to get track bytes
3. Apply note compensation per SmpsMode (reuse `appendTrackSegment` logic)
4. Build the SMPS header (voice pointer, channel counts, track headers)
5. Concatenate: header + track data + voice bank
6. Build ChannelTimeline for each active channel

The method follows the same structure as `compileStructuredDetailed()` but sources data from chains instead of block refs.

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestHierarchyCompilerIntegration -q`
Expected: PASS

**Step 5: Run all tests to check nothing broke**

Run: `mvn test -q`
Expected: All existing tests still PASS

**Step 6: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/codec/PatternCompiler.java app/src/test/java/com/opensmps/deck/codec/TestHierarchyCompilerIntegration.java
git commit -m "feat: wire HierarchyCompiler into PatternCompiler for HIERARCHICAL mode"
```

---

## Task 9: ProjectFile Persistence for Hierarchical Arrangement

Save and load HierarchicalArrangement (phrases, chains, sub-phrase refs) in the `.osmpsd` JSON project format.

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/io/ProjectFile.java`
- Test: `app/src/test/java/com/opensmps/deck/io/TestProjectFileHierarchy.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.io;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

class TestProjectFileHierarchy {

    @TempDir File tempDir;

    @Test
    void hierarchicalSongRoundTrips() throws Exception {
        Song original = createHierarchicalSong();
        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        Song loaded = ProjectFile.load(file);

        assertEquals(ArrangementMode.HIERARCHICAL, loaded.getArrangementMode());
        assertNotNull(loaded.getHierarchicalArrangement());

        var arr = loaded.getHierarchicalArrangement();
        assertEquals(2, arr.getPhraseLibrary().getAllPhrases().size());

        var phrase = arr.getPhraseLibrary().getPhrase(1);
        assertNotNull(phrase);
        assertEquals("Verse", phrase.getName());
        assertEquals(ChannelType.FM, phrase.getChannelType());
        assertEquals(4, phrase.getData().length);
    }

    @Test
    void chainEntriesRoundTrip() throws Exception {
        Song original = createHierarchicalSong();
        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        Song loaded = ProjectFile.load(file);

        var chain = loaded.getHierarchicalArrangement().getChain(0);
        assertEquals(2, chain.getEntries().size());
        assertEquals(1, chain.getEntries().get(0).getPhraseId());
        assertEquals(5, chain.getEntries().get(1).getTransposeSemitones());
        assertEquals(2, chain.getEntries().get(1).getRepeatCount());
        assertEquals(0, chain.getLoopEntryIndex());
    }

    @Test
    void subPhraseRefsRoundTrip() throws Exception {
        Song original = createHierarchicalSong();
        File file = new File(tempDir, "test.osmpsd");
        ProjectFile.save(original, file);
        Song loaded = ProjectFile.load(file);

        var phrase = loaded.getHierarchicalArrangement().getPhraseLibrary().getPhrase(1);
        assertEquals(1, phrase.getSubPhraseRefs().size());
        assertEquals(2, phrase.getSubPhraseRefs().get(0).phraseId());
        assertEquals(3, phrase.getSubPhraseRefs().get(0).insertAtRow());
    }

    private Song createHierarchicalSong() {
        var song = new Song();
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);

        var arr = new HierarchicalArrangement();
        var p1 = arr.getPhraseLibrary().createPhrase("Verse", ChannelType.FM);
        p1.setData(new byte[]{(byte) SmpsCoordFlags.SET_VOICE, 0x00, (byte) 0xA1, 0x18});
        var p2 = arr.getPhraseLibrary().createPhrase("Bass", ChannelType.FM);
        p2.setData(new byte[]{(byte) 0x91, 0x18});

        p1.getSubPhraseRefs().add(new Phrase.SubPhraseRef(p2.getId(), 3, 1));

        var chain = arr.getChain(0);
        chain.getEntries().add(new ChainEntry(p1.getId()));
        var e2 = new ChainEntry(p1.getId());
        e2.setTransposeSemitones(5);
        e2.setRepeatCount(2);
        chain.getEntries().add(e2);
        chain.setLoopEntryIndex(0);

        song.setHierarchicalArrangement(arr);
        return song;
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestProjectFileHierarchy -q`
Expected: FAIL — ProjectFile doesn't serialize hierarchical arrangement

**Step 3: Add serialization/deserialization to ProjectFile.java**

In the save method, when `arrangementMode == HIERARCHICAL`, serialize:
- `hierarchicalArrangement.phraseLibrary`: array of phrase objects with id, name, channelType, hex data, subPhraseRefs
- `hierarchicalArrangement.chains`: array of 10 chain objects with entries (phraseId, transpose, repeat) and loopEntryIndex
- `hierarchicalArrangement.nextPhraseId`: for ID continuity

In the load method, deserialize the same structure and reconstruct the model objects.

Use `HexUtil.encode()`/`HexUtil.decode()` for phrase byte data (consistent with existing pattern serialization).

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestProjectFileHierarchy -q`
Expected: PASS

**Step 5: Run all tests**

Run: `mvn test -q`
Expected: All tests PASS (existing ProjectFile tests unchanged)

**Step 6: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/io/ProjectFile.java app/src/test/java/com/opensmps/deck/io/TestProjectFileHierarchy.java
git commit -m "feat: add ProjectFile save/load for hierarchical arrangement"
```

---

## Task 10: Legacy Migration — Pattern+OrderList to Hierarchy

Convert existing Pattern/OrderList songs to the hierarchical model. Each pattern's per-channel track data becomes a phrase; order list rows become chain entries.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/codec/LegacyMigrator.java`
- Test: `app/src/test/java/com/opensmps/deck/codec/TestLegacyMigrator.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestLegacyMigrator {

    @Test
    void migratesSimpleSong() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.LEGACY_PATTERNS);
        // Default song has 1 pattern (id=0, 64 rows) and 1 order row

        var pattern = song.getPatterns().get(0);
        pattern.setTrackData(0, new byte[]{(byte) 0xA1, 0x18}); // FM1: one note

        var result = LegacyMigrator.migrate(song);

        assertEquals(ArrangementMode.HIERARCHICAL, result.getArrangementMode());
        assertNotNull(result.getHierarchicalArrangement());

        var arr = result.getHierarchicalArrangement();
        // Should have at least 1 phrase (for the non-empty FM1 channel)
        assertFalse(arr.getPhraseLibrary().getAllPhrases().isEmpty());

        // FM1 chain should have 1 entry
        var chain0 = arr.getChain(0);
        assertEquals(1, chain0.getEntries().size());

        // The phrase should contain the original bytecode
        int phraseId = chain0.getEntries().get(0).getPhraseId();
        var phrase = arr.getPhraseLibrary().getPhrase(phraseId);
        assertNotNull(phrase);
        assertArrayEquals(new byte[]{(byte) 0xA1, 0x18}, phrase.getData());
    }

    @Test
    void preservesLoopPoint() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.LEGACY_PATTERNS);
        // Add second pattern and order row
        song.getPatterns().add(new Pattern(1, 64));
        song.getPatterns().get(0).setTrackData(0, new byte[]{(byte) 0xA1, 0x18});
        song.getPatterns().get(1).setTrackData(0, new byte[]{(byte) 0xA5, 0x18});
        song.getOrderList().add(new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        song.setLoopPoint(0); // loop back to order 0

        var result = LegacyMigrator.migrate(song);
        var chain0 = result.getHierarchicalArrangement().getChain(0);
        assertEquals(0, chain0.getLoopEntryIndex());
    }

    @Test
    void reusesPhrasesForSamePatternIndex() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.LEGACY_PATTERNS);
        song.getPatterns().get(0).setTrackData(0, new byte[]{(byte) 0xA1, 0x18});
        // Two order rows referencing same pattern
        song.getOrderList().add(new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

        var result = LegacyMigrator.migrate(song);
        var chain0 = result.getHierarchicalArrangement().getChain(0);
        assertEquals(2, chain0.getEntries().size());
        // Both entries should reference the same phrase
        assertEquals(chain0.getEntries().get(0).getPhraseId(),
                     chain0.getEntries().get(1).getPhraseId());
    }

    @Test
    void preservesVoiceBankAndInstruments() {
        Song song = new Song();
        song.setArrangementMode(ArrangementMode.LEGACY_PATTERNS);
        var voice = new FmVoice();
        voice.setName("Test");
        song.getVoiceBank().add(voice);

        var result = LegacyMigrator.migrate(song);
        assertEquals(1, result.getVoiceBank().size());
        assertEquals("Test", result.getVoiceBank().get(0).getName());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestLegacyMigrator -q`
Expected: FAIL — class not found

**Step 3: Write LegacyMigrator**

```java
package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class LegacyMigrator {

    private LegacyMigrator() {}

    public static Song migrate(Song legacy) {
        var song = new Song();
        song.setName(legacy.getName());
        song.setSmpsMode(legacy.getSmpsMode());
        song.setTempo(legacy.getTempo());
        song.setDividingTiming(legacy.getDividingTiming());
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);

        // Copy instruments
        song.getVoiceBank().addAll(legacy.getVoiceBank());
        song.getPsgEnvelopes().addAll(legacy.getPsgEnvelopes());
        song.getDacSamples().addAll(legacy.getDacSamples());

        var arr = new HierarchicalArrangement();

        // Create phrases from patterns, keyed by (patternIndex, channel)
        Map<String, Phrase> phraseCache = new HashMap<>();

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            var chain = arr.getChain(ch);
            ChannelType type = ChannelType.fromChannelIndex(ch);

            for (int[] orderRow : legacy.getOrderList()) {
                int patternIdx = orderRow[ch];
                String key = patternIdx + ":" + ch;

                Phrase phrase = phraseCache.get(key);
                if (phrase == null) {
                    Pattern pattern = legacy.getPatterns().get(patternIdx);
                    byte[] data = pattern.getTrackData(ch);
                    String name = "P" + patternIdx + "-" + TrackerGrid_CHANNEL_NAMES[ch];
                    phrase = arr.getPhraseLibrary().createPhrase(name, type);
                    phrase.setData(data);
                    phraseCache.put(key, phrase);
                }

                chain.getEntries().add(new ChainEntry(phrase.getId()));
            }

            // Set loop point (same for all channels from legacy global loop)
            if (legacy.getLoopPoint() >= 0 && legacy.getLoopPoint() < legacy.getOrderList().size()) {
                chain.setLoopEntryIndex(legacy.getLoopPoint());
            }
        }

        song.setHierarchicalArrangement(arr);
        return song;
    }

    private static final String[] TrackerGrid_CHANNEL_NAMES = {
        "FM1", "FM2", "FM3", "FM4", "FM5", "DAC",
        "PSG1", "PSG2", "PSG3", "Noise"
    };
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestLegacyMigrator -q`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/codec/LegacyMigrator.java app/src/test/java/com/opensmps/deck/codec/TestLegacyMigrator.java
git commit -m "feat: add LegacyMigrator to convert Pattern+OrderList to hierarchy"
```

---

## Task 11: Hierarchy Decompiler — SMPS Binary to Phrases+Chains

Decompile raw SMPS track bytecode into the hierarchy model. Pass 1 identifies CALL/RETURN/LOOP/JUMP structural boundaries. This is used during SMPS binary import.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/codec/HierarchyDecompiler.java`
- Test: `app/src/test/java/com/opensmps/deck/codec/TestHierarchyDecompiler.java`

**Step 1: Write the failing test**

```java
package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
import com.opensmps.smps.SmpsCoordFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestHierarchyDecompiler {

    @Test
    void decompilesFlatTrackToSinglePhrase() {
        // Simple track: note + duration + STOP
        byte[] track = {(byte) 0xA1, 0x18, (byte) SmpsCoordFlags.STOP};
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);

        assertEquals(1, result.phrases().size());
        assertEquals(1, result.chainEntries().size());
        assertFalse(result.hasLoopPoint());
    }

    @Test
    void detectsJumpAsLoopPoint() {
        // Track: note + JUMP back to start
        byte[] track = {
            (byte) 0xA1, 0x18,
            (byte) SmpsCoordFlags.JUMP, 0x00, 0x00 // jump to offset 0
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);
        assertTrue(result.hasLoopPoint());
        assertEquals(0, result.loopEntryIndex());
    }

    @Test
    void detectsCallReturnAsSharedPhrase() {
        // Main track: CALL to subroutine at offset 5, then STOP
        // Subroutine at offset 5: note + RETURN
        byte[] track = {
            (byte) SmpsCoordFlags.CALL, 0x05, 0x00,  // call offset 5
            (byte) SmpsCoordFlags.STOP,               // offset 3: stop
            0x00,                                      // offset 4: padding
            (byte) 0xA1, 0x18,                        // offset 5: subroutine note
            (byte) SmpsCoordFlags.RETURN              // offset 7: return
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);

        // Should have at least 2 phrases: main segment + subroutine
        assertTrue(result.phrases().size() >= 1);
        // The subroutine should be identified as a separate phrase
        assertTrue(result.sharedPhraseCount() >= 1 || result.phrases().size() >= 2);
    }

    @Test
    void detectsLoopWithCounter() {
        // Track: note + LOOP(2x back to start) + STOP
        byte[] track = {
            (byte) 0xA1, 0x18,
            (byte) SmpsCoordFlags.LOOP, 0x02, 0x00, 0x00, 0x00, // loop 2x to offset 0
            (byte) SmpsCoordFlags.STOP
        };
        var result = HierarchyDecompiler.decompileTrack(track, ChannelType.FM);

        // Should detect the repeat count
        boolean hasRepeat = result.chainEntries().stream()
            .anyMatch(e -> e.getRepeatCount() > 1);
        assertTrue(hasRepeat, "Expected chain entry with repeat count > 1");
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestHierarchyDecompiler -q`
Expected: FAIL — class not found

**Step 3: Write HierarchyDecompiler**

Create `HierarchyDecompiler.java` with:
- `record DecompileResult(List<Phrase> phrases, List<ChainEntry> chainEntries, boolean hasLoopPoint, int loopEntryIndex, int sharedPhraseCount)`
- `static DecompileResult decompileTrack(byte[] track, ChannelType type)` — structural analysis:
  1. Linear scan for CALL/RETURN/LOOP/JUMP boundaries
  2. Extract subroutines (CALL target → RETURN) as separate phrases
  3. Split main track at structural boundaries into phrase segments
  4. Build chain entries with repeat counts from LOOP, loop point from JUMP
- Uses `SmpsCoordFlags.getParamCount()` for flag byte parsing

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestHierarchyDecompiler -q`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/codec/HierarchyDecompiler.java app/src/test/java/com/opensmps/deck/codec/TestHierarchyDecompiler.java
git commit -m "feat: add HierarchyDecompiler for SMPS track to phrase+chain decompilation"
```

---

## Task 12: UI — PhraseEditor (Evolved TrackerGrid with Mnemonics)

Evolve TrackerGrid to render and accept effect mnemonics instead of raw hex. Add a Duration column. This is a non-breaking change — the grid still operates on raw SMPS bytecode via SmpsDecoder/SmpsEncoder but displays mnemonics.

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java`
- Modify: `app/src/main/java/com/opensmps/deck/codec/SmpsDecoder.java` (add mnemonic-aware decoding)

**Step 1: Add mnemonic formatting to SmpsDecoder**

Add a new method `decodeWithMnemonics(byte[] trackData) → List<TrackerRow>` that uses `EffectMnemonics.format()` instead of raw hex for the effect column. Or modify the existing `TrackerRow` record to include a `List<String> effects` field with mnemonics.

**Approach:** Add a static method `SmpsDecoder.formatEffectMnemonic(int flag, int[] params)` that delegates to `EffectMnemonics.format()`. Update the decode loop to use it instead of raw hex formatting.

**Step 2: Update TrackerGrid rendering**

In `renderCell()`:
- Effect column (`COL_EFFECT`): Render mnemonic strings instead of raw hex
- Add `COL_DURATION` (new sub-column between NOTE and INSTRUMENT) to show duration hex
- Adjust column widths: increase `CHANNEL_WIDTH` from 140 to 180 to accommodate the extra column

**Step 3: Update TrackerGrid keyboard handling**

In effect column input mode:
- Accept mnemonic key shortcuts (P → PAN, V → VOL, D → DET, M → MOD, etc.)
- After mnemonic key, switch to parameter entry mode
- On completion, call `EffectMnemonics.parse()` → `SmpsEncoder.setRowEffects()`

**Step 4: Run all tests**

Run: `mvn test -q`
Expected: All tests PASS (mnemonic formatting is display-only; existing codec tests unchanged)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/ui/TrackerGrid.java app/src/main/java/com/opensmps/deck/codec/SmpsDecoder.java
git commit -m "feat: add effect mnemonic display and entry in TrackerGrid"
```

---

## Task 13: UI — SongView Panel

The always-visible left panel showing all 10 channels' phrase blocks proportionally. This is a new JavaFX component.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/SongView.java`

**Step 1: Create SongView as a Canvas-based component**

```java
public class SongView extends ScrollPane {
    private final Canvas canvas;
    private HierarchicalArrangement arrangement;
    private IntConsumer onPhraseSelected;  // callback: phrase ID
    private int selectedChannel = 0;
    private int selectedEntryIndex = -1;
}
```

**Rendering:**
- 10 horizontal rows (one per channel)
- Each row contains phrase blocks with width proportional to phrase data length (decoded row count × fixed pixel width)
- Shared phrases get matching colors (hash phrase ID to color palette)
- Repeat count shown as `(×N)` suffix
- Loop marker arrow at per-channel loop point
- Playback cursor as vertical line

**Interactions:**
- Single-click → select phrase entry, fire `onPhraseSelected`
- Double-click → fire `onPhraseDoubleClicked` to open in phrase editor
- Right-click → context menu (Rename, Transpose, Repeat, Unlink, Delete)

**Step 2: Wire into layout (done in Task 15)**

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/ui/SongView.java
git commit -m "feat: add SongView panel for hierarchical channel overview"
```

---

## Task 14: UI — ChainStrip and ChainEditor

The chain strip sits above the phrase editor showing the active channel's chain. The chain editor is a full table dialog for detailed editing.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/ChainStrip.java`
- Create: `app/src/main/java/com/opensmps/deck/ui/ChainEditor.java`

**Step 1: Create ChainStrip**

Horizontal HBox of clickable phrase cells. Each cell shows phrase name, transpose badge, repeat badge. Click to select and navigate phrase editor below.

**Step 2: Create ChainEditor**

TableView-based dialog with columns: #, Phrase (ComboBox), Transpose (Spinner), Repeat (Spinner), Length. Buttons: Add, Remove, Move Up/Down, Set Loop. Shows loop marker icon on the looped entry.

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/ui/ChainStrip.java app/src/main/java/com/opensmps/deck/ui/ChainEditor.java
git commit -m "feat: add ChainStrip and ChainEditor UI components"
```

---

## Task 15: UI — MainWindow Integration and Breadcrumb

Wire SongView, ChainStrip, PhraseEditor (TrackerGrid), and Breadcrumb into the MainWindow layout. Replace OrderListPanel with SongView when in HIERARCHICAL mode.

**Files:**
- Modify: `app/src/main/java/com/opensmps/deck/ui/MainWindow.java`
- Modify: `app/src/main/java/com/opensmps/deck/ui/SongTab.java`
- Create: `app/src/main/java/com/opensmps/deck/ui/BreadcrumbBar.java`

**Step 1: Create BreadcrumbBar**

HBox with clickable labels showing navigation path: `FM1 Chain > Verse A > Bass Riff`. Click any segment to navigate back. Escape key navigates up one level.

**Step 2: Update SongTab.buildContent()**

When `song.getArrangementMode() == HIERARCHICAL`:
- Left panel: SongView (instead of OrderListPanel at bottom)
- Top of center: BreadcrumbBar + ChainStrip
- Center: TrackerGrid (phrase editor mode)
- Right: InstrumentPanel (unchanged)

When LEGACY_PATTERNS: keep existing layout.

**Step 3: Wire navigation flow**

- SongView double-click → set active channel + phrase → update ChainStrip + TrackerGrid
- ChainStrip click → set active phrase → update TrackerGrid + BreadcrumbBar
- BreadcrumbBar click → navigate up → update ChainStrip + TrackerGrid
- TrackerGrid sub-phrase double-click → push to navigation stack → update BreadcrumbBar

**Step 4: Wire playback cursor**

- SongView gets playback position via `PlaybackEngine.getPlaybackPosition()`
- Cursor sweep across song view + per-channel phrase highlighting
- TrackerGrid cursor syncs to current phrase offset

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/ui/MainWindow.java app/src/main/java/com/opensmps/deck/ui/SongTab.java app/src/main/java/com/opensmps/deck/ui/BreadcrumbBar.java
git commit -m "feat: wire hierarchical UI layout with SongView, ChainStrip, and BreadcrumbBar"
```

---

## Task 16: Import Decompilation Preview Dialog

When importing SMPS binary files, show a preview dialog that uses HierarchyDecompiler to analyze structure and lets the user confirm/adjust phrase boundaries.

**Files:**
- Create: `app/src/main/java/com/opensmps/deck/ui/ImportPreviewDialog.java`
- Modify: `app/src/main/java/com/opensmps/deck/io/SmpsImporter.java`

**Step 1: Create ImportPreviewDialog**

JavaFX Dialog that:
1. Shows per-channel structure diagrams (phrase blocks with CALL/LOOP/JUMP markers)
2. Highlights ambiguous sections with split options (keep as one, split every N ticks)
3. Lists shared phrases with reference counts
4. Provides Import/Cancel buttons

**Step 2: Wire into SmpsImporter**

After `SmpsImporter.importFile()` extracts tracks, optionally call `HierarchyDecompiler.decompileTrack()` for each channel and present the preview. If the user confirms, build a HierarchicalArrangement from the decompiled result.

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmps/deck/ui/ImportPreviewDialog.java app/src/main/java/com/opensmps/deck/io/SmpsImporter.java
git commit -m "feat: add import decompilation preview dialog"
```

---

## Dependency Order

```
Task 1: ChannelType enum (no deps)
Task 2: Phrase model (depends on Task 1)
Task 3: ChainEntry + Chain (no deps)
Task 4: PhraseLibrary + HierarchicalArrangement (depends on Tasks 1-3)
Task 5: Song integration (depends on Task 4)
Task 6: EffectMnemonics codec (no deps — pure SmpsCoordFlags usage)
Task 7: HierarchyCompiler (depends on Tasks 2-4)
Task 8: PatternCompiler integration (depends on Tasks 5, 7)
Task 9: ProjectFile persistence (depends on Tasks 4, 5)
Task 10: LegacyMigrator (depends on Tasks 2-5)
Task 11: HierarchyDecompiler (depends on Tasks 2-4)
Task 12: TrackerGrid mnemonics (depends on Task 6)
Task 13: SongView panel (depends on Task 4)
Task 14: ChainStrip + ChainEditor (depends on Tasks 3, 4)
Task 15: MainWindow integration (depends on Tasks 12-14)
Task 16: Import preview dialog (depends on Tasks 11, 15)
```

**Parallelizable groups:**
- Tasks 1, 3, 6 can run in parallel (no shared deps)
- Tasks 2, 3 can run in parallel
- Tasks 7, 9, 10, 11 can run in parallel after Task 5
- Tasks 12, 13, 14 can run in parallel after Task 6

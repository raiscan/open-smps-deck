# Phase 7: Multi-Document & Import Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add tab-based multi-document editing, cross-song copy-paste with instrument resolution, ROM/SMPSPlay voice import browser, and SMPS binary song import.

**Architecture:** MainWindow converts from single-song BorderPane to TabPane. Each tab wraps a SongTab containing its own TrackerGrid, OrderListPanel, InstrumentPanel, and Song reference. ClipboardData tracks its source Song for cross-paste detection. A byte-scanning utility rewrites instrument indices when pasting between songs. RomVoiceImporter scans directories of SMPS `.bin` files to extract deduplicated voice lists for import.

**Tech Stack:** Java 21, JavaFX (TabPane, Dialog, TableView, FileChooser), existing SmpsImporter/SmpsEncoder/SmpsCoordFlags

---

### Task 24: SongTab Class

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/ui/SongTab.java`
- Test: `app/src/test/java/com/opensmpsdeck/ui/TestSongTab.java`

**Step 1: Write the failing test**

```java
package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSongTab {

    @Test
    void testNewSongTabHasDefaultSong() {
        SongTab tab = new SongTab();
        assertNotNull(tab.getSong());
        assertEquals("Untitled", tab.getSong().getName());
        assertNull(tab.getFile());
        assertFalse(tab.isDirty());
    }

    @Test
    void testSongTabWithExistingSong() {
        Song song = new Song();
        song.setName("Test Song");
        SongTab tab = new SongTab(song);
        assertEquals("Test Song", tab.getSong().getName());
    }

    @Test
    void testSongTabFileTracking() {
        SongTab tab = new SongTab();
        assertNull(tab.getFile());
        java.io.File file = new java.io.File("test.osmpsd");
        tab.setFile(file);
        assertEquals(file, tab.getFile());
    }

    @Test
    void testSongTabDirtyFlag() {
        SongTab tab = new SongTab();
        assertFalse(tab.isDirty());
        tab.setDirty(true);
        assertTrue(tab.isDirty());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl app -Dtest=TestSongTab -q`
Expected: FAIL — class SongTab does not exist

**Step 3: Write minimal implementation**

```java
package com.opensmpsdeck.ui;

import com.opensmpsdeck.model.Song;

import java.io.File;

/**
 * Represents a single song editor tab. Holds the Song model, file reference,
 * dirty flag, and the UI components (TrackerGrid, OrderListPanel, InstrumentPanel)
 * for this editor context.
 *
 * <p>UI components are lazily created when {@link #buildContent()} is called,
 * allowing the model to be tested without JavaFX.
 */
public class SongTab {

    private Song song;
    private File file;
    private boolean dirty;

    // UI components (null until buildContent() is called)
    private TrackerGrid trackerGrid;
    private OrderListPanel orderListPanel;
    private InstrumentPanel instrumentPanel;

    /** Create a new tab with an empty default Song. */
    public SongTab() {
        this(new Song());
    }

    /** Create a tab wrapping an existing Song. */
    public SongTab(Song song) {
        this.song = song;
    }

    public Song getSong() { return song; }
    public void setSong(Song song) { this.song = song; }

    public File getFile() { return file; }
    public void setFile(File file) { this.file = file; }

    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }

    public TrackerGrid getTrackerGrid() { return trackerGrid; }
    public OrderListPanel getOrderListPanel() { return orderListPanel; }
    public InstrumentPanel getInstrumentPanel() { return instrumentPanel; }

    /**
     * Returns the display title for this tab.
     * Shows the file name if saved, otherwise "Untitled". Appends "*" if dirty.
     */
    public String getTitle() {
        String name = file != null ? file.getName() : song.getName();
        return dirty ? name + " *" : name;
    }

    /**
     * Build the JavaFX UI components for this tab.
     * Must be called on the JavaFX application thread.
     */
    public void buildContent() {
        trackerGrid = new TrackerGrid();
        trackerGrid.setSong(song);

        orderListPanel = new OrderListPanel(song);
        instrumentPanel = new InstrumentPanel(song);

        trackerGrid.setInstrumentPanel(instrumentPanel);
    }

    /**
     * Refresh all UI panels from the current Song model.
     */
    public void refreshAllPanels() {
        if (trackerGrid != null) {
            trackerGrid.setSong(song);
            orderListPanel.setSong(song);
            instrumentPanel.setSong(song);
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl app -Dtest=TestSongTab -q`
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/SongTab.java app/src/test/java/com/opensmpsdeck/ui/TestSongTab.java
git commit -m "feat: add SongTab model for multi-document support"
```

---

### Task 25: MainWindow TabPane Conversion

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TransportBar.java`

**Step 1: Modify TransportBar to support song switching**

TransportBar currently stores a `final Song song` reference set in the constructor. We need it to support changing the active song when tabs switch.

Add to `TransportBar.java`:
- Change `private final Song song;` to `private Song song;`
- Add `setSong(Song song)` method that updates the reference and syncs the spinners/mode selector
- In `onPlay()`, use the current `song` reference (already does this)

```java
/**
 * Update the song reference when the active tab changes.
 */
public void setSong(Song song) {
    this.song = song;
    tempoSpinner.getValueFactory().setValue(song.getTempo());
    modeSelector.setValue(song.getSmpsMode());
}
```

**Step 2: Convert MainWindow to TabPane**

Replace the single-song setup with a TabPane. Key changes:

1. Replace `Song currentSong` with `TabPane tabPane` and helpers to get the active `SongTab`
2. `setupLayout()` creates the TabPane with a [+] button tab
3. Menu actions (New/Open/Save/SaveAs/Export) operate on the active tab
4. Tab selection listener updates TransportBar and title

```java
// Fields to replace:
private final TabPane tabPane = new TabPane();
// Remove: Song currentSong, File currentFile, TrackerGrid, OrderListPanel, InstrumentPanel

// New helper methods:
private SongTab getActiveSongTab() {
    Tab selected = tabPane.getSelectionModel().getSelectedItem();
    if (selected != null && selected.getUserData() instanceof SongTab st) {
        return st;
    }
    return null;
}

private Tab createSongTabUI(SongTab songTab) {
    songTab.buildContent();

    BorderPane content = new BorderPane();
    content.setCenter(songTab.getTrackerGrid());
    content.setBottom(songTab.getOrderListPanel());
    content.setRight(songTab.getInstrumentPanel());

    // Wire order list selection
    songTab.getOrderListPanel().setOnOrderRowSelected(rowIndex -> {
        Song song = songTab.getSong();
        if (!song.getOrderList().isEmpty()) {
            int[] orderRow = song.getOrderList().get(rowIndex);
            songTab.getTrackerGrid().setCurrentPatternIndex(orderRow[0]);
        }
    });

    Tab tab = new Tab(songTab.getTitle(), content);
    tab.setUserData(songTab);
    tab.setOnClosed(e -> { /* cleanup if needed */ });
    return tab;
}

private void addNewTab(SongTab songTab) {
    Tab tab = createSongTabUI(songTab);
    // Insert before the [+] button tab (last tab)
    int insertIndex = Math.max(0, tabPane.getTabs().size() - 1);
    tabPane.getTabs().add(insertIndex, tab);
    tabPane.getSelectionModel().select(tab);
}
```

**Step 3: Update setupLayout()**

```java
private void setupLayout() {
    // Top: MenuBar + Transport bar
    MenuBar menuBar = createMenuBar();
    transportBar = new TransportBar(playbackEngine, new Song()); // placeholder
    VBox topContainer = new VBox(menuBar, transportBar);
    root.setTop(topContainer);

    // Center: TabPane
    tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
    tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
        SongTab st = getActiveSongTab();
        if (st != null) {
            transportBar.setSong(st.getSong());
            updateTitle();
        }
    });

    // Add initial empty song tab
    addNewTab(new SongTab());

    // Add [+] button tab (not closable)
    Tab plusTab = new Tab("+");
    plusTab.setClosable(false);
    tabPane.getTabs().add(plusTab);

    // When [+] is selected, create new tab and deselect [+]
    tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
        if (newTab != null && "+".equals(newTab.getText()) && newTab.getUserData() == null) {
            addNewTab(new SongTab());
        }
    });

    root.setCenter(tabPane);
}
```

**Step 4: Update menu actions**

Each action delegates to `getActiveSongTab()`:

- `onNew()` → `addNewTab(new SongTab())`
- `onOpen()` → load file, `addNewTab(new SongTab(song))`, set file on tab
- `onSave()` → `getActiveSongTab().getFile()` or fall through to Save As
- `onSaveAs()` → save, `getActiveSongTab().setFile(file)`
- `onExportSmps()` → export `getActiveSongTab().getSong()`

Add "Import SMPS..." menu item (wired in Task 30).

**Step 5: Run all tests**

Run: `mvn test -q`
Expected: All tests pass (existing tests don't depend on MainWindow internals)

**Step 6: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/MainWindow.java app/src/main/java/com/opensmpsdeck/ui/TransportBar.java
git commit -m "feat: convert MainWindow to tab-based multi-document editor"
```

---

### Task 26: ClipboardData Source Song + Cross-Paste Byte Scanner

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/model/ClipboardData.java`
- Create: `app/src/main/java/com/opensmpsdeck/codec/InstrumentRemapper.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java`
- Test: `app/src/test/java/com/opensmpsdeck/codec/TestInstrumentRemapper.java`

**Step 1: Add sourceSong to ClipboardData**

```java
// Add field and constructor parameter:
private final Song sourceSong;

public ClipboardData(byte[][] channelData, int rowCount, Song sourceSong) {
    // ... existing defensive copy logic ...
    this.sourceSong = sourceSong;
}

public Song getSourceSong() { return sourceSong; }
```

Update TrackerGrid.copySelection() to pass the current song:
```java
clipboard = new ClipboardData(channelData, rowCount, song);
```

**Step 2: Write the InstrumentRemapper failing tests**

```java
package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TestInstrumentRemapper {

    @Test
    void testScanFindsVoiceAndPsgRefs() {
        // EF 02 = SET_VOICE to index 2, F5 01 = PSG_INSTRUMENT to index 1
        byte[] data = {(byte) 0x80, 0x18, (byte) 0xEF, 0x02, (byte) 0x82, 0x18, (byte) 0xF5, 0x01};
        InstrumentRemapper.ScanResult result = InstrumentRemapper.scan(data);
        assertTrue(result.voiceIndices().contains(2));
        assertTrue(result.psgIndices().contains(1));
    }

    @Test
    void testScanEmptyData() {
        InstrumentRemapper.ScanResult result = InstrumentRemapper.scan(new byte[0]);
        assertTrue(result.voiceIndices().isEmpty());
        assertTrue(result.psgIndices().isEmpty());
    }

    @Test
    void testRewriteVoiceIndex() {
        byte[] data = {(byte) 0xEF, 0x00, (byte) 0x80, 0x18};
        Map<Integer, Integer> voiceMap = Map.of(0, 5);
        byte[] rewritten = InstrumentRemapper.rewrite(data, voiceMap, Map.of());
        assertEquals((byte) 0xEF, rewritten[0]);
        assertEquals(5, rewritten[1] & 0xFF);
    }

    @Test
    void testRewritePsgIndex() {
        byte[] data = {(byte) 0xF5, 0x03, (byte) 0x80, 0x18};
        Map<Integer, Integer> psgMap = Map.of(3, 7);
        byte[] rewritten = InstrumentRemapper.rewrite(data, Map.of(), psgMap);
        assertEquals((byte) 0xF5, rewritten[0]);
        assertEquals(7, rewritten[1] & 0xFF);
    }

    @Test
    void testAutoRemapByteIdenticalVoices() {
        Song src = new Song();
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32;
        src.getVoiceBank().add(new FmVoice("Lead", voiceData));

        Song dst = new Song();
        dst.getVoiceBank().add(new FmVoice("Bass", voiceData.clone())); // byte-identical

        Map<Integer, Integer> map = InstrumentRemapper.autoRemap(
                src.getVoiceBank(), dst.getVoiceBank(), Set.of(0));
        // Source index 0 should map to destination index 0 (byte-identical)
        assertEquals(0, map.get(0));
    }

    @Test
    void testAutoRemapNoMatch() {
        Song src = new Song();
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32;
        src.getVoiceBank().add(new FmVoice("Lead", voiceData));

        Song dst = new Song();
        byte[] differentData = new byte[25];
        differentData[0] = 0x07;
        dst.getVoiceBank().add(new FmVoice("Other", differentData));

        Map<Integer, Integer> map = InstrumentRemapper.autoRemap(
                src.getVoiceBank(), dst.getVoiceBank(), Set.of(0));
        // No byte-identical match found
        assertFalse(map.containsKey(0));
    }
}
```

**Step 3: Run tests to verify they fail**

Run: `mvn test -pl app -Dtest=TestInstrumentRemapper -q`
Expected: FAIL — class InstrumentRemapper does not exist

**Step 4: Implement InstrumentRemapper**

```java
package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmps.smps.SmpsCoordFlags;

import java.util.*;

/**
 * Scans SMPS bytecode for instrument references and rewrites indices
 * for cross-song paste operations.
 */
public class InstrumentRemapper {

    /** Result of scanning bytecode for instrument references. */
    public record ScanResult(Set<Integer> voiceIndices, Set<Integer> psgIndices) {}

    /**
     * Scan SMPS bytecode for SET_VOICE (0xEF) and PSG_INSTRUMENT (0xF5) commands.
     * Returns the set of referenced voice and PSG indices.
     */
    public static ScanResult scan(byte[] data) {
        Set<Integer> voices = new LinkedHashSet<>();
        Set<Integer> psg = new LinkedHashSet<>();
        int pos = 0;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b);
                if (b == SmpsCoordFlags.SET_VOICE && pos + 1 < data.length) {
                    voices.add(data[pos + 1] & 0xFF);
                } else if (b == SmpsCoordFlags.PSG_INSTRUMENT && pos + 1 < data.length) {
                    psg.add(data[pos + 1] & 0xFF);
                }
                pos += 1 + paramCount;
            } else {
                pos++;
            }
        }
        return new ScanResult(voices, psg);
    }

    /**
     * Rewrite instrument indices in SMPS bytecode.
     *
     * @param data the original bytecode
     * @param voiceMap old voice index -> new voice index
     * @param psgMap old PSG index -> new PSG index
     * @return new byte array with remapped indices
     */
    public static byte[] rewrite(byte[] data, Map<Integer, Integer> voiceMap, Map<Integer, Integer> psgMap) {
        byte[] result = data.clone();
        int pos = 0;
        while (pos < result.length) {
            int b = result[pos] & 0xFF;
            if (b >= 0xE0) {
                int paramCount = SmpsCoordFlags.getParamCount(b);
                if (b == SmpsCoordFlags.SET_VOICE && pos + 1 < result.length) {
                    int oldIdx = result[pos + 1] & 0xFF;
                    if (voiceMap.containsKey(oldIdx)) {
                        result[pos + 1] = (byte) voiceMap.get(oldIdx).intValue();
                    }
                } else if (b == SmpsCoordFlags.PSG_INSTRUMENT && pos + 1 < result.length) {
                    int oldIdx = result[pos + 1] & 0xFF;
                    if (psgMap.containsKey(oldIdx)) {
                        result[pos + 1] = (byte) psgMap.get(oldIdx).intValue();
                    }
                }
                pos += 1 + paramCount;
            } else {
                pos++;
            }
        }
        return result;
    }

    /**
     * Auto-remap voice indices by finding byte-identical matches in the destination.
     *
     * @param srcVoices source voice bank
     * @param dstVoices destination voice bank
     * @param neededIndices set of source voice indices to resolve
     * @return map of source index -> destination index for byte-identical matches
     */
    public static Map<Integer, Integer> autoRemap(
            List<FmVoice> srcVoices, List<FmVoice> dstVoices, Set<Integer> neededIndices) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int srcIdx : neededIndices) {
            if (srcIdx < 0 || srcIdx >= srcVoices.size()) continue;
            byte[] srcData = srcVoices.get(srcIdx).getData();
            for (int dstIdx = 0; dstIdx < dstVoices.size(); dstIdx++) {
                if (Arrays.equals(srcData, dstVoices.get(dstIdx).getData())) {
                    map.put(srcIdx, dstIdx);
                    break;
                }
            }
        }
        return map;
    }

    /**
     * Auto-remap PSG envelope indices by finding byte-identical matches.
     */
    public static Map<Integer, Integer> autoRemapPsg(
            List<PsgEnvelope> srcEnvelopes, List<PsgEnvelope> dstEnvelopes, Set<Integer> neededIndices) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int srcIdx : neededIndices) {
            if (srcIdx < 0 || srcIdx >= srcEnvelopes.size()) continue;
            byte[] srcData = srcEnvelopes.get(srcIdx).getData();
            for (int dstIdx = 0; dstIdx < dstEnvelopes.size(); dstIdx++) {
                if (Arrays.equals(srcData, dstEnvelopes.get(dstIdx).getData())) {
                    map.put(srcIdx, dstIdx);
                    break;
                }
            }
        }
        return map;
    }
}
```

**Step 5: Run tests to verify they pass**

Run: `mvn test -pl app -Dtest=TestInstrumentRemapper -q`
Expected: PASS (6 tests)

**Step 6: Update TrackerGrid.copySelection() to record source song**

In `TrackerGrid.java`, update the `copySelection()` method:
```java
clipboard = new ClipboardData(channelData, rowCount, song);
```

**Step 7: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 8: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/model/ClipboardData.java \
       app/src/main/java/com/opensmpsdeck/codec/InstrumentRemapper.java \
       app/src/test/java/com/opensmpsdeck/codec/TestInstrumentRemapper.java \
       app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java
git commit -m "feat: add instrument remapper for cross-song paste operations"
```

---

### Task 27: InstrumentResolveDialog + TrackerGrid Wiring

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/ui/InstrumentResolveDialog.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java`

**Step 1: Create InstrumentResolveDialog**

```java
package com.opensmpsdeck.ui;

import com.opensmpsdeck.codec.InstrumentRemapper;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.*;

/**
 * Modal dialog for resolving instrument references when pasting
 * between different songs. Shows unresolved voice/PSG references
 * and lets the user choose: copy, remap, or skip for each.
 */
public class InstrumentResolveDialog extends Dialog<InstrumentResolveDialog.Resolution> {

    /** Resolution result containing the final voice and PSG index mappings. */
    public record Resolution(Map<Integer, Integer> voiceMap, Map<Integer, Integer> psgMap,
                             List<FmVoice> voicesToCopy, List<PsgEnvelope> envelopesToCopy) {}

    /** Action for each unresolved instrument. */
    private enum Action { COPY, REMAP, SKIP }

    private record UnresolvedEntry(int sourceIndex, String name, Action action, int remapTarget) {}

    public InstrumentResolveDialog(Song srcSong, Song dstSong,
                                    Set<Integer> unresolvedVoices, Set<Integer> unresolvedPsg) {
        setTitle("Resolve Instruments");
        setHeaderText("Some instruments differ between songs. Choose how to handle each:");

        DialogPane pane = getDialogPane();
        pane.setPrefWidth(500);
        pane.setPrefHeight(400);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Voice resolution table
        if (!unresolvedVoices.isEmpty()) {
            Label voiceLabel = new Label("FM Voices:");
            voiceLabel.setStyle("-fx-font-weight: bold;");
            TableView<UnresolvedEntry> voiceTable = buildTable(
                    srcSong.getVoiceBank(), dstSong.getVoiceBank(), unresolvedVoices, "Voice");
            content.getChildren().addAll(voiceLabel, voiceTable);
        }

        // PSG resolution table
        if (!unresolvedPsg.isEmpty()) {
            Label psgLabel = new Label("PSG Envelopes:");
            psgLabel.setStyle("-fx-font-weight: bold;");
            TableView<UnresolvedEntry> psgTable = buildTable(
                    srcSong.getPsgEnvelopes(), dstSong.getPsgEnvelopes(), unresolvedPsg, "Env");
            content.getChildren().addAll(psgLabel, psgTable);
        }

        pane.setContent(content);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Result converter builds the Resolution from table state
        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return buildResolution(srcSong, dstSong, unresolvedVoices, unresolvedPsg);
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private <T> TableView<UnresolvedEntry> buildTable(
            List<T> srcList, List<T> dstList, Set<Integer> indices, String prefix) {
        List<UnresolvedEntry> entries = new ArrayList<>();
        for (int idx : indices) {
            String name = prefix + " " + idx;
            if (idx >= 0 && idx < srcList.size()) {
                T item = srcList.get(idx);
                if (item instanceof FmVoice v) name = v.getName();
                else if (item instanceof PsgEnvelope e) name = e.getName();
            }
            entries.add(new UnresolvedEntry(idx, name, Action.COPY, -1));
        }

        TableView<UnresolvedEntry> table = new TableView<>(FXCollections.observableArrayList(entries));
        table.setPrefHeight(150);

        TableColumn<UnresolvedEntry, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(
                String.format("%02X: %s", c.getValue().sourceIndex(), c.getValue().name())));
        nameCol.setPrefWidth(200);

        TableColumn<UnresolvedEntry, String> actionCol = new TableColumn<>("Action");
        actionCol.setCellValueFactory(c -> new SimpleStringProperty("Copy into song"));
        actionCol.setPrefWidth(250);

        table.getColumns().addAll(nameCol, actionCol);
        return table;
    }

    private Resolution buildResolution(Song srcSong, Song dstSong,
                                        Set<Integer> unresolvedVoices, Set<Integer> unresolvedPsg) {
        // For MVP: all unresolved instruments are copied into the destination song
        Map<Integer, Integer> voiceMap = new HashMap<>();
        Map<Integer, Integer> psgMap = new HashMap<>();
        List<FmVoice> voicesToCopy = new ArrayList<>();
        List<PsgEnvelope> envelopesToCopy = new ArrayList<>();

        int nextVoiceIdx = dstSong.getVoiceBank().size();
        for (int srcIdx : unresolvedVoices) {
            if (srcIdx >= 0 && srcIdx < srcSong.getVoiceBank().size()) {
                FmVoice voice = srcSong.getVoiceBank().get(srcIdx);
                voicesToCopy.add(new FmVoice(voice.getName(), voice.getData()));
                voiceMap.put(srcIdx, nextVoiceIdx++);
            }
        }

        int nextPsgIdx = dstSong.getPsgEnvelopes().size();
        for (int srcIdx : unresolvedPsg) {
            if (srcIdx >= 0 && srcIdx < srcSong.getPsgEnvelopes().size()) {
                PsgEnvelope env = srcSong.getPsgEnvelopes().get(srcIdx);
                envelopesToCopy.add(new PsgEnvelope(env.getName(), env.getData()));
                psgMap.put(srcIdx, nextPsgIdx++);
            }
        }

        return new Resolution(voiceMap, psgMap, voicesToCopy, envelopesToCopy);
    }
}
```

**Step 2: Wire cross-paste into TrackerGrid.pasteAtCursor()**

In `TrackerGrid.pasteAtCursor()`, after the null/song check and before mutation, add cross-song resolution:

```java
private void pasteAtCursor() {
    if (clipboard == null || song == null) return;

    // Cross-song paste resolution
    byte[][] pasteChannelData = new byte[clipboard.getChannelCount()][];
    for (int ch = 0; ch < clipboard.getChannelCount(); ch++) {
        pasteChannelData[ch] = clipboard.getChannelData(ch);
    }

    if (clipboard.getSourceSong() != null && clipboard.getSourceSong() != song) {
        pasteChannelData = resolveCrossPaste(pasteChannelData, clipboard.getSourceSong(), song);
        if (pasteChannelData == null) return; // user cancelled
    }

    Pattern pattern = song.getPatterns().get(currentPatternIndex);

    // ... existing atomic undo + paste logic, but use pasteChannelData[ch] instead of clipboard.getChannelData(ch) ...
}

private byte[][] resolveCrossPaste(byte[][] channelData, Song srcSong, Song dstSong) {
    // Scan all channels for instrument references
    Set<Integer> allVoices = new LinkedHashSet<>();
    Set<Integer> allPsg = new LinkedHashSet<>();
    for (byte[] data : channelData) {
        InstrumentRemapper.ScanResult scan = InstrumentRemapper.scan(data);
        allVoices.addAll(scan.voiceIndices());
        allPsg.addAll(scan.psgIndices());
    }

    if (allVoices.isEmpty() && allPsg.isEmpty()) return channelData;

    // Auto-remap byte-identical instruments
    Map<Integer, Integer> voiceMap = InstrumentRemapper.autoRemap(
            srcSong.getVoiceBank(), dstSong.getVoiceBank(), allVoices);
    Map<Integer, Integer> psgMap = InstrumentRemapper.autoRemapPsg(
            srcSong.getPsgEnvelopes(), dstSong.getPsgEnvelopes(), allPsg);

    // Find unresolved
    Set<Integer> unresolvedVoices = new LinkedHashSet<>(allVoices);
    unresolvedVoices.removeAll(voiceMap.keySet());
    Set<Integer> unresolvedPsg = new LinkedHashSet<>(allPsg);
    unresolvedPsg.removeAll(psgMap.keySet());

    // If all resolved, just rewrite
    if (unresolvedVoices.isEmpty() && unresolvedPsg.isEmpty()) {
        return rewriteAll(channelData, voiceMap, psgMap);
    }

    // Show dialog for unresolved
    InstrumentResolveDialog dialog = new InstrumentResolveDialog(
            srcSong, dstSong, unresolvedVoices, unresolvedPsg);
    Optional<InstrumentResolveDialog.Resolution> result = dialog.showAndWait();
    if (result.isEmpty()) return null; // cancelled

    InstrumentResolveDialog.Resolution res = result.get();
    // Copy instruments into destination song
    dstSong.getVoiceBank().addAll(res.voicesToCopy());
    dstSong.getPsgEnvelopes().addAll(res.envelopesToCopy());

    // Merge all mappings
    voiceMap.putAll(res.voiceMap());
    psgMap.putAll(res.psgMap());

    return rewriteAll(channelData, voiceMap, psgMap);
}

private byte[][] rewriteAll(byte[][] channelData, Map<Integer, Integer> voiceMap, Map<Integer, Integer> psgMap) {
    byte[][] result = new byte[channelData.length][];
    for (int i = 0; i < channelData.length; i++) {
        result[i] = InstrumentRemapper.rewrite(channelData[i], voiceMap, psgMap);
    }
    return result;
}
```

**Step 3: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/InstrumentResolveDialog.java \
       app/src/main/java/com/opensmpsdeck/ui/TrackerGrid.java
git commit -m "feat: add cross-song paste with instrument resolution dialog"
```

---

### Task 28: ImportableVoice + RomVoiceImporter

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/io/ImportableVoice.java`
- Create: `app/src/main/java/com/opensmpsdeck/io/RomVoiceImporter.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/TestRomVoiceImporter.java`

**Step 1: Write the failing tests**

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestRomVoiceImporter {

    @Test
    void testImportableVoiceRecord() {
        byte[] data = new byte[25];
        data[0] = 0x32; // algo 2, fb 6
        ImportableVoice voice = new ImportableVoice("TestSong", 0, data, 2);
        assertEquals("TestSong", voice.sourceSong());
        assertEquals(0, voice.originalIndex());
        assertEquals(2, voice.algorithm());
        assertArrayEquals(data, voice.voiceData());
    }

    @Test
    void testScanSingleFile(@TempDir Path tempDir) throws Exception {
        // Create a minimal SMPS binary with 1 FM channel and 1 voice
        Song song = new Song();
        song.setTempo(120);
        song.setDividingTiming(1);
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32;
        song.getVoiceBank().add(new FmVoice("Lead", voiceData));
        song.getPatterns().get(0).setTrackData(0, new byte[]{(byte) 0x80, 0x18});

        SmpsExporter exporter = new SmpsExporter();
        File binFile = tempDir.resolve("test.bin").toFile();
        exporter.export(song, binFile);

        RomVoiceImporter importer = new RomVoiceImporter();
        List<ImportableVoice> voices = importer.scanDirectory(tempDir.toFile());
        assertFalse(voices.isEmpty());
        assertEquals(0x32, voices.get(0).voiceData()[0] & 0xFF);
        assertEquals("test", voices.get(0).sourceSong());
    }

    @Test
    void testDeduplication(@TempDir Path tempDir) throws Exception {
        // Create two SMPS files with identical voices
        byte[] voiceData = new byte[25];
        voiceData[0] = 0x32;

        for (String name : new String[]{"song1.bin", "song2.bin"}) {
            Song song = new Song();
            song.setTempo(120);
            song.setDividingTiming(1);
            song.getVoiceBank().add(new FmVoice("Lead", voiceData.clone()));
            song.getPatterns().get(0).setTrackData(0, new byte[]{(byte) 0x80, 0x18});
            new SmpsExporter().export(song, tempDir.resolve(name).toFile());
        }

        RomVoiceImporter importer = new RomVoiceImporter();
        List<ImportableVoice> voices = importer.scanDirectory(tempDir.toFile());
        // Should deduplicate: only 1 unique voice
        assertEquals(1, voices.size());
    }

    @Test
    void testEmptyDirectory(@TempDir Path tempDir) {
        RomVoiceImporter importer = new RomVoiceImporter();
        List<ImportableVoice> voices = importer.scanDirectory(tempDir.toFile());
        assertTrue(voices.isEmpty());
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -pl app -Dtest=TestRomVoiceImporter -q`
Expected: FAIL — classes do not exist

**Step 3: Create ImportableVoice record**

```java
package com.opensmpsdeck.io;

/**
 * A voice discovered during ROM/SMPS file scanning, with provenance metadata.
 *
 * @param sourceSong name of the SMPS file this voice came from
 * @param originalIndex the voice's index within the source song's voice bank
 * @param voiceData the 25-byte SMPS FM voice data
 * @param algorithm the FM algorithm (0-7), extracted from voiceData[0] bits 0-2
 */
public record ImportableVoice(String sourceSong, int originalIndex, byte[] voiceData, int algorithm) {

    public ImportableVoice {
        voiceData = voiceData.clone(); // defensive copy
    }

    @Override
    public byte[] voiceData() {
        return voiceData.clone(); // defensive copy on read
    }
}
```

**Step 4: Create RomVoiceImporter**

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.Song;

import java.io.File;
import java.util.*;

/**
 * Scans a directory of SMPS binary (.bin) files and extracts all FM voices
 * with deduplication. Uses {@link SmpsImporter} for parsing.
 */
public class RomVoiceImporter {

    private final SmpsImporter importer = new SmpsImporter();

    /**
     * Scan all .bin files in a directory and extract deduplicated FM voices.
     *
     * @param directory the directory to scan
     * @return list of unique importable voices
     */
    public List<ImportableVoice> scanDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return List.of();
        }

        File[] binFiles = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".bin"));
        if (binFiles == null || binFiles.length == 0) {
            return List.of();
        }

        // Use LinkedHashMap to preserve discovery order while deduplicating
        Map<String, ImportableVoice> uniqueVoices = new LinkedHashMap<>();

        for (File file : binFiles) {
            try {
                Song song = importer.importFile(file);
                String songName = file.getName().replaceAll("\\.[^.]+$", "");

                for (int i = 0; i < song.getVoiceBank().size(); i++) {
                    FmVoice voice = song.getVoiceBank().get(i);
                    byte[] data = voice.getData();
                    String key = bytesToHexKey(data);
                    if (!uniqueVoices.containsKey(key)) {
                        int algorithm = data[0] & 0x07;
                        uniqueVoices.put(key, new ImportableVoice(songName, i, data, algorithm));
                    }
                }
            } catch (Exception e) {
                // Skip unparseable files
            }
        }

        return new ArrayList<>(uniqueVoices.values());
    }

    private static String bytesToHexKey(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
```

**Step 5: Run tests to verify they pass**

Run: `mvn test -pl app -Dtest=TestRomVoiceImporter -q`
Expected: PASS (4 tests)

**Step 6: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 7: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/io/ImportableVoice.java \
       app/src/main/java/com/opensmpsdeck/io/RomVoiceImporter.java \
       app/src/test/java/com/opensmpsdeck/io/TestRomVoiceImporter.java
git commit -m "feat: add RomVoiceImporter for SMPS voice bank scanning"
```

---

### Task 29: VoiceImportDialog + Menu Wiring

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/ui/VoiceImportDialog.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java`

**Step 1: Create VoiceImportDialog**

```java
package com.opensmpsdeck.ui;

import com.opensmpsdeck.io.ImportableVoice;
import com.opensmpsdeck.io.RomVoiceImporter;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for browsing and importing FM voices from SMPS .bin files.
 * Scans a user-selected directory, displays voices in a filterable table,
 * and allows multi-select import into the active song's voice bank.
 */
public class VoiceImportDialog extends Dialog<List<ImportableVoice>> {

    private static File lastDirectory;
    private final RomVoiceImporter importer = new RomVoiceImporter();
    private final TableView<ImportableVoice> table;
    private final FilteredList<ImportableVoice> filteredList;

    public VoiceImportDialog() {
        setTitle("Import Voices from SMPS Files");
        setHeaderText("Select a directory containing SMPS .bin files:");

        DialogPane pane = getDialogPane();
        pane.setPrefWidth(600);
        pane.setPrefHeight(500);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        // Directory picker row
        Label dirLabel = new Label("No directory selected");
        Button browseButton = new Button("Browse...");
        HBox dirRow = new HBox(8, dirLabel, browseButton);
        HBox.setHgrow(dirLabel, Priority.ALWAYS);

        // Filter
        TextField filterField = new TextField();
        filterField.setPromptText("Filter by song name or algorithm...");

        // Table
        filteredList = new FilteredList<>(FXCollections.observableArrayList(), p -> true);
        table = new TableView<>(filteredList);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<ImportableVoice, String> songCol = new TableColumn<>("Source Song");
        songCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sourceSong()));
        songCol.setPrefWidth(180);

        TableColumn<ImportableVoice, Number> idxCol = new TableColumn<>("Index");
        idxCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().originalIndex()));
        idxCol.setPrefWidth(60);

        TableColumn<ImportableVoice, Number> algoCol = new TableColumn<>("Algo");
        algoCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().algorithm()));
        algoCol.setPrefWidth(60);

        table.getColumns().addAll(List.of(songCol, idxCol, algoCol));

        // Filter logic
        filterField.textProperty().addListener((obs, oldVal, newVal) -> {
            String filter = newVal.toLowerCase().trim();
            filteredList.setPredicate(v -> {
                if (filter.isEmpty()) return true;
                return v.sourceSong().toLowerCase().contains(filter)
                        || String.valueOf(v.algorithm()).contains(filter);
            });
        });

        // Browse action
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select SMPS Directory");
            if (lastDirectory != null && lastDirectory.isDirectory()) {
                chooser.setInitialDirectory(lastDirectory);
            }
            File dir = chooser.showDialog(pane.getScene().getWindow());
            if (dir != null) {
                lastDirectory = dir;
                dirLabel.setText(dir.getAbsolutePath());
                List<ImportableVoice> voices = importer.scanDirectory(dir);
                filteredList.getSource().clear();
                ((javafx.collections.ObservableList<ImportableVoice>) filteredList.getSource()).addAll(voices);
            }
        });

        content.getChildren().addAll(dirRow, filterField, table);
        pane.setContent(content);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return new ArrayList<>(table.getSelectionModel().getSelectedItems());
            }
            return null;
        });
    }
}
```

**Step 2: Add "Import Voices..." menu item to MainWindow**

In `MainWindow.createMenuBar()`, add after the Export SMPS item:

```java
MenuItem importVoicesItem = new MenuItem("Import Voices...");
importVoicesItem.setOnAction(e -> onImportVoices());

fileMenu.getItems().addAll(newItem, openItem, new SeparatorMenuItem(),
        saveItem, saveAsItem, separator, exportItem,
        importVoicesItem);
```

```java
private void onImportVoices() {
    SongTab songTab = getActiveSongTab();
    if (songTab == null) return;

    VoiceImportDialog dialog = new VoiceImportDialog();
    Optional<List<ImportableVoice>> result = dialog.showAndWait();
    if (result.isPresent() && !result.get().isEmpty()) {
        for (ImportableVoice iv : result.get()) {
            songTab.getSong().getVoiceBank().add(
                    new FmVoice(iv.sourceSong() + " #" + iv.originalIndex(), iv.voiceData()));
        }
        songTab.getInstrumentPanel().refresh();
    }
}
```

**Step 3: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 4: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/VoiceImportDialog.java \
       app/src/main/java/com/opensmpsdeck/ui/MainWindow.java
git commit -m "feat: add voice import dialog with directory scanning and filtering"
```

---

### Task 30: Import SMPS Song Menu Item

**Files:**
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java`

**Step 1: Add "Import SMPS..." menu item**

In `MainWindow.createMenuBar()`, add after Import Voices:

```java
MenuItem importSmpsItem = new MenuItem("Import SMPS...");
importSmpsItem.setOnAction(e -> onImportSmps());
```

```java
private void onImportSmps() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Import SMPS Binary");
    fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("SMPS Binary", "*.bin"));
    File file = fileChooser.showOpenDialog(stage);
    if (file != null) {
        try {
            SmpsImporter importer = new SmpsImporter();
            Song song = importer.importFile(file);
            SongTab songTab = new SongTab(song);
            songTab.setFile(null); // imported, not saved yet
            addNewTab(songTab);
        } catch (Exception ex) {
            showError("Failed to import SMPS", ex.getMessage());
        }
    }
}
```

Add import statement: `import com.opensmpsdeck.io.SmpsImporter;`

**Step 2: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 3: Commit**

```bash
git add app/src/main/java/com/opensmpsdeck/ui/MainWindow.java
git commit -m "feat: add Import SMPS menu item for full song import into new tab"
```

# Phase 7: Multi-Document & Import — Design

## Goal

Add tab-based multi-document editing, cross-song copy-paste with instrument resolution, ROM/SMPSPlay voice import browser, and SMPS binary song import to OpenSMPS Deck.

## Components

### 1. Tab-Based Multi-Document

Replace MainWindow's single-song BorderPane with a TabPane. Each tab wraps an independent editor context.

**SongTab** — new class containing:
- `Song song`, `File file` (nullable), `boolean dirty`
- `TrackerGrid`, `OrderListPanel`, `InstrumentPanel` (created in constructor)
- `refreshAllPanels()`, `getSong()`, `getFile()`

**MainWindow changes:**
- TabPane replaces the BorderPane center content
- Each tab's content is a BorderPane (tracker center, order list bottom, instrument panel right)
- [+] button tab at the end creates a new empty Song tab
- File > New creates a new tab; File > Open loads into a new tab
- Close tab via standard × button
- Active tab change updates TransportBar song reference and title bar
- MenuBar, TransportBar, PlaybackEngine remain shared at window level
- File > Save/SaveAs operates on active tab's song/file

### 2. Cross-Song Copy-Paste with Instrument Resolution

When pasting clipboard data from a different song, referenced voice/envelope indices may need remapping.

**Detection:** ClipboardData gets `Song sourceSong` field. On paste, if `sourceSong != currentSong`, trigger resolution.

**Resolution logic:**
1. Scan pasted bytes for `0xEF xx` (SET_VOICE) and `0xF5 xx` (PSG_INSTRUMENT) commands
2. Collect unique referenced indices
3. Auto-remap silently when source voice is byte-identical to a destination voice
4. Show InstrumentResolveDialog for unresolved instruments

**InstrumentResolveDialog** (modal):
- TableView listing each unresolved instrument: Source Name, Source Index, Action
- Actions per row: "Copy into song" (append + rewrite IDs), "Remap to existing" (ComboBox), "Skip"
- OK applies remappings to paste bytes before insertion

**Byte rewriting:** Iterate pasted bytes, replace old indices with new for both `0xEF` and `0xF5` commands.

### 3. ROM/SMPSPlay Voice Import Browser

Parse SMPSPlay `.bin` rips to extract voice tables for import.

**RomVoiceImporter:**
- `List<ImportableVoice> scanDirectory(File dir)` — scans directory of `.bin` files
- Uses existing SmpsImporter to parse each file, extracts voice bank
- Returns `ImportableVoice` records: sourceSong, originalIndex, voiceData, algorithm
- Deduplicates by voice byte data across songs

**VoiceImportDialog** (modal):
- Top: directory picker (remembers last directory)
- Center: TableView with columns: Name, Source Song, Algorithm, duplicate count
- Filter TextField (song name or algorithm number)
- Multi-select + Import button → appends to active song's voice bank

### 4. Full Song Import from SMPS Binary

**File menu addition:** "Import SMPS..." after Export SMPS.
- FileChooser filtered to `*.bin`
- Calls existing `SmpsImporter.importFile()` to parse into Song
- Opens the imported song in a new tab

No custom dialog needed — the existing importer handles everything.

## Testing

- SongTab creation/switching updates active song reference
- Cross-paste byte scanning for `0xEF`/`0xF5` commands
- Auto-remap when voices are byte-identical
- Index rewrite logic in pasted bytes
- RomVoiceImporter directory scanning and deduplication
- InstrumentResolveDialog and VoiceImportDialog tested manually (visual)

## Files

- Create: `SongTab.java`, `InstrumentResolveDialog.java`, `RomVoiceImporter.java`, `VoiceImportDialog.java`, `ImportableVoice.java`
- Modify: `MainWindow.java` (TabPane conversion, import menu), `ClipboardData.java` (sourceSong field), `TrackerGrid.java` (cross-paste resolution)

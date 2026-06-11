package com.opensmpsdeck.ui;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.audio.match.DrumSliceExtractor;
import com.opensmpsdeck.audio.match.MonophonicWindowFinder;
import com.opensmpsdeck.audio.match.WavStemReader;
import com.opensmpsdeck.io.midi.*;
import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.SmpsMode;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Mapping/preview dialog for MIDI import. Result: a confirmed MidiImportSpec. */
public class MidiImportDialog extends Dialog<MidiImportSpec> {

    /** One editable row in the mapping grid. */
    public static final class LineRow {
        final String stemName;
        final VoiceSeparator.SeparatedLine line;
        final int gmProgram;
        int targetChannel;          // -1 = not imported
        int octaveShift = 0;
        FmVoice voice;              // pre-filled GM suggestion; replaceable

        LineRow(MappingSuggester.Suggestion s) {
            this.stemName = s.stemName();
            this.line = s.line();
            this.gmProgram = s.gmProgram();
            this.targetChannel = s.targetChannel();
            this.voice = GmVoiceSuggestions.forProgram(s.gmProgram());
        }
    }

    private final ObservableList<LineRow> rows = FXCollections.observableArrayList();
    private final Spinner<Integer> phraseBars = new Spinner<>(1, 16, 4);
    private final ComboBox<SmpsMode> modeBox = new ComboBox<>(
            FXCollections.observableArrayList(SmpsMode.values()));
    private final CheckBox loopSong = new CheckBox("Loop whole song");
    private final Label tempoLabel = new Label();
    private final TextArea warningsArea = new TextArea();
    private final List<MidiStem> stems;
    /** stem name (file base name) → source .mid file, for locating sibling .wav stems. */
    private final Map<String, File> sourceByStem = new HashMap<>();
    private final CheckBox extractDrumSamples = new CheckBox("Extract samples from WAV");
    private PlaybackEngine previewEngine;
    private TempoFitter.TempoFit fit;
    private GmDrumMapper.SplitResult drumSplit;
    private final GmDrumMapper.Mapping drumMapping = GmDrumMapper.defaultMapping();

    /** Default DAC rate byte, matching {@code InstrumentPanel}'s default. */
    private static final int DEFAULT_DAC_RATE = 0x0C;
    /** Isolation window (seconds) for picking a clean drum one-shot. */
    private static final double DRUM_ISOLATION_SEC = 0.06;

    public MidiImportDialog(List<MidiStem> stems) {
        this(stems, List.of());
    }

    /**
     * @param stems        parsed MIDI stems
     * @param sourceFiles  the original {@code .mid} files (same order/identity as the
     *                     stems); used to locate sibling {@code .wav} files by base name
     *                     for WAV voice matching and drum sample extraction. May be empty.
     */
    public MidiImportDialog(List<MidiStem> stems, List<File> sourceFiles) {
        this.stems = stems;
        for (File f : sourceFiles) {
            sourceByStem.put(stripExtension(f.getName()), f);
        }
        setTitle("Import MIDI");
        modeBox.setValue(SmpsMode.S2);
        loopSong.setSelected(true);

        for (var s : MappingSuggester.suggest(stems)) rows.add(new LineRow(s));

        extractDrumSamples.setSelected(findDrumWav() != null);
        extractDrumSamples.setDisable(findDrumWav() == null);

        VBox content = new VBox(10,
                new Label("Files: " + stems.size() + " stem(s)"),
                tempoLabel,
                buildMappingTable(),
                new HBox(10, new Label("Bars per phrase:"), phraseBars,
                        new Label("Mode:"), modeBox, loopSong),
                new HBox(10, new Label("Drums:"), extractDrumSamples),
                new Label("Warnings:"), warningsArea);
        content.setPadding(new Insets(10));
        warningsArea.setEditable(false);
        warningsArea.setPrefRowCount(4);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Must run AFTER the button types are added so lookupButton(OK) can
        // disable the OK button when the initial tempo fit fails.
        recomputeTempoAndDrums();
        modeBox.valueProperty().addListener((o, a, b) -> recomputeTempoAndDrums());

        setResultConverter(bt -> bt == ButtonType.OK ? buildSpec() : null);
    }

    /** Sets the engine used to audition matched voices inside the match dialog. */
    public void setPreviewEngine(PlaybackEngine engine) {
        this.previewEngine = engine;
    }

    private void recomputeTempoAndDrums() {
        MidiStem first = stems.get(0);
        StringBuilder warn = new StringBuilder();
        try {
            fit = TempoFitter.fit(first.tempoMap(), first.totalTicks(), first.ppq(),
                    modeBox.getValue());
            tempoLabel.setText(String.format(
                    "Tempo: %.1f BPM → tempo byte %02X, dividing timing %d, 16th = %d units (err %.2f%%)",
                    fit.bpm(), fit.tempoByte(), fit.dividingTiming(),
                    fit.unitsPerSixteenth(), fit.errorPercent()));
        } catch (IllegalArgumentException ex) {
            fit = null;
            tempoLabel.setText("Tempo: cannot fit — " + ex.getMessage());
        }
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        if (ok != null) ok.setDisable(fit == null);

        List<NoteQuantizer.QuantizedNote> drumNotes = new ArrayList<>();
        for (MidiStem stem : stems) {
            for (var track : stem.tracks()) {
                if (track.drumTrack()) {
                    drumNotes.addAll(NoteQuantizer.quantize(track.notes(), stem.ppq()));
                }
            }
        }
        drumSplit = GmDrumMapper.split(drumNotes, drumMapping);
        for (int p : drumSplit.droppedPitches()) {
            warn.append("Unmapped GM drum pitch ").append(p).append(" dropped\n");
        }
        warningsArea.setText(warn.toString());
    }

    private TableView<LineRow> buildMappingTable() {
        TableView<LineRow> table = new TableView<>(rows);
        table.setEditable(true);
        table.setPrefHeight(260);

        TableColumn<LineRow, String> stemCol = new TableColumn<>("Stem");
        stemCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                d.getValue().stemName + " line " + d.getValue().line.rank()));

        TableColumn<LineRow, String> notesCol = new TableColumn<>("Notes");
        notesCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(d.getValue().line.notes().size())));

        TableColumn<LineRow, String> channelCol = new TableColumn<>("Channel");
        channelCol.setCellFactory(c -> comboCell(table,
                List.of("—", "FM1", "FM2", "FM3", "FM4", "FM5", "PSG1", "PSG2", "PSG3"),
                row -> row.targetChannel < 0 ? "—" : channelName(row.targetChannel),
                (row, v) -> row.targetChannel = channelIndex(v)));

        TableColumn<LineRow, String> shiftCol = new TableColumn<>("Octave");
        shiftCol.setCellFactory(c -> comboCell(table,
                List.of("-2", "-1", "0", "+1", "+2"),
                row -> String.valueOf(row.octaveShift),
                (row, v) -> row.octaveShift = Integer.parseInt(v.replace("+", ""))));

        TableColumn<LineRow, String> voiceCol = new TableColumn<>("Instrument");
        voiceCol.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                d.getValue().voice != null ? d.getValue().voice.getName() : "(default)"));

        TableColumn<LineRow, String> matchCol = new TableColumn<>("Match");
        matchCol.setCellFactory(c -> matchCell(table));

        table.getColumns().addAll(List.of(stemCol, notesCol, channelCol, shiftCol, voiceCol, matchCol));
        return table;
    }

    /**
     * A TableCell with a "Match…" button, enabled only when a sibling {@code .wav}
     * with the row's stem base name exists. Clicking runs WAV voice matching and,
     * on accept, replaces the row's voice.
     */
    private TableCell<LineRow, String> matchCell(TableView<LineRow> table) {
        return new TableCell<>() {
            private final Button button = new Button("Match…");
            {
                button.setOnAction(e -> {
                    LineRow row = getTableView().getItems().get(getIndex());
                    File wav = siblingWav(row.stemName);
                    if (wav == null) return;
                    var map = new TickTimeMapper(stemPpq(row.stemName), stemTempoMap(row.stemName));
                    VoiceMatchDialog dialog = new VoiceMatchDialog(
                            wav, row.line.notes(), map, false);
                    dialog.setPreviewEngine(previewEngine);
                    dialog.initOwner(getDialogPane().getScene().getWindow());
                    dialog.showAndWait().ifPresent(v -> {
                        row.voice = v;
                        table.refresh();
                    });
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    LineRow row = getTableView().getItems().get(getIndex());
                    button.setDisable(siblingWav(row.stemName) == null);
                    setGraphic(button);
                }
            }
        };
    }

    /** The {@code .wav} alongside the stem's source {@code .mid}, or null if none exists. */
    private File siblingWav(String stemName) {
        File mid = sourceByStem.get(stemName);
        if (mid == null) return null;
        File wav = new File(mid.getParentFile(), stemName + ".wav");
        return wav.isFile() ? wav : null;
    }

    private int stemPpq(String stemName) {
        return stems.stream().filter(s -> s.name().equals(stemName))
                .mapToInt(MidiStem::ppq).findFirst().orElse(stems.get(0).ppq());
    }

    private List<MidiStem.TempoEvent> stemTempoMap(String stemName) {
        return stems.stream().filter(s -> s.name().equals(stemName))
                .map(MidiStem::tempoMap).findFirst().orElse(stems.get(0).tempoMap());
    }

    /**
     * A TableCell housing a ComboBox of fixed options. The display value is read
     * via {@code getter}; selecting a new value writes it back through {@code setter}
     * and refreshes the table so dependent columns repaint.
     */
    private static TableCell<LineRow, String> comboCell(TableView<LineRow> table,
                                                        List<String> options,
                                                        Function<LineRow, String> getter,
                                                        BiConsumer<LineRow, String> setter) {
        return new TableCell<>() {
            private final ComboBox<String> combo =
                    new ComboBox<>(FXCollections.observableArrayList(options));
            {
                combo.setMaxWidth(Double.MAX_VALUE);
                combo.valueProperty().addListener((o, a, b) -> {
                    if (b == null || isEmpty()) return;
                    LineRow row = getTableView().getItems().get(getIndex());
                    if (!b.equals(getter.apply(row))) {
                        setter.accept(row, b);
                        table.refresh();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    LineRow row = getTableView().getItems().get(getIndex());
                    combo.setValue(getter.apply(row));
                    setGraphic(combo);
                }
            }
        };
    }

    private static String channelName(int ch) {
        return ch <= 4 ? "FM" + (ch + 1) : "PSG" + (ch - 5);
    }

    private static int channelIndex(String name) {
        if (name.equals("—")) return -1;
        return name.startsWith("FM") ? Integer.parseInt(name.substring(2)) - 1
                                     : Integer.parseInt(name.substring(3)) + 5;
    }

    private MidiImportSpec buildSpec() {
        if (fit == null) return null; // no valid tempo fit: OK is disabled, but stay safe
        MidiStem first = stems.get(0);
        List<MidiImportSpec.LineAssignment> assignments = new ArrayList<>();
        for (LineRow r : rows) {
            if (r.targetChannel < 0) continue;
            int ppq = stems.stream()
                    .filter(s -> s.name().equals(r.stemName))
                    .mapToInt(MidiStem::ppq)
                    .findFirst().orElse(first.ppq());
            assignments.add(new MidiImportSpec.LineAssignment(
                    r.stemName, r.line, r.targetChannel, r.octaveShift, r.voice, -1, ppq));
        }
        // 16 sixteenth-steps per 4/4 bar, scaled by the time signature
        int stepsPerBar = 16 * first.timeSignature().numerator()
                / first.timeSignature().denominator();

        Map<Integer, DacSample> dacSampleOverrides = Map.of();
        if (extractDrumSamples.isSelected() && findDrumWav() != null) {
            dacSampleOverrides = extractDrumSamples();
        }

        return new MidiImportSpec(
                commonPrefix(stems), modeBox.getValue(), fit.tempoByte(), fit.dividingTiming(),
                fit.unitsPerSixteenth(), stepsPerBar, phraseBars.getValue(),
                loopSong.isSelected(), first.ppq(), assignments,
                drumSplit.dacHits(), drumSplit.noiseHits(), drumMapping, dacSampleOverrides);
    }

    /** The source {@code .wav} alongside the drum stem's {@code .mid}, or null. */
    private File findDrumWav() {
        for (MidiStem stem : stems) {
            boolean hasDrums = stem.tracks().stream().anyMatch(MidiStem.MidiNoteTrack::drumTrack);
            if (hasDrums) {
                File wav = siblingWav(stem.name());
                if (wav != null) return wav;
            }
        }
        return null;
    }

    /**
     * Slices a one-shot per used DAC slot (kick/snare/tom) from the drum stem's WAV.
     * Runs synchronously on OK; slices are milliseconds of audio so it is fast.
     */
    private Map<Integer, DacSample> extractDrumSamples() {
        // Locate the drum stem and its WAV.
        MidiStem drumStem = null;
        for (MidiStem stem : stems) {
            if (stem.tracks().stream().anyMatch(MidiStem.MidiNoteTrack::drumTrack)
                    && siblingWav(stem.name()) != null) {
                drumStem = stem;
                break;
            }
        }
        if (drumStem == null) return Map.of();
        File wav = siblingWav(drumStem.name());

        Scene scene = getDialogPane().getScene();
        Cursor prev = scene != null ? scene.getCursor() : null;
        if (scene != null) scene.setCursor(Cursor.WAIT);
        try {
            float[] audio = WavStemReader.readMono44k(wav);
            var map = new TickTimeMapper(drumStem.ppq(), drumStem.tempoMap());

            // Gather raw drum NoteEvents per DAC slot, plus all drum hits (for isolation).
            Map<Integer, List<NoteEvent>> bySlot = new HashMap<>();
            List<NoteEvent> allDrumHits = new ArrayList<>();
            for (var track : drumStem.tracks()) {
                if (!track.drumTrack()) continue;
                for (NoteEvent n : track.notes()) {
                    allDrumHits.add(n);
                    GmDrumMapper.DrumTarget t = drumMapping.targetFor(n.pitch());
                    if (t.isDac()) {
                        bySlot.computeIfAbsent(t.dacSlot, k -> new ArrayList<>()).add(n);
                    }
                }
            }

            Map<Integer, DacSample> overrides = new LinkedHashMap<>();
            for (var entry : bySlot.entrySet()) {
                int slot = entry.getKey();
                List<NoteEvent> classHits = entry.getValue();
                List<NoteEvent> otherHits = new ArrayList<>(allDrumHits);
                otherHits.removeAll(classHits);
                var isolated = MonophonicWindowFinder.findDrumHits(
                        classHits, otherHits, map, DRUM_ISOLATION_SEC);
                if (isolated.isEmpty()) continue;
                var hit = isolated.get(0); // loudest isolated hit
                String name = slotName(slot);
                DacSample sample = DrumSliceExtractor.extract(
                        audio, WavStemReader.TARGET_RATE, hit.startSec(),
                        hit.startSec() + 0.4, name, DEFAULT_DAC_RATE);
                overrides.put(slot, sample);
            }
            return overrides;
        } catch (Exception ex) {
            new Alert(Alert.AlertType.WARNING,
                    "Could not extract drum samples: " + ex.getMessage(),
                    ButtonType.OK).showAndWait();
            return Map.of();
        } finally {
            if (scene != null) scene.setCursor(prev);
        }
    }

    private static String slotName(int dacSlot) {
        return switch (dacSlot) {
            case 0 -> "Kick";
            case 1 -> "Snare";
            case 2 -> "Tom";
            default -> "DAC" + dacSlot;
        };
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String commonPrefix(List<MidiStem> stems) {
        String name = stems.get(0).name();
        int paren = name.indexOf(" (");
        return paren > 0 ? name.substring(0, paren) : name;
    }
}

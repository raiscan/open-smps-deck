package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.NativeBankAnalysis;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.StudioProject;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Address-preserving editing of native music, with a separate playback preflight. */
final class NativeProgramPane extends BorderPane {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final TextArea bytes = new TextArea();
    private final TextField frames = new TextField();
    private final Label bankInfo = new Label();
    private final Label diagnostic = new Label();
    private final Label draftInfo = new Label();
    private final TableView<NativeBankAnalysis.Event> events = new TableView<>();
    private final Consumer<UnaryOperator<StudioProject>> edit;
    private final Consumer<String> error;
    private StudioProject project;
    private NativeProgram baseline;
    private String baselineText = "";

    NativeProgramPane(Consumer<UnaryOperator<StudioProject>> edit, Consumer<String> error) {
        this.edit = edit;
        this.error = error;
        bytes.setId("native-bank-bytes");
        bytes.setWrapText(true);
        bytes.setPromptText("Native bank in hexadecimal");
        frames.setId("native-preview-frames");
        frames.setPrefColumnCount(6);
        bankInfo.getStyleClass().add("section");
        diagnostic.getStyleClass().add("help");
        diagnostic.setWrapText(true);
        draftInfo.getStyleClass().add("muted");
        draftInfo.setWrapText(true);
        draftInfo.visibleProperty().bind(draftInfo.textProperty().isNotEmpty());
        draftInfo.managedProperty().bind(draftInfo.visibleProperty());
        bytes.textProperty().addListener((obs, old, value) -> refreshDraft());
        frames.textProperty().addListener((obs, old, value) -> refreshDraft());
        Button apply = button("Apply bank", "apply-native-bank", this::apply);
        Button reload = button("Reset draft", "reload-native-bank", this::loadLatest);
        var actions = new FlowPane(8, 6, new Label("Preview frames"), frames, apply, reload);
        var tools = new VBox(8, bankInfo, actions, draftInfo, diagnostic);
        tools.setPadding(new Insets(10));
        setTop(tools);

        events.setId("native-events");
        events.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        var address = new TableColumn<NativeBankAnalysis.Event, String>("Address");
        address.setMinWidth(76);
        address.setCellValueFactory(row -> new ReadOnlyStringWrapper(HEX.toHexDigits((short)row.getValue().address())));
        var data = new TableColumn<NativeBankAnalysis.Event, String>("Bytes");
        data.setPrefWidth(124);
        data.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().bytes().stream()
                .map(value -> HEX.toHexDigits(value.byteValue())).collect(Collectors.joining(" "))));
        var meaning = new TableColumn<NativeBankAnalysis.Event, String>("Event");
        meaning.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().meaning()));
        events.getColumns().setAll(java.util.List.of(address, data, meaning));
        events.setPlaceholder(new Label("No playable events to display"));
        events.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> {
            if (row == null || baseline == null || !bytes.getText().equals(baselineText)) return;
            int offset = row.address() - baseline.bankBase();
            if (offset < 0 || offset >= baseline.bank().length) return;
            int start = offset * 3;
            int end = Math.min(bytes.getLength(), start + row.bytes().size() * 3 - 1);
            bytes.selectRange(start, end);
        });
        var raw = new VBox(6, new Label("Bank bytes"), bytes);
        VBox.setVgrow(bytes, Priority.ALWAYS);
        var split = new SplitPane(events, raw);
        split.setDividerPositions(.54);
        setCenter(split);
    }

    void setProject(StudioProject next) {
        if (Objects.equals(project, next)) return;
        boolean keep = next != null && next.nativeProgram() != null && baseline != null && changed();
        project = next;
        if (!keep) loadLatest();
        else refreshDraft();
        inspect();
    }

    private void loadLatest() {
        baseline = project == null ? null : project.nativeProgram();
        baselineText = baseline == null ? "" : formatBank(baseline.bank());
        bytes.setText(baselineText);
        frames.setText(baseline == null ? "" : Integer.toString(baseline.previewFrames()));
        refreshDraft();
    }

    private void inspect() {
        if (project == null || project.nativeProgram() == null) {
            events.getItems().clear();
            diagnostic.setText("");
            bankInfo.setText("");
            return;
        }
        NativeProgram program = project.nativeProgram();
        bankInfo.setText(String.format(Locale.ROOT, "S3K bank %04X · header %04X · %,d bytes",
                program.bankBase(), program.bankBase() + program.headerOffset(), program.bank().length));
        try {
            var analysis = NativeBankAnalysis.inspectForEditing(ProjectCompiler.compileWithAssets(project));
            events.getItems().setAll(analysis.events());
            String playback = analysis.requiresPreviousMusic()
                    ? "Playback requires a previous song" : "Playback preflight passed";
            diagnostic.setText(playback + " · " + analysis.events().size() + " reachable events");
        } catch (IllegalArgumentException failure) {
            events.getItems().clear();
            diagnostic.setText("Playback blocked · " + failure.getMessage());
        }
    }

    private void apply() {
        NativeProgram expected = baseline;
        if (expected == null) return;
        try {
            byte[] edited = parseBank(bytes.getText(), expected.bank().length);
            int length = Integer.parseInt(frames.getText().strip());
            var replacement = NativeProgram.of(expected.bankBase(), expected.headerOffset(),
                    expected.localVoiceCount(), length, edited, expected.envelopeSlots(), expected.dacSlots());
            edit.accept(p -> {
                if (!expected.equals(p.nativeProgram()))
                    throw new IllegalArgumentException("The native bank changed on disk. Reset the draft before applying.");
                return ProjectEdits.setNativeProgram(p, replacement);
            });
            if (project != null && replacement.equals(project.nativeProgram())) loadLatest();
        } catch (IllegalArgumentException failure) {
            error.accept(failure.getMessage());
        }
    }

    private void refreshDraft() {
        if (baseline == null) draftInfo.setText("");
        else if (project != null && !baseline.equals(project.nativeProgram()))
            draftInfo.setText("The bank changed on disk. Your draft is preserved. Reset it to edit the current bank.");
        else draftInfo.setText(changed() ? "Unapplied bank edits" : "");
    }

    private boolean changed() {
        return !baselineText.equals(bytes.getText()) || !frames.getText().equals(Integer.toString(baseline.previewFrames()));
    }

    private static String formatBank(byte[] bank) {
        var output = new StringBuilder(bank.length * 3);
        for (int i = 0; i < bank.length; i++) {
            if (i != 0) output.append(i % 16 == 0 ? '\n' : ' ');
            output.append(HEX.toHexDigits(bank[i]));
        }
        return output.toString();
    }

    static byte[] parseBank(String value, int bankLength) {
        if (value.length() > 131_072)
            throw new IllegalArgumentException("Bank draft exceeds the size of a 32 KiB hex bank.");
        byte[] bank = HEX.parseHex(value.replaceAll("\\s+", ""));
        if (bank.length != bankLength)
            throw new IllegalArgumentException("Keep the bank length at " + bankLength + " bytes to preserve native addresses.");
        return bank;
    }

    private static Button button(String text, String id, Runnable action) {
        Button button = new Button(text);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }
}

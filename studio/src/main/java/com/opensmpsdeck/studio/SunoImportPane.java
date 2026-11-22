package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.headless.SunoImport;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

final class SunoImportPane extends VBox {
    private record Row(String file, String icon, String part, String message, boolean stem) {}
    private record OverrideChoice(Integer part, boolean exclude, String label) {
        @Override public String toString() { return label; }
    }

    private static final OverrideChoice AUTOMATIC = new OverrideChoice(null, false, "Automatic");
    private static final OverrideChoice EXCLUDE = new OverrideChoice(null, true, "Exclude");

    private final Path source;
    private final Path work;
    private final Consumer<Path> openProject;
    private final Map<String, Integer> assignments = new HashMap<>();
    private final Set<String> exclusions = new HashSet<>();
    private final TableView<Row> files = new TableView<>();
    private final Button check = new Button("Re-check");
    private final Button start = new Button("Start reconstruction");
    private final ComboBox<TargetGame> target = new ComboBox<>();
    private final Label status = new Label();
    private final ProgressBar progress = new ProgressBar();
    private SunoImport.Report report;
    private List<OverrideChoice> choices = List.of(AUTOMATIC, EXCLUDE);
    private Task<?> active;

    SunoImportPane(Path source, Path work, Consumer<Path> openProject) {
        this.source = source;
        this.work = work;
        this.openProject = openProject;
        setPadding(new Insets(16));
        setSpacing(10);
        var location = new Label(source.toString());
        location.getStyleClass().add("muted");
        location.setWrapText(true);
        files.setId("suno-import-files");
        files.getColumns().add(column("Status", Row::icon, 65));
        files.getColumns().add(column("File or MIDI part", Row::file, 200));
        files.getColumns().add(column("Mapped part", Row::part, 100));
        files.getColumns().add(column("Message", Row::message, 320));
        files.getColumns().add(assignmentColumn());
        files.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        files.setPlaceholder(new Label("Checking the download folder…"));
        VBox.setVgrow(files, Priority.ALWAYS);

        check.setId("suno-import-check");
        check.setOnAction(event -> recheck());
        start.setId("suno-import-start");
        start.setOnAction(event -> chooseCatalogue());
        status.setId("suno-import-status");
        status.setWrapText(true);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.managedProperty().bind(progress.visibleProperty());
        progress.setVisible(false);
        target.setId("suno-import-target");
        target.getItems().setAll(TargetGame.values());
        target.setValue(TargetGame.S3K);
        target.valueProperty().addListener((observable, oldValue, newValue) -> recheck());
        var targetLabel = new Label("Target game");
        targetLabel.setLabelFor(target);
        getChildren().addAll(location, new HBox(8, targetLabel, target), files, status, progress, new HBox(8, check, start));
        recheck();
    }

    private static TableColumn<Row, String> column(String heading, Function<Row, String> value, double width) {
        var column = new TableColumn<Row, String>(heading);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        if (heading.equals("Message")) column.setCellFactory(unused -> new TableCell<>() {
            private final Label label = new Label();
            {
                label.setWrapText(true);
                label.maxWidthProperty().bind(widthProperty().subtract(12));
            }
            @Override protected void updateItem(String message, boolean empty) {
                super.updateItem(message, empty);
                label.setText(message);
                setGraphic(empty ? null : label);
            }
        });
        return column;
    }

    private TableColumn<Row, Row> assignmentColumn() {
        var column = new TableColumn<Row, Row>("Override");
        column.setPrefWidth(160);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setCellFactory(unused -> new TableCell<>() {
            private final ComboBox<OverrideChoice> select = new ComboBox<>();
            {
                select.setMaxWidth(Double.MAX_VALUE);
                select.setAccessibleText("Stem mapping override");
            }
            @Override protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || !row.stem()) {
                    setGraphic(null);
                    return;
                }
                select.setOnAction(null);
                select.getItems().setAll(choices);
                select.setValue(exclusions.contains(row.file()) ? EXCLUDE : choices.stream()
                        .filter(choice -> choice.part() != null && choice.part().equals(assignments.get(row.file())))
                        .findFirst().orElse(AUTOMATIC));
                select.setOnAction(event -> change(row.file(), select.getValue()));
                setGraphic(select);
            }
        });
        return column;
    }

    private void change(String file, OverrideChoice choice) {
        if (choice == null) return;
        assignments.remove(file);
        exclusions.remove(file);
        if (choice.exclude()) exclusions.add(file);
        else if (choice.part() != null) assignments.put(file, choice.part());
        start.setDisable(true);
        status.setText("Overrides changed. Re-check to apply them.");
    }

    private void recheck() {
        var selected = Map.copyOf(assignments);
        var skipped = Set.copyOf(exclusions);
        var game = target.getValue();
        Task<SunoImport.Report> task = new Task<>() {
            @Override protected SunoImport.Report call() throws Exception {
                return SunoImport.inspect(source, work, null, selected, skipped, (done, total, message) -> {
                    updateProgress(done, total);
                    updateMessage(message);
                }, game);
            }
        };
        launch(task, this::showReport);
    }

    private void showReport(SunoImport.Report checked) {
        report = checked;
        var names = new HashMap<Integer, String>();
        var available = new ArrayList<>(List.of(AUTOMATIC, EXCLUDE));
        for (var part : report.parts()) {
            String label = part.id() + " · " + part.name();
            names.put(part.id(), label);
            available.add(new OverrideChoice(part.id(), false, label));
        }
        choices = List.copyOf(available);
        var rows = new ArrayList<Row>();
        for (var file : report.files()) {
            String message = String.join("\n", file.issues().stream().map(SunoImport.Issue::message).distinct().toList());
            rows.add(new Row(file.file(), file.status().icon(), names.get(file.part()), message, file.stem()));
        }
        for (var gap : report.gaps())
            rows.add(new Row("Part " + names.get(gap.part()), gap.status().icon(), "", gap.message(), false));
        for (var issue : report.issues())
            rows.add(new Row("Folder", issue.status().icon(), "", issue.message(), false));
        files.getItems().setAll(rows);
        files.refresh();
        String summary = report.status().icon() + " " + switch (report.status()) {
            case ok -> "Import checks passed";
            case warning -> "Import has warnings";
            case error -> "Resolve import errors to continue";
        };
        if (report.leadPart() != null) summary += " · Lead candidate " + names.get(report.leadPart());
        status.setText(summary);
        status.setTooltip(new Tooltip(work.resolve(SunoImport.FILE).toString()));
        start.setDisable(!report.canRun());
    }

    private void chooseCatalogue() {
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Choose the instrument catalogue");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Instrument catalogue JSON", "*.json"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;
        var checked = report;
        Task<Path> task = new Task<>() {
            @Override protected Path call() throws Exception {
                updateMessage("Verifying checked input files…");
                return SunoImport.run(checked, file.toPath(), (done, total, message) -> {
                    updateProgress(done, total);
                    updateMessage(message);
                });
            }
        };
        launch(task, output -> {
            status.setText("Reconstructed " + output.getFileName());
            openProject.accept(output);
        });
    }

    private <T> void launch(Task<T> task, Consumer<T> success) {
        active = task;
        files.setDisable(true);
        target.setDisable(true);
        check.setDisable(true);
        start.setDisable(true);
        status.textProperty().bind(task.messageProperty());
        progress.progressProperty().bind(task.progressProperty());
        progress.setVisible(true);
        task.setOnSucceeded(event -> {
            finish();
            success.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            finish();
            status.setText(task.getException().getMessage());
        });
        task.setOnCancelled(event -> finish());
        var thread = new Thread(task, "studio-suno-import");
        thread.setDaemon(true);
        thread.start();
    }

    private void finish() {
        active = null;
        status.textProperty().unbind();
        progress.progressProperty().unbind();
        progress.setVisible(false);
        files.setDisable(false);
        target.setDisable(false);
        check.setDisable(false);
    }

    void close() {
        if (active != null) active.cancel();
    }
}

package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Match;
import com.opensmpsdeck.headless.InstrumentCatalogue.Occurrence;
import com.opensmpsdeck.headless.InstrumentCatalogue.Query;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Browses a user-supplied catalogue without adding it to the project. */
final class InstrumentCataloguePane extends VBox implements AutoCloseable {
    private static final int PAGE_SIZE = 30;

    private final TextField search = new TextField();
    private final ComboBox<KindFilter> kind = new ComboBox<>();
    private final ListView<Match> results = new ListView<>();
    private final TextArea details = new TextArea();
    private final Label status = new Label();
    private final Label range = new Label();
    private final Button open = button("Open catalogue…", "catalogue-open");
    private final Button previous = button("Previous", "catalogue-previous");
    private final Button next = button("Next", "catalogue-next");
    private final Button audition = button("Audition", "catalogue-audition");
    private final Button apply = button("Add to project", "catalogue-apply");
    private final Tab detailTab = new Tab("Variant");
    private final TabPane pages = new TabPane();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(180));
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), work -> {
                var thread = new Thread(work, "studio-instrument-catalogue");
                thread.setDaemon(true);
                return thread;
            });

    private InstrumentCatalogue index;
    private Future<?> pending;
    private long generation;
    private String fileName;
    private int offset, total;
    private boolean loading, querying, editable, closed;

    InstrumentCataloguePane(BiConsumer<InstrumentCatalogue, String> onAudition,
                            BiConsumer<InstrumentCatalogue, String> onApply) {
        setId("instrument-catalogue");
        setMinSize(0, 0);
        setSpacing(5);
        setPadding(new Insets(7, 8, 4, 8));
        search.setId("catalogue-search");
        search.setPromptText("Search instruments");
        search.setAccessibleText("Search instruments");
        search.setPrefWidth(145);
        kind.setId("catalogue-kind");
        kind.setAccessibleText("Instrument kind");
        kind.setPrefWidth(185);
        kind.getItems().add(new KindFilter(null));
        for (Kind item : Kind.values()) kind.getItems().add(new KindFilter(item));
        kind.getSelectionModel().selectFirst();
        var filters = new FlowPane(6, 5, open, search, kind);
        open.setOnAction(event -> chooseCatalogue());

        status.setId("catalogue-status");
        status.getStyleClass().add("muted");
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        results.setId("catalogue-results");
        results.setMinHeight(0);
        results.setPlaceholder(new Label("No variants to display"));
        results.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Match match, boolean empty) {
                super.updateItem(match, empty);
                setPrefWidth(1);
                String label = empty || match == null ? null : rowLabel(match);
                setText(label);
                setTooltip(label == null ? null : new Tooltip(label));
            }
        });
        Button inspect = button("View variant", "catalogue-inspect");
        inspect.disableProperty().bind(results.getSelectionModel().selectedItemProperty().isNull());
        inspect.setOnAction(event -> pages.getSelectionModel().select(detailTab));
        results.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !inspect.isDisabled()) inspect.fire();
        });
        results.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !inspect.isDisabled()) {
                inspect.fire();
                event.consume();
            }
        });
        results.getSelectionModel().selectedItemProperty().addListener((o, before, selected) -> {
            details.setText(selected == null ? "" : describe(selected));
            details.positionCaret(0);
            updateControls();
        });
        previous.setOnAction(event -> query(Math.max(0, offset - PAGE_SIZE)));
        next.setOnAction(event -> query(offset + PAGE_SIZE));
        var pager = new FlowPane(6, 4, previous, next, range, inspect);
        var resultBody = new VBox(4, pager, results);
        VBox.setVgrow(results, Priority.ALWAYS);

        details.setId("catalogue-details");
        details.setAccessibleText("Selected variant recipe and source occurrences");
        details.setEditable(false);
        details.setWrapText(true);
        details.setMinHeight(0);
        details.setPrefRowCount(4);
        audition.setOnAction(event -> withSelection(onAudition));
        apply.setOnAction(event -> withSelection(onApply));
        var variantBody = new VBox(4, new FlowPane(6, 4, audition, apply), details);
        VBox.setVgrow(details, Priority.ALWAYS);
        detailTab.setContent(variantBody);
        pages.getTabs().addAll(new Tab("Results", resultBody), detailTab);
        pages.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        pages.setMinSize(0, 0);
        VBox.setVgrow(pages, Priority.ALWAYS);
        getChildren().addAll(filters, status, pages);
        debounce.setOnFinished(event -> query(0));
        search.textProperty().addListener((o, before, after) -> scheduleQuery());
        kind.valueProperty().addListener((o, before, after) -> scheduleQuery());
        search.setOnAction(event -> {
            debounce.stop();
            query(0);
        });
        setStatus("Open a local instrument catalogue to browse variants.");
        updateControls();
    }

    private void chooseCatalogue() {
        var chooser = new FileChooser();
        chooser.setTitle("Open instrument catalogue");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Instrument catalogue (*.json)", "*.json"));
        var file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) open(file.toPath());
    }

    void open(Path file) {
        if (closed) return;
        debounce.stop();
        loading = true;
        setStatus("Opening " + file.getFileName() + "…");
        updateControls();
        run(() -> InstrumentCatalogue.open(file), catalogue -> {
            index = catalogue;
            fileName = file.getFileName().toString();
            loading = false;
            query(0);
        }, error -> {
            loading = false;
            querying = false;
            setStatus("Could not open " + file.getFileName() + ". " + error);
            updateControls();
        });
    }

    boolean ownsFocus(Node focus) {
        for (Node node = focus; node != null; node = node.getParent())
            if (node == this) return true;
        return false;
    }

    void setEditable(boolean editable) {
        this.editable = editable;
        updateControls();
    }

    private void scheduleQuery() {
        if (index == null || loading || closed) return;
        cancelPending();
        querying = true;
        results.getItems().clear();
        pages.getSelectionModel().selectFirst();
        setStatus("Searching " + fileName + "…");
        updateControls();
        debounce.playFromStart();
    }

    private void query(int start) {
        if (index == null || loading || closed) return;
        var snapshot = index;
        var selection = kind.getValue();
        var filter = new Query(search.getText(), selection == null ? null : selection.kind(),
                null, null, null, null, start, PAGE_SIZE);
        querying = true;
        results.getItems().clear();
        pages.getSelectionModel().selectFirst();
        setStatus("Searching " + fileName + "…");
        updateControls();
        run(() -> snapshot.search(filter), page -> {
            querying = false;
            offset = page.offset();
            total = page.total();
            results.getItems().setAll(page.items());
            if (!page.items().isEmpty()) results.getSelectionModel().selectFirst();
            setStatus(fileName);
            updateControls();
        }, error -> {
            querying = false;
            total = 0;
            offset = 0;
            setStatus("Search failed. " + error);
            updateControls();
        });
    }

    private void withSelection(BiConsumer<InstrumentCatalogue, String> action) {
        Match selected = results.getSelectionModel().getSelectedItem();
        if (index != null && selected != null && !loading && !querying)
            action.accept(index, selected.variant().id());
    }

    private void updateControls() {
        boolean busy = loading || querying;
        boolean unavailable = index == null || loading;
        boolean selected = results.getSelectionModel().getSelectedItem() != null;
        open.setDisable(loading);
        search.setDisable(unavailable);
        kind.setDisable(unavailable);
        previous.setDisable(busy || offset == 0);
        next.setDisable(busy || offset + PAGE_SIZE >= total);
        detailTab.setDisable(busy || !selected);
        audition.setDisable(busy || !selected);
        apply.setDisable(busy || !selected || !editable);
        apply.setText(editable ? "Add to project" : "Editable copy required");
        apply.setAccessibleHelp(editable ? "Add this variant to the current project"
                : "Save an editable copy of the native arrangement to add catalogue instruments");
        range.setText(busy || index == null ? "" : total == 0 ? "0 variants"
                : (offset + 1) + "–" + (offset + results.getItems().size()) + " of " + total);
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setTooltip(new Tooltip(message));
    }

    private <T> void run(Callable<T> work, Consumer<T> success, Consumer<String> failure) {
        cancelPending();
        long expected = generation;
        pending = worker.submit(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> {
                    if (!closed && generation == expected) success.accept(result);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (!closed && generation == expected)
                        failure.accept(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                });
            }
        });
    }

    private void cancelPending() {
        generation++;
        if (pending != null) pending.cancel(true);
        worker.purge();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        debounce.stop();
        cancelPending();
        worker.shutdownNow();
    }

    private static String rowLabel(Match match) {
        var variant = match.variant();
        var recipe = variant.recipe();
        var label = new StringBuilder(variant.name()).append(" · ").append(kindName(match.asset().kind()));
        if (variant.target() != com.opensmpsdeck.project.TargetGame.S3K)
            label.append(" · ").append(variant.driver().toUpperCase(java.util.Locale.ROOT));
        if (recipe.rate() != null) label.append(" · rate %02X".formatted(recipe.rate()));
        if (recipe.pan() != null) label.append(" · pan %02X".formatted(recipe.pan()));
        if (match.asset().kind() == Kind.PSG_NOISE && !recipe.notes().isEmpty()) {
            label.append(" · MIDI ").append(recipe.notes().getFirst().pitch());
            label.append(" · ").append(recipe.durationTicks()).append(" ticks");
        }
        return label.toString();
    }

    private static String describe(Match match) {
        var asset = match.asset();
        var variant = match.variant();
        var recipe = variant.recipe();
        var body = new StringBuilder(variant.name()).append("\n");
        fact(body, "Instrument", asset.name() + " · " + kindName(asset.kind()));
        fact(body, "Roles", String.join(", ", variant.roles()));
        body.append("\nPlayback recipe\n");
        fact(body, "Rate", recipe.rate());
        fact(body, "Pan", recipe.pan());
        fact(body, "Volume", recipe.volume());
        fact(body, "Duration", recipe.durationTicks() + " ticks");
        fact(body, "Volume envelope", recipe.volumeEnvelope());
        fact(body, "Modulation envelope", recipe.modulationEnvelope());
        recipe.notes().forEach(note -> fact(body, "Note", note.pitch() + " at tick " + note.tick() + " for " + note.duration() + " ticks"));
        recipe.commands().forEach(command -> fact(body, "Command", command.command() + " at tick " + command.tick()
                + " " + command.arguments() + (command.assetId() == null ? "" : " · " + command.assetId())));
        byte[] bytes = asset.bytes();
        if (asset.kind() == Kind.DAC_SAMPLE) fact(body, "PCM", bytes.length + " bytes");
        else fact(body, "Native bytes", HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes));
        body.append("\nSource occurrences (").append(variant.occurrences().size()).append(")\n");
        for (Occurrence occurrence : variant.occurrences()) {
            body.append("\n");
            fact(body, "Source", occurrence.source());
            fact(body, "Driver", occurrence.driver());
            fact(body, "Revision", occurrence.revision());
            fact(body, "Song", occurrence.song());
            fact(body, "Song ID", occurrence.songId());
            fact(body, "Table", occurrence.table());
            fact(body, "Slot", occurrence.slot());
            fact(body, "Lane", occurrence.lane());
            fact(body, "Tick", occurrence.tick());
            fact(body, "Played", occurrence.played() ? "Yes" : "No");
        }
        body.append("\nCatalogue identity\n");
        fact(body, "Asset ID", asset.id());
        fact(body, "Variant ID", variant.id());
        return body.toString();
    }

    private static void fact(StringBuilder body, String label, Object value) {
        if (value != null && !value.toString().isBlank())
            body.append(label).append(": ").append(value).append("\n");
    }

    private static String kindName(Kind kind) {
        return switch (kind) {
            case FM_VOICE -> "FM voice";
            case PSG_VOLUME -> "PSG volume envelope";
            case MODULATION -> "Modulation envelope";
            case DAC_SAMPLE -> "DAC sample";
            case PSG_NOISE -> "PSG noise";
        };
    }

    private record KindFilter(Kind kind) {
        @Override public String toString() { return kind == null ? "All kinds" : kindName(kind); }
    }

    private static Button button(String text, String id) {
        var button = new Button(text);
        button.setId(id);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }
}

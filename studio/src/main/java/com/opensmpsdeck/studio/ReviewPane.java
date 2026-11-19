package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.AudioReference;
import com.opensmpsdeck.project.ReviewNote;
import com.opensmpsdeck.project.StudioProject;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Side list and composer. Committing either action uses the window's shared edit stack. */
final class ReviewPane extends VBox {
    record Location(double start, Double end, String lane, String clip) {}

    final FlowPane toolbar = new FlowPane(8, 6);
    final ComboBox<ReviewTransport.Mode> mode = new ComboBox<>();
    final ComboBox<AudioReference> references = new ComboBox<>();
    final Button loop = button("Loop selection", "review-loop");
    final Button addNote = button("Add note", "add-review-note");
    final Button addReference = button("Add reference…", "add-reference");
    final Label abSide = new Label();
    private final ListView<ReviewNote> open = new ListView<>();
    private final TextArea text = new TextArea();
    private final Label locationLabel = new Label();
    private final Button create = button("Add", "submit-review-note");
    private final Button cancel = button("Cancel", "cancel-review-note");
    private final Button resolve = button("Resolve selected", "resolve-review-note");
    private final VBox composer = new VBox(6, locationLabel, text, new HBox(6, create, cancel));
    private Location location;
    private boolean updating;
    private ReviewNote pressedNote;
    Consumer<ReviewNote> jump = ignored -> {};
    Consumer<ReviewNote> submit = ignored -> {};
    Consumer<ReviewNote> resolveNote = ignored -> {};

    ReviewPane() {
        super(8);
        setId("review-notes");
        setPadding(new Insets(10));
        setPrefWidth(232);
        setMinWidth(170);
        getStyleClass().add("inspector");
        toolbar.getStyleClass().add("panelbar");
        mode.setId("review-source");
        mode.getItems().setAll(ReviewTransport.Mode.values());
        mode.setValue(ReviewTransport.Mode.RENDER);
        references.setId("review-reference-picker");
        references.setPrefWidth(200);
        references.setConverter(new StringConverter<>() {
            @Override public String toString(AudioReference ref) {
                return ref == null ? "" : ref.role() == null ? ref.label() : ref.label() + " · " + ref.role();
            }
            @Override public AudioReference fromString(String label) { return null; }
        });
        references.setPromptText("Reference WAV");
        abSide.getStyleClass().add("muted");
        toolbar.getChildren().addAll(addReference, references, mode, abSide, loop, addNote);
        var heading = new Label("OPEN REVIEW NOTES");
        heading.getStyleClass().add("section");
        open.setId("open-review-notes");
        open.setPlaceholder(new Label("No open review notes"));
        open.setCellFactory(ignored -> new ListCell<>() {
            private final Text body = new Text();
            {
                body.wrappingWidthProperty().bind(widthProperty().subtract(20));
            }
            @Override protected void updateItem(ReviewNote note, boolean empty) {
                super.updateItem(note, empty);
                setGraphic(empty ? null : body);
                if (!empty) {
                    body.setText(time(note.startSeconds()) + " · " + note.author() + "\n" + note.text());
                    body.setFill(Color.web("agent".equalsIgnoreCase(note.author()) ? "#c0adff" : "#dcebf7"));
                }
            }
        });
        open.getSelectionModel().selectedItemProperty().addListener((o, before, note) -> {
            if (!updating && note != null) jump.accept(note);
        });
        open.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                event -> pressedNote = open.getSelectionModel().getSelectedItem());
        open.setOnMouseClicked(event -> {
            var note = open.getSelectionModel().getSelectedItem();
            if (note != null && note == pressedNote) jump.accept(note);
            pressedNote = null;
        });
        resolve.disableProperty().bind(open.getSelectionModel().selectedItemProperty().isNull());
        resolve.setOnAction(event -> resolveNote.accept(open.getSelectionModel().getSelectedItem()));
        text.setId("review-note-text");
        text.setPromptText("Describe what to check");
        text.setWrapText(true);
        text.setPrefRowCount(3);
        create.disableProperty().bind(text.textProperty().isEmpty());
        create.setOnAction(event -> {
            if (location == null || text.getText().isBlank()) return;
            submit.accept(new ReviewNote("note-" + UUID.randomUUID(), location.start(), location.end(),
                    location.lane(), location.clip(), text.getText().strip(), "human", ReviewNote.OPEN,
                    Instant.now().toString(), null));
        });
        cancel.setOnAction(event -> resetComposer());
        composer.managedProperty().bind(composer.visibleProperty());
        locationLabel.setWrapText(true);
        resetComposer();
        VBox.setVgrow(open, Priority.ALWAYS);
        getChildren().addAll(heading, composer, open, resolve);
    }

    void update(StudioProject project) {
        updating = true;
        var selected = references.getValue();
        if (!references.getItems().equals(project.references())) {
            references.getItems().setAll(project.references());
            var next = project.references().stream()
                    .filter(ref -> selected != null && ref.id().equals(selected.id())).findFirst()
                    .orElse(project.references().isEmpty() ? null : project.references().getFirst());
            references.setValue(next);
        }
        references.setDisable(project.references().isEmpty());
        var notes = project.notes().stream().filter(ReviewNote::isOpen).toList();
        if (!open.getItems().equals(notes)) {
            var previous = open.getSelectionModel().getSelectedItem();
            open.getItems().setAll(notes);
            if (previous != null && notes.contains(previous)) open.getSelectionModel().select(previous);
        }
        updating = false;
    }

    boolean ownsFocus(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == this || current == toolbar) return true;
        }
        return false;
    }

    boolean updating() { return updating; }

    void compose(Location where) {
        if (location == null) {
            location = Objects.requireNonNull(where);
            locationLabel.setText(time(where.start()) + (where.end() == null ? "" : " – " + time(where.end()))
                    + (where.lane() == null ? "" : " · " + where.lane()));
            composer.setVisible(true);
        }
        text.requestFocus();
    }

    void resetComposer() {
        location = null;
        text.clear();
        composer.setVisible(false);
    }

    private static String time(double value) { return String.format(Locale.ROOT, "%.3f s", value); }

    private static Button button(String label, String id) {
        var button = new Button(label);
        button.setId(id);
        return button;
    }
}

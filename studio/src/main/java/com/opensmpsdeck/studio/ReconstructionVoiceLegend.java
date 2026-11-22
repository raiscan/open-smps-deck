package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionVoicePlan.Kind;
import com.opensmpsdeck.studio.ReconstructionVoices.Entry;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.canvas.Canvas;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

final class ReconstructionVoiceLegend extends TitledPane {
    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");
    private final VBox rows = new VBox(3);
    private final ReconstructionCanvas view;
    private final Function<ReconstructionBulk.Voice, Boolean> assign;
    private final Function<ReconstructionBulk.Voice, ContextMenu> actions;
    private final Consumer<Entry> editTag;
    private final CheckBox showDrums = new CheckBox("Show drums");
    private final MenuButton create = new MenuButton("New voice");
    private final MenuItem createFromSelection = new MenuItem("From selected notes…");
    private final ScrollPane viewport = new ScrollPane(rows);
    private final ToggleGroup solos = new ToggleGroup();
    private boolean updating;
    private final SimpleBooleanProperty editing = new SimpleBooleanProperty();
    private final SimpleBooleanProperty hasSelection = new SimpleBooleanProperty();

    ReconstructionVoiceLegend(ReconstructionCanvas view, Function<ReconstructionBulk.Voice, Boolean> assign,
                              Consumer<Kind> add, Function<ReconstructionBulk.Voice, ContextMenu> actions,
                              Consumer<Entry> editTag) {
        this.view = view;
        this.assign = assign;
        this.actions = actions;
        this.editTag = editTag;
        setId("reconstruction-voice-legend");
        setText("Voices");
        setAnimated(false);
        getStyleClass().add("voice-legend");

        var tonal = new MenuItem("Empty tonal voice…");
        var drum = new MenuItem("Empty drum voice…");
        createFromSelection.setOnAction(event -> add.accept(null));
        tonal.setOnAction(event -> add.accept(Kind.tonal));
        drum.setOnAction(event -> add.accept(Kind.drum));
        create.setId("reconstruction-new-voice");
        create.getItems().addAll(createFromSelection, tonal, drum);
        create.disableProperty().bind(editing.not());
        createFromSelection.disableProperty().bind(hasSelection.not());
        showDrums.setId("reconstruction-show-drums");
        showDrums.setTooltip(new Tooltip("Show drum hits in a separate strip in Roles"));
        showDrums.managedProperty().bind(showDrums.visibleProperty());
        showDrums.setVisible(false);
        showDrums.selectedProperty().addListener((ignored, old, show) -> view.showDrums(show));
        var header = new HBox(8, showDrums, create);
        header.setAlignment(Pos.CENTER_RIGHT);
        viewport.getStyleClass().add("editor-scroll");
        viewport.setFitToWidth(true);
        VBox.setVgrow(viewport, Priority.ALWAYS);
        var content = new VBox(4, header, viewport);
        content.getStyleClass().add("voice-legend-content");
        setContent(content);
        solos.selectedToggleProperty().addListener((ignored, old, next) -> {
            if (!updating) view.soloVoice(next == null ? null : (String) next.getUserData());
        });
    }

    void show(List<Entry> entries) {
        var selected = solos.getSelectedToggle();
        String solo = selected == null ? null : (String) selected.getUserData();
        updating = true;
        solos.getToggles().clear();
        rows.getChildren().clear();
        for (var entry : entries) rows.getChildren().add(row(entry, solo));
        if (entries.isEmpty()) rows.getChildren().add(new Label("No voices"));
        updating = false;
        if (solos.getSelectedToggle() == null) view.soloVoice(null);
        // Keep short lists compact and scroll long arrangements.
        double height = Math.min(8, Math.max(1, entries.size())) * 43;
        viewport.setPrefViewportHeight(height);
        viewport.setMaxHeight(height + 2);
    }

    void rolesView(boolean roles) {
        showDrums.setVisible(roles);
    }

    void controls(boolean editable, boolean selected) {
        editing.set(editable);
        hasSelection.set(selected);
    }

    private HBox row(Entry entry, String solo) {
        String name = entry.tag() + " · " + entry.shortName();
        String role = entry.roles();
        int count = entry.notes().size();
        String summary = count + (count == 1 ? " note" : " notes") + " · " + role;
        var swatch = new Canvas(16, 12);
        var g = swatch.getGraphicsContext2D();
        if (entry.drum()) entry.drumMark().draw(g, entry.style().color(), 8, 6);
        else entry.style().draw(g, 0, 0, 16, 12, .85);
        var title = new Label(name);
        title.getStyleClass().add("voice-name");
        var subtitle = new Label(summary);
        subtitle.getStyleClass().add("muted");
        var caption = new VBox(title, subtitle);
        caption.setMinWidth(0);

        var graphic = new HBox(7, swatch, caption);
        graphic.setAlignment(Pos.CENTER_LEFT);
        var select = new Button("", graphic);
        select.setAlignment(Pos.CENTER_LEFT);
        select.setId("reconstruction-voice-select-" + entry.id());
        select.setAccessibleText("Select all notes in " + entry.name());
        select.setTooltip(new Tooltip(entry.name() + "\n" + summary + "\nClick to select these notes"));
        select.setDisable(count == 0);
        select.setOnAction(event -> view.selectNotes(entry.notes()));
        select.setMaxWidth(Double.MAX_VALUE);
        select.setMinWidth(0);
        HBox.setHgrow(select, Priority.ALWAYS);

        var visible = new CheckBox();
        visible.setAccessibleText("Show " + entry.name());
        visible.setTooltip(new Tooltip("Show or hide " + entry.shortName()));
        visible.setSelected(view.voiceVisible(entry.id()));
        visible.selectedProperty().addListener((ignored, old, show) -> view.voiceVisible(entry.id(), show));

        var focus = new ToggleButton("S");
        focus.setAccessibleText("Solo highlight " + entry.name());
        focus.setTooltip(new Tooltip("Solo highlight " + entry.shortName()));
        focus.setUserData(entry.id());
        focus.setToggleGroup(solos);
        focus.setSelected(Objects.equals(solo, entry.id()));

        var row = new HBox(5, select, visible, focus);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("voice-legend-row");
        if (entry.voice() == null) return row;

        var move = new Button("→");
        move.setId("reconstruction-voice-assign-" + entry.id());
        move.setAccessibleText("Assign selection to " + entry.name());
        move.setTooltip(new Tooltip("Assign selected notes to " + entry.shortName()));
        move.setOnAction(event -> assign.apply(entry.voice()));

        var menu = new Button("⋯");
        menu.setAccessibleText("Manage " + entry.name());
        menu.setTooltip(new Tooltip("Edit tag, rename, split or merge this voice"));
        menu.setOnAction(event -> menu(entry).show(menu, javafx.geometry.Side.BOTTOM, 0, 0));
        row.getChildren().addAll(move, menu);
        move.disableProperty().bind(editing.not().or(hasSelection.not()));

        row.setOnDragOver(event -> {
            if (!editing.get() || event.getGestureSource() != view.canvas) return;
            event.acceptTransferModes(TransferMode.MOVE);
            row.pseudoClassStateChanged(DROP_TARGET, true);
            event.consume();
        });
        row.setOnDragExited(event -> row.pseudoClassStateChanged(DROP_TARGET, false));
        row.setOnDragDropped(event -> {
            if (!editing.get() || event.getGestureSource() != view.canvas) return;
            event.setDropCompleted(assign.apply(entry.voice()));
            row.pseudoClassStateChanged(DROP_TARGET, false);
            event.consume();
        });
        row.setOnContextMenuRequested(event -> {
            menu(entry).show(row, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        return row;
    }

    private ContextMenu menu(Entry entry) {
        var menu = editing.get() ? actions.apply(entry.voice()) : new ContextMenu();
        var tag = new MenuItem("Edit tag…");
        tag.setOnAction(event -> editTag.accept(entry));
        menu.getItems().addFirst(tag);
        return menu;
    }
}

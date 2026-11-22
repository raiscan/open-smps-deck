package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionVoicePlan.Kind;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
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
    private final ToggleGroup roleSolos = new ToggleGroup();
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
        setText("Voice and role legend");
        setCollapsible(false);
        setMinHeight(0);
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
        viewport.setMinHeight(0);
        VBox.setVgrow(viewport, Priority.ALWAYS);
        var roleRows = new VBox(3);
        for (Role role : Role.values()) roleRows.getChildren().add(roleRow(role));
        roleSolos.selectedToggleProperty().addListener((ignored, old, next) ->
                view.soloRole(next == null ? null : (Role) next.getUserData()));
        var roleList = new TitledPane("Musical roles", roleRows);
        roleList.setAnimated(false);
        roleList.setExpanded(false);
        var content = new VBox(6, roleList, header, viewport);
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
        viewport.setPrefViewportHeight(Math.min(8, Math.max(1, entries.size())) * 43);
    }

    void rolesView(boolean roles) {
        showDrums.setVisible(roles);
    }

    void controls(boolean editable, boolean selected) {
        editing.set(editable);
        hasSelection.set(selected);
    }

    private static ToggleButton visibilityButton() {
        var eye = new javafx.scene.shape.SVGPath();
        eye.setContent("M 0 5 Q 6 -2 12 5 Q 6 12 0 5 Z M 8 5 A 2 2 0 1 0 4 5 A 2 2 0 1 0 8 5 Z");
        eye.getStyleClass().add("visibility-icon");
        return new ToggleButton("", eye);
    }

    private HBox roleRow(Role role) {
        String name = ReconstructionCanvas.roleName(role);
        var label = new Label(name);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        var visible = visibilityButton();
        visible.setTooltip(new Tooltip("Show or hide " + name + " notes"));
        visible.setAccessibleText(visible.getTooltip().getText());
        visible.setSelected(true);
        visible.setOnAction(event -> view.roleVisible(role, visible.isSelected()));
        var focus = new ToggleButton("◎");
        focus.setTooltip(new Tooltip("Solo highlight " + name + " notes"));
        focus.setAccessibleText(focus.getTooltip().getText());
        focus.setUserData(role);
        focus.setToggleGroup(roleSolos);
        var select = new Button("↖");
        select.setId("reconstruction-role-" + role.name().toLowerCase(java.util.Locale.ROOT));
        select.setAccessibleText("Select all " + name + " notes");
        select.setTooltip(new Tooltip(select.getAccessibleText() + ". Shift-click or Ctrl-click to add."));
        select.setOnAction(event -> view.selectRole(role, false));
        select.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                    && (event.isShiftDown() || event.isControlDown() || event.isMetaDown())) {
                view.selectRole(role, true);
                event.consume();
            }
        });
        var row = new HBox(5, label, visible, focus, select);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
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
        for (var label : List.of(title, subtitle)) {
            label.setMinWidth(0);
            label.setMaxWidth(Double.MAX_VALUE);
        }
        var caption = new VBox(title, subtitle);
        caption.setMinWidth(0);
        HBox.setHgrow(caption, Priority.ALWAYS);
        var cursor = new Label("↖");
        cursor.setMinWidth(Control.USE_PREF_SIZE);
        var graphic = new HBox(7, swatch, caption, cursor);
        graphic.setAlignment(Pos.CENTER_LEFT);
        graphic.setMinWidth(0);
        var select = new Button("", graphic);
        select.setAlignment(Pos.CENTER_LEFT);
        select.setId("reconstruction-voice-select-" + entry.id());
        select.setAccessibleText("Select all notes in " + entry.name());
        select.setTooltip(new Tooltip(entry.name() + "\n" + summary + "\nClick to select these notes"));
        select.setDisable(count == 0);
        select.setOnAction(event -> view.selectNotes(entry.notes()));
        select.setMaxWidth(Double.MAX_VALUE);
        select.setMinWidth(0);
        select.setPrefWidth(172);
        graphic.prefWidthProperty().bind(select.widthProperty().subtract(10));
        graphic.maxWidthProperty().bind(graphic.prefWidthProperty());
        HBox.setHgrow(select, Priority.ALWAYS);

        var visible = visibilityButton();
        visible.setAccessibleText("Show " + entry.name());
        visible.setTooltip(new Tooltip("Show or hide " + entry.shortName()));
        visible.setSelected(view.voiceVisible(entry.id()));
        visible.selectedProperty().addListener((ignored, old, show) -> view.voiceVisible(entry.id(), show));

        var focus = new ToggleButton("◎");
        focus.setAccessibleText("Solo highlight " + entry.name());
        focus.setTooltip(new Tooltip("Solo highlight " + entry.shortName()));
        focus.setUserData(entry.id());
        focus.setToggleGroup(solos);
        focus.setSelected(Objects.equals(solo, entry.id()));

        var row = new HBox(5, select, visible, focus);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("voice-legend-row");
        if (entry.voice() == null) return row;

        var move = new Button("Assign");
        move.setId("reconstruction-voice-assign-" + entry.id());
        move.setAccessibleText("Assign selection to " + entry.name());
        move.setTooltip(new Tooltip("Assign selected notes to " + entry.shortName()));
        move.setOnAction(event -> assign.apply(entry.voice()));

        var menu = new Button("⋯");
        menu.setAccessibleText("Manage " + entry.name());
        menu.setTooltip(new Tooltip("Edit tag, rename, split or merge this voice"));
        menu.setOnAction(event -> menu(entry).show(menu, javafx.geometry.Side.BOTTOM, 0, 0));
        for (var control : List.of(visible, focus, move, menu)) control.setMinWidth(Control.USE_PREF_SIZE);
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

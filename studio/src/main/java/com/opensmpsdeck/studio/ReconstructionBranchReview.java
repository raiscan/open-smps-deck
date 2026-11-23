package com.opensmpsdeck.studio;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class ReconstructionBranchReview {
    enum Scope {
        BRANCH("Whole branch"), PART("By part"), REGION("By region"), GROUP("By edit group");

        private final String label;
        Scope(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    record Part(int id, String name) {
        @Override public String toString() { return name.isBlank() ? "Part " + id : name; }
    }

    record Group(String id, String name, String rationale) {
        @Override public String toString() { return name; }
    }

    record Branch(String name, String author, String revision, boolean stale, int editCount,
                  String status, List<Group> groups, ReconstructionVoices.Style style) {}

    record Take(String branch, Part part, ReviewTransport.Range range, Group group) {
        @Override public String toString() {
            String scope = part != null ? part.toString() : group != null ? group.name()
                    : range == null ? "Whole branch"
                    : String.format(Locale.ROOT, "%.2f–%.2f s", range.start(), range.end());
            return branch + " · " + scope;
        }
    }

    private final Map<String, Label> statuses = new HashMap<>();
    private final VBox list = new VBox(6);
    private final VBox details = new VBox(8);
    private final VBox stitch = new VBox(8);
    private final VBox merge = new VBox(7);
    private final VBox conflicts = new VBox(8);
    private final Label diff = description("");
    private final Label feedback = description("");
    private final Label guidance = description("Choose a branch to inspect its changes.");
    private final Button apply = button("Apply to main", "reconstruction-branch-apply");
    private final ComboBox<Scope> scope = new ComboBox<>();
    private final ComboBox<Part> part = new ComboBox<>();
    private final ComboBox<Group> group = new ComboBox<>();
    private final Button take = button("Add to merge", "reconstruction-branch-take");
    private final Button rebase = button("Rebase branch", "reconstruction-branch-rebase");
    private final Button listen = button("Hear branch", "reconstruction-branch-listen");
    private final ToggleGroup rows = new ToggleGroup();
    private final Node region;
    final Tab tab;
    private Branch selected;
    private Consumer<Branch> onSelect = ignored -> {};
    private BiConsumer<Branch, Boolean> onOverlay = (ignored, value) -> {};
    private Consumer<Take> onTake = ignored -> {};

    ReconstructionBranchReview(Node region, Runnable refresh, Consumer<Branch> rebaseAction,
                               Consumer<Branch> hearAction, Runnable applyAction) {
        this.region = region;
        var refreshButton = button("Refresh", "reconstruction-branch-refresh");
        refreshButton.setOnAction(event -> refresh.run());
        var title = new Label("Edit branches");
        title.getStyleClass().add("inspector-title");
        var header = new HBox(8, title, refreshButton);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);
        list.setId("reconstruction-branch-list");
        feedback.setId("reconstruction-branch-feedback");
        diff.setId("reconstruction-branch-summary");
        scope.setId("reconstruction-branch-take-scope");
        scope.getItems().setAll(Scope.values());
        scope.setValue(Scope.BRANCH);
        part.setId("reconstruction-branch-take-part");
        part.setPromptText("Choose a part");
        group.setId("reconstruction-branch-take-group");
        group.setPromptText("Choose an edit group");
        for (var node : List.of(part, group, take, region, details, feedback, merge, conflicts, rebase))
            node.managedProperty().bind(node.visibleProperty());
        scope.valueProperty().addListener((obs, before, after) -> updateTake());
        part.valueProperty().addListener((obs, before, after) -> updateTake());
        group.valueProperty().addListener((obs, before, after) -> updateTake());
        take.setOnAction(event -> onTake.accept(new Take(selected.name(),
                scope.getValue() == Scope.PART ? part.getValue() : null, null,
                scope.getValue() == Scope.GROUP ? group.getValue() : null)));
        rebase.setOnAction(event -> rebaseAction.accept(selected));
        listen.setOnAction(event -> hearAction.accept(selected));
        apply.getStyleClass().add("primary-action");
        apply.setOnAction(event -> applyAction.run());
        stitch.getChildren().setAll(new Label("Choose edits"), scope, part, group, new FlowPane(6, 5, take, region));
        updateTake();
        details.setVisible(false);
        feedback.setVisible(false);
        merge.setVisible(false);
        var content = new VBox(12, header, guidance, list, details, feedback, merge);
        content.getStyleClass().addAll("inspector", "reconstruction-branch-review");
        var scroll = new ScrollPane(content);
        scroll.getStyleClass().add("reconstruction-inspector-scroll");
        scroll.setFitToWidth(true);
        tab = new Tab("Branches", scroll);
        tab.setId("reconstruction-branches-tab");
        rows.selectedToggleProperty().addListener((obs, before, after) -> {
            if (after != null) {
                selected = (Branch) after.getUserData();
                showDetails();
                onSelect.accept(selected);
            }
        });
    }

    void onSelect(Consumer<Branch> action) { onSelect = action; }
    void onOverlay(BiConsumer<Branch, Boolean> action) { onOverlay = action; }
    void onTake(Consumer<Take> action) { onTake = action; }

    void branches(List<Branch> branches, String keep, Set<String> overlays) {
        selected = null;
        rows.getToggles().clear();
        list.getChildren().clear();
        statuses.clear();
        details.setVisible(false);
        guidance.setText(branches.isEmpty()
                ? "Agent and human edit branches will appear here. Refresh after saving one."
                : "Choose a branch to inspect its changes.");
        guidance.setVisible(true);
        guidance.setManaged(true);
        for (var branch : branches) {
            var name = new ToggleButton(branch.name(), ReconstructionBranchOverlay.swatch(branch.style()));
            name.setId("reconstruction-branch-" + branch.name());
            name.setUserData(branch);
            name.setToggleGroup(rows);
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            name.setTooltip(new Tooltip(branch.name()));
            name.setOnAction(event -> {
                if (!name.isSelected()) name.setSelected(true);
            });
            name.setAlignment(Pos.CENTER_LEFT);
            var toggle = new CheckBox("Overlay");
            toggle.setId("reconstruction-branch-overlay-" + branch.name());
            toggle.setAccessibleText("Show diff overlay for " + branch.name());
            toggle.setSelected(overlays.contains(branch.name()));
            toggle.setDisable(branch.stale());
            toggle.setOnAction(event -> onOverlay.accept(branch, toggle.isSelected()));
            var row = new HBox(6, name, toggle);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(name, Priority.ALWAYS);
            var meta = description(metadata(branch, branch.status()));
            statuses.put(branch.name(), meta);
            var card = new VBox(4, row, meta);
            card.getStyleClass().add("reconstruction-proposal");
            list.getChildren().add(card);
        }
        rows.getToggles().stream().filter(toggle -> ((Branch) toggle.getUserData()).name().equals(keep))
                .findFirst().ifPresent(rows::selectToggle);
    }

    void status(Branch branch, String status) {
        var label = statuses.get(branch.name());
        if (label != null) label.setText(metadata(branch, status));
    }

    private static String metadata(Branch branch, String status) {
        return branch.author() + " · " + branch.editCount() + (branch.editCount() == 1 ? " edit · " : " edits · ")
                + (branch.stale() && !status.contains("conflict") ? "Needs rebase" : status);
    }

    private void showDetails() {
        guidance.setVisible(false);
        guidance.setManaged(false);
        details.setVisible(true);
        var base = description("Base " + shortRevision(selected.revision())
                + (selected.stale() ? " · Needs rebase" : " · Current"));
        base.setTooltip(new Tooltip(selected.revision()));
        var notes = new VBox(6);
        for (var item : selected.groups()) {
            var entry = new VBox(2, description(item.name()));
            if (!item.rationale().isBlank()) entry.getChildren().add(description(item.rationale()));
            notes.getChildren().add(entry);
        }
        var rationale = new TitledPane("Edit groups and rationale", notes);
        rationale.setId("reconstruction-branch-rationale");
        rationale.setExpanded(false);
        rationale.setAnimated(false);
        rationale.setVisible(!selected.groups().isEmpty());
        rationale.setManaged(rationale.isVisible());
        group.getItems().setAll(selected.groups());
        group.getSelectionModel().clearSelection();
        part.getSelectionModel().clearSelection();
        scope.setValue(Scope.BRANCH);
        rebase.setVisible(selected.stale());
        listen.setDisable(selected.stale());
        updateTake();
        diff.setText("Loading note changes…");
        details.getChildren().setAll(base, new FlowPane(6, 5, listen, rebase), diff, rationale, stitch);
    }

    private static String shortRevision(String revision) {
        return revision.substring(0, Math.min(10, revision.length()));
    }

    void parts(List<Part> parts) { part.getItems().setAll(parts); }

    void summary(List<String> lines) {
        diff.setText(lines.isEmpty() ? "No audible note changes"
                : "+ Added   − Removed   ~ Changed\n" + String.join("\n", lines));
    }

    void summaryUnavailable(String message) { diff.setText(message); }

    void feedback(String message) {
        feedback.setText(message);
        feedback.setVisible(!message.isBlank());
    }

    void merge(List<Take> takes, Consumer<Integer> remove, List<Node> issues, boolean canApply, Runnable hear) {
        merge.getChildren().clear();
        merge.setVisible(!takes.isEmpty());
        if (takes.isEmpty()) return;
        var title = new Label("Merge draft");
        title.getStyleClass().add("inspector-title");
        merge.getChildren().add(title);
        for (int index = 0; index < takes.size(); index++) {
            int at = index;
            var label = description(takes.get(index).toString());
            HBox.setHgrow(label, Priority.ALWAYS);
            label.setMaxWidth(Double.MAX_VALUE);
            var button = button("Remove", "reconstruction-branch-remove-" + index);
            button.setAccessibleText("Remove " + takes.get(index));
            button.setOnAction(event -> remove.accept(at));
            merge.getChildren().add(new HBox(6, label, button));
        }
        conflicts.getChildren().setAll(issues);
        conflicts.setVisible(!issues.isEmpty());
        var audition = button("Hear merge", "reconstruction-branch-hear-merge");
        audition.setDisable(hear == null);
        audition.setOnAction(event -> hear.run());
        apply.setDisable(!canApply);
        merge.getChildren().addAll(conflicts, new FlowPane(6, 5, audition, apply));
    }

    Take region(ReviewTransport.Range range) {
        return selected == null || range == null ? null : new Take(selected.name(), null, range, null);
    }

    private void updateTake() {
        var mode = scope.getValue();
        part.setVisible(mode == Scope.PART);
        group.setVisible(mode == Scope.GROUP);
        region.setVisible(mode == Scope.REGION);
        take.setVisible(mode != Scope.REGION);
        stitch.setDisable(selected == null || selected.stale());
        take.setDisable(mode == Scope.PART && part.getValue() == null
                || mode == Scope.GROUP && group.getValue() == null);
    }

    private static Label description(String text) {
        var label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return label;
    }

    private static Button button(String label, String id) {
        var button = new Button(label);
        button.setId(id);
        return button;
    }
}

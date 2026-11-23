package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionBranches;
import com.opensmpsdeck.headless.ReconstructionBranches.DirectConflict;
import com.opensmpsdeck.headless.ReconstructionBranches.EditRef;
import com.opensmpsdeck.headless.ReconstructionBranches.SourceSelection;
import com.opensmpsdeck.headless.ReconstructionBranches.State;
import com.opensmpsdeck.headless.ReconstructionBranches.StitchPlan;
import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionLivePreview;
import com.opensmpsdeck.headless.ReconstructionLivePreview.Prepared;
import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.headless.ReconstructionStages;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.opensmpsdeck.studio.ReconstructionBranchReview.Branch;
import static com.opensmpsdeck.studio.ReconstructionBranchReview.Take;

/** Coordinates branch inspection and an in-memory stitch using the headless branch planner. */
final class ReconstructionBranchSession implements AutoCloseable {
    private static final ReconstructionStages.Progress QUIET = (done, total, message) -> {};
    private static final Variant PARENT = new Variant("", "A · Parent");
    private static final Variant MERGE = new Variant("", "Merge draft");

    private record Variant(String branch, String label) {
        @Override public String toString() { return label; }
    }

    private record LoadedBranch(List<ReconstructionBranchOverlay.Delta> changes, Prepared parent,
                                Prepared variant, String status) {}

    private record Merge(StitchPlan plan, Prepared audio) {}

    private final Path work;
    private final ReconstructionCanvas canvas;
    private final ReconstructionEditor editor;
    private final ReconstructionTransport transport;
    private final Consumer<String> status;
    private final TabPane tabs;
    private final ReconstructionBranchReview view;
    private final ScreenshotLasso region;
    private final ComboBox<Variant> versions = new ComboBox<>();
    private final HBox versionControl = new HBox(6, new Label("Chip version"), versions);
    private ExecutorService worker;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(180));
    private final Map<String, State> branches = new LinkedHashMap<>();
    private final Map<String, LoadedBranch> loaded = new LinkedHashMap<>();
    private final Map<String, Integer> colours = new LinkedHashMap<>();
    private final Set<String> loading = new LinkedHashSet<>();
    private final Set<String> failed = new LinkedHashSet<>();
    private final Set<String> overlays = new LinkedHashSet<>();
    private final List<Take> takes = new ArrayList<>();
    private final Set<EditRef> excluded = new LinkedHashSet<>();
    private final Map<String, String> rebaseProblems = new LinkedHashMap<>();
    private ReconstructionReview.Revision revision;
    private ReconstructionInspection parentInspection;
    private ReconstructionInspection observedInspection;
    private Prepared parentAudio;
    private Prepared conflictAudio;
    private String selected;
    private Merge merge;
    private Future<?> planJob;
    private long catalogRequest, generation, stitchRequest, auditionRequest;
    private boolean reviewing, closed, updatingVersions, activeVersion, indirectAccepted, applying;
    private boolean previousReady;

    ReconstructionBranchSession(Path work, ReconstructionCanvas canvas, ReconstructionEditor editor,
                                ReconstructionTransport transport, Consumer<String> status) {
        this.work = work;
        this.canvas = canvas;
        this.editor = editor;
        this.transport = transport;
        this.status = status;
        tabs = editor.reviewInspector();
        revision = editor.branchRevision();
        observedInspection = canvas.inspection();
        region = ScreenshotLasso.region(canvas, this::pickRegion, status, canvas::panning);
        view = new ReconstructionBranchReview(region.controls(), this::reload, this::rebase,
                branch -> choose(branch.name()), this::apply);
        view.onSelect(this::selected);
        view.onOverlay((branch, show) -> {
            if (show) {
                overlays.add(branch.name());
                load(branch.name());
            } else overlays.remove(branch.name());
            drawOverlays();
        });
        view.onTake(this::add);
        view.tab.selectedProperty().addListener((obs, old, active) -> {
            if (!active) region.cancel();
        });
        tabs.getTabs().add(view.tab);

        versions.setId("reconstruction-branch-variant");
        versions.setAccessibleText("Chip version");
        versions.setTooltip(new Tooltip("Compare Parent, branches and the merge at the current playhead"));
        versions.setPrefWidth(148);
        versionControl.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        versionControl.setVisible(false);
        versionControl.managedProperty().bind(versionControl.visibleProperty());
        versions.valueProperty().addListener((obs, before, after) -> {
            if (!updatingVersions && after != null) versionSelected(after);
        });
        transport.onLiveReady = this::auditionReady;
        editor.onCompare = () -> parent(true);
        editor.onJournalState = this::journalState;
        reload();
    }

    private void pickRegion(javafx.geometry.Rectangle2D rectangle) {
        var take = view.region(canvas.branchRegion(rectangle));
        if (take != null) add(take);
    }

    Node versionControl() { return versionControl; }

    void reviewing(boolean active) {
        if (reviewing == active) return;
        reviewing = active;
        if (active) reload();
        else {
            region.cancel();
            stopWorker();
            view.feedback("");
            if (!takes.isEmpty()) updateMergeView();
        }
    }

    private void reload() {
        if (closed) return;
        long request = ++catalogRequest;
        var inspection = canvas.inspection();
        run(() -> {
            var states = new LinkedHashMap<String, State>();
            for (var branch : ReconstructionBranches.list(work))
                states.put(branch.name(), ReconstructionBranches.state(work, branch.name()));
            return states;
        }, states -> {
            if (request != catalogRequest) return;
            boolean firstBranches = branches.isEmpty() && !states.isEmpty();
            boolean changed = !states.equals(branches) || inspection != parentInspection;
            if (!changed) {
                if (reviewing && merge == null && !takes.isEmpty() && planJob == null) plan();
                checkNext();
                return;
            }
            generation++;
            loading.clear();
            failed.clear();
            loaded.clear();
            parentAudio = null;
            parentInspection = inspection;
            parent(true);
            branches.clear();
            branches.putAll(states);
            overlays.retainAll(states.keySet());
            if (!states.containsKey(selected)) selected = null;
            view.parts(inspection.parts().stream().map(part ->
                    new ReconstructionBranchReview.Part(part.id(), part.name())).toList());
            var items = states.values().stream().map(this::display).toList();
            view.branches(items, selected, overlays);
            if (firstBranches && (reviewing || tabs.getSelectionModel().getSelectedIndex() == 0))
                tabs.getSelectionModel().select(view.tab);
            editor.branchCount(states.size());
            view.feedback("");
            if (reviewing) {
                for (String branch : overlays) load(branch);
                if (!takes.isEmpty() && !applying) plan();
            } else if (!takes.isEmpty()) {
                merge = null;
                updateMergeView();
            }
            drawOverlays();
            updateVersions(PARENT);
            checkNext();
        }, message -> {
            if (request == catalogRequest) status.accept("Could not load edit branches · " + message);
        });
    }

    private Branch display(State state) {
        var branch = state.branch();
        int slot = colours.computeIfAbsent(branch.name(), name -> colours.size());
        var groups = new LinkedHashMap<String, ReconstructionBranchReview.Group>();
        for (var item : branch.groups())
            groups.put(item.id(), new ReconstructionBranchReview.Group(item.id(), item.label(), item.rationale()));
        for (var edit : branch.edits()) {
            String group = ReconstructionDraft.text(edit, "group", "");
            if (!group.isBlank()) groups.putIfAbsent(group, new ReconstructionBranchReview.Group(group, group, ""));
        }
        int count = branch.edits().size() + (branch.removedEditIds() == null ? 0 : branch.removedEditIds().size());
        return new Branch(branch.name(), branch.author(), branch.parent().revision(), state.stale(), count,
                rebaseProblems.getOrDefault(branch.name(), "Select to inspect"), List.copyOf(groups.values()),
                ReconstructionBranchOverlay.style(slot));
    }

    private void selected(Branch branch) {
        selected = branch.name();
        if (branch.stale()) {
            view.summaryUnavailable(rebaseProblems.getOrDefault(selected, "Rebase to compare against the current parent."));
            return;
        }
        var result = loaded.get(selected);
        if (result != null) view.summary(ReconstructionBranchOverlay.summary(result.changes()));
        else if (reviewing) load(selected);
    }

    private void checkNext() {
        if (!reviewing || !loading.isEmpty()) return;
        branches.values().stream().filter(state -> !state.stale())
                .map(state -> state.branch().name())
                .filter(name -> !loaded.containsKey(name) && !failed.contains(name))
                .findFirst().ifPresent(this::load);
    }

    private void load(String name) {
        var state = branches.get(name);
        if (state == null || state.stale() || loaded.containsKey(name) || !loading.add(name)) return;
        view.status(display(state), "Checking…");
        long request = generation;
        run(() -> {
            var base = ReconstructionInspection.open(ReconstructionBranches.buildMain(work, QUIET));
            var output = ReconstructionBranches.build(work, name);
            var variant = ReconstructionInspection.open(output);
            var diff = ReconstructionBranches.diff(work, name, QUIET);
            var changes = diff.getAsJsonArray("changes").asList().stream()
                    .map(change -> change.getAsJsonObject().get("id").getAsString())
                    .collect(java.util.stream.Collectors.toSet());
            var statuses = diff.getAsJsonObject("outcomes").asMap().values().stream()
                    .flatMap(value -> value.getAsJsonArray().asList().stream())
                    .map(result -> result.getAsJsonObject().get("status").getAsString())
                    .collect(java.util.stream.Collectors.toSet());
            if (!state.equals(ReconstructionBranches.state(work, name))) throw new ReconstructionReview.Changed();
            return new LoadedBranch(ReconstructionBranchOverlay.diff(base, variant, changes), prepare(base), prepare(variant),
                    statuses.contains("conflict") || statuses.contains("invalid") ? "Conflicts"
                            : statuses.contains("orphaned") || statuses.contains("unresolved") ? "Orphaned edits" : "Clean");
        }, result -> {
            if (request != generation) return;
            loading.remove(name);
            loaded.put(name, result);
            parentAudio = result.parent();
            view.status(display(state), result.status());
            if (name.equals(selected)) view.summary(ReconstructionBranchOverlay.summary(result.changes()));
            drawOverlays();
            if (versions.getValue() != null && name.equals(versions.getValue().branch())) auditionReady();
            checkNext();
        }, message -> {
            if (request != generation) return;
            loading.remove(name);
            failed.add(name);
            view.status(display(state), "Could not build");
            if (name.equals(selected)) view.summaryUnavailable(message);
            if (versions.getValue() != null && name.equals(versions.getValue().branch())) {
                parent(true);
                status.accept("Branch audition unavailable · " + message);
            }
            checkNext();
        });
    }

    private static Prepared prepare(ReconstructionInspection inspection) {
        return inspection.project() == null ? null : ReconstructionLivePreview.forComparison(inspection.project());
    }

    private void drawOverlays() {
        canvas.branchLayers(overlays.stream().filter(loaded::containsKey)
                .map(name -> new ReconstructionBranchOverlay.Layer(display(branches.get(name)).style(), loaded.get(name).changes()))
                .toList());
    }

    private void rebase(Branch branch) {
        view.feedback("Rebasing " + branch.name() + "…");
        run(() -> ReconstructionBranches.rebase(work, branch.name(), QUIET), result -> {
            if (result.get("rebased").getAsBoolean()) {
                rebaseProblems.remove(branch.name());
                reload();
            } else {
                String message = result.getAsJsonArray("directConflicts").size() + " conflicts with main";
                rebaseProblems.put(branch.name(), message);
                view.status(branch, "Needs rebase · " + message);
                if (branch.name().equals(selected)) view.summaryUnavailable(message);
                view.feedback("Rebase paused · revise conflicting edits before retrying.");
            }
        }, message -> view.feedback("Rebase unavailable · " + message));
    }

    private void choose(String branch) {
        versions.getItems().stream().filter(variant -> branch.equals(variant.branch()))
                .findFirst().ifPresent(variant -> {
                    if (variant.equals(versions.getValue())) versionSelected(variant);
                    else versions.setValue(variant);
                });
    }

    private void updateVersions(Variant preferred) {
        var next = new ArrayList<Variant>();
        next.add(PARENT);
        int letter = 1;
        for (var state : branches.values()) {
            if (state.stale()) continue;
            String name = state.branch().name();
            next.add(new Variant(name, versionLetter(letter++) + " · " + name));
        }
        if (merge != null && merge.audio() != null && merge.plan().directConflicts().isEmpty()) next.add(MERGE);
        if (preferred != null && preferred.branch().isEmpty() && !preferred.equals(PARENT)
                && !preferred.equals(MERGE) && conflictAudio != null) next.add(preferred);
        updatingVersions = true;
        versions.getItems().setAll(next);
        versions.setValue(next.contains(preferred) ? preferred : PARENT);
        versionControl.setVisible(next.size() > 1);
        updatingVersions = false;
    }

    private static String versionLetter(int index) {
        if (index < 26) return Character.toString('A' + index);
        return versionLetter(index / 26 - 1) + versionLetter(index % 26);
    }

    private void versionSelected(Variant version) {
        auditionRequest++;
        if (version.equals(PARENT)) {
            parent(true);
        } else if (!editor.readyForBranch() || !transport.activateBranchMonitor()) {
            parent(true);
            status.accept("Branch audition needs a clean editable arrangement and a Chip song, section or selection");
        } else {
            if (!version.branch().isBlank()) load(version.branch());
            auditionReady();
        }
    }

    private void auditionReady() {
        Variant version = versions.getValue();
        if (version == null || version.equals(PARENT)) return;
        Prepared audio;
        if (version.equals(MERGE)) audio = merge == null ? null : merge.audio();
        else if (version.branch().isEmpty()) audio = conflictAudio;
        else {
            var result = loaded.get(version.branch());
            audio = result == null ? null : result.variant();
        }
        if (audio != null && editor.auditionBranch(audio, version.label())) activeVersion = true;
    }

    private void parent(boolean restore) {
        if (activeVersion && restore) {
            if (parentAudio == null || !editor.auditionBranch(parentAudio, PARENT.label())) transport.restoreBranchParent();
        }
        activeVersion = false;
        auditionRequest++;
        conflictAudio = null;
        if (versions.getValue() != null && !PARENT.equals(versions.getValue())) {
            updatingVersions = true;
            versions.setValue(PARENT);
            updatingVersions = false;
        }
    }

    private void journalState() {
        var next = editor.branchRevision();
        var inspection = canvas.inspection();
        if (!next.equals(revision) || inspection != observedInspection) {
            revision = next;
            observedInspection = inspection;
            reload();
        }
        boolean ready = editor.readyForBranch();
        if (previousReady != ready) {
            previousReady = ready;
            if (!ready) parent(false);
            if (!takes.isEmpty()) updateMergeView();
        }
    }

    private void add(Take take) {
        if (!takes.contains(take)) {
            takes.add(take);
            plan();
        }
    }

    private void remove(int index) {
        takes.remove(index);
        excluded.removeIf(edit -> takes.stream().noneMatch(take -> take.branch().equals(edit.branch())));
        plan();
    }

    private List<SourceSelection> selections(Set<EditRef> omitted) {
        return takes.stream().map(take -> {
            var selection = new ReconstructionBranches.Selection(take.part() == null ? Set.of() : Set.of(take.part().id()),
                    take.range() == null ? null : take.range().start(), take.range() == null ? null : take.range().end(),
                    take.group() == null ? Set.of() : Set.of(take.group().id()), Set.of());
            var exclusions = omitted.stream().filter(edit -> take.branch().equals(edit.branch()))
                    .map(EditRef::editId).collect(java.util.stream.Collectors.toSet());
            return new SourceSelection(take.branch(), selection, exclusions);
        }).toList();
    }

    private void plan() {
        long request = ++stitchRequest;
        if (planJob != null) planJob.cancel(true);
        debounce.stop();
        indirectAccepted = false;
        if (versions.getValue() != null && versions.getValue().branch().isEmpty()) parent(true);
        merge = null;
        updateVersions(versions.getValue());
        updateMergeView();
        if (takes.isEmpty()) {
            excluded.clear();
            view.feedback("");
            return;
        }
        view.feedback("Checking merge…");
        var sources = selections(excluded);
        debounce.setOnFinished(event -> planJob = run(() -> {
            var candidate = ReconstructionBranches.planStitch(work, sources, QUIET);
            var audio = prepare(ReconstructionInspection.open(candidate.build()));
            return new Merge(candidate, audio);
        }, result -> {
            if (request != stitchRequest) return;
            merge = result;
            updateVersions(versions.getValue());
            updateMergeView();
            view.feedback(result.plan().selectedEdits() == 0 ? "The selections contain no changes." : "");
        }, message -> {
            if (request == stitchRequest) view.feedback("Could not check merge · " + message);
        }));
        debounce.playFromStart();
    }

    private void updateMergeView() {
        var issues = new ArrayList<Node>();
        var candidate = merge == null ? null : merge.plan();
        boolean valid = candidate != null && candidate.selectedEdits() > 0;
        if (!excluded.isEmpty()) {
            var reset = new Button("Reset conflict choices");
            reset.setOnAction(event -> {
                excluded.clear();
                plan();
            });
            issues.add(reset);
        }
        if (candidate != null) {
            if (candidate.selectedEdits() > 0 && ReconstructionEditor.accepted(candidate.journal()).equals(
                    ReconstructionEditor.accepted(candidate.mainRevision().entries()))) {
                issues.add(label("The merge changes proposal metadata only."));
                valid = false;
            }
            if (!candidate.directConflicts().isEmpty()) {
                var cards = new VBox(7);
                int index = 0;
                for (var conflict : candidate.directConflicts()) cards.getChildren().add(conflict(conflict, index++));
                var disclosure = new TitledPane(candidate.directConflicts().size() + " conflicting edits", cards);
                disclosure.setAnimated(false);
                issues.add(disclosure);
                valid = false;
            }
            long orphans = candidate.outcomes().values().stream().flatMap(List::stream)
                    .filter(outcome -> outcome.status().equals("orphaned") || outcome.status().equals("conflict")
                            || outcome.status().equals("invalid") || outcome.status().equals("unresolved")).count();
            if (orphans > 0) {
                issues.add(label(orphans + " edit outcomes need changes before applying."));
                valid = false;
            }
            if (!candidate.indirectConflicts().isEmpty()) {
                var count = candidate.indirectConflicts().size();
                var note = label(count + " notes outside the selections sound different or moved lanes.");
                var accept = new Button(indirectAccepted ? "Ripple changes accepted" : "Keep ripple changes");
                accept.setId("reconstruction-branch-accept-ripple");
                accept.setDisable(indirectAccepted || !valid);
                accept.setOnAction(event -> {
                    indirectAccepted = true;
                    updateMergeView();
                });
                issues.add(new VBox(5, note, accept));
                valid &= indirectAccepted;
            }
        }
        view.merge(takes, this::remove, issues, valid && !applying && editor.readyForBranch(),
                merge == null || merge.audio() == null || !candidate.directConflicts().isEmpty()
                        ? null : () -> {
                            if (MERGE.equals(versions.getValue())) versionSelected(MERGE);
                            else versions.setValue(MERGE);
                        });
    }

    private Node conflict(DirectConflict conflict, int index) {
        var sides = new HBox(6, side("A", conflict.left(), conflict.right(), conflict.leftSpan(), index),
                side("B", conflict.right(), conflict.left(), conflict.rightSpan(), index));
        for (var side : sides.getChildren()) HBox.setHgrow(side, Priority.ALWAYS);
        var reason = switch (conflict.reason()) {
            case "editId" -> "Both branches change the same edit.";
            case "noteId" -> "Both edits touch the same notes.";
            case "partSpanFamily" -> "The edits overlap in the same part and time range.";
            default -> "These edits conflict.";
        };
        return new VBox(5, label(reason), sides);
    }

    private Node side(String letter, EditRef take, EditRef omit, ReconstructionBranches.Range range, int index) {
        var state = branches.get(take.branch());
        var edit = state == null ? null : state.branch().edits().stream()
                .filter(row -> take.editId().equals(row.get("id").getAsString())).findFirst().orElse(null);
        String action = edit == null ? "Remove or reorder inherited edit" : ReconstructionEditor.proposalAction(edit);
        String reason = edit == null ? take.editId() : ReconstructionDraft.text(edit, "reason", "");
        var hear = new Button("Hear " + letter);
        hear.setId("reconstruction-branch-audition-" + letter.toLowerCase(Locale.ROOT) + "-" + index);
        hear.setOnAction(event -> hearSide(letter, take, omit));
        var keep = new Button("Keep " + letter);
        keep.setId("reconstruction-branch-keep-" + letter.toLowerCase(Locale.ROOT) + "-" + index);
        keep.setOnAction(event -> {
            excluded.add(omit);
            plan();
        });
        var summary = label(action);
        summary.setTooltip(new Tooltip(reason));
        var details = new VBox(4, label(letter + " · " + take.branch()), summary);
        details.setMinWidth(0);
        details.setMaxWidth(Double.MAX_VALUE);
        if (range != null) details.getChildren().add(label(String.format(Locale.ROOT, "%.2f–%.2f s", range.start(), range.end())));
        details.getChildren().add(new FlowPane(4, 4, hear, keep));
        details.getStyleClass().add("reconstruction-proposal");
        return details;
    }

    private void hearSide(String letter, EditRef take, EditRef omit) {
        long request = ++auditionRequest;
        long stitch = stitchRequest;
        var alternative = new LinkedHashSet<>(excluded);
        alternative.add(omit);
        var choices = selections(alternative);
        view.feedback("Preparing conflict side " + letter + "…");
        run(() -> {
            var candidate = ReconstructionBranches.planStitch(work, choices, QUIET);
            return prepare(ReconstructionInspection.open(candidate.build()));
        }, audio -> {
            if (request != auditionRequest || stitch != stitchRequest) return;
            conflictAudio = audio;
            var version = new Variant("", "Conflict " + letter + " · " + take.branch());
            updateVersions(version);
            versionSelected(version);
            view.feedback("");
        }, message -> {
            if (request == auditionRequest) view.feedback("Could not audition conflict · " + message);
        });
    }

    private void apply() {
        if (merge == null || applying) return;
        var checked = merge.plan();
        var sources = selections(excluded);
        long request = stitchRequest;
        applying = true;
        updateMergeView();
        view.feedback("Checking main and source revisions…");
        run(() -> ReconstructionBranches.planStitch(work, sources, QUIET), result -> {
            applying = false;
            if (request != stitchRequest || merge == null) return;
            if (!checked.mainRevision().equals(result.mainRevision()) || !checked.build().equals(result.build())
                    || !checked.directConflicts().equals(result.directConflicts())
                    || !checked.indirectConflicts().equals(result.indirectConflicts())) {
                plan();
                view.feedback("Source changed · review the refreshed merge before applying.");
                return;
            }
            if (!editor.readyForBranch() || !result.mainRevision().equals(editor.branchRevision())) {
                updateMergeView();
                view.feedback("Main changed · finish pending changes and check again.");
                return;
            }
            parent(true);
            editor.applyBranch(result.mainRevision(), result.journal());
            takes.clear();
            excluded.clear();
            plan();
        }, message -> {
            applying = false;
            updateMergeView();
            view.feedback("Could not apply merge · " + message);
        });
    }

    private static Label label(String value) {
        var label = new Label(value);
        label.setWrapText(true);
        return label;
    }

    @FunctionalInterface
    private interface Work<T> { T run() throws Exception; }

    private <T> Future<?> run(Work<T> task, Consumer<T> success, Consumer<String> failure) {
        if (worker == null) worker = Executors.newSingleThreadExecutor(
                job -> Thread.ofPlatform().daemon().name("reconstruction-branches").unstarted(job));
        var current = worker;
        return current.submit(() -> {
            try {
                T result = task.run();
                Platform.runLater(() -> { if (!closed && worker == current) success.accept(result); });
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted() || error instanceof java.util.concurrent.CancellationException) return;
                String message = ReconstructionEditor.describe(error);
                Platform.runLater(() -> { if (!closed && worker == current) failure.accept(message); });
            }
        });
    }

    boolean buildsStopped() { return worker == null || worker.isTerminated(); }

    private void stopWorker() {
        debounce.stop();
        planJob = null;
        loading.clear();
        applying = false;
        if (worker == null) return;
        worker.shutdownNow();
        // Cancellation must finish the build's staging cleanup before the workspace can close.
        boolean interrupted = false;
        try {
            while (!worker.isTerminated()) {
                try {
                    worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException waitInterrupted) {
                    interrupted = true;
                }
            }
        } finally {
            worker = null;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public void close() {
        closed = true;
        stopWorker();
    }
}

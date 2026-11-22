package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionEditing;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.headless.ReconstructionInspection.Style;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.stage.WindowEvent;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.opensmpsdeck.studio.StudioCaptureRun.*;
import static com.opensmpsdeck.studio.StudioSurfaceCapture.slug;

final class StudioAnalysisCapture {
    private static final String VOCAL_REVIEW = "Vocal lead review";

    private StudioAnalysisCapture() {}

    static void capture(StudioCaptureRun run, StudioWindow window, Path work) throws Exception {
        var pane = fx(() -> {
            window.openReconstruction(work);
            return window.reconstruction();
        });
        try {
            var scene = pane.stage().getScene();
            run.resize(scene);
            await("reconstruction load", () -> {
                var status = scene.lookup("#reconstruction-status");
                if (status instanceof Label label && label.getText().startsWith("Could not open"))
                    throw new IllegalStateException(label.getText());
                return pane.canvas() != null;
            });
            var canvas = fx(pane::canvas);
            var model = fx(pane::inspection);
            var editing = ReconstructionEditing.open(work);
            var tonal = model.overlays().stream().filter(note -> note.style() == Style.SOLID)
                    .filter(note -> note.lane() != null && note.lane().startsWith("FM"))
                    .filter(note -> editing.attack(note.id()) != null)
                    .sorted(Comparator.comparingDouble(note -> note.interval().sourceStart())).toList();
            var lead = tonal.stream().filter(note -> note.role() == Role.MELODY).findFirst()
                    .orElse(tonal.isEmpty() ? null : tonal.getFirst());
            var near = lead == null ? List.<Overlay>of() : tonal.stream()
                    .filter(note -> note.part() == lead.part() && note.interval().sourceStart() >= lead.interval().sourceStart())
                    .limit(3).toList();

            run.attempt("Analysis spectrogram", () -> {
                require(!model.sources().isEmpty(), "No source WAV or chip render in the reconstruction");
                await("visible spectrogram tiles", canvas::spectrumReady);
                await("initial audio source", () -> !label(scene, "reconstruction-status").startsWith("Loading"));
            });
            run.shot("analysis-roles", "Roles", "Landing workspace with voice legend and next step", scene);
            ui(() -> {
                press(scene, "reconstruction-show-drums");
                disclosure(scene, "Musical roles").setExpanded(true);
            });
            run.shot("analysis-role-filters", "Roles", "Show, highlight and select by musical role", scene);
            ui(() -> press(scene, "reconstruction-detail-view"));
            run.shot("analysis-detail", "Notes", "Spectrogram, note tools and empty inspector", scene);
            ui(() -> press(scene, "reconstruction-view-settings"));
            run.shot("analysis-view", "View settings", "Zoom, evidence and display controls", scene);
            ui(() -> press(scene, "reconstruction-view-settings"));

            if (lead == null) {
                run.miss("Analysis note, tool modes, Selection, layers and dirty edit",
                        "No editable placed FM note in the reconstruction");
            } else {
                ui(() -> {
                    node(scene, "reconstruction-zoom", Slider.class).setValue(canvas.detailZoom(near));
                    layout(scene);
                    canvas.center(lead.interval().sourceStart());
                    canvas.fitPitch();
                    canvas.select(lead, lead.lane());
                    inspector(scene, "Note");
                });
                if (!model.sources().isEmpty())
                    run.attempt("Analysis zoomed spectrogram", () -> await("zoomed spectrogram", canvas::spectrumReady));
                captureNoteStates(run, scene);
                run.attempt("Analysis multi-selection and layers", () -> captureSelection(run, scene, canvas, near));
            }

            captureJournalAndProposals(run, scene);
            captureTransport(run, scene, canvas, model, lead, near);
            ui(() -> press(scene, "reconstruction-export-view"));
            run.shot("analysis-export-workspace", "Export", "Open or export the built arrangement", scene);
            run.attempt("Analysis export", () -> dialog(() -> press(scene, "reconstruction-export"), "export-dialog",
                    export -> run.shot("analysis-export", "Analysis export", "Arrangement export from Analysis", export)));
            run.attempt("Open as arrangement", () -> {
                ui(() -> press(scene, "open-as-arrangement"));
                require(fx(() -> window.document().path().equals(work.resolve("arrangement.smps.json"))),
                        "Studio did not open the reconstruction arrangement");
                run.shot("analysis-open-as-arrangement", "Open as arrangement", "Reconstruction result opened in Studio", window.scene());
            });

            if (lead != null) run.attempt("Analysis unsaved edit", () -> {
                ui(() -> {
                    canvas.select(lead, lead.lane());
                    inspector(scene, "Selection");
                    var transpose = button(scene, "+1");
                    if (transpose.isDisabled()) transpose = button(scene, "−1");
                    fire(transpose);
                    require(!node(scene, "reconstruction-apply", javafx.scene.control.Button.class).isDisabled(),
                            "Transpose did not create an applicable edit");
                    inspector(scene, "Note");
                });
                run.shot("analysis-dirty", "Analysis unsaved edit", "Pending transpose, preview overlay and status", scene);
                ui(() -> inspector(scene, "Edits"));
                run.shot("analysis-dirty-journal", "Edit journal", "Pending transpose in the journal", scene);
                dialog(() -> Event.fireEvent(pane.stage(), new WindowEvent(pane.stage(), WindowEvent.WINDOW_CLOSE_REQUEST)),
                        "Confirmation", prompt -> run.shot("analysis-discard-prompt",
                                "Analysis unsaved edit", "Discard confirmation", prompt));
            });
        } finally {
            ui(pane::close);
        }
    }

    private static void captureNoteStates(StudioCaptureRun run, Scene scene) throws Exception {
        for (var mode : ReconstructionTools.Mode.values()) {
            run.attempt("Analysis tool " + mode.label, () -> {
                var suffix = slug(mode.name());
                ui(() -> {
                    var button = node(scene, "reconstruction-tool-" + suffix, ToggleButton.class);
                    if (!button.isSelected()) fire(button);
                });
                run.shot("analysis-tool-" + suffix, "Analysis " + mode.label,
                        "Tool active with a selected FM note and Note inspector", scene);
            });
        }
        ui(() -> press(scene, "reconstruction-tool-select"));
        run.attempt("Raw trace JSON", () -> {
            ui(() -> {
                node(scene, "reconstruction-note-evidence", TitledPane.class).setExpanded(true);
                layout(scene);
                var raw = node(scene, "reconstruction-raw-trace", TitledPane.class);
                require(visible(raw), "No raw trace for the selected note");
                raw.setAnimated(false);
                raw.setExpanded(true);
                reveal(raw);
            });
            run.shot("analysis-raw-trace", "Note inspector", "Raw trace JSON expanded", scene);
            ui(() -> {
                var raw = node(scene, "reconstruction-raw-trace", TitledPane.class);
                raw.setExpanded(false);
                node(scene, "reconstruction-note-evidence", TitledPane.class).setExpanded(false);
                containingScroll(raw).setVvalue(0);
            });
        });
    }

    private static void captureSelection(StudioCaptureRun run, Scene scene, ReconstructionCanvas canvas,
                                         List<Overlay> near) throws Exception {
        require(near.size() > 1, "No adjacent editable FM notes in one part");
        ui(() -> {
            canvas.selectNotes(near);
            inspector(scene, "Selection");
        });
        run.shot("analysis-selection", "Analysis Selection", "Multiple FM notes and bulk actions", scene);
        ui(() -> {
            disclosure(scene, "Instrument").setExpanded(true);
            layout(scene);
            var instrument = scene.getRoot().lookupAll(".text-field").stream().filter(TextField.class::isInstance)
                    .map(TextField.class::cast).filter(field -> "Search catalogue".equals(field.getPromptText()))
                    .findFirst().orElseThrow();
            reveal(instrument);
        });
        run.shot("analysis-selection-instrument", "Analysis Selection", "Instrument assignment and audition controls", scene);
        ui(() -> {
            disclosure(scene, "Instrument").setExpanded(false);
            disclosure(scene, "Retrigger").setExpanded(true);
            layout(scene);
            reveal(node(scene, "reconstruction-queue-retrigger", javafx.scene.control.Button.class));
        });
        run.shot("analysis-selection-detail", "Analysis Selection", "Instrument, retrigger and combine controls", scene);

        ui(() -> {
            disclosure(scene, "Retrigger").setExpanded(false);
            disclosure(scene, "Layers").setExpanded(true);
            layout(scene);
        });
        for (var layer : List.of("Echo", "Chorus", "Double", "Off")) {
            run.attempt("Layer " + layer, () -> {
                ui(() -> {
                    var modes = node(scene, "reconstruction-layer-mode", ComboBox.class);
                    modes.getSelectionModel().select(modes.getItems().indexOf(layer));
                    reveal(modes);
                });
                run.shot("analysis-layer-" + slug(layer), "Analysis Layer", layer + " controls", scene);
            });
        }
    }

    private static void captureJournalAndProposals(StudioCaptureRun run, Scene scene) {
        run.attempt("Edit journal", () -> {
            ui(() -> {
                inspector(scene, "Edits");
                var entries = node(scene, "reconstruction-edit-list", ListView.class);
                if (!entries.getItems().isEmpty()) entries.getSelectionModel().selectFirst();
            });
            run.shot("analysis-journal", "Edit journal", "Saved edit intents and build outcomes", scene);
        });
        run.attempt("Trace diff", () -> {
            ui(() -> inspector(scene, "Trace diff"));
            run.shot("analysis-trace-diff", "Trace diff", "Before the first Apply in this window", scene);
        });
        run.attempt("Pending proposals", () -> {
            ui(() -> {
                inspector(scene, "Proposals");
                var list = node(scene, "reconstruction-proposals", javafx.scene.Parent.class);

                require(visible(list), "Proposal inspector did not open");
            });
            run.shot("analysis-proposals", "Proposals", "Pending proposals and group decisions", scene);
        });
        run.attempt("Vocal-review proposals", () -> {
            ui(() -> {
                var list = node(scene, "reconstruction-proposals", javafx.scene.Parent.class);
                var vocal = list.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(label -> VOCAL_REVIEW.equals(label.getText())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No pending vocal-lead-review group in the journal"));
                reveal(vocal);
            });
            run.shot("analysis-vocal-proposals", "Proposals", "Vocal lead review with evidence and A/B controls", scene);
        });
    }

    private static TitledPane disclosure(Scene scene, String title) {
        return scene.getRoot().lookupAll(".titled-pane").stream().filter(TitledPane.class::isInstance)
                .map(TitledPane.class::cast).filter(pane -> title.equals(pane.getText())).findFirst().orElseThrow();
    }

    private static void inspector(Scene scene, String name) {
        boolean notes = name.equals("Note") || name.equals("Selection");
        String id = "reconstruction-" + (notes ? "detail" : "review") + "-view";
        var workspace = node(scene, id, ToggleButton.class);
        if (!workspace.isSelected()) fire(workspace);
        if (!notes) {
            var content = tab(node(scene, "reconstruction-inspector-tabs", TabPane.class), name);
            if (content instanceof ScrollPane scroll) scroll.setVvalue(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static void captureTransport(StudioCaptureRun run, Scene scene, ReconstructionCanvas canvas,
                                         com.opensmpsdeck.headless.ReconstructionInspection model,
                                         Overlay lead, List<Overlay> near) throws Exception {
        ui(() -> {
            press(scene, "reconstruction-detail-view");
            disclosure(scene, "Layers").setExpanded(false);
            canvas.select(null, null);
            node(scene, "reconstruction-zoom", Slider.class).setValue(0);
            layout(scene);
            canvas.fitPitch();
        });
        var scopes = fx(() -> (ComboBox<ReconstructionTransport.Scope>) node(scene, "reconstruction-play-scope", ComboBox.class));
        var hearing = fx(() -> (ComboBox<ReconstructionTransport.Hearing>) node(scene, "reconstruction-hearing", ComboBox.class));
        for (var scope : ReconstructionTransport.Scope.values()) {
            run.attempt("Transport " + scope, () -> {
                ui(() -> {
                    if (scope == ReconstructionTransport.Scope.Section) {
                        var section = model.sections().sections().stream().findFirst().orElseThrow();
                        double second = (section.sourceStart() + section.sourceEnd()) / 2;
                        double x = 76 + (canvas.canvas.getWidth() - 76) * second / model.duration();
                        double y = 31 + (section.level().ordinal() + .5) * 18;
                        Event.fireEvent(canvas.canvas, new javafx.scene.input.MouseEvent(
                                javafx.scene.input.MouseEvent.MOUSE_RELEASED, x, y, x, y,
                                javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                                false, false, false, false, false, true,
                                new javafx.scene.input.PickResult(canvas.canvas, new javafx.geometry.Point3D(x, y, 0), 0)));
                        require(scopes.getValue() == scope, "Band click did not set the Section scope");
                    } else {
                        if (scope == ReconstructionTransport.Scope.Selection) {
                            require(!near.isEmpty(), "No note selection");
                            canvas.selectNotes(near);
                        }
                        scopes.setValue(scope);
                        if (scope == ReconstructionTransport.Scope.Voice) {
                            var voices = node(scene, "reconstruction-play-voice", ComboBox.class);
                            require(!voices.getItems().isEmpty(), "No built voices");
                            voices.getSelectionModel().selectFirst();
                        }
                        if (scope == ReconstructionTransport.Scope.Lane) {
                            require(lead != null, "No chip lane selection");
                            canvas.select(lead, lead.lane());
                        }
                    }
                    hearing.setValue(switch (scope) {
                        case Song, Voice -> ReconstructionTransport.Hearing.ORIGINAL;
                        case Section, Lane -> ReconstructionTransport.Hearing.CHIP;
                        case Selection -> ReconstructionTransport.Hearing.AB;
                    });
                });
                await("transport source", () -> !label(scene, "reconstruction-status").startsWith("Loading"));
                run.shot("analysis-transport-" + slug(scope.name()), "Transport " + scope, "Play range and monitor", scene);
            });
        }
        ui(() -> {
            scopes.setValue(ReconstructionTransport.Scope.Song);
            hearing.setValue(ReconstructionTransport.Hearing.AB);
            canvas.onRange.accept(new ReviewTransport.Range(0, Math.min(4, model.duration())));
        });
        await("A/B sources", () -> !label(scene, "reconstruction-status").startsWith("Loading"));
        run.shot("analysis-transport-loop", "Transport loop", "Visible loop with Clear loop and A/B side", scene);
    }
}

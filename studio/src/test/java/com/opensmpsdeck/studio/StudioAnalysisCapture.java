package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionEditing;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioKind;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioSource;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.headless.ReconstructionInspection.Style;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
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
    private static final String VOCAL_REVIEW = "vocal-lead-review";

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
                var status = ((javafx.scene.layout.BorderPane) scene.getRoot()).getBottom();
                if (status.lookup(".status") instanceof Label label && label.getText().startsWith("Could not open"))
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

            ui(() -> press(scene, "reconstruction-fit-pitch"));
            run.attempt("Analysis spectrogram", () -> {
                require(!model.sources().isEmpty(), "No source WAV or chip render in the reconstruction");
                await("visible spectrogram tiles", canvas::spectrumReady);
                await("initial audio source", () -> !label(scene, "reconstruction-status").startsWith("Loading"));
            });
            run.shot("analysis-detail", "Analysis Detail", "Full song, source spectrogram and chip overlays", scene);
            run.attempt("Analysis Roles", () -> {
                ui(() -> {
                    press(scene, "reconstruction-roles-view");
                    press(scene, "reconstruction-show-drums");
                });
                run.shot("analysis-roles", "Analysis Roles", "Voice legend, tonal labels and drums", scene);
            });
            ui(() -> press(scene, "reconstruction-detail-view"));

            if (lead == null) {
                run.miss("Analysis note, tool modes, Selection, layers and dirty edit",
                        "No editable placed FM note in the reconstruction");
            } else {
                ui(() -> {
                    node(scene, "reconstruction-zoom", Slider.class).setValue(canvas.detailZoom(near));
                    layout(scene);
                    canvas.center(lead.interval().sourceStart());
                    press(scene, "reconstruction-fit-pitch");
                    canvas.select(lead, lead.lane());
                    inspector(scene, "Note");
                });
                if (!model.sources().isEmpty())
                    run.attempt("Analysis zoomed spectrogram", () -> await("zoomed spectrogram", canvas::spectrumReady));
                captureNoteStates(run, scene);
                run.attempt("Analysis multi-selection and layers", () -> captureSelection(run, scene, canvas, near));
            }

            captureJournalAndProposals(run, scene);
            run.attempt("Analysis audition and transport", () -> {
                var chip = model.sources().stream().filter(source -> source.kind() == AudioKind.CHIP).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No chip render in the reconstruction"));
                ui(() -> {
                    chooseAudio(scene, chip);
                    if (lead != null) {
                        canvas.select(lead, lead.lane());
                        fire(button(scene, "Solo note"));
                        inspector(scene, "Note");
                    }
                });
                await("chip waveform", () -> !label(scene, "reconstruction-status").startsWith("Loading"));
                require(fx(() -> label(scene, "reconstruction-status").endsWith(" ready")), "Chip render could not load");
                await("chip spectrogram", canvas::spectrumReady);
                run.shot("analysis-audition", "Analysis transport", "Chip render ready for audition with note solo", scene);
            });

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
            var instrument = scene.getRoot().lookupAll(".text-field").stream().filter(TextField.class::isInstance)
                    .map(TextField.class::cast).filter(field -> "Search catalogue".equals(field.getPromptText()))
                    .findFirst().orElseThrow();
            reveal(instrument);
        });
        run.shot("analysis-selection-instrument", "Analysis Selection", "Instrument assignment and audition controls", scene);
        ui(() -> reveal(node(scene, "reconstruction-queue-retrigger", javafx.scene.control.Button.class)));
        run.shot("analysis-selection-detail", "Analysis Selection", "Instrument, retrigger and combine controls", scene);

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
                require(list.lookupAll(".check-box").stream().anyMatch(CheckBox.class::isInstance),
                        "No pending proposals in the journal");
                var headings = list.lookupAll(".panel-heading").stream().filter(Label.class::isInstance)
                        .map(Label.class::cast);
                headings.filter(label -> !VOCAL_REVIEW.equals(label.getText())).findFirst()
                        .ifPresent(StudioCaptureRun::reveal);
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

    private static void inspector(Scene scene, String name) {
        var content = tab(node(scene, "reconstruction-inspector-tabs", TabPane.class), name);
        if (content instanceof ScrollPane scroll) scroll.setVvalue(0);
    }

    @SuppressWarnings("unchecked")
    private static void chooseAudio(Scene scene, AudioSource source) {
        ((ComboBox<AudioSource>) node(scene, "reconstruction-source", ComboBox.class)).setValue(source);
    }
}

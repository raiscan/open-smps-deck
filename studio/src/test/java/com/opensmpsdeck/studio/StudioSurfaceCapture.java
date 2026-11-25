package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ProjectExport;
import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.project.AudioReference;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ReviewNote;
import com.opensmpsdeck.project.Sequence;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.opensmpsdeck.studio.StudioCaptureRun.*;

/** Captures Studio's actual launch, arrangement, dialogs and Analysis surfaces. */
public final class StudioSurfaceCapture {
    private static final Path FIXTURE = Path.of("studio/src/test/fixtures/design-capture").toAbsolutePath();

    private record Inputs(Path output, Path work, Path project) {
        static Inputs parse(String[] args) throws Exception {
            Path output = null, work = null, project = null;
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
                var path = Path.of(args[i + 1]).toAbsolutePath().normalize();
                switch (args[i]) {
                    case "--output" -> output = path;
                    case "--work-dir" -> work = path;
                    case "--project" -> project = path;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if (output == null) throw new IllegalArgumentException("Specify --output DIRECTORY");
            require(!output.startsWith(Path.of("").toAbsolutePath()), "Choose an output outside the checkout");
            if (work != null) {
                require(Files.isRegularFile(work.resolve("score.json")), "No score.json in the work directory");
                require(!output.startsWith(work.toRealPath()), "Choose an output outside the work directory");
                if (project == null) project = work.resolve("arrangement.smps.json");
            }
            return new Inputs(output, work == null ? FIXTURE.resolve("reconstruction") : work, project);
        }
    }

    public static void main(String[] args) throws Exception {
        var inputs = Inputs.parse(args);
        var size = new Size(Integer.getInteger("studio.capture.width", 1920),
                Integer.getInteger("studio.capture.height", 1080));
        Files.createDirectories(inputs.output());
        var output = Files.createDirectory(inputs.output().resolve(size.toString()));
        var run = new StudioCaptureRun(output, size);
        var ready = new CountDownLatch(1);
        DesktopScale.configure();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        require(ready.await(10, TimeUnit.SECONDS), "JavaFX did not start");
        try {
            launch(null, window ->
                    run.shot("launch-demo", "Launch", "No arguments · bundled demo", window.scene()));
            var project = inputs.project() == null ? fixtureProject() : inputs.project();
            launch(project, window -> {
                arrangement(run, window);
                dialogs(run, window, size);
                run.attempt("Analysis", () -> StudioAnalysisCapture.capture(run, window, inputs.work()));
                if (Boolean.getBoolean("studio.capture.branches"))
                    run.attempt("Edit branches", () -> StudioBranchCapture.capture(run, window.scene().getWindow()));
            });
            launch(Path.of("examples/native-evening-clock.smps.json").toAbsolutePath(),
                    window -> nativeProgram(run, window, size));
        } finally {
            Platform.exit();
            run.finish();
        }
    }

    @FunctionalInterface private interface WindowAction { void run(StudioWindow window) throws Exception; }

    private static void launch(Path project, WindowAction action) throws Exception {
        var window = fx(() -> StudioApplication.open(new Stage(), project == null ? List.of() : List.of(project.toString())));
        try {
            action.run(window);
        } finally {
            ui(() -> {
                window.close();
                window.scene().getWindow().hide();
            });
        }
    }

    private static Path fixtureProject() throws Exception {
        var folder = Files.createTempDirectory("studio-arrangement-");
        var project = FIXTURE.resolve("reconstruction/arrangement.smps.json");
        var fixture = ProjectFiles.load(project).project();
        fixture = StudioDesignCapture.withCommands(fixture, false);
        var path = folder.resolve("copper-kites.smps.json");
        var guide = FIXTURE.resolve("reconstruction/mix.wav");
        fixture = ProjectEdits.addReference(fixture, new AudioReference("guide", "Synthetic guide",
                AudioReference.pathFor(path, guide), 0, -3, "mix"));
        fixture = ProjectEdits.addReviewNote(fixture, new ReviewNote("lead-review", 1.03, 1.43, "FM1", null,
                "Check the lead entrance against the guide.", "human", "open", "2026-01-01T00:00:00Z", null));
        fixture = ProjectEdits.addReviewNote(fixture, new ReviewNote("bass-review", 2.0, null, "FM2", null,
                "Bass sustain ready for review.", "agent", "open", "2026-01-01T00:00:01Z", null));
        ProjectFiles.save(path, fixture, null);
        return path;
    }

    private static void arrangement(StudioCaptureRun run, StudioWindow window) throws Exception {
        var scene = window.scene();
        run.shot("launch-project", "Launch", "Opened project", scene);
        run.resize(scene);
        var project = fx(() -> window.document().project());
        if (project.nativeProgram() != null) {
            run.miss("Editable arrangement editor", "Opened project uses a native bank");
            return;
        }
        ui(() -> showReview(scene, false));
        var chosen = project.sequences().stream().filter(sequence -> !sequence.notes().isEmpty())
                .filter(sequence -> project.clips().stream().anyMatch(clip -> clip.sequenceId().equals(sequence.id())))
                .findFirst();
        if (chosen.isPresent()) {
            String clip = project.clips().stream().filter(item -> item.sequenceId().equals(chosen.get().id()))
                    .findFirst().orElseThrow().id();
            ui(() -> window.timeline.selection.accept(clip));
        } else {
            run.miss("Clip inspector and note selection", "No placed tonal clip in the opened project");
        }
        run.shot("arrangement-timeline", "Arrangement timeline", "Timeline, clip inspector and transport", scene);
        if (chosen.isPresent()) {
            ui(() -> {
                window.showNotes();
                window.piano.selected = chosen.get().notes().getFirst().id();
                window.piano.selection.accept(window.piano.selected);
                window.piano.draw();
            });
            run.shot("arrangement-note", "Notes", "Piano roll with a selected note and clip inspector", scene);
        }

        run.attempt("Commands", () -> {
            ui(() -> {
                project.sequences().stream().filter(sequence -> !sequence.commands().isEmpty()).findFirst()
                        .map(Sequence::id).flatMap(id -> project.clips().stream().filter(clip -> clip.sequenceId().equals(id))
                                .findFirst()).ifPresent(clip -> window.timeline.selection.accept(clip.id()));
                window.showCommands();
                var sequence = window.piano.sequence;
                if (sequence != null && !sequence.commands().isEmpty())
                    window.commands.selectCommand(sequence.commands().getFirst().id());
            });
            run.shot("arrangement-commands", "Commands", "Timed commands editor", scene);
            ui(() -> press(scene, "browse-commands"));
            run.shot("arrangement-command-browser", "Commands", "Command browser open", scene);
        });

        for (var asset : List.of("FM voices", "Envelopes", "DAC samples")) {
            run.attempt(asset, () -> {
                ui(() -> {
                    window.showAssets();
                    tab(node(scene, "asset-editor-tabs", TabPane.class), asset);
                });
                run.shot("arrangement-" + slug(asset), asset, "Asset editor", scene);
            });
        }
        run.attempt("DAC trigger editor", () -> {
            var dac = project.sequences().stream().filter(sequence -> sequence.type().equals("DAC"))
                    .map(Sequence::id).flatMap(id -> project.clips().stream().filter(clip -> clip.sequenceId().equals(id)))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No placed DAC clip in the opened project"));
            ui(() -> {
                window.timeline.selection.accept(dac.id());
                window.showNotes();
            });
            run.shot("arrangement-dac-triggers", "DAC triggers", "DAC clip selected in Notes", scene);
        });
        run.attempt("Catalogue", () -> {
            ui(() -> window.openCatalogue(FIXTURE.resolve("catalogue.json")));
            await("catalogue results", () -> !node(scene, "catalogue-results", ListView.class).getItems().isEmpty());
            run.shot("arrangement-catalogue", "Catalogue", "Synthetic FM and PSG results", scene);
            ui(() -> {
                var tabs = node(scene, "instrument-catalogue", javafx.scene.Parent.class).lookupAll(".tab-pane").stream()
                        .filter(TabPane.class::isInstance).map(TabPane.class::cast)
                        .filter(pane -> pane.getTabs().stream().anyMatch(tab -> tab.getText().equals("Variant")))
                        .findFirst().orElseThrow();
                tab(tabs, "Variant");
            });
            run.shot("arrangement-catalogue-variant", "Catalogue", "Selected variant details", scene);
        });

        run.attempt("Reference and review", () -> {
            require(!project.references().isEmpty(), "Opened project has no audio references");
            ui(() -> {
                showReview(scene, true);
                if (chosen.isPresent()) {
                    project.clips().stream().filter(clip -> clip.sequenceId().equals(chosen.get().id()))
                            .findFirst().ifPresent(clip -> window.timeline.selection.accept(clip.id()));
                }
                window.showNotes();
            });
            await("reference waveform", () -> window.timeline.referenceLane.wave() != null);
            ui(() -> {
                node(scene, "review-source", ComboBox.class).getSelectionModel().select(ReviewTransport.Mode.AB.ordinal());
                var notes = node(scene, "open-review-notes", ListView.class);
                if (!notes.getItems().isEmpty()) notes.getSelectionModel().selectFirst();
            });
            run.shot("arrangement-reference-review", "Reference and review",
                    "Loaded waveform, A/B transport and review sidebar", scene);
        });
    }

    private static void showReview(Scene scene, boolean show) {
        if (visible(node(scene, "review-source", ComboBox.class)) != show) press(scene, "show-review");
    }

    private static void nativeProgram(StudioCaptureRun run, StudioWindow window, Size size) {
        var scene = window.scene();
        run.attempt("Native program", () -> {
            run.resize(scene);
            await("native arrangement", () -> window.nativeArrangement.timeline.project != null);
            run.shot("native-arrangement", "Native arrangement", "Decoded bank timeline", scene);
            if (size.height() == 768) compact(run, scene, "native-compact", "Compact native arrangement");
            ui(() -> {
                tab(node(scene, "native-program-tabs", TabPane.class), "Native bank");
                var events = node(scene, "native-events", TableView.class);
                events.getSelectionModel().selectFirst();
            });
            run.shot("native-bank", "Native bank", "Event selected with bank bytes", scene);
            ui(window::showAssets);
            run.shot("native-assets", "Native voices and assets", "Assets tab open", scene);
        });
    }

    private static void dialogs(StudioCaptureRun run, StudioWindow window, Size size) throws Exception {
        var scene = window.scene();
        run.attempt("Export", () -> dialog(() -> press(scene, "export-project"), "export-dialog", export -> {
            await("ROM verification", () -> !label(export, "export-rom-hint").startsWith("Checking"));
            ui(() -> {
                fire(button(export, "Limit to a span"));
                node(export, "export-start", TextField.class).setText("0.5");
                node(export, "export-end", TextField.class).setText("4");
                fire(button(export, "Separate channel stems"));
            });
            run.shot("dialog-export-audio", "Export", "Audio span and channel stems", export);
            for (var format : List.of(ProjectExport.Format.MP3, ProjectExport.Format.VGM, ProjectExport.Format.SMPS, ProjectExport.Format.PACKAGE,
                    ProjectExport.Format.PORTABLE, ProjectExport.Format.MOD, ProjectExport.Format.DISASM)) {
                ui(() -> {
                    var choices = node(export, "export-format", ChoiceBox.class);
                    int index = choices.getItems().indexOf(format);
                    require(index >= 0, "Export format unavailable: " + format);
                    choices.getSelectionModel().select(index);
                    ((Stage) export.getWindow()).sizeToScene();
                });
                run.shot("dialog-export-" + slug(format.name()), "Export", format.name() + " options", export);
            }
        }));
        run.attempt("Test in game", () -> {
            var project = fx(() -> window.document().project());
            Path path = fx(() -> window.document().path());
            var library = new RomLibrary(Path.of(System.getProperty("java.io.tmpdir"), "capture-roms.json"));
            var game = RomGame.parse(project.target().id());
            dialog(() -> TestInGameDialog.show(scene.getWindow(), project.name(),
                            () -> new ProjectExport.Source(project, null), path, game, library, Runnable::run, unused -> {}),
                    "test-in-game-dialog", dialog -> run.shot("dialog-test-in-game", "Test in game",
                            "Destination form before selecting outputs", dialog));
        });
        run.attempt("ROM settings", () -> dialog(() -> press(scene, "rom-settings"), "rom-settings-dialog", rom -> {
            await("ROM settings", () -> !label(rom, "rom-status-s3k").equals("Checking…"));
            run.shot("dialog-rom-settings", "ROM settings", "No ROMs registered in the capture profile", rom);
        }));
        run.attempt("Song settings", () -> dialog(() -> fire(button(scene, "Song settings…")), "Song settings",
                settings -> run.shot("dialog-song-settings", "Song settings", "Project name and tempo", settings)));
        run.attempt("New sequence", () -> dialog(() -> fire(button(scene, "+ New sequence")), "New linked sequence",
                form -> run.shot("dialog-new-sequence", "New sequence", "Empty source form", form)));
        run.attempt("Open reconstruction picker", () -> run.nativePicker(scene, "dialog-open-reconstruction",
                "Open reconstruction", "Open reconstruction work directory", () -> press(scene, "open-reconstruction")));
        run.attempt("Suno import", () -> importReport(run, window, false));
        run.attempt("Local import", () -> importReport(run, window, true));

        if (size.height() == 768) compact(run, scene, "compact", "Compact arrangement");
    }

    private static void compact(StudioCaptureRun run, Scene scene, String prefix, String surface) {
        // A 768px work area also needs coverage of the short-window tab layout.
        run.attempt(surface, () -> {
            ui(() -> scene.getWindow().setHeight(690));
            try {
                await("compact editor tabs", () -> scene.lookup("#compact-editor-tabs") != null);
                for (var name : List.of("Arrangement", "Notes")) {
                    ui(() -> tab(node(scene, "compact-editor-tabs", TabPane.class), name));
                    run.shot(prefix + "-" + slug(name), surface, name + " tab open", scene);
                }
            } finally {
                run.resize(scene);
            }
        });
    }

    private static void importReport(StudioCaptureRun run, StudioWindow window, boolean local) throws Exception {
        var scratch = Files.createTempDirectory("studio-import-");
        var source = Files.createDirectory(scratch.resolve(local ? "local-stems" : "suno-download"));
        var audio = FIXTURE.resolve("reconstruction");
        Files.copy(audio.resolve("mix.wav"), source.resolve("mix.wav"));
        var stems = local ? Files.createDirectory(source.resolve("stems")) : source;
        for (var part : List.of("vocals", "bass", "keys", "drums"))
            Files.copy(audio.resolve(part + ".wav"), stems.resolve(part + ".wav"));
        if (local) {
            Files.writeString(source.resolve("separation.json"), """
                    {"tool":"fixture","model":"synthetic","version":"1",
                     "checkpointSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                     "stemOrder":["vocals","bass","keys","drums"],"settings":{}}
                    """);
        } else {
            Files.copy(FIXTURE.resolve("mix.mid"), source.resolve("mix.mid"));
            // An extra stem exercises import warnings alongside the usable mappings.
            Files.copy(audio.resolve("keys.wav"), stems.resolve("unmapped.wav"));
        }
        var stage = fx(() -> window.openSunoImport(source, scratch.resolve("work")));
        try {
            var scene = stage.getScene();
            await("import file checks", () -> !node(scene, "suno-import-files", TableView.class).getItems().isEmpty());
            String kind = local ? "local" : "suno";
            run.shot("dialog-" + kind + "-import", local ? "Local stem import" : "Suno import",
                    "Processing · detected files and mapping checks", scene);
            if (!local) {
                Files.writeString(source.resolve("broken.wav"), "Invalid WAV fixture");
                ui(() -> press(scene, "suno-import-check"));
                await("import error report", () -> label(scene, "suno-import-status").contains("Resolve import errors"));
                run.shot("dialog-suno-import-errors", "Suno import", "Processing · blocking file errors", scene);
            }
        } finally {
            ui(stage::hide);
        }
    }

    static String slug(String text) { return text.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'); }
}

package com.opensmpsdeck.studio;

import javafx.scene.control.CheckBox;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.stage.Window;

import java.nio.file.Files;

import static com.opensmpsdeck.studio.StudioCaptureRun.*;

final class StudioBranchCapture {
    private StudioBranchCapture() {}

    static void capture(StudioCaptureRun run, Window owner) throws Exception {
        var work = StudioBranchFixture.create(Files.createTempDirectory("studio-branches-"));
        StudioBranchFixture.branches(work);
        var pane = fx(() -> new ReconstructionPane(owner, work, null, () -> null, StudioTestAudio::line));
        try {
            var scene = pane.stage().getScene();
            run.resize(scene);
            await("Review workspace", () -> scene.lookup("#reconstruction-review-view") != null);
            ui(() -> press(scene, "reconstruction-review-view"));
            await("branch list", () -> scene.lookup("#reconstruction-branch-astra") != null);
            node(scene, "reconstruction-inspector-tabs", TabPane.class);
            run.shot("analysis-branches", "Edit branches", "Choose a branch to review", scene);
            ui(() -> {
                press(scene, "reconstruction-branch-astra");
                fire(node(scene, "reconstruction-branch-overlay-astra", CheckBox.class));
                node(scene, "reconstruction-branch-rationale", TitledPane.class).setExpanded(true);
            });
            await("overlay", () -> !pane.canvas().branchLayers().isEmpty());
            run.shot("analysis-branch-detail", "Edit branch", "Rationale and overlay with part and time summaries", scene);
            ui(() -> press(scene, "reconstruction-roles-view"));
            run.shot("analysis-branch-roles", "Roles", "Branch pattern remains visible in Roles", scene);
            ui(() -> press(scene, "reconstruction-detail-view"));
            run.shot("analysis-branch-notes", "Notes", "Branch pattern remains visible in Notes", scene);
            ui(() -> {
                press(scene, "reconstruction-review-view");
                node(scene, "reconstruction-branch-rationale", TitledPane.class).setExpanded(false);
                press(scene, "reconstruction-branch-take");
                press(scene, "reconstruction-branch-fable");
                press(scene, "reconstruction-branch-take");
            });
            await("conflict", () -> scene.lookup("#reconstruction-branch-keep-a-0") != null);
            ui(() -> reveal(node(scene, "reconstruction-branch-keep-a-0", javafx.scene.control.Button.class)));
            run.shot("analysis-branch-conflict", "Merge draft", "Compare two conflicting pitch edits", scene);
            ui(() -> press(scene, "reconstruction-branch-keep-a-0"));
            await("resolved merge", () -> scene.lookup("#reconstruction-branch-keep-a-0") == null
                    && !node(scene, "reconstruction-branch-hear-merge", javafx.scene.control.Button.class).isDisabled());
            ui(() -> {
                reveal(node(scene, "reconstruction-branch-hear-merge", javafx.scene.control.Button.class));
                pane.canvas().onRange.accept(new ReviewTransport.Range(0, 3));
                press(scene, "reconstruction-branch-hear-merge");
                press(scene, "reconstruction-play");
            });
            await("merge audition", () -> label(scene, "reconstruction-live-status").equals("Merge draft"));
            run.shot("analysis-branch-audition", "Merge draft", "Audition selected changes at the playhead", scene);
        } finally {
            ui(pane::close);
        }
    }
}

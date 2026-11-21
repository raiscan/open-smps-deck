package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionReview;
import com.opensmpsdeck.project.ProjectDocument;
import com.opensmpsdeck.project.ProjectFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class DetachedArrangement {
    record Approval(String revision) {}

    private DetachedArrangement() {}

    static Optional<Approval> confirm(Window owner, Path work, ProjectDocument open) throws IOException {
        var path = work.resolve(ReconstructionReview.ARRANGEMENT).toAbsolutePath().normalize();
        if (open != null && !open.path().equals(path)) open = null;
        var saved = ReconstructionReview.arrangement(work);
        boolean pending = open != null && open.dirty() && open.project().detachedFromReconstruction();
        boolean detached = saved != null && saved.project().detachedFromReconstruction();
        if (!pending && !detached) return Optional.of(new Approval(null));

        var keep = new ButtonType("Keep a copy and apply", ButtonBar.ButtonData.OK_DONE);
        var separate = new ButtonType("Save as separate project…");
        var replace = new ButtonType("Overwrite and apply");
        var warning = new Alert(Alert.AlertType.WARNING, "", ButtonType.CANCEL, keep, separate, replace);
        var explanation = new Label(
                "Apply regenerates arrangement.smps.json and overwrites the hand-edited notes, clips and commands.");
        explanation.setWrapText(true);
        warning.getDialogPane().setContent(explanation);
        warning.initOwner(owner);
        warning.setTitle("Apply reconstruction");
        warning.setHeaderText("This arrangement has hand edits");
        warning.getDialogPane().setId("detached-arrangement-warning");
        warning.getDialogPane().setPrefWidth(740);
        for (var type : warning.getDialogPane().getButtonTypes()) {
            var button = (Region) warning.getDialogPane().lookupButton(type);
            ButtonBar.setButtonUniformSize(button, false);
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        var choice = warning.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return Optional.empty();

        var project = pending ? open.project() : saved.project();
        if (choice == keep) {
            Path backup = work.resolve("arrangement-hand-edits-" + UUID.randomUUID() + ".smps.json");
            ProjectFiles.save(backup, project, null);
        } else if (choice == separate) {
            var chooser = new FileChooser();
            chooser.setTitle("Save a separate arrangement project");
            chooser.setInitialFileName("edited-arrangement.smps.json");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SMPS Studio project", "*.smps.json"));
            var file = chooser.showSaveDialog(owner);
            if (file == null) return Optional.empty();
            ProjectFiles.save(file.toPath(), StudioWindow.relocateReview(project, path, file.toPath())
                    .withDetachedFromReconstruction(false), null);
        }
        if (pending) {
            open.save();
            return Optional.of(new Approval(open.savedRevision()));
        }
        return Optional.of(new Approval(saved.revision()));
    }
}

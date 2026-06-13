package com.opensmpsdeck.ui;

import com.opensmpsdeck.library.LibraryPaths;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

final class LibraryLocationDialog extends Dialog<Path> {

    LibraryLocationDialog() {
        setTitle("Instrument Library Location");
        setHeaderText("Choose where OpenSMPSDeck stores the instrument library.");

        AtomicReference<Path> selectedPath = new AtomicReference<>(LibraryPaths.getLibraryRoot());
        Label pathLabel = new Label(selectedPath.get().toString());
        pathLabel.setMaxWidth(Double.MAX_VALUE);
        pathLabel.setWrapText(true);

        Button chooseButton = new Button("Choose...");
        chooseButton.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose Instrument Library Location");
            Path current = selectedPath.get();
            if (current != null && Files.isDirectory(current)) {
                chooser.setInitialDirectory(current.toFile());
            } else {
                DialogPaths.applyTo(chooser, "instrumentLibraryLocation");
            }
            File chosen = chooser.showDialog(getDialogPane().getScene().getWindow());
            DialogPaths.remember("instrumentLibraryLocation", chosen);
            if (chosen != null) {
                Path path = chosen.toPath().toAbsolutePath().normalize();
                selectedPath.set(path);
                pathLabel.setText(path.toString());
            }
        });

        HBox row = new HBox(8, pathLabel, chooseButton);
        HBox.setHgrow(pathLabel, Priority.ALWAYS);
        VBox content = new VBox(8, new Label("Current location:"), row);
        content.setPadding(new Insets(10));

        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(620);
        ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButton, ButtonType.CANCEL);
        setResultConverter(button -> button == saveButton ? selectedPath.get() : null);
    }
}

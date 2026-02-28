package com.opensmps.deck;

import com.opensmps.deck.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX application entry point for OpenSMPS Deck.
 */
public class OpenSmpsDeck extends Application {

    @Override
    public void start(Stage primaryStage) {
        MainWindow mainWindow = new MainWindow(primaryStage);
        mainWindow.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

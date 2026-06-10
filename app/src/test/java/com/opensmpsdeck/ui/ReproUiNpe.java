package com.opensmpsdeck.ui;

import com.opensmpsdeck.io.SmpsImporter;
import com.opensmpsdeck.model.Song;
import javafx.application.Platform;

import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Constructs real SongTab UI for imported S3K songs and reports FX-thread exceptions. */
public class ReproUiNpe {
    public static void main(String[] args) throws Exception {
        CountDownLatch boot = new CountDownLatch(1);
        Platform.startup(boot::countDown);
        boot.await(15, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);

        String[] files = {
            "Sonic The Hedgehog 2/1-00 Continue.sm2",
            "Sonic The Hedgehog 3/0D Launch Base 1.s3k",
            "Sonic The Hedgehog 3/01 Angel Island 1.8000.s3k",
        };
        boolean anyFail = false;
        for (String name : files) {
            File f = new File("../docs/SMPS-rips/" + name);
            if (!f.exists()) f = new File("docs/SMPS-rips/" + name);
            Song song = new SmpsImporter().importFile(f);

            CountDownLatch latch = new CountDownLatch(1);
            final Throwable[] error = new Throwable[1];
            Platform.runLater(() -> {
                Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
                    if (error[0] == null) error[0] = e;
                });
                Stage stage = null;
                try {
                    SongTab tab = new SongTab(song);
                    tab.buildContent();
                    BorderPane root = new BorderPane();
                    root.setLeft(tab.getSongView());
                    VBox center = new VBox(tab.getBreadcrumbBar(), tab.getChainStrip(), tab.getTrackerGrid());
                    root.setCenter(center);
                    root.setRight(tab.getInstrumentPanel());
                    stage = new Stage();
                    stage.setScene(new Scene(root, 1400, 900));
                    stage.show();
                } catch (Throwable t) {
                    error[0] = t;
                } finally {
                    final Stage s = stage;
                    // let several pulses fire, then close
                    Platform.runLater(() -> Platform.runLater(() -> {
                        if (s != null) s.hide();
                        latch.countDown();
                    }));
                }
            });
            latch.await(30, TimeUnit.SECONDS);

            if (error[0] != null) {
                anyFail = true;
                System.out.println("FAIL " + name + ": " + error[0]);
                error[0].printStackTrace(System.out);
            } else {
                System.out.println("OK   " + name); System.out.flush();
            }
        }
        Platform.exit();
        System.exit(anyFail ? 1 : 0);
    }
}

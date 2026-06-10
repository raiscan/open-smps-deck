package com.opensmpsdeck.ui;

import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.prefs.Preferences;

/**
 * Remembers the last-used directory per dialog purpose so each file chooser
 * reopens where the user last was. Stored in user preferences; falls back
 * gracefully when a remembered path no longer exists.
 */
public final class DialogPaths {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(DialogPaths.class).node("dialogPaths");

    private DialogPaths() {
    }

    /**
     * Set the chooser's initial directory to the remembered path for this key,
     * when it still exists.
     *
     * @param key stable identifier for the dialog purpose (e.g. "project", "smpsImport")
     */
    public static void applyTo(FileChooser chooser, String key) {
        File dir = rememberedDir(key);
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
    }

    /** Directory chooser variant of {@link #applyTo(FileChooser, String)}. */
    public static void applyTo(DirectoryChooser chooser, String key) {
        File dir = rememberedDir(key);
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
    }

    /**
     * Remember the directory containing the chosen file (or the directory
     * itself) for this key. Null-safe: call with the chooser result directly.
     */
    public static void remember(String key, File chosen) {
        if (chosen == null) return;
        File dir = chosen.isDirectory() ? chosen : chosen.getParentFile();
        if (dir != null) {
            PREFS.put(key, dir.getAbsolutePath());
        }
    }

    private static File rememberedDir(String key) {
        String path = PREFS.get(key, null);
        if (path == null) return null;
        File dir = new File(path);
        return (dir.isDirectory()) ? dir : null;
    }
}

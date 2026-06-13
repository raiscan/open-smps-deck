package com.opensmpsdeck.library;

import java.nio.file.Path;
import java.util.prefs.Preferences;

public final class LibraryPaths {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(LibraryPaths.class).node("instrumentLibrary");
    private static final String ROOT_KEY = "root";

    private LibraryPaths() {
    }

    public static Path getLibraryRoot() {
        String configured = PREFS.get(ROOT_KEY, "");
        if (!configured.isBlank()) {
            return Path.of(configured);
        }
        return defaultLibraryRoot();
    }

    public static void setLibraryRoot(Path root) {
        PREFS.put(ROOT_KEY, root.toAbsolutePath().normalize().toString());
    }

    public static Path defaultLibraryRoot() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "OpenSMPSDeck", "instrument-library");
        }
        return Path.of(System.getProperty("user.home"), ".opensmpsdeck", "instrument-library");
    }
}

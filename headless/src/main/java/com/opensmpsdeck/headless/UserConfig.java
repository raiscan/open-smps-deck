package com.opensmpsdeck.headless;

import java.nio.file.Path;

/** Locations of per-user OpenSMPSDeck settings shared with Studio. */
public final class UserConfig {
    private UserConfig() {}

    public static Path file(String filename) {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path root = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
        return root.resolve("opensmpsdeck").resolve(filename);
    }
}

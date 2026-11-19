package com.opensmpsdeck.project;

import java.nio.file.Path;

/** A WAV on the project timeline. Offset is the project time of the first sample. */
public record AudioReference(String id, String label, String path, double offsetSeconds, double gainDb, String role) {
    public Path resolve(Path projectFile) {
        return projectFile.toAbsolutePath().normalize().resolveSibling(path).normalize();
    }

    public static String pathFor(Path projectFile, Path wav) {
        Path parent = projectFile.toAbsolutePath().normalize().getParent();
        Path absolute = wav.toAbsolutePath().normalize();
        return absolute.startsWith(parent) ? parent.relativize(absolute).toString() : absolute.toString();
    }

    public AudioReference withOffset(double seconds) {
        return new AudioReference(id, label, path, seconds, gainDb, role);
    }
}

package com.opensmpsdeck.project;

import java.util.Objects;

/** A validated project together with the SHA-256 of its exact UTF-8 file bytes. */
public record LoadedProject(StudioProject project, String revision) {
    public LoadedProject {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(revision, "revision");
    }
}

package com.opensmpsdeck.project;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** The local editing state and last valid disk baseline of one shared project. */
public final class ProjectDocument {
    private final Path path;
    private StudioProject project;
    private LoadedProject baseline;
    private LoadedProject externalPending;
    private boolean dirty;
    private boolean conflicted;
    private String message = "Saved";

    private ProjectDocument(Path path, LoadedProject loaded) {
        this.path = path;
        this.project = loaded.project();
        this.baseline = loaded;
    }

    public static ProjectDocument open(Path path) throws IOException {
        Path file = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return new ProjectDocument(file, ProjectFiles.load(file));
    }

    public synchronized StudioProject project() { return project; }
    public synchronized Path path() { return path; }
    public synchronized boolean dirty() { return dirty; }
    public synchronized boolean conflicted() { return conflicted; }
    public synchronized String message() { return message; }

    public synchronized void edit(StudioProject changed) {
        Objects.requireNonNull(changed, "project");
        ProjectCompiler.compile(changed);
        project = changed;
        dirty = !changed.equals(baseline.project());
        message = conflicted ? "Conflict: disk changed; reload with recovery to keep both versions" : dirty ? "Unsaved edits" : "Saved";
    }

    /** Returns true only when a valid external version is adopted. Always reread message(). */
    public synchronized boolean refresh() {
        final LoadedProject loaded;
        try {
            loaded = ProjectFiles.load(path);
        } catch (IOException exception) {
            if (dirty) conflicted = true;
            externalPending = null;
            message = "Cannot refresh: " + exception.getMessage() + (dirty ? "; local edits retained" : "; last valid project retained");
            return false;
        }
        if (loaded.revision().equals(baseline.revision())) {
            boolean recovered = conflicted || message.startsWith("Cannot refresh:");
            conflicted = false;
            externalPending = null;
            if (recovered) message = dirty ? "Unsaved edits" : "Saved";
            return false;
        }
        if (dirty) {
            externalPending = loaded;
            conflicted = true;
            message = "Conflict: disk changed; local edits retained. Reload with recovery to keep both versions";
            return false;
        }
        adopt(loaded);
        message = "Refreshed from disk";
        return true;
    }

    public synchronized void save() throws IOException {
        if (conflicted || externalPending != null) {
            throw new IOException("Project has an external conflict; reload with recovery before saving");
        }
        try {
            LoadedProject saved = ProjectFiles.save(path, project, baseline.revision());
            adopt(saved);
            message = "Saved";
        } catch (IOException exception) {
            // A failed optimistic save must never be followed by an unguarded retry.
            conflicted = true;
            message = "Save failed: " + exception.getMessage() + "; local edits retained";
            throw exception;
        }
    }

    /**
     * Validate disk first, preserve dirty local edits in a unique create-new file,
     * then reread disk before adopting it. Invalid/missing disk never discards work.
     * Returns the recovery path, or null if no local edits needed preservation.
     */
    public synchronized Path reloadKeepingRecovery() throws IOException {
        Path recovery = null;
        try {
            LoadedProject loaded = ProjectFiles.load(path);
            if (dirty) {
                while (true) {
                    Path candidate = path.resolveSibling(path.getFileName() + ".recovery-" + UUID.randomUUID() + ".json");
                    try {
                        ProjectFiles.save(candidate, project, null);
                        recovery = candidate;
                        break;
                    } catch (FileAlreadyExistsException ignored) {
                        // A user file is never reused or overwritten, even on collision.
                    }
                }
                loaded = ProjectFiles.load(path);
            }
            adopt(loaded);
            message = recovery == null ? "Reloaded from disk" : "Reloaded from disk; local edits recovered to " + recovery;
            return recovery;
        } catch (IOException exception) {
            if (dirty) conflicted = true;
            message = "Cannot reload: " + exception.getMessage() + "; local project retained"
                    + (recovery == null ? "" : "; recovery also saved to " + recovery);
            throw exception;
        }
    }

    private void adopt(LoadedProject loaded) {
        project = loaded.project();
        baseline = loaded;
        dirty = false;
        conflicted = false;
        externalPending = null;
    }
}

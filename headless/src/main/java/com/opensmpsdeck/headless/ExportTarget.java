package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Stages a forced export beside its destination, on the same filesystem. */
final class ExportTarget implements AutoCloseable {
    private final Path destination;
    private final boolean replace;
    private final Path staging;

    ExportTarget(Path destination, boolean replace) throws IOException {
        this.destination = destination;
        this.replace = replace;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && (!replace || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)))
            throw new FileAlreadyExistsException(destination.toString());
        this.staging = replace ? destination.resolveSibling(".export-" + UUID.randomUUID()) : destination;
    }

    Path path() { return staging; }

    void publish() throws IOException {
        if (replace) Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public void close() throws IOException {
        if (replace) Files.deleteIfExists(staging);
    }
}

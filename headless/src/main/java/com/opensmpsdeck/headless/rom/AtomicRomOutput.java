package com.opensmpsdeck.headless.rom;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stage privately beside the destination. Publish a complete file in one filesystem operation. */
final class AtomicRomOutput implements AutoCloseable {
    private final Path destination;
    private final boolean replace;
    private final Path staging;

    AtomicRomOutput(Path destination, boolean replace) throws IOException {
        this.destination = destination;
        this.replace = replace;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && (!replace || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))) {
            throw new FileAlreadyExistsException(destination.toString());
        }
        staging = Files.createTempFile(destination.getParent(), ".rom-export-", ".stage");
    }

    Path path() { return staging; }

    void publish() throws IOException {
        if (replace) {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            // ATOMIC_MOVE may overwrite even without REPLACE_EXISTING. Linking the staged
            // output provides an atomic no-clobber publish even when another writer races.
            try {
                Files.createLink(destination, staging);
            } catch (FileAlreadyExistsException e) {
                throw e;
            } catch (UnsupportedOperationException | FileSystemException e) {
                // FAT32/exFAT flash-cart cards and some network shares have no hard links.
                publishWithoutLink();
            }
        }
    }

    /** Plain move without replacement: refuses an existing file, with a narrow check-then-act race. */
    void publishWithoutLink() throws IOException {
        Files.move(staging, destination);
    }

    @Override public void close() throws IOException {
        Files.deleteIfExists(staging);
    }
}

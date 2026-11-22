package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Local references recover captures after a lossy image transfer. */
final class NoteClipRegistry {
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");
    private final Path index;

    NoteClipRegistry(Path index) {
        this.index = index;
    }

    static NoteClipRegistry user() {
        String xdg = System.getenv("XDG_STATE_HOME");
        Path root = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".local", "state");
        return new NoteClipRegistry(root.resolve("opensmpsdeck").resolve("clips"));
    }

    static boolean valid(String ref) {
        return ref != null && ref.matches("[0-9A-F]{10}");
    }

    void store(NoteHandoff.Capture capture) throws IOException {
        String ref = capture.reference();
        if (!valid(ref)) throw new IllegalArgumentException("Invalid lasso reference");
        Path project = Path.of(capture.path());
        Path directory = Files.isDirectory(project) ? project : project.getParent();
        Files.createDirectories(index);
        Path copy = directory.resolve(".clips").resolve(ref + ".txt");
        try {
            Files.createDirectories(copy.getParent());
            writePrivate(copy, capture.payload());
        } catch (java.nio.file.FileAlreadyExistsException collision) {
            throw collision;
        } catch (IOException readOnly) {
            copy = index.resolve(ref + ".txt");
            writePrivate(copy, capture.payload());
        }
        writePrivate(index.resolve(ref + ".path"), copy.toString());
    }

    String find(String ref) throws IOException {
        if (!valid(ref)) return null;
        var locator = index.resolve(ref + ".path");
        if (!Files.isRegularFile(locator) || Files.size(locator) > 16_384) return null;
        try {
            var file = Path.of(Files.readString(locator));
            if (!file.isAbsolute() || !Files.isRegularFile(file) || Files.size(file) > NoteScreenshot.PAYLOAD_LIMIT) return null;
            return Files.readString(file);
        } catch (InvalidPathException malformed) {
            return null;
        }
    }

    private static void writePrivate(Path path, String content) throws IOException {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE));
            try {
                Files.writeString(path, content, StandardOpenOption.WRITE);
            } catch (IOException failure) {
                Files.deleteIfExists(path);
                throw failure;
            }
        } else {
            Files.writeString(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }
}

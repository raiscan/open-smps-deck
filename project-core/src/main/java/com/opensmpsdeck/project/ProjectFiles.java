package com.opensmpsdeck.project;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Bounded, revision-aware persistence for the shared project source. */
public final class ProjectFiles {
    public static final int MAX_FILE_BYTES = 1_048_576;

    private ProjectFiles() {}

    public static LoadedProject load(Path path) throws IOException {
        Path file = normalized(path);
        byte[] bytes = readBytes(file);
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            StudioProject project = ProjectFormat.read(source);
            ProjectCompiler.compile(project);
            return new LoadedProject(project, revision(bytes));
        } catch (CharacterCodingException exception) {
            throw new IOException("Project is not valid UTF-8: " + file, exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid project '" + file + "': " + exception.getMessage(), exception);
        }
    }

    /**
     * Save only if the destination still has expectedRevision, or create a new
     * file when it is null. Existing files are staged beside the destination and
     * atomically replaced where the filesystem supports it.
     *
     * This is optimistic concurrency, not a transaction with arbitrary external
     * writers: another process can write between the final comparison and move.
     * New files use CREATE_NEW, because Java's ATOMIC_MOVE may overwrite an
     * existing target even without REPLACE_EXISTING. Their first publication is
     * therefore not atomic, but can never overwrite a pre-existing file.
     */
    public static LoadedProject save(Path path, StudioProject project, String expectedRevision) throws IOException {
        Path file = normalized(path);
        Objects.requireNonNull(project, "project");
        final byte[] bytes;
        try {
            ProjectCompiler.compile(project);
            bytes = ProjectFormat.write(project).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid project: " + exception.getMessage(), exception);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Project exceeds the " + MAX_FILE_BYTES + " byte source limit");
        }
        if (expectedRevision != null) requireRevision(file, expectedRevision);
        Path staged = Files.createTempFile(file.getParent(), "." + file.getFileName() + "-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                writeAll(output, bytes);
                output.force(true);
            }
            if (expectedRevision == null) {
                // CREATE_NEW checks and opens atomically, including existing symlinks.
                try (FileChannel output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    writeAll(output, bytes);
                    output.force(true);
                }
            } else {
                preservePermissions(file, staged);
                requireRevision(file, expectedRevision);
                try {
                    Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    requireRevision(file, expectedRevision);
                    Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new LoadedProject(project, revision(bytes));
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void preservePermissions(Path file, Path staged) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(staged, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems use their normal inherited permissions.
        }
    }

    private static void writeAll(FileChannel output, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) output.write(buffer);
    }

    private static void requireRevision(Path file, String expectedRevision) throws IOException {
        if (!expectedRevision.equals(revision(readBytes(file)))) {
            throw new IOException("Project changed on disk; reload with recovery before saving: " + file);
        }
    }

    static byte[] readBytes(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Project must be a regular file, not a link or directory: " + file);
        if (attributes.size() > MAX_FILE_BYTES) throw new IOException("Project exceeds the " + MAX_FILE_BYTES + " byte source limit: " + file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("Project exceeds the " + MAX_FILE_BYTES + " byte source limit: " + file);
            return bytes;
        }
    }

    static String revision(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}

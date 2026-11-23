package com.opensmpsdeck.project;

import java.io.IOException;
import java.io.InputStream;
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
    public static final String SOURCE_LIMIT_PROPERTY = "opensmpsdeck.project.maxSourceBytes";
    private static final int DEFAULT_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_SOURCE_BYTES = 32 * 1024 * 1024;

    private ProjectFiles() {}

    public static int maxSourceBytes() {
        String value = System.getProperty(SOURCE_LIMIT_PROPERTY);
        if (value == null) return DEFAULT_SOURCE_BYTES;
        try {
            int limit = Integer.parseInt(value);
            if (limit > 0 && limit <= MAX_SOURCE_BYTES) return limit;
        } catch (NumberFormatException ignored) {
            // Report the same configuration error for malformed and out-of-range values.
        }
        throw new IllegalArgumentException(SOURCE_LIMIT_PROPERTY + " must be an integer in 1.." + MAX_SOURCE_BYTES);
    }

    static String sourceLimitMessage(int limit) {
        return "Project exceeds the " + limit + " byte UTF-8 source limit (set -D" + SOURCE_LIMIT_PROPERTY + "=BYTES)";
    }

    public static LoadedProject load(Path path) throws IOException {
        return load(path, true);
    }

    public static StudioProject loadSource(Path path) throws IOException {
        return load(path, false).project();
    }

    private static LoadedProject load(Path path, boolean compile) throws IOException {
        Path file = normalized(path);
        byte[] bytes = readBytes(file);
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            StudioProject project = ProjectFormat.read(source);
            if (compile) ProjectCompiler.compile(project);
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
            bytes = encodeSource(project);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid project: " + exception.getMessage(), exception);
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
        if (!expectedRevision.equals(fileRevision(file))) {
            throw new IOException("Project changed on disk; reload with recovery before saving: " + file);
        }
    }

    private static byte[] readBytes(Path file) throws IOException {
        int limit = maxSourceBytes();
        checkFile(file, limit);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return readSource(input, limit);
        }
    }

    /** Read a source entry with a bound on actual bytes, including decompressed zip entries. */
    public static byte[] readSource(InputStream input) throws IOException {
        return readSource(input, maxSourceBytes());
    }

    private static byte[] readSource(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException(sourceLimitMessage(limit));
        return bytes;
    }

    /** Serialize project source using the same limit as the file and package readers. */
    public static byte[] encodeSource(StudioProject project) throws IOException {
        int limit = maxSourceBytes();
        String source = ProjectFormat.write(project);
        if (source.length() > limit) throw new IOException(sourceLimitMessage(limit));
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IOException(sourceLimitMessage(limit));
        return bytes;
    }

    private static void checkFile(Path file, int limit) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Project must be a regular file, not a link or directory: " + file);
        if (attributes.size() > limit) throw new IOException(sourceLimitMessage(limit) + ": " + file);
    }

    /** Hash the current project source without parsing or compiling it. */
    public static String fileRevision(Path file) throws IOException {
        int limit = maxSourceBytes();
        checkFile(file, limit);
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int remaining = limit + 1;
            while (remaining > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) return HexFormat.of().formatHex(digest.digest());
                digest.update(buffer, 0, count);
                remaining -= count;
            }
        }
        throw new IOException(sourceLimitMessage(limit) + ": " + file);
    }

    private static String revision(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static Path normalized(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}

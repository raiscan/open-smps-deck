package com.opensmpsdeck.headless.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class McpPaths {
    private static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;

    private McpPaths() {}

    static Path absolute(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Expected an absolute path: " + value);
        return path.normalize();
    }

    static Path input(String value) throws IOException {
        Path path = absolute(value);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Input file does not exist: " + path);
        return path.toRealPath();
    }

    static Path directory(String value) throws IOException {
        Path path = absolute(value);
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Directory does not exist: " + path);
        return path.toRealPath();
    }

    static Path project(String value) throws IOException {
        Path path = absolute(value);
        if (!Files.isRegularFile(path, NO_LINKS))
            throw new IllegalArgumentException("Expected an existing project file (no symbolic links): " + path);
        return path.toRealPath();
    }

    static Path newDirectory(String value) throws IOException {
        Path path = absolute(value);
        if (path.getParent() == null || Files.exists(path, NO_LINKS))
            throw new IllegalArgumentException("Expected a new work directory: " + path);
        return directory(path.getParent().toString()).resolve(path.getFileName());
    }

    static Path output(Path root, String value) throws IOException {
        Path path = Path.of(value);
        path = root.resolve(path).normalize();
        if (!path.startsWith(root) || path.equals(root))
            throw new IllegalArgumentException("Output must be inside " + root + ": " + value);
        Path parent = path.getParent().toRealPath();
        if (!parent.startsWith(root) || !Files.isDirectory(parent))
            throw new IllegalArgumentException("Output must be inside " + root + ": " + value);
        Path output = parent.resolve(path.getFileName());
        if (Files.exists(output, NO_LINKS))
            throw new IllegalArgumentException("Output already exists: " + output);
        return output;
    }

    static Path managed(Path root, String name) {
        Path path = root.resolve(name);
        if (Files.exists(path, NO_LINKS) && !Files.isRegularFile(path, NO_LINKS))
            throw new IllegalArgumentException("Expected a regular file inside " + root + ": " + name);
        return path;
    }
}

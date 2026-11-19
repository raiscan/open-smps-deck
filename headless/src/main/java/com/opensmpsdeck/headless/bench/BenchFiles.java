package com.opensmpsdeck.headless.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

final class BenchFiles {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private BenchFiles() {}

    static <T> T read(Path path, Class<T> type) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            T value = JSON.fromJson(reader, type);
            if (value == null) throw new IOException("Empty benchmark JSON: " + path);
            return value;
        } catch (JsonParseException failure) {
            throw new IOException("Invalid benchmark JSON: " + path, failure);
        }
    }

    static void write(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.toJson(value) + "\n");
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static String hash(Path path) throws IOException {
        var sha = digest();
        try (var in = new DigestInputStream(Files.newInputStream(path), sha)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    static void deleteScratch(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}

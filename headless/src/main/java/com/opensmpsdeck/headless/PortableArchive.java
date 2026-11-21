package com.opensmpsdeck.headless;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Reads and publishes bounded, deterministic archives. Entry names are never extracted. */
final class PortableArchive {
    static final int MAX_TRACK = 32 * 1024;
    static final int MAX_MANIFEST = 1024 * 1024;
    static final int MAX_SOURCE = 16 * 1024 * 1024;
    static final int MAX_ENTRIES = 1 + 1 + 1 + 255 + 127 + 95 + 1 + 1;
    static final int WITHOUT_SOURCE = 2 * 1024 * 1024;
    static final int WITH_SOURCE = 20 * 1024 * 1024;

    private PortableArchive() {}

    static void write(Path output, Map<String, byte[]> entries, Integer limit) throws IOException {
        var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean complete = false;
        try {
            try (var zip = new ZipOutputStream(new BufferedOutputStream(stream), StandardCharsets.UTF_8)) {
                for (var entry : entries.entrySet()) {
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedIOException("Package export cancelled");
                    byte[] data = entry.getValue();
                    var item = new ZipEntry(entry.getKey());
                    item.setTime(0);
                    item.setMethod(ZipEntry.STORED);
                    item.setSize(data.length);
                    item.setCrc(crc(data));
                    zip.putNextEntry(item);
                    zip.write(data);
                    zip.closeEntry();
                }
            }
            if (limit != null && Files.size(output) > limit)
                throw new IllegalArgumentException("Package exceeds the " + limit + " byte archive limit");
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(output);
        }
    }

    static LinkedHashMap<String, byte[]> read(Path path) throws IOException {
        if (Files.size(path) > WITH_SOURCE)
            throw new IllegalArgumentException("Package exceeds the 20 MiB archive limit");
        var entries = new LinkedHashMap<String, byte[]>();
        int total = 0;
        try (var zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            if (zip.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many package entries");
            for (var iter = zip.entries(); iter.hasMoreElements();) {
                var entry = iter.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("/") || name.contains("\\")
                        || java.util.Arrays.asList(name.split("/", -1)).contains("..")
                        || !name.matches("[a-zA-Z0-9/_.-]+"))
                    throw new IllegalArgumentException("Unsafe package entry: " + name);
                if (entry.getMethod() != ZipEntry.STORED || entry.getTime() != 0)
                    throw new IllegalArgumentException(name + " must be stored with timestamp 0");
                if (entries.containsKey(name)) throw new IllegalArgumentException("Duplicate package entry: " + name);
                int limit = entryLimit(name);
                if (entry.getSize() < 0 || entry.getSize() > limit || entry.getCompressedSize() != entry.getSize())
                    throw new IllegalArgumentException(name + " exceeds its entry size limit");
                total += (int) entry.getSize();
                if (total > WITH_SOURCE) throw new IllegalArgumentException("Package payload exceeds 20 MiB");
                try (var input = zip.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes((int) entry.getSize() + 1);
                    if (bytes.length != entry.getSize() || crc(bytes) != entry.getCrc())
                        throw new IllegalArgumentException(name + " has an invalid ZIP size or CRC");
                    entries.put(name, bytes);
                }
            }
        }
        verifyLocalEntries(path, entries);
        if (!entries.containsKey("source.smps.json") && (total > WITHOUT_SOURCE || Files.size(path) > WITHOUT_SOURCE))
            throw new IllegalArgumentException("Package without source exceeds the 2 MiB archive limit");
        return entries;
    }

    private static void verifyLocalEntries(Path path, Map<String, byte[]> entries) throws IOException {
        // ZIP stores metadata in the local headers as well as the central
        // directory. Reject ambiguous archives whose two inventories differ.
        try (var zip = new ZipInputStream(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            for (var expected : entries.entrySet()) {
                var local = zip.getNextEntry();
                if (local == null || !local.getName().equals(expected.getKey())
                        || local.getMethod() != ZipEntry.STORED || local.getTime() != 0
                        || local.getSize() != expected.getValue().length
                        || !java.util.Arrays.equals(zip.readNBytes(expected.getValue().length + 1), expected.getValue()))
                    throw new IllegalArgumentException("ZIP local header disagrees with the directory: " + expected.getKey());
            }
            if (zip.getNextEntry() != null) throw new IllegalArgumentException("Unexpected ZIP local entry");
        }
    }

    static int entryLimit(String path) {
        return switch (path) {
            case "manifest.json", "LICENSE.txt" -> MAX_MANIFEST;
            case "track.bin" -> MAX_TRACK;
            case "source.smps.json" -> MAX_SOURCE;
            case "voices/global.bin" -> 128 * 25;
            default -> {
                if (path.matches("(psg|mod)/asset-[0-9]{3}\\.bin")) yield 256;
                if (path.matches("dac/asset-[0-9]{3}\\.pcm")) yield 65535;
                throw new IllegalArgumentException("Unknown package entry: " + path);
            }
        };
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }
}

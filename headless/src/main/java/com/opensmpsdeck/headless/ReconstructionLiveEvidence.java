package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source evidence owned by one editor's canonical replay worker. */
final class ReconstructionLiveEvidence {
    private record Stamp(Object key, FileTime modified, long size) {
        static Stamp read(Path file) throws IOException {
            var info = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stamp(info.fileKey(), info.lastModifiedTime(), info.size());
        }
    }

    @FunctionalInterface
    interface Read<T> { T read() throws IOException; }

    private static final ThreadLocal<ReconstructionLiveEvidence> CURRENT = new ThreadLocal<>();
    private final LinkedHashMap<Object, Object> gates = new LinkedHashMap<>(256, .75f, true);
    private final Map<Path, Stamp> stamps = new HashMap<>();
    private final LinkedHashMap<Object, Object> values = new LinkedHashMap<>(32, .75f, true);

    synchronized <T> T run(Read<T> action) throws IOException {
        if (!current()) {
            stamps.clear();
            values.clear();
            gates.clear();
        }
        CURRENT.set(this);
        try {
            return action.read();
        } finally {
            CURRENT.remove();
        }
    }

    static void track(Path path) throws IOException {
        var cache = CURRENT.get();
        if (cache != null && !cache.stamps.containsKey(path)) cache.stamps.put(path, Stamp.read(path));
    }

    static void requireCurrent() throws IOException {
        var cache = CURRENT.get();
        if (cache != null && !cache.current()) throw new ReconstructionReview.Changed();
    }

    private boolean current() throws IOException {
        for (var entry : stamps.entrySet()) {
            if (Files.notExists(entry.getKey()) || !entry.getValue().equals(Stamp.read(entry.getKey()))) return false;
        }
        return true;
    }

    private record TranscriptionKey(long duration, com.google.gson.JsonElement inputs) {}

    static <T> T transcription(long duration, ReconstructionStages.Inputs inputs, Read<T> source) throws IOException {
        if (CURRENT.get() == null) return source.read();
        return memo(new TranscriptionKey(duration, new com.google.gson.Gson().toJsonTree(inputs)), source);
    }

    static <T> T memo(Object key, Read<T> source) throws IOException {
        var cache = CURRENT.get();
        return cache == null ? source.read() : memo(cache.values, 32, key, source);
    }

    static <T> T gate(Object key, Read<T> source) throws IOException {
        var cache = CURRENT.get();
        return cache == null ? source.read() : memo(cache.gates, 16_384, key, source);
    }

    @SuppressWarnings("unchecked")
    private static <T> T memo(LinkedHashMap<Object, Object> entries, int max, Object key, Read<T> source) throws IOException {
        var retained = entries.get(key);
        if (retained != null) return (T) retained;
        var value = source.read();
        if (!Thread.currentThread().isInterrupted()) {
            entries.put(key, value);
            if (entries.size() > max) entries.pollFirstEntry();
        }
        return value;
    }
}

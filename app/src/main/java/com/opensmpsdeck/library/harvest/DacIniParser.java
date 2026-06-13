package com.opensmpsdeck.library.harvest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DacIniParser {

    private DacIniParser() {
    }

    public record Entry(
            int id,
            String compressionLabel,
            String file,
            int rate,
            String pan,
            String param1,
            String param2) {

        public boolean isDpcm() {
            return "DPCM".equalsIgnoreCase(compressionLabel)
                    || "True".equalsIgnoreCase(compressionLabel);
        }
    }

    public static List<Entry> parse(Path ini) throws IOException {
        List<Entry> entries = new ArrayList<>();
        SectionBuilder current = null;

        for (String rawLine : Files.readAllLines(ini, StandardCharsets.UTF_8)) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                addIfComplete(entries, current);
                current = newSection(line.substring(1, line.length() - 1).trim());
                continue;
            }

            if (current == null || current.id < 0) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(equals + 1).trim();
            switch (key) {
                case "compr" -> current.compressionLabel = normalizeCompressionLabel(value);
                case "file" -> current.file = value;
                case "rate" -> current.rate = parseNumber(value);
                case "pan" -> current.pan = value;
                case "param1" -> current.param1 = value;
                case "param2" -> current.param2 = value;
                default -> {
                }
            }
        }
        addIfComplete(entries, current);
        return List.copyOf(entries);
    }

    private static SectionBuilder newSection(String idText) {
        try {
            return new SectionBuilder(Integer.parseInt(idText, 16));
        } catch (NumberFormatException e) {
            return new SectionBuilder(-1);
        }
    }

    private static void addIfComplete(List<Entry> entries, SectionBuilder current) {
        if (current != null && current.id >= 0 && current.file != null && !current.file.isBlank()) {
            entries.add(new Entry(
                    current.id,
                    current.compressionLabel,
                    current.file,
                    current.rate,
                    current.pan,
                    current.param1,
                    current.param2));
        }
    }

    private static String normalizeCompressionLabel(String value) {
        if (value == null || value.isBlank()) {
            return "PCM";
        }
        if ("dpcm".equalsIgnoreCase(value)) {
            return "DPCM";
        }
        if ("pcm".equalsIgnoreCase(value)) {
            return "PCM";
        }
        if ("true".equalsIgnoreCase(value)) {
            return "True";
        }
        return value;
    }

    private static int parseNumber(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Integer.parseInt(trimmed.substring(2), 16);
        }
        if (trimmed.startsWith("$")) {
            return Integer.parseInt(trimmed.substring(1), 16);
        }
        if (trimmed.matches(".*[A-Fa-f].*")) {
            return Integer.parseInt(trimmed, 16);
        }
        return Integer.parseInt(trimmed);
    }

    private static String stripComment(String line) {
        int semicolon = line.indexOf(';');
        int hash = line.indexOf('#');
        int comment = -1;
        if (semicolon >= 0 && hash >= 0) {
            comment = Math.min(semicolon, hash);
        } else if (semicolon >= 0) {
            comment = semicolon;
        } else if (hash >= 0) {
            comment = hash;
        }
        return comment >= 0 ? line.substring(0, comment) : line;
    }

    private static final class SectionBuilder {
        private final int id;
        private String compressionLabel = "PCM";
        private String file;
        private int rate;
        private String pan;
        private String param1;
        private String param2;

        private SectionBuilder(int id) {
            this.id = id;
        }
    }
}

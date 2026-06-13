package com.opensmpsdeck.library.rip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SmpsRipConfigParser {

    private SmpsRipConfigParser() {
    }

    public static SmpsRipConfig parse(Path configFile) throws IOException {
        Path baseDirectory = configFile.toAbsolutePath().normalize().getParent();
        Map<String, Map<String, String>> rawSections = readIniSections(configFile);
        Map<String, SmpsRipConfig.Section> sections = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, String>> entry : rawSections.entrySet()) {
            Map<String, String> values = entry.getValue();
            SmpsRipConfig.Section preliminary = new SmpsRipConfig.Section("", baseDirectory, values);
            String extension = firstPresent(preliminary, "ext", "extension");
            if (extension.isBlank() && entry.getKey().startsWith(".")) {
                extension = entry.getKey();
            }
            Path directory = resolveDirectory(baseDirectory, firstPresent(preliminary, "dir", "directory"));
            sections.put(entry.getKey(), new SmpsRipConfig.Section(extension, directory, values));
        }

        return new SmpsRipConfig(configFile, sections);
    }

    static Map<String, Map<String, String>> readIniSections(Path file) throws IOException {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> current = null;

        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String sectionName = line.substring(1, line.length() - 1).trim();
                current = sections.computeIfAbsent(sectionName, ignored -> new LinkedHashMap<>());
                continue;
            }
            if (current == null) {
                current = sections.computeIfAbsent("", ignored -> new LinkedHashMap<>());
            }
            int equals = line.indexOf('=');
            if (equals > 0) {
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                current.put(key, value);
            }
        }

        return sections;
    }

    static String stripComment(String line) {
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

    private static String firstPresent(SmpsRipConfig.Section section, String... keys) {
        for (String key : keys) {
            String value = section.value(key);
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    private static Path resolveDirectory(Path baseDirectory, String directory) {
        if (directory == null || directory.isBlank()) {
            return baseDirectory.normalize();
        }
        Path path = Path.of(directory);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDirectory.resolve(path).normalize();
    }
}

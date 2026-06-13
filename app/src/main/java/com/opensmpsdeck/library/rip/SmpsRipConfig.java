package com.opensmpsdeck.library.rip;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record SmpsRipConfig(Path configFile, Map<String, Section> sections) {

    public SmpsRipConfig {
        sections = Map.copyOf(sections);
    }

    public record Section(String extension, Path directory, Map<String, String> values) {

        public Section {
            directory = directory.normalize();
            values = normalizeKeys(values);
        }

        public String value(String key) {
            if (key == null) {
                return null;
            }
            return values.get(normalizeKey(key));
        }

        public Path resolve(String key) {
            String value = value(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                return path.normalize();
            }
            return directory.resolve(path).normalize();
        }

        private static Map<String, String> normalizeKeys(Map<String, String> source) {
            Map<String, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : source.entrySet()) {
                normalized.put(normalizeKey(entry.getKey()), entry.getValue());
            }
            return Map.copyOf(normalized);
        }

        private static String normalizeKey(String key) {
            return key.toLowerCase(Locale.ROOT);
        }
    }
}

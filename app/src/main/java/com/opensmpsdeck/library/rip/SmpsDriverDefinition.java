package com.opensmpsdeck.library.rip;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record SmpsDriverDefinition(
        String ptrFmt,
        String tempoMode,
        String insMode,
        String insRegs,
        boolean hasPreSmpsTrackHeader) {

    public static SmpsDriverDefinition parse(Path path) throws IOException {
        Map<String, String> values = readProperties(path);
        return new SmpsDriverDefinition(
                value(values, "ptrfmt"),
                value(values, "tempomode"),
                value(values, "insmode"),
                value(values, "insregs"),
                hasPreSmpsTrackHeader(values));
    }

    public String summary() {
        return "PtrFmt=" + nullToEmpty(ptrFmt)
                + " TempoMode=" + nullToEmpty(tempoMode)
                + " InsMode=" + nullToEmpty(insMode)
                + " InsRegs=" + nullToEmpty(insRegs)
                + " PreSMPS=" + hasPreSmpsTrackHeader;
    }

    private static Map<String, String> readProperties(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map<String, String> section : SmpsRipConfigParser.readIniSections(path).values()) {
            for (Map.Entry<String, String> entry : section.entrySet()) {
                values.put(normalize(entry.getKey()), entry.getValue());
            }
        }
        return values;
    }

    private static String value(Map<String, String> values, String key) {
        return values.getOrDefault(key, "");
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("yes") || normalized.equals("1");
    }

    private static boolean hasPreSmpsTrackHeader(Map<String, String> values) {
        String preSmpsTrackHeader = value(values, "presmpstrkhdr");
        if (!preSmpsTrackHeader.isBlank()) {
            return true;
        }
        return parseBoolean(value(values, "presmps"));
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

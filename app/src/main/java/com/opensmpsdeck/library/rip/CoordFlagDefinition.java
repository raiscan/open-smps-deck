package com.opensmpsdeck.library.rip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CoordFlagDefinition {

    private final Map<Integer, CoordFlagCommand> mainCommands;
    private final Map<Integer, CoordFlagCommand> metaCommands;

    private CoordFlagDefinition(Map<Integer, CoordFlagCommand> mainCommands,
                                Map<Integer, CoordFlagCommand> metaCommands) {
        this.mainCommands = Map.copyOf(mainCommands);
        this.metaCommands = Map.copyOf(metaCommands);
    }

    public static CoordFlagDefinition parse(Path path) throws IOException {
        Map<Integer, CoordFlagCommand> main = new LinkedHashMap<>();
        Map<Integer, CoordFlagCommand> meta = new LinkedHashMap<>();
        Map<Integer, CoordFlagCommand> current = main;

        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = SmpsRipConfigParser.stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = sectionMap(line.substring(1, line.length() - 1), main, meta);
                continue;
            }
            CoordFlagCommand command = parseCommand(line);
            if (command != null) {
                current.put(command.byteValue(), command);
            }
        }

        return new CoordFlagDefinition(main, meta);
    }

    public CoordFlagCommand mainCommand(int byteValue) {
        return mainCommands.get(byteValue & 0xFF);
    }

    public CoordFlagCommand metaCommand(int byteValue) {
        return metaCommands.get(byteValue & 0xFF);
    }

    private static Map<Integer, CoordFlagCommand> sectionMap(String name,
                                                             Map<Integer, CoordFlagCommand> main,
                                                             Map<Integer, CoordFlagCommand> meta) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("meta") ? meta : main;
    }

    private static CoordFlagCommand parseCommand(String line) {
        String[] columns = line.trim().split("\\s+");
        if (columns.length < 2) {
            return null;
        }

        int byteValue = parseByte(columns[0]);
        String name = columns[1];
        int parameterLength = -1;
        int jumpOffset = -1;

        if (columns.length >= 4 && !isInteger(columns[2]) && isInteger(columns[3]) && !columns[2].contains("=")) {
            parameterLength = Integer.parseInt(columns[3]);
            if (columns.length >= 5 && isInteger(columns[4])) {
                jumpOffset = Integer.parseInt(columns[4]);
            }
            return new CoordFlagCommand(byteValue, name, parameterLength, jumpOffset);
        }

        for (int i = 2; i < columns.length; i++) {
            String cell = columns[i].trim();
            if (cell.isEmpty()) {
                continue;
            }
            if (cell.contains("=")) {
                String[] parts = cell.split("/");
                for (String part : parts) {
                    String[] keyValue = part.split("=", 2);
                    if (keyValue.length != 2) {
                        continue;
                    }
                    String key = keyValue[0].trim().toLowerCase(Locale.ROOT);
                    int value = Integer.parseInt(keyValue[1].trim());
                    if (key.equals("len")) {
                        parameterLength = value;
                    } else if (key.equals("jmpofs")) {
                        jumpOffset = value;
                    }
                }
            } else if (parameterLength < 0) {
                parameterLength = Integer.parseInt(cell);
            } else if (jumpOffset < 0) {
                jumpOffset = Integer.parseInt(cell);
            }
        }

        if (parameterLength < 0) {
            parameterLength = 0;
        }
        return new CoordFlagCommand(byteValue, name, parameterLength, jumpOffset);
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int parseByte(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        return Integer.parseInt(normalized, 16) & 0xFF;
    }

    public record CoordFlagCommand(int byteValue, String name, int parameterLength, int jumpOffset) {
    }
}

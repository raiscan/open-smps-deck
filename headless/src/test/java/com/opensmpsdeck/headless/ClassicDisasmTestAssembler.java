package com.opensmpsdeck.headless;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Independent two-pass assembler for the emitted S1 and S2 SMPS2ASM subset. */
final class ClassicDisasmTestAssembler {
    private static final Map<String, Integer> FLAGS = Map.ofEntries(
            Map.entry("smpsPan", 0xE0), Map.entry("smpsDetune", 0xE1), Map.entry("smpsAlterVol", 0xE6),
            Map.entry("smpsNoteFill", 0xE8), Map.entry("smpsChangeTransposition", 0xE9),
            Map.entry("smpsPSGAlterVol", 0xEC), Map.entry("smpsFMvoice", 0xEF), Map.entry("smpsModSet", 0xF0),
            Map.entry("smpsStop", 0xF2), Map.entry("smpsPSGform", 0xF3), Map.entry("smpsModOff", 0xF4),
            Map.entry("smpsPSGvoice", 0xF5));
    private static final Map<String, Integer> ARITY = Map.ofEntries(
            Map.entry("smpsPan", 2), Map.entry("smpsDetune", 1), Map.entry("smpsAlterVol", 1),
            Map.entry("smpsNoteFill", 1), Map.entry("smpsChangeTransposition", 1),
            Map.entry("smpsPSGAlterVol", 1), Map.entry("smpsFMvoice", 1), Map.entry("smpsModSet", 4),
            Map.entry("smpsStop", 0), Map.entry("smpsPSGform", 1), Map.entry("smpsModOff", 0),
            Map.entry("smpsPSGvoice", 1));

    private ClassicDisasmTestAssembler() {}

    static byte[] assemble(DisassemblyKit kit, boolean s1, int placement) {
        String path = "sound/music/" + kit.name;
        String source = new String(kit.files.get(path + ".asm"), StandardCharsets.UTF_8);
        var voices = new HashMap<String, String>();
        if (kit.files.containsKey(path + "_stock.asm")) {
            var lines = new String(kit.files.get(path + "_stock.asm"), StandardCharsets.UTF_8).lines().toList();
            for (int i = 0; i < lines.size(); i += 3) {
                voices.put(lines.get(i).split(" ")[0], lines.get(i + 1).strip());
            }
        }
        var symbols = new HashMap<String, Integer>();
        symbols.put("smpsNoAttack", 0xE7);
        symbols.put("dKick", 0x81);
        symbols.put("dSnare", 0x82);
        var lines = source.lines().map(line -> line.split(";", 2)[0].strip()).filter(line -> !line.isEmpty()).toList();
        var bytes = new ByteArrayOutputStream();
        for (int pass = 0; pass < 2; pass++) {
            bytes.reset();
            for (String line : lines) {
                if (line.endsWith(":")) {
                    symbols.put(line.substring(0, line.length() - 1), bytes.size());
                    continue;
                }
                if (line.contains(" equ ")) {
                    var equ = line.split(" equ ");
                    symbols.put(equ[0], value(equ[1], symbols, false));
                    continue;
                }
                if (line.startsWith("include ")) continue;
                line = voices.getOrDefault(line, line);
                var parts = line.split("\\s+", 2);
                String macro = parts[0];
                String[] args = parts.length == 2 ? parts[1].split(",") : new String[0];
                switch (macro) {
                    case "smpsHeaderStartSong" -> {
                        if (!args[0].equals(s1 ? "1" : "2")) throw new AssertionError(line);
                    }
                    case "smpsHeaderVoiceNull" -> {
                        bytes.write(0);
                        bytes.write(0);
                    }
                    case "smpsHeaderVoice", "smpsHeaderDAC", "smpsHeaderFM", "smpsHeaderPSG" -> {
                        int target = value(args[0], symbols, pass == 0);
                        if (s1) {
                            bytes.write(target >> 8);
                            bytes.write(target);
                        } else {
                            int pointer = placement + target;
                            bytes.write(pointer);
                            bytes.write(pointer >> 8);
                        }
                        writeArgs(bytes, args, 1, symbols);
                    }
                    case "dc.b", "smpsHeaderChan", "smpsHeaderTempo" -> writeArgs(bytes, args, 0, symbols);
                    default -> {
                        if (!FLAGS.containsKey(macro) || args.length != ARITY.get(macro)) throw new AssertionError(line);
                        bytes.write(FLAGS.get(macro));
                        if (macro.equals("smpsPan")) {
                            bytes.write(value(args[0], symbols, false) + value(args[1], symbols, false));
                        } else {
                            writeArgs(bytes, args, 0, symbols);
                        }
                    }
                }
            }
        }
        return bytes.toByteArray();
    }

    private static int value(String token, Map<String, Integer> symbols, boolean unresolved) {
        if (token.startsWith("$")) return Integer.parseInt(token.substring(1), 16);
        if (token.equals("0")) return 0;
        Integer value = symbols.get(token);
        if (value == null && !unresolved) throw new AssertionError("Unknown symbol " + token);
        return value == null ? 0 : value;
    }

    private static void writeArgs(ByteArrayOutputStream out, String[] args, int skip, Map<String, Integer> symbols) {
        for (int i = skip; i < args.length; i++) out.write(value(args[i], symbols, false));
    }
}

package com.opensmpsdeck.headless;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Test-only assembler for the emitted subset. Pointers resolve at the package base. */
final class DisasmTestAssembler {
    private final Map<String, Integer> labels = new HashMap<>();
    private final Map<String, String> includes;
    private final Map<String, String> macros = new HashMap<>();
    private final Map<String, int[]> voice = new HashMap<>();
    private final int base;
    private ByteArrayOutputStream out;

    DisasmTestAssembler(int base, Map<String, String> includes) {
        this.base = base;
        this.includes = includes;
        labels.put("smpsNoAttack", 0xe7);
        labels.put("dKickS3", 0x86);
    }

    byte[] assemble(String source) {
        for (int pass = 0; pass < 2; pass++) {
            out = new ByteArrayOutputStream();
            parse(source, pass == 0);
        }
        return out.toByteArray();
    }

    private void parse(String source, boolean collecting) {
        String declaration = null;
        var body = new StringBuilder();
        for (String line : source.lines().toList()) {
            line = line.split(";", 2)[0].strip();
            if (line.isEmpty()) continue;
            if (declaration != null) {
                if (line.equals("endm")) {
                    macros.put(declaration, body.toString());
                    declaration = null;
                    body.setLength(0);
                } else {
                    body.append(line).append('\n');
                }
                continue;
            }
            if (line.endsWith(" macro")) {
                declaration = line.substring(0, line.indexOf(' '));
                continue;
            }
            if (line.endsWith(":")) {
                labels.put(line.substring(0, line.length() - 1), base + out.size());
                continue;
            }
            if (line.contains(" equ ")) {
                var parts = line.split(" equ ");
                labels.put(parts[0], value(parts[1], collecting));
                continue;
            }
            var parts = line.split("\\s+", 2);
            var args = parts.length == 2 ? parts[1].split(",\\s*") : new String[0];
            String op = parts[0];
            if (op.equals("include")) {
                String include = includes.get(args[0].substring(1, args[0].length() - 1));
                assertNotNull(include, line);
                parse(include, collecting);
            } else if (macros.containsKey(op)) {
                parse(macros.get(op), collecting);
            } else {
                emit(op, args, collecting);
            }
        }
    }

    private void emit(String op, String[] args, boolean collecting) {
        int[] values = new int[args.length];
        for (int i = 0; i < args.length; i++) values[i] = value(args[i], collecting);
        switch (op) {
            case "smpsHeaderStartSong" -> {}
            case "smpsHeaderVoiceNull" -> word(0);
            case "smpsHeaderVoiceUVB" -> word(0x17d8);
            case "smpsHeaderVoice", "dc.w" -> word(values[0]);
            case "smpsHeaderDAC", "smpsHeaderFM", "smpsHeaderPSG" -> {
                word(values[0]);
                for (int i = 1; i < values.length; i++) out.write(values[i]);
            }
            case "smpsHeaderChan", "smpsHeaderTempo", "dc.b" -> write(values);
            case "smpsConditionalJump" -> branch(0xeb, values);
            case "smpsJump" -> branch(0xf6, values);
            case "smpsLoop" -> branch(0xf7, values);
            case "smpsCall" -> branch(0xf8, values);
            case "smpsContinuousLoop" -> branch(0xfc, values);
            case "smpsPan" -> write(0xe0, values[0] + values[1]);
            case "smpsFMAlterVol" -> flag(values.length == 1 ? 0xe6 : 0xe5, values);
            case "smpsSetTempoMod" -> write(0xff, 0, values[0]);
            case "smpsPlaySound" -> write(0xff, 1, values[0]);
            case "smpsHaltMusic" -> write(0xff, 2, values[0]);
            case "smpsSetTempoDiv" -> write(0xff, 4, values[0]);
            case "smpsSSGEG" -> write(0xff, 5, values[0], values[2], values[1], values[3]);
            case "smpsFMVolEnv" -> write(0xff, 6, values[0], values[1]);
            case "smpsResetSpindashRev" -> write(0xff, 7);
            case "smpsPlayDACSample" -> write(0xea, values[0] & 0x7f);
            default -> {
                if (op.startsWith("smpsVc")) {
                    voice.put(op.substring(6), values);
                    if (op.equals("smpsVcTotalLevel")) writeVoice();
                } else {
                    Integer code = FLAGS.get(op);
                    assertNotNull(code, op);
                    flag(code, values);
                }
            }
        }
    }

    private static final Map<String, Integer> FLAGS = Map.ofEntries(
            Map.entry("smpsDetune", 0xe1), Map.entry("smpsFade", 0xe2), Map.entry("smpsStopFM", 0xe3),
            Map.entry("smpsSetVol", 0xe4), Map.entry("smpsNoteFill", 0xe8), Map.entry("smpsSpindashRev", 0xe9),
            Map.entry("smpsPSGAlterVol", 0xec), Map.entry("smpsSetNote", 0xed), Map.entry("smpsFMICommand", 0xee),
            Map.entry("smpsFMvoice", 0xef), Map.entry("smpsModSet", 0xf0), Map.entry("smpsModChange2", 0xf1),
            Map.entry("smpsStop", 0xf2), Map.entry("smpsPSGform", 0xf3), Map.entry("smpsModChange", 0xf4),
            Map.entry("smpsPSGvoice", 0xf5), Map.entry("smpsReturn", 0xf9), Map.entry("smpsModOff", 0xfa),
            Map.entry("smpsChangeTransposition", 0xfb), Map.entry("smpsAlternateSMPS", 0xfd),
            Map.entry("smpsFM3SpecialMode", 0xfe));

    private int value(String operand, boolean collecting) {
        if (operand.startsWith("$")) return Integer.parseInt(operand.substring(1), 16);
        if (operand.startsWith("z80_ptr(")) return value(operand.substring(8, operand.length() - 1), collecting);
        if (operand.matches("[0-9]+")) return Integer.parseInt(operand);
        int plus = operand.indexOf('+');
        if (plus >= 0) return value(operand.substring(0, plus), collecting) + value(operand.substring(plus + 1), collecting);
        Integer label = labels.get(operand);
        if (!collecting) assertNotNull(label, operand);
        return label == null ? 0 : label;
    }

    private void writeVoice() {
        write(get("UnusedBits", 0) << 6 | get("Feedback", 0) << 3 | get("Algorithm", 0));
        for (int i = 3; i >= 0; i--) write(get("Detune", i) << 4 | get("CoarseFreq", i));
        for (int i = 3; i >= 0; i--) write(get("RateScale", i) << 6 | get("AttackRate", i));
        for (int i = 3; i >= 0; i--) write(get("AmpMod", i) << 7 | get("UnusedBits", i + 1) << 5 | get("DecayRate1", i));
        for (int i = 3; i >= 0; i--) write(get("DecayRate2", i));
        for (int i = 3; i >= 0; i--) write(get("DecayLevel", i) << 4 | get("ReleaseRate", i));
        for (int i = 3; i >= 0; i--) write(get("TotalLevel", i));
    }

    private int get(String field, int index) { return voice.get(field)[index]; }

    private void branch(int opcode, int[] args) {
        write(opcode);
        for (int i = 0; i < args.length - 1; i++) write(args[i]);
        word(args[args.length - 1]);
    }

    private void flag(int opcode, int[] args) { write(opcode); write(args); }
    private void word(int value) { write(value, value >>> 8); }
    private void write(int... values) { for (int value : values) out.write(value); }
}

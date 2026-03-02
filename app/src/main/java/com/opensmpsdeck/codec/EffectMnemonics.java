package com.opensmpsdeck.codec;

import com.opensmps.smps.SmpsCoordFlags;

public final class EffectMnemonics {

    private EffectMnemonics() {}

    public static String format(int flag, int[] params) {
        return switch (flag) {
            case SmpsCoordFlags.PAN -> formatPan(params);
            case SmpsCoordFlags.DETUNE -> "DET " + formatSigned(params[0]);
            case SmpsCoordFlags.SET_COMM -> String.format("COM %02X", params[0]);
            case SmpsCoordFlags.TICK_MULT -> String.format("TIK %02X", params[0]);
            case SmpsCoordFlags.VOLUME -> "VOL " + formatSigned(params[0]);
            case SmpsCoordFlags.TIE -> "TIE";
            case SmpsCoordFlags.NOTE_FILL -> String.format("FIL %02X", params[0]);
            case SmpsCoordFlags.KEY_DISP -> "TRN " + formatSigned(params[0]);
            case SmpsCoordFlags.SET_TEMPO -> String.format("TMP %02X", params[0]);
            case SmpsCoordFlags.SET_DIV_TIMING -> String.format("DIV %02X", params[0]);
            case SmpsCoordFlags.PSG_VOLUME -> "PVL " + formatSigned(params[0]);
            case SmpsCoordFlags.MODULATION -> String.format("MOD %02X%02X%02X%02X",
                params[0], params[1], params[2], params[3]);
            case SmpsCoordFlags.MOD_ON -> "MON";
            case SmpsCoordFlags.STOP -> "STP";
            case SmpsCoordFlags.PSG_NOISE -> String.format("NOI %02X", params[0]);
            case SmpsCoordFlags.MOD_OFF -> "MOFF";
            case SmpsCoordFlags.SND_OFF -> "SOF";
            case SmpsCoordFlags.SET_VOICE -> String.format("VOI %02X", params[0]);
            case SmpsCoordFlags.PSG_INSTRUMENT -> String.format("PSI %02X", params[0]);
            case SmpsCoordFlags.FADE_IN -> "FIN";
            case SmpsCoordFlags.FADE_OUT -> "FOT";
            case SmpsCoordFlags.JUMP -> "JMP";
            case SmpsCoordFlags.LOOP -> "LOP";
            case SmpsCoordFlags.CALL -> "CAL";
            case SmpsCoordFlags.RETURN -> "RET";
            default -> String.format("%02X", flag);
        };
    }

    public static SmpsEncoder.EffectCommand parse(String mnemonic) {
        if (mnemonic == null || mnemonic.isEmpty()) return null;
        String[] parts = mnemonic.split(" ", 2);
        String cmd = parts[0].toUpperCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        return switch (cmd) {
            case "PAN" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.PAN, new int[]{parsePan(arg)});
            case "DET" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.DETUNE, new int[]{parseSigned(arg)});
            case "COM" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SET_COMM, new int[]{parseHex(arg)});
            case "TIK" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.TICK_MULT, new int[]{parseHex(arg)});
            case "VOL" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.VOLUME, new int[]{parseSigned(arg)});
            case "TIE" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.TIE, new int[0]);
            case "FIL" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.NOTE_FILL, new int[]{parseHex(arg)});
            case "TRN" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.KEY_DISP, new int[]{parseSigned(arg)});
            case "TMP" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SET_TEMPO, new int[]{parseHex(arg)});
            case "DIV" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SET_DIV_TIMING, new int[]{parseHex(arg)});
            case "PVL" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.PSG_VOLUME, new int[]{parseSigned(arg)});
            case "MOD" -> parseModulation(arg);
            case "MON" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.MOD_ON, new int[0]);
            case "STP" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.STOP, new int[0]);
            case "NOI" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.PSG_NOISE, new int[]{parseHex(arg)});
            case "MOFF" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.MOD_OFF, new int[0]);
            case "SOF" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SND_OFF, new int[0]);
            case "VOI" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.SET_VOICE, new int[]{parseHex(arg)});
            case "PSI" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.PSG_INSTRUMENT, new int[]{parseHex(arg)});
            case "FIN" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.FADE_IN, new int[0]);
            case "FOT" -> new SmpsEncoder.EffectCommand(SmpsCoordFlags.FADE_OUT, new int[0]);
            default -> null;
        };
    }

    private static String formatPan(int[] params) {
        int raw = params[0] & 0xFF;
        int pan = raw & 0xC0;
        int amsFms = raw & 0x3F;
        String panStr = switch (pan) {
            case 0xC0 -> "LR";
            case 0x80 -> "L";
            case 0x40 -> "R";
            default -> "--";
        };
        // Preserve AMS/FMS bits if non-zero
        if (amsFms != 0) {
            return String.format("PAN %s %02X", panStr, amsFms);
        }
        return "PAN " + panStr;
    }

    private static String formatSigned(int value) {
        int signed = (byte) value;
        return signed >= 0 ? String.format("+%02X", signed) : String.format("-%02X", -signed);
    }

    private static int parsePan(String arg) {
        String[] panParts = arg.split("\\s+", 2);
        int panBits = switch (panParts[0].toUpperCase()) {
            case "LR", "L+R" -> 0xC0;
            case "L" -> 0x80;
            case "R" -> 0x40;
            default -> 0x00;
        };
        // Parse optional AMS/FMS suffix
        if (panParts.length > 1 && !panParts[1].isEmpty()) {
            try {
                panBits |= Integer.parseInt(panParts[1], 16) & 0x3F;
            } catch (NumberFormatException ignored) {}
        }
        return panBits;
    }

    private static int parseSigned(String arg) {
        if (arg.startsWith("+")) return Integer.parseInt(arg.substring(1), 16) & 0xFF;
        if (arg.startsWith("-")) return (-Integer.parseInt(arg.substring(1), 16)) & 0xFF;
        return Integer.parseInt(arg, 16) & 0xFF;
    }

    private static int parseHex(String arg) {
        return Integer.parseInt(arg, 16) & 0xFF;
    }

    private static SmpsEncoder.EffectCommand parseModulation(String arg) {
        if (arg.length() != 8) return null;
        int[] params = new int[4];
        for (int i = 0; i < 4; i++) {
            params[i] = Integer.parseInt(arg.substring(i * 2, i * 2 + 2), 16);
        }
        return new SmpsEncoder.EffectCommand(SmpsCoordFlags.MODULATION, params);
    }
}

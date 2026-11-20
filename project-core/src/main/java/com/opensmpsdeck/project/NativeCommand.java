package com.opensmpsdeck.project;

import java.util.List;

/** Bounded native S3K commands; arguments are exact unsigned byte values. */
public enum NativeCommand {
    PAN(0xE0, "FM", "Pan / AMS / FMS", "Raw pan/AMS/FMS byte: 80 left, 40 right, C0 stereo (hex).", List.of(0xC0)),
    DETUNE(SmpsCoordFlags.DETUNE, "", "Detune", "Signed frequency offset: 00..7F positive, 80..FF negative (raw hex bytes).", List.of(0)),
    VOLUME(SmpsCoordFlags.ABSOLUTE_VOLUME, "", "Absolute volume", "Native absolute volume: 00 silent, 7F loudest; bit 7 ignored. PSG quantizes to 16 levels.", List.of(100)),
    FM_VOLUME(0xE6, "FM", "FM volume change", "Signed attenuation change, clamped to 00..7F: positive is quieter; FF means -1.", List.of(1)),
    PSG_VOLUME(0xEC, "PSG", "PSG volume change", "Signed attenuation change with native byte wrap then unsigned clamp to 0F; positive is quieter.", List.of(1)),
    TRANSPOSE(0xED, "", "Set transpose", "Biased native transpose: 40 hex is neutral, 4C is +12; subtraction wraps as a signed byte. Applies to the next note attack.", List.of(0x40)),
    TRANSPOSE_ADD(0xFB, "", "Add transpose", "Signed semitone addition with byte wrapping; FF means -1. Applies to the next note attack.", List.of(0)),
    MODULATION(SmpsCoordFlags.MODULATION, "", "Modulation setup", "Four raw bytes: delay, rate, signed delta, steps. Native pending parameters are prepared on an attack. Tied setup retains the active counters. Zero values retain native counter wrap.", List.of(1,1,1,4)),
    MODULATION_OFF(SmpsCoordFlags.MODULATION_OFF, "", "Modulation off", "Disables custom modulation. The next attacked setup prepares fresh counters. A source-level envelope assignment also clears.", List.of()),
    HOLD(SmpsCoordFlags.HOLD, "", "Hold", "Suppresses the next note attack while allowing pitch changes.", List.of()),
    PSG_NOISE(0xF3, "PSG", "PSG noise", "Native PSG noise byte. 00 restores tone mode. Low nonzero bytes set the noise mode from their low nibble.", List.of(0xE7)),
    PSG_ENVELOPE(SmpsCoordFlags.PSG_INSTRUMENT, -1, "PSG", "Set PSG envelope", "Selects a PSG volume envelope; no asset disables it.", Asset.PSG_VOLUME, List.of()),
    MODULATION_ENVELOPE(SmpsCoordFlags.MODULATION_ENVELOPE, -1, "", "Set modulation envelope", "Selects a modulation envelope; no asset disables it.", Asset.MODULATION, List.of()),
    FM_VOICE(SmpsCoordFlags.SET_VOICE, -1, "FM", "Set FM voice", "Loads a local FM voice.", Asset.VOICE, List.of()),
    FM_VOLUME_ENVELOPE(0xFF, 0x06, "FM", "FM volume envelope", "Selects a volume envelope and a raw operator mask; no asset disables it.", Asset.PSG_VOLUME, List.of(0x0F)),
    SSG_EG(0xFF, 0x05, "FM", "SSG-EG", "Four raw SSG-EG register values in native operator order.", Asset.NONE, List.of(0, 0, 0, 0));

    public enum Asset { NONE, VOICE, PSG_VOLUME, MODULATION }

    private final int opcode, subOpcode;
    private final Asset asset;
    private final String type, label, help;
    private final List<Integer> defaults;

    NativeCommand(int opcode, String type, String label, String help, List<Integer> defaults) {
        this(opcode, -1, type, label, help, Asset.NONE, defaults);
    }

    NativeCommand(int opcode, int subOpcode, String type, String label, String help, Asset asset, List<Integer> defaults) {
        this.subOpcode=subOpcode; this.asset=asset;
        this.opcode=opcode; this.type=type; this.label=label; this.help=help; this.defaults=defaults;
    }
    public int opcode() { return opcode; }
    public int subOpcode() { return subOpcode; }
    public Asset asset() { return asset; }
    int minimumVersion() { return ordinal() >= HOLD.ordinal() ? 4 : 2; }
    public int argumentCount() { return defaults.size(); }
    public boolean supports(String trackType) { return ("FM".equals(trackType) || "PSG".equals(trackType)) && (type.isEmpty() || type.equals(trackType)); }
    public String label() { return label; }
    public String help() { return help; }
    public List<Integer> defaultArguments() { return defaults; }
}

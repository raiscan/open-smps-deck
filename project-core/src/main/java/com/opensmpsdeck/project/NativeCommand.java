package com.opensmpsdeck.project;

import java.util.List;

/** Bounded native S3K commands; arguments are exact unsigned byte values. */
public enum NativeCommand {
    PAN(0xE0, "FM", "Pan / AMS / FMS", "Raw pan/AMS/FMS byte: 80 left, 40 right, C0 stereo (hex).", List.of(0xC0)),
    DETUNE(0xE1, "", "Detune", "Signed frequency offset: 00..7F positive, 80..FF negative (raw hex bytes).", List.of(0)),
    VOLUME(0xE4, "", "Absolute volume", "Native absolute volume: 00 silent, 7F loudest; bit 7 ignored. PSG quantizes to 16 levels.", List.of(100)),
    FM_VOLUME(0xE6, "FM", "FM volume change", "Signed attenuation change, clamped to 00..7F: positive is quieter; FF means -1.", List.of(1)),
    PSG_VOLUME(0xEC, "PSG", "PSG volume change", "Signed attenuation change with native byte wrap then unsigned clamp to 0F; positive is quieter.", List.of(1)),
    TRANSPOSE(0xED, "", "Set transpose", "Biased native transpose: 40 hex is neutral, 4C is +12; subtraction wraps as a signed byte. Applies to the next note attack.", List.of(0x40)),
    TRANSPOSE_ADD(0xFB, "", "Add transpose", "Signed semitone addition with byte wrapping; FF means -1. Applies to the next note attack.", List.of(0)),
    MODULATION(0xF0, "", "Modulation setup", "Four raw bytes: delay, rate, signed delta, steps. Native pending parameters are prepared on an attack; setup is currently restricted to explicit note attack ticks. Zero values retain native counter wrap.", List.of(1,1,1,4)),
    MODULATION_OFF(0xFA, "", "Modulation off", "Disables modulation and clears its accumulator. Latent counters are prepared by the next attacked modulation setup.", List.of());

    private final int opcode;
    private final String type, label, help;
    private final List<Integer> defaults;

    NativeCommand(int opcode, String type, String label, String help, List<Integer> defaults) {
        this.opcode=opcode; this.type=type; this.label=label; this.help=help; this.defaults=defaults;
    }
    public int opcode() { return opcode; }
    public int argumentCount() { return defaults.size(); }
    public boolean supports(String trackType) { return ("FM".equals(trackType) || "PSG".equals(trackType)) && (type.isEmpty() || type.equals(trackType)); }
    public String label() { return label; }
    public String help() { return help; }
    public List<Integer> defaultArguments() { return defaults; }
}

package com.opensmps.smps;

/**
 * Authoritative SMPS coordination flag definitions for the Z80 sound driver.
 *
 * <p>All flag byte values, parameter counts, and human-readable labels are
 * defined here. Every component that reads or writes SMPS bytecode
 * (sequencer, decoder, encoder, importer, compiler) must use this class
 * to ensure consistent interpretation.
 *
 * <p>The flag assignments match the S2 Z80 driver convention used by
 * SMPSPlay and the SmpsSequencer. Game-specific overrides (e.g., S1's
 * different flag mappings) are handled via {@link SmpsSequencerConfig}.
 */
public final class SmpsCoordFlags {

    private SmpsCoordFlags() {}

    // --- Flag byte constants ---

    /** Pan/AMS/FMS (1 param: pan bits). */
    public static final int PAN = 0xE0;

    /** Detune (1 param: signed offset). */
    public static final int DETUNE = 0xE1;

    /** Set communication byte (1 param: value). */
    public static final int SET_COMM = 0xE2;

    /** Return from subroutine (0 params). */
    public static final int RETURN = 0xE3;

    /** Fade in / stop track (0 params). */
    public static final int FADE_IN = 0xE4;

    /** Tick multiplier / dividing timing per-track (1 param). */
    public static final int TICK_MULT = 0xE5;

    /** Volume offset (1 param: signed offset). */
    public static final int VOLUME = 0xE6;

    /** Tie next note (0 params). */
    public static final int TIE = 0xE7;

    /** Note fill / staccato (1 param: fill value). */
    public static final int NOTE_FILL = 0xE8;

    /** Key displacement / transpose (1 param: signed offset). */
    public static final int KEY_DISP = 0xE9;

    /** Set main tempo (1 param: tempo value). */
    public static final int SET_TEMPO = 0xEA;

    /** Set dividing timing (1 param). */
    public static final int SET_DIV_TIMING = 0xEB;

    /** PSG volume (1 param). */
    public static final int PSG_VOLUME = 0xEC;

    /** Unused/reserved (1 param, kept for safety). */
    public static final int UNUSED_ED = 0xED;

    /** No-op / game-specific (0 params by default; S1 uses as track end). */
    public static final int NOOP_EE = 0xEE;

    /** Set FM voice / instrument (1 param: voice ID). */
    public static final int SET_VOICE = 0xEF;

    /** Enable modulation (4 params: wait, speed, delta, steps). */
    public static final int MODULATION = 0xF0;

    /** Modulation on / re-enable (0 params). */
    public static final int MOD_ON = 0xF1;

    /** Stop / track end (0 params). */
    public static final int STOP = 0xF2;

    /** PSG noise mode (1 param). */
    public static final int PSG_NOISE = 0xF3;

    /** Modulation off / clear (0 params). */
    public static final int MOD_OFF = 0xF4;

    /** PSG instrument / envelope (1 param: envelope ID). */
    public static final int PSG_INSTRUMENT = 0xF5;

    /** Jump / goto (2 params: 16-bit LE pointer). */
    public static final int JUMP = 0xF6;

    /** Loop (4 params: counter, padding, 16-bit LE pointer). */
    public static final int LOOP = 0xF7;

    /** Call subroutine (2 params: 16-bit LE pointer). */
    public static final int CALL = 0xF8;

    /** Sound off / silence all (0 params). */
    public static final int SND_OFF = 0xF9;

    /** Custom fade out (2 params: steps, delay). Internal/testing. */
    public static final int FADE_OUT = 0xFD;

    // --- Parameter count table ---

    /**
     * Number of parameter bytes following each coordination flag.
     * Index = flag byte - 0xE0 (range 0-31).
     *
     * <p>This is the authoritative source of truth. All SMPS bytecode
     * readers/writers must use {@link #getParamCount(int)} rather than
     * maintaining their own tables.
     */
    private static final int[] PARAM_COUNTS = new int[32];
    static {
        // 1-parameter commands
        PARAM_COUNTS[PAN - 0xE0] = 1;
        PARAM_COUNTS[DETUNE - 0xE0] = 1;
        PARAM_COUNTS[SET_COMM - 0xE0] = 1;
        PARAM_COUNTS[TICK_MULT - 0xE0] = 1;
        PARAM_COUNTS[VOLUME - 0xE0] = 1;
        PARAM_COUNTS[NOTE_FILL - 0xE0] = 1;
        PARAM_COUNTS[KEY_DISP - 0xE0] = 1;
        PARAM_COUNTS[SET_TEMPO - 0xE0] = 1;
        PARAM_COUNTS[SET_DIV_TIMING - 0xE0] = 1;
        PARAM_COUNTS[PSG_VOLUME - 0xE0] = 1;
        PARAM_COUNTS[UNUSED_ED - 0xE0] = 1;
        PARAM_COUNTS[SET_VOICE - 0xE0] = 1;
        PARAM_COUNTS[PSG_NOISE - 0xE0] = 1;
        PARAM_COUNTS[PSG_INSTRUMENT - 0xE0] = 1;

        // 2-parameter commands
        PARAM_COUNTS[JUMP - 0xE0] = 2;
        PARAM_COUNTS[CALL - 0xE0] = 2;
        PARAM_COUNTS[FADE_OUT - 0xE0] = 2;

        // 4-parameter commands
        PARAM_COUNTS[MODULATION - 0xE0] = 4;
        PARAM_COUNTS[LOOP - 0xE0] = 4;

        // 0-parameter commands (RETURN, FADE_IN, TIE, NOOP_EE, MOD_ON, STOP,
        // MOD_OFF, SND_OFF) are already 0 from array initialization
    }

    /**
     * Get the number of parameter bytes for a coordination flag.
     *
     * @param flagByte the flag byte (0xE0-0xFF)
     * @return the number of parameter bytes, or 0 if unknown
     */
    public static int getParamCount(int flagByte) {
        int index = (flagByte & 0xFF) - 0xE0;
        if (index < 0 || index >= PARAM_COUNTS.length) return 0;
        return PARAM_COUNTS[index];
    }

    // --- Human-readable labels ---

    /** Labels for display in tracker effect columns. */
    private static final String[] FLAG_LABELS = new String[32];
    static {
        FLAG_LABELS[PAN - 0xE0] = "Pan";
        FLAG_LABELS[DETUNE - 0xE0] = "Detune";
        FLAG_LABELS[SET_COMM - 0xE0] = "Comm";
        FLAG_LABELS[RETURN - 0xE0] = "Return";
        FLAG_LABELS[FADE_IN - 0xE0] = "FadeIn";
        FLAG_LABELS[TICK_MULT - 0xE0] = "TickMul";
        FLAG_LABELS[VOLUME - 0xE0] = "Volume";
        FLAG_LABELS[TIE - 0xE0] = "Tie";
        FLAG_LABELS[NOTE_FILL - 0xE0] = "Fill";
        FLAG_LABELS[KEY_DISP - 0xE0] = "KeyDisp";
        FLAG_LABELS[SET_TEMPO - 0xE0] = "Tempo";
        FLAG_LABELS[SET_DIV_TIMING - 0xE0] = "DivTim";
        FLAG_LABELS[PSG_VOLUME - 0xE0] = "PSGVol";
        FLAG_LABELS[UNUSED_ED - 0xE0] = "???";
        FLAG_LABELS[NOOP_EE - 0xE0] = "NoOp";
        FLAG_LABELS[SET_VOICE - 0xE0] = "Voice";
        FLAG_LABELS[MODULATION - 0xE0] = "Mod";
        FLAG_LABELS[MOD_ON - 0xE0] = "ModOn";
        FLAG_LABELS[STOP - 0xE0] = "Stop";
        FLAG_LABELS[PSG_NOISE - 0xE0] = "Noise";
        FLAG_LABELS[MOD_OFF - 0xE0] = "ModOff";
        FLAG_LABELS[PSG_INSTRUMENT - 0xE0] = "PSGIns";
        FLAG_LABELS[JUMP - 0xE0] = "Jump";
        FLAG_LABELS[LOOP - 0xE0] = "Loop";
        FLAG_LABELS[CALL - 0xE0] = "Call";
        FLAG_LABELS[SND_OFF - 0xE0] = "SndOff";
        FLAG_LABELS[FADE_OUT - 0xE0] = "FadeOut";
    }

    /**
     * Get a human-readable label for a coordination flag.
     *
     * @param flagByte the flag byte (0xE0-0xFF)
     * @return human-readable label, or hex string if unknown
     */
    public static String getLabel(int flagByte) {
        int index = (flagByte & 0xFF) - 0xE0;
        if (index < 0 || index >= FLAG_LABELS.length) return String.format("%02X", flagByte & 0xFF);
        String label = FLAG_LABELS[index];
        return label != null ? label : String.format("%02X", flagByte & 0xFF);
    }

    /**
     * Check whether a flag byte sets the FM voice (instrument).
     *
     * @param flagByte the flag byte
     * @return true if this is a Set Voice command
     */
    public static boolean isSetVoice(int flagByte) {
        return (flagByte & 0xFF) == SET_VOICE;
    }

    /**
     * Check whether a flag byte sets the PSG instrument/envelope.
     *
     * @param flagByte the flag byte
     * @return true if this is a PSG Instrument command
     */
    public static boolean isPsgInstrument(int flagByte) {
        return (flagByte & 0xFF) == PSG_INSTRUMENT;
    }

    /**
     * Check whether a flag byte is a track terminator (Stop or Jump).
     *
     * @param flagByte the flag byte
     * @return true if this terminates the track
     */
    public static boolean isTrackTerminator(int flagByte) {
        int b = flagByte & 0xFF;
        return b == STOP || b == JUMP;
    }
}

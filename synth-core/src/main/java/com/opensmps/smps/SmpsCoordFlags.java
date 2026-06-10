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

    // --- Per-driver dialect tables ---

    /**
     * SMPS driver dialect. The default tables above describe S2; S1 and S3K
     * assign different sizes and meanings to several flags (per the SMPSPlay
     * DefCFlag definitions for each game).
     */
    public enum Dialect { S1, S2, S3K }

    /** S1 deviations from S2: ED is ClearPush with no parameter. */
    private static final int[] S1_PARAM_COUNTS = PARAM_COUNTS.clone();
    static {
        S1_PARAM_COUNTS[UNUSED_ED - 0xE0] = 0;
    }

    /**
     * S3K flag sizes (S3K DefCFlag.txt). Notable size changes vs S2:
     * E4 VOL_ABS(1), E5 VOL_CC_FMP2(2), E9 SPINDASH_REV(0), EB LOOP_EXIT(3),
     * EE FM_COMMAND(2), F1 MOD_ENV(2), F4 MOD_ENV_GEN(1), FB TRANSPOSE_ADD(1),
     * FC CONT_SFX(2), FD RAW_FREQ(1), FE SPC_FM3(4), FF META prefix(1 + meta).
     */
    private static final int[] S3K_PARAM_COUNTS = new int[32];
    static {
        S3K_PARAM_COUNTS[0xE0 - 0xE0] = 1; // PANAFMS
        S3K_PARAM_COUNTS[0xE1 - 0xE0] = 1; // DETUNE
        S3K_PARAM_COUNTS[0xE2 - 0xE0] = 1; // FADE_IN_SONG
        S3K_PARAM_COUNTS[0xE3 - 0xE0] = 0; // TRK_END (mute)
        S3K_PARAM_COUNTS[0xE4 - 0xE0] = 1; // VOL_ABS
        S3K_PARAM_COUNTS[0xE5 - 0xE0] = 2; // VOL_CC_FMP2
        S3K_PARAM_COUNTS[0xE6 - 0xE0] = 1; // VOL_CC_FM
        S3K_PARAM_COUNTS[0xE7 - 0xE0] = 0; // HOLD
        S3K_PARAM_COUNTS[0xE8 - 0xE0] = 1; // NOTE_STOP
        S3K_PARAM_COUNTS[0xE9 - 0xE0] = 0; // SPINDASH_REV
        S3K_PARAM_COUNTS[0xEA - 0xE0] = 1; // PLAY_DAC
        S3K_PARAM_COUNTS[0xEB - 0xE0] = 3; // LOOP_EXIT
        S3K_PARAM_COUNTS[0xEC - 0xE0] = 1; // PSG_VOL
        S3K_PARAM_COUNTS[0xED - 0xE0] = 1; // TRANSPOSE_SET
        S3K_PARAM_COUNTS[0xEE - 0xE0] = 2; // FM_COMMAND
        S3K_PARAM_COUNTS[0xEF - 0xE0] = 1; // INSTRUMENT
        S3K_PARAM_COUNTS[0xF0 - 0xE0] = 4; // MOD_SETUP
        S3K_PARAM_COUNTS[0xF1 - 0xE0] = 2; // MOD_ENV (FMP)
        S3K_PARAM_COUNTS[0xF2 - 0xE0] = 0; // TRK_END
        S3K_PARAM_COUNTS[0xF3 - 0xE0] = 1; // PSG_NOISE
        S3K_PARAM_COUNTS[0xF4 - 0xE0] = 1; // MOD_ENV (generic)
        S3K_PARAM_COUNTS[0xF5 - 0xE0] = 1; // PSG_INSTRUMENT
        S3K_PARAM_COUNTS[0xF6 - 0xE0] = 2; // GOTO
        S3K_PARAM_COUNTS[0xF7 - 0xE0] = 4; // LOOP
        S3K_PARAM_COUNTS[0xF8 - 0xE0] = 2; // GOSUB
        S3K_PARAM_COUNTS[0xF9 - 0xE0] = 0; // RETURN
        S3K_PARAM_COUNTS[0xFA - 0xE0] = 0; // MODS_OFF
        S3K_PARAM_COUNTS[0xFB - 0xE0] = 1; // TRANSPOSE_ADD
        S3K_PARAM_COUNTS[0xFC - 0xE0] = 2; // CONT_SFX
        S3K_PARAM_COUNTS[0xFD - 0xE0] = 1; // RAW_FREQ
        S3K_PARAM_COUNTS[0xFE - 0xE0] = 4; // SPC_FM3
        S3K_PARAM_COUNTS[0xFF - 0xE0] = 1; // META prefix (sub-command byte; see getMetaParamCount)
    }

    /** Parameter bytes following each S3K FF meta sub-command (after the sub byte). */
    private static final int[] S3K_META_PARAM_COUNTS = {
        1, // 00 TEMPO_SET
        1, // 01 SND_CMD
        1, // 02 MUS_PAUSE
        3, // 03 COPY_MEM
        1, // 04 TICK_MULT
        4, // 05 SSG_EG
        2, // 06 FM_VOLENV
        0, // 07 SPINDASH_REV_RESET
    };

    /** S3K meta-command prefix (FF). */
    public static final int S3K_META = 0xFF;

    /** S3K return-from-subroutine flag (F9). */
    public static final int S3K_RETURN = 0xF9;

    /** S3K transpose-add flag (FB), the equivalent of S2's E9 KEY_DISP. */
    public static final int S3K_TRANSPOSE_ADD = 0xFB;

    /**
     * Get the number of parameter bytes for a flag in the given dialect.
     * For the S3K FF meta prefix this returns 1 (the sub-command byte);
     * callers must add {@link #getMetaParamCount(int)} for the sub-command.
     */
    public static int getParamCount(int flagByte, Dialect dialect) {
        int index = (flagByte & 0xFF) - 0xE0;
        if (index < 0 || index >= PARAM_COUNTS.length) return 0;
        return switch (dialect) {
            case S1 -> S1_PARAM_COUNTS[index];
            case S2 -> PARAM_COUNTS[index];
            case S3K -> S3K_PARAM_COUNTS[index];
        };
    }

    /**
     * Parameter bytes following an S3K FF meta sub-command byte.
     *
     * @param subCommand the byte after FF
     * @return parameter byte count (0 for unknown sub-commands)
     */
    public static int getMetaParamCount(int subCommand) {
        int sub = subCommand & 0xFF;
        return (sub < S3K_META_PARAM_COUNTS.length) ? S3K_META_PARAM_COUNTS[sub] : 0;
    }

    /** Whether the flag returns from an F8 subroutine call in the dialect. */
    public static boolean isReturn(int flagByte, Dialect dialect) {
        int b = flagByte & 0xFF;
        return dialect == Dialect.S3K ? b == S3K_RETURN : b == RETURN;
    }

    /**
     * Whether the flag ends the track in the dialect
     * (F2 everywhere; S1 also EE; S3K also E3 = TEND_MUTE).
     */
    public static boolean isTrackEnd(int flagByte, Dialect dialect) {
        int b = flagByte & 0xFF;
        if (b == STOP) return true;
        return switch (dialect) {
            case S1 -> b == NOOP_EE;
            case S2 -> false;
            case S3K -> b == RETURN; // 0xE3 = TRK_END (mute) in S3K
        };
    }

    /** The additive transpose flag for the dialect (E9 in S1/S2, FB in S3K). */
    public static int transposeAddFlag(Dialect dialect) {
        return dialect == Dialect.S3K ? S3K_TRANSPOSE_ADD : KEY_DISP;
    }

    /** The return-from-subroutine flag for the dialect (E3 in S1/S2, F9 in S3K). */
    public static int returnFlag(Dialect dialect) {
        return dialect == Dialect.S3K ? S3K_RETURN : RETURN;
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

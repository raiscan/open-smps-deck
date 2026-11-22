package com.opensmpsdeck.project;

/** S3K coordination bytes shared by the finite arrangement compiler and its command model. */
public final class SmpsCoordFlags {
    public static final int DETUNE = 0xE1;
    public static final int ABSOLUTE_VOLUME = 0xE4;
    public static final int HOLD = 0xE7;
    public static final int SET_VOICE = 0xEF;
    public static final int MODULATION = 0xF0;
    public static final int STOP = 0xF2;
    public static final int MODULATION_ENVELOPE = 0xF4;
    public static final int PSG_INSTRUMENT = 0xF5;
    public static final int LOOP = 0xF7;
    public static final int CALL = 0xF8;
    public static final int RETURN = 0xF9;
    public static final int MODULATION_OFF = 0xFA;

    private SmpsCoordFlags() {}
}

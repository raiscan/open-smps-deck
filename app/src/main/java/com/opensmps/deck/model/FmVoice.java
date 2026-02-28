package com.opensmps.deck.model;

/**
 * Named wrapper for 25-byte SMPS FM voice data.
 *
 * Layout: byte[0] = algorithm (bits 0-2) | feedback (bits 3-5),
 * then 4 operators x 5 bytes each (DT_MUL, TL, RS_AR, AM_D1R, D2R, D1L_RR),
 * then 4 bytes TL overrides (S3K).
 */
public class FmVoice {

    public static final int VOICE_SIZE = 25;
    public static final int OPERATOR_COUNT = 4;
    public static final int PARAMS_PER_OPERATOR = 5;

    private String name;
    private final byte[] data;

    public FmVoice(String name, byte[] data) {
        if (data == null || data.length != VOICE_SIZE) {
            throw new IllegalArgumentException("FM voice data must be exactly " + VOICE_SIZE + " bytes");
        }
        this.name = name;
        this.data = data.clone();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns a defensive copy of the voice data.
     */
    public byte[] getData() {
        return data.clone();
    }

    /**
     * Returns the internal data array directly (for performance-sensitive code).
     */
    public byte[] getRawData() {
        return data;
    }

    /**
     * Returns the algorithm value (bits 0-2 of byte[0]).
     */
    public int getAlgorithm() {
        return data[0] & 0x07;
    }

    /**
     * Returns the feedback value (bits 3-5 of byte[0]).
     */
    public int getFeedback() {
        return (data[0] >> 3) & 0x07;
    }

    /**
     * Sets the algorithm value (bits 0-2 of byte[0]).
     */
    public void setAlgorithm(int algo) {
        data[0] = (byte) ((data[0] & 0xF8) | (algo & 0x07));
    }

    /**
     * Sets the feedback value (bits 3-5 of byte[0]).
     */
    public void setFeedback(int fb) {
        data[0] = (byte) ((data[0] & 0xC7) | ((fb & 0x07) << 3));
    }

    /**
     * Reads an operator parameter.
     *
     * @param opIndex    operator index (0-3)
     * @param paramOffset parameter offset within the 5-byte operator block (0-4)
     * @return unsigned parameter value
     */
    public int getOpParam(int opIndex, int paramOffset) {
        validateOpParam(opIndex, paramOffset);
        return data[1 + opIndex * PARAMS_PER_OPERATOR + paramOffset] & 0xFF;
    }

    /**
     * Writes an operator parameter.
     *
     * @param opIndex    operator index (0-3)
     * @param paramOffset parameter offset within the 5-byte operator block (0-4)
     * @param value      parameter value (0-255)
     */
    public void setOpParam(int opIndex, int paramOffset, int value) {
        validateOpParam(opIndex, paramOffset);
        data[1 + opIndex * PARAMS_PER_OPERATOR + paramOffset] = (byte) (value & 0xFF);
    }

    private void validateOpParam(int opIndex, int paramOffset) {
        if (opIndex < 0 || opIndex >= OPERATOR_COUNT) {
            throw new IndexOutOfBoundsException("opIndex must be 0-3, got " + opIndex);
        }
        if (paramOffset < 0 || paramOffset >= PARAMS_PER_OPERATOR) {
            throw new IndexOutOfBoundsException("paramOffset must be 0-4, got " + paramOffset);
        }
    }
}

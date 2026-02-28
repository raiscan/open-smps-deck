package com.opensmps.deck.model;

/**
 * Named wrapper for 25-byte SMPS FM voice data.
 *
 * Layout: byte[0] = algorithm (bits 0-2) | feedback (bits 3-5),
 * then 4 operators x 6 bytes each (DT_MUL, TL, RS_AR, AM_D1R, D2R, D1L_RR).
 */
public class FmVoice {

    public static final int VOICE_SIZE = 25;
    public static final int OPERATOR_COUNT = 4;
    public static final int PARAMS_PER_OPERATOR = 6;

    private static final int[] DISPLAY_TO_SMPS = {0, 2, 1, 3};

    // Carrier table: [algorithm][smpsOpIndex] = true if carrier
    // SMPS op indices: 0=Op1, 1=Op3, 2=Op2, 3=Op4
    private static final boolean[][] CARRIER_TABLE = {
        {false, false, false, true},   // Algo 0: Op4
        {false, false, false, true},   // Algo 1: Op4
        {false, false, false, true},   // Algo 2: Op4
        {false, false, false, true},   // Algo 3: Op4
        {false, false, true,  true},   // Algo 4: Op2(idx2), Op4(idx3)
        {false, true,  true,  true},   // Algo 5: Op2(idx2), Op3(idx1), Op4(idx3)
        {false, true,  true,  true},   // Algo 6: Op2(idx2), Op3(idx1), Op4(idx3)
        {true,  true,  true,  true},   // Algo 7: all
    };

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
     * Get the internal data array WITHOUT defensive copy.
     * WARNING: Do not retain or mutate the returned reference.
     * Use only for performance-sensitive read-only streaming.
     */
    public byte[] getDataUnsafe() {
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
     * @param paramOffset parameter offset within the 6-byte operator block (0-5)
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
     * @param paramOffset parameter offset within the 6-byte operator block (0-5)
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
            throw new IndexOutOfBoundsException("paramOffset must be 0-5, got " + paramOffset);
        }
    }

    // --- Bit-field accessors ---

    public int getMul(int op) { return getOpParam(op, 0) & 0x0F; }
    public void setMul(int op, int v) { setOpParam(op, 0, (getOpParam(op, 0) & 0x70) | (v & 0x0F)); }

    public int getDt(int op) { return (getOpParam(op, 0) >> 4) & 0x07; }
    public void setDt(int op, int v) { setOpParam(op, 0, (getOpParam(op, 0) & 0x0F) | ((v & 0x07) << 4)); }

    public int getTl(int op) { return getOpParam(op, 1) & 0x7F; }
    public void setTl(int op, int v) { setOpParam(op, 1, v & 0x7F); }

    public int getRs(int op) { return (getOpParam(op, 2) >> 6) & 0x03; }
    public void setRs(int op, int v) { setOpParam(op, 2, (getOpParam(op, 2) & 0x1F) | ((v & 0x03) << 6)); }

    public int getAr(int op) { return getOpParam(op, 2) & 0x1F; }
    public void setAr(int op, int v) { setOpParam(op, 2, (getOpParam(op, 2) & 0xC0) | (v & 0x1F)); }

    public boolean getAm(int op) { return (getOpParam(op, 3) & 0x80) != 0; }
    public void setAm(int op, boolean v) { setOpParam(op, 3, v ? (getOpParam(op, 3) | 0x80) : (getOpParam(op, 3) & 0x7F)); }

    public int getD1r(int op) { return getOpParam(op, 3) & 0x1F; }
    public void setD1r(int op, int v) { setOpParam(op, 3, (getOpParam(op, 3) & 0x80) | (v & 0x1F)); }

    public int getD2r(int op) { return getOpParam(op, 4) & 0x1F; }
    public void setD2r(int op, int v) { setOpParam(op, 4, v & 0x1F); }

    public int getD1l(int op) { return (getOpParam(op, 5) >> 4) & 0x0F; }
    public void setD1l(int op, int v) { setOpParam(op, 5, (getOpParam(op, 5) & 0x0F) | ((v & 0x0F) << 4)); }

    public int getRr(int op) { return getOpParam(op, 5) & 0x0F; }
    public void setRr(int op, int v) { setOpParam(op, 5, (getOpParam(op, 5) & 0xF0) | (v & 0x0F)); }

    /**
     * Returns true if the given operator (by SMPS index) is a carrier for the current algorithm.
     */
    public boolean isCarrier(int smpsOpIndex) { return CARRIER_TABLE[getAlgorithm()][smpsOpIndex]; }

    /**
     * Converts a display-order operator index (1,2,3,4) to SMPS order (0=Op1, 2=Op2, 1=Op3, 3=Op4).
     */
    public static int displayToSmps(int displayIndex) { return DISPLAY_TO_SMPS[displayIndex]; }
}

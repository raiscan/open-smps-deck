package com.opensmps.deck.model;

/**
 * A DAC PCM sample for Mega Drive playback.
 *
 * <p>Stores raw unsigned 8-bit PCM audio data and a playback rate byte
 * (0-255) that controls the Z80 DAC driver's sample output speed.
 * Higher rate values produce slower playback due to longer {@code djnz}
 * delay loops in the driver.
 */
public class DacSample {

    private String name;
    private byte[] data;
    private int rate;

    /**
     * Creates a new DAC sample.
     *
     * @param name display name for the sample
     * @param data raw unsigned 8-bit PCM sample data (cloned on storage)
     * @param rate playback rate byte (masked to 0x00-0xFF)
     */
    public DacSample(String name, byte[] data, int rate) {
        this.name = name;
        this.data = data.clone();
        this.rate = rate & 0xFF;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns a defensive copy of the sample data.
     */
    public byte[] getData() {
        return data.clone();
    }

    /**
     * Returns the raw internal data array without copying.
     * Use for performance-critical paths where the caller will not modify the array.
     */
    public byte[] getDataDirect() {
        return data;
    }

    /**
     * Replaces the sample data (cloned on storage).
     */
    public void setData(byte[] data) {
        this.data = data.clone();
    }

    public int getRate() {
        return rate;
    }

    public void setRate(int rate) {
        this.rate = rate & 0xFF;
    }
}

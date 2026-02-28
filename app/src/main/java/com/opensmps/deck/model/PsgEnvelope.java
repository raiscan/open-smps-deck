package com.opensmps.deck.model;

/**
 * Named wrapper for a PSG volume envelope step array.
 *
 * Each step is a volume value (0-7). The array is terminated by a 0x80 byte.
 */
public class PsgEnvelope {

    private String name;
    private byte[] data;

    public PsgEnvelope(String name, byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("PSG envelope data must not be null");
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
     * Returns a defensive copy of the envelope data.
     */
    public byte[] getData() {
        return data.clone();
    }

    /**
     * Returns the number of volume steps before the 0x80 terminator.
     */
    public int getStepCount() {
        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 0xFF) == 0x80) {
                return i;
            }
        }
        return data.length;
    }

    /**
     * Returns the volume value at the given step index.
     *
     * @param index step index (0 to getStepCount()-1)
     * @return volume value (0-7)
     */
    public int getStep(int index) {
        if (index < 0 || index >= getStepCount()) {
            throw new IndexOutOfBoundsException("Step index " + index + " out of range [0, " + getStepCount() + ")");
        }
        return data[index] & 0xFF;
    }

    /**
     * Sets the volume value at the given step index.
     *
     * @param index step index (0 to getStepCount()-1)
     * @param value volume value (0-7)
     */
    public void setStep(int index, int value) {
        if (index < 0 || index >= getStepCount()) {
            throw new IndexOutOfBoundsException("Step index " + index + " out of range [0, " + getStepCount() + ")");
        }
        data[index] = (byte) (value & 0xFF);
    }

    /** Add a volume step at the end (before the 0x80 terminator). */
    public void addStep(int volume) {
        int count = getStepCount();
        byte[] newData = new byte[count + 2]; // steps + new step + terminator
        System.arraycopy(data, 0, newData, 0, count);
        newData[count] = (byte) (volume & 0xFF);
        newData[count + 1] = (byte) 0x80;
        this.data = newData;
    }

    /** Remove the step at the given index. */
    public void removeStep(int index) {
        int count = getStepCount();
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Step index " + index + " out of range [0, " + count + ")");
        }
        byte[] newData = new byte[count]; // (count - 1) steps + terminator
        System.arraycopy(data, 0, newData, 0, index);
        System.arraycopy(data, index + 1, newData, index, count - index - 1);
        newData[count - 1] = (byte) 0x80;
        this.data = newData;
    }

    /** Replace the envelope data entirely. */
    public void setData(byte[] newData) {
        if (newData == null) {
            throw new IllegalArgumentException("PSG envelope data must not be null");
        }
        this.data = newData.clone();
    }
}

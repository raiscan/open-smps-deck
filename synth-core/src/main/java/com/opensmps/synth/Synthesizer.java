package com.opensmps.synth;

import com.opensmps.smps.DacData;

/**
 * Interface for the Mega Drive sound hardware abstraction layer.
 *
 * <p>Provides register-level access to the YM2612 FM chip and SN76489 PSG,
 * plus higher-level operations for instrument loading, DAC playback,
 * per-channel muting, and global silence.
 */
public interface Synthesizer {
    void writeFm(Object source, int port, int reg, int val);
    void writePsg(Object source, int val);
    void setInstrument(Object source, int channelId, byte[] voice);
    void playDac(Object source, int note);
    void stopDac(Object source);
    void setDacData(DacData data);
    void setFmMute(int channel, boolean mute);
    void setPsgMute(int channel, boolean mute);
    void setDacInterpolate(boolean interpolate);
    void silenceAll();
}

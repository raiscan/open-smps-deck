package com.opensmpsdeck.audio;

import com.opensmpsdeck.model.FmVoice;
import com.opensmps.smps.AbstractSmpsData;

/**
 * Wraps a compiled SMPS binary blob for playback.
 * The baseNoteOffset is configurable per SMPS mode:
 * S1=0, S2=1, S3K=0.
 */
public class SimpleSmpsData extends AbstractSmpsData {

    private byte[][] psgEnvelopes;
    private final int baseNoteOffset;
    private final int psgBaseNoteOffset;

    public SimpleSmpsData(byte[] data) {
        this(data, 1, 0); // S2 default: FM offset=1, PSG offset=0
    }

    public SimpleSmpsData(byte[] data, int baseNoteOffset) {
        this(data, baseNoteOffset, 0); // PSG always uses offset 0
    }

    public SimpleSmpsData(byte[] data, int baseNoteOffset, int psgBaseNoteOffset) {
        super(data, 0); // file-relative pointers (z80StartAddress=0)
        this.baseNoteOffset = baseNoteOffset;
        this.psgBaseNoteOffset = psgBaseNoteOffset;
    }

    public void setPsgEnvelopes(byte[][] envelopes) {
        this.psgEnvelopes = envelopes;
    }

    @Override
    protected void parseHeader() {
        if (data.length < 6) return;

        voicePtr = read16(0);
        channels = data[2] & 0xFF;
        psgChannels = data[3] & 0xFF;
        dividingTiming = data[4] & 0xFF;
        tempo = data[5] & 0xFF;

        fmPointers = new int[channels];
        fmKeyOffsets = new int[channels];
        fmVolumeOffsets = new int[channels];

        int offset = 6;
        for (int i = 0; i < channels; i++) {
            fmPointers[i] = read16(offset);
            fmKeyOffsets[i] = (byte) data[offset + 2];
            fmVolumeOffsets[i] = (byte) data[offset + 3];
            offset += 4;
        }

        psgPointers = new int[psgChannels];
        psgKeyOffsets = new int[psgChannels];
        psgVolumeOffsets = new int[psgChannels];
        psgModEnvs = new int[psgChannels];
        psgInstruments = new int[psgChannels];

        for (int i = 0; i < psgChannels; i++) {
            psgPointers[i] = read16(offset);
            psgKeyOffsets[i] = (byte) data[offset + 2];
            psgVolumeOffsets[i] = (byte) data[offset + 3];
            psgModEnvs[i] = data[offset + 4] & 0xFF;
            psgInstruments[i] = data[offset + 5] & 0xFF;
            offset += 6;
        }
    }

    @Override
    public byte[] getVoice(int voiceId) {
        if (voicePtr == 0 || voicePtr >= data.length) return null;
        int offset = voicePtr + voiceId * FmVoice.VOICE_SIZE;
        if (offset + FmVoice.VOICE_SIZE > data.length) return null;
        byte[] voice = new byte[FmVoice.VOICE_SIZE];
        System.arraycopy(data, offset, voice, 0, FmVoice.VOICE_SIZE);
        return voice;
    }

    @Override
    public byte[] getPsgEnvelope(int id) {
        if (psgEnvelopes == null || id < 0 || id >= psgEnvelopes.length) return null;
        return psgEnvelopes[id];
    }

    @Override
    public int read16(int offset) {
        if (offset + 2 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    @Override
    public int getBaseNoteOffset() {
        return baseNoteOffset;
    }

    @Override
    public int getPsgBaseNoteOffset() {
        return psgBaseNoteOffset;
    }
}

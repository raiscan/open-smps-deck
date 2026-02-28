package com.opensmps.smps;

public class TestSmpsData extends AbstractSmpsData {
    private byte[][] psgEnvelopes;

    public TestSmpsData(byte[] data, int z80StartAddress) {
        super(data, z80StartAddress);
    }

    public void setPsgEnvelopes(byte[][] envelopes) {
        this.psgEnvelopes = envelopes;
    }

    @Override
    protected void parseHeader() {
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
        int offset = voicePtr;
        if (offset == 0) return null;
        offset += voiceId * 25;
        if (offset + 25 > data.length) return null;
        byte[] voice = new byte[25];
        System.arraycopy(data, offset, voice, 0, 25);
        return voice;
    }

    @Override
    public byte[] getPsgEnvelope(int id) {
        if (psgEnvelopes == null || id < 0 || id >= psgEnvelopes.length) return null;
        return psgEnvelopes[id];
    }

    @Override
    public int read16(int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    @Override
    public int getBaseNoteOffset() {
        return 1;
    }
}

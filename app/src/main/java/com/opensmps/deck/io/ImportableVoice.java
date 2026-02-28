package com.opensmps.deck.io;

/**
 * A voice discovered during SMPS file scanning, with provenance metadata.
 *
 * @param sourceSong name of the SMPS file this voice came from
 * @param originalIndex the voice's index within the source song's voice bank
 * @param voiceData the 25-byte SMPS FM voice data
 * @param algorithm the FM algorithm (0-7), extracted from voiceData[0] bits 0-2
 */
public record ImportableVoice(String sourceSong, int originalIndex, byte[] voiceData, int algorithm) {

    public ImportableVoice {
        voiceData = voiceData.clone();
    }

    @Override
    public byte[] voiceData() {
        return voiceData.clone();
    }
}

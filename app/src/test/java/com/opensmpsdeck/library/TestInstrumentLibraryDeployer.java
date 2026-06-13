package com.opensmpsdeck.library;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestInstrumentLibraryDeployer {

    @Test
    void appendsMissingAssetsAndReusesIdenticalOnSecondDeploy() {
        Song song = new Song();
        SourceReference source = SourceReference.minimal("C:/rips", "PSG.lst", "0");
        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        voiceData[0] = 0x3F;
        List<InstrumentLibraryEntry> entries = List.of(
                InstrumentLibraryEntry.fmVoice("FM", voiceData, source),
                InstrumentLibraryEntry.psgEnvelope("PSG", new byte[]{1, 2, (byte) 0x80}, source),
                InstrumentLibraryEntry.modEnvelope("MOD", new byte[]{3, 4, (byte) 0x80}, source),
                InstrumentLibraryEntry.dacSample("DAC", new byte[]{0x55}, 0x20,
                        "PCM", null, null, null, "81", source)
        );

        DeployResult first = InstrumentLibraryDeployer.deploy(song, entries);
        DeployResult second = InstrumentLibraryDeployer.deploy(song, entries);

        assertEquals(4, first.appendedCount());
        assertEquals(0, first.reusedCount());
        assertEquals(0, second.appendedCount());
        assertEquals(4, second.reusedCount());
        assertEquals(1, song.getVoiceBank().size());
        assertEquals(1, song.getPsgEnvelopes().size());
        assertEquals(1, song.getModEnvelopes().size());
        assertEquals(1, song.getDacSamples().size());
    }

    @Test
    void deploymentCopiesDataIntoSongModels() {
        Song song = new Song();
        SourceReference source = SourceReference.minimal("C:/rips", "DAC.ini", "81");
        byte[] dac = new byte[]{0x01, 0x02};
        InstrumentLibraryEntry entry = InstrumentLibraryEntry.dacSample(
                "DAC", dac, 0x44, "PCM", null, null, null, "81", source);

        InstrumentLibraryDeployer.deploy(song, List.of(entry));
        dac[0] = 0x7F;
        DacSample sample = song.getDacSamples().getFirst();

        assertArrayEquals(new byte[]{0x01, 0x02}, sample.getData());
        assertEquals(0x44, sample.getRate());
    }
}

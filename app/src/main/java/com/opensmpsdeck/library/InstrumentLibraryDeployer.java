package com.opensmpsdeck.library;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class InstrumentLibraryDeployer {

    private InstrumentLibraryDeployer() {
    }

    public static DeployResult deploy(Song song, List<InstrumentLibraryEntry> entries) {
        Objects.requireNonNull(song, "song");
        Objects.requireNonNull(entries, "entries");

        int appended = 0;
        int reused = 0;
        for (InstrumentLibraryEntry entry : entries) {
            if (contains(song, entry)) {
                reused++;
            } else {
                append(song, entry);
                appended++;
            }
        }
        return new DeployResult(appended, reused);
    }

    private static boolean contains(Song song, InstrumentLibraryEntry entry) {
        return switch (entry.kind()) {
            case FM_VOICE -> containsVoice(song.getVoiceBank(), entry.data());
            case PSG_ENVELOPE -> containsEnvelope(song.getPsgEnvelopes(), entry.data());
            case MOD_ENVELOPE -> containsEnvelope(song.getModEnvelopes(), entry.data());
            case DAC_SAMPLE -> containsDac(song.getDacSamples(), entry.data(), entry.playbackRate());
        };
    }

    private static boolean containsVoice(List<FmVoice> voices, byte[] data) {
        for (FmVoice voice : voices) {
            if (Arrays.equals(voice.getData(), data)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsEnvelope(List<PsgEnvelope> envelopes, byte[] data) {
        for (PsgEnvelope envelope : envelopes) {
            if (Arrays.equals(envelope.getData(), data)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDac(List<DacSample> samples, byte[] data, int rate) {
        int normalizedRate = rate & 0xFF;
        for (DacSample sample : samples) {
            if (sample.getRate() == normalizedRate && Arrays.equals(sample.getData(), data)) {
                return true;
            }
        }
        return false;
    }

    private static void append(Song song, InstrumentLibraryEntry entry) {
        switch (entry.kind()) {
            case FM_VOICE -> song.getVoiceBank().add(new FmVoice(entry.displayName(), entry.data()));
            case PSG_ENVELOPE -> song.getPsgEnvelopes().add(new PsgEnvelope(entry.displayName(), entry.data()));
            case MOD_ENVELOPE -> song.getModEnvelopes().add(new PsgEnvelope(entry.displayName(), entry.data()));
            case DAC_SAMPLE -> song.getDacSamples().add(
                    new DacSample(entry.displayName(), entry.data(), entry.playbackRate()));
        }
    }
}

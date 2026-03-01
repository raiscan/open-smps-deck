package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;

import java.util.HashMap;
import java.util.Map;

public final class LegacyMigrator {

    private LegacyMigrator() {}

    private static final String[] CHANNEL_NAMES = {
        "FM1", "FM2", "FM3", "FM4", "FM5", "DAC",
        "PSG1", "PSG2", "PSG3", "Noise"
    };

    public static Song migrate(Song legacy) {
        var song = new Song();
        song.setName(legacy.getName());
        song.setSmpsMode(legacy.getSmpsMode());
        song.setTempo(legacy.getTempo());
        song.setDividingTiming(legacy.getDividingTiming());
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);

        // Copy instruments
        song.getVoiceBank().addAll(legacy.getVoiceBank());
        song.getPsgEnvelopes().addAll(legacy.getPsgEnvelopes());
        song.getDacSamples().addAll(legacy.getDacSamples());

        var arr = new HierarchicalArrangement();

        // Create phrases from patterns, keyed by (patternIndex, channel)
        Map<String, Phrase> phraseCache = new HashMap<>();

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            var chain = arr.getChain(ch);
            ChannelType type = ChannelType.fromChannelIndex(ch);

            for (int[] orderRow : legacy.getOrderList()) {
                int patternIdx = orderRow[ch];
                String key = patternIdx + ":" + ch;

                Phrase phrase = phraseCache.get(key);
                if (phrase == null) {
                    Pattern pattern = legacy.getPatterns().get(patternIdx);
                    byte[] data = pattern.getTrackData(ch);
                    String name = "P" + patternIdx + "-" + CHANNEL_NAMES[ch];
                    phrase = arr.getPhraseLibrary().createPhrase(name, type);
                    phrase.setData(data);
                    phraseCache.put(key, phrase);
                }

                chain.getEntries().add(new ChainEntry(phrase.getId()));
            }

            // Set loop point (same for all channels from legacy global loop)
            if (legacy.getLoopPoint() >= 0 && legacy.getLoopPoint() < legacy.getOrderList().size()) {
                chain.setLoopEntryIndex(legacy.getLoopPoint());
            }
        }

        song.setHierarchicalArrangement(arr);
        return song;
    }
}

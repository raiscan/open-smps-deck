package com.opensmpsdeck.headless;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.opensmpsdeck.project.StockRef;

import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

/** Native ROM byte order, including S1 voices before the sequencer's operator swap. */
final class ClassicStockAssets implements StockAssets {
    private final boolean s1;
    private final SmpsLoader loader;
    private final DacData dac;
    private final byte[][] s1Envelopes;
    private final byte[] image;
    private final Map<Integer, AbstractSmpsData> songs = new HashMap<>();
    private Map<Integer, byte[]> s2Envelopes;

    ClassicStockAssets(boolean s1, Rom rom, byte[] image) {
        this.s1 = s1;
        this.image = image;
        loader = s1 ? new Sonic1SmpsLoader(rom) : new Sonic2SmpsLoader(rom);
        dac = loader.loadDacData();
        s1Envelopes = s1 ? ((Sonic1SmpsLoader) loader).getPsgEnvelopes() : null;
    }

    int dacRate(int note) {
        var entry = dac == null ? null : dac.mappingForNote(note);
        return entry == null ? -1 : entry.rate();
    }

    AbstractSmpsData song(int id) {
        return songs.computeIfAbsent(id, loader::loadMusic);
    }

    static int voiceOffset(AbstractSmpsData data) {
        return data.getVoicePtr() == 0 ? -1 : data.getVoicePtr() - data.getZ80StartAddress();
    }

    static int voiceCount(AbstractSmpsData song) {
        int start = voiceOffset(song);
        if (start < 0 || start >= song.getData().length) return 0;
        // The final song in each loader's uncompressed extent has trailing
        // ROM data. The disassembly's Get Emerald and Credits tables end here.
        int limit = song instanceof com.openggf.game.sonic1.audio.smps.Sonic1SmpsData && song.getId() == 0x93 ? 2
                : song instanceof com.openggf.game.sonic2.audio.smps.Sonic2SmpsData && song.getId() == 0xbd ? 38 : 256;
        return Math.min(limit, (song.getData().length - start) / 25);
    }

    @Override public byte[] payload(StockRef ref) {
        if (!ref.game().equals(s1 ? "s1" : "s2")) return null;
        return switch (ref.table()) {
            case "local-fm" -> {
                var data = song(ref.songId());
                if (data == null) yield null;
                int start = voiceOffset(data);
                byte[] bank = data.getData();
                int offset = start + 25 * ref.slot();
                yield ref.slot() >= voiceCount(data) ? null : Arrays.copyOfRange(bank, offset, offset + 25);
            }
            case "psg" -> envelope(ref.index());
            case "dac" -> S3kPackageDriver.dacPayload(dac, ref.note());
            default -> null;
        };
    }

    private byte[] envelope(int index) {
        if (s1) return s1Envelopes[index - 1];
        if (s2Envelopes == null) {
            try {
                s2Envelopes = Sonic2RomEnvelopes.extract(image);
            } catch (java.io.IOException error) {
                throw new StockAssets.Unavailable(error.getMessage(), error);
            }
        }
        return s2Envelopes.get(index);
    }
}

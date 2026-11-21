package com.opensmpsdeck.headless.rompatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.rompatch.SongLayout.*;
import static com.opensmpsdeck.headless.rompatch.S3kRevision.*;

final class RetailLayoutAudit {
    private static final int S3 = 0x2E6000, S3_MUSIC_BANKS = 0xB48, S3_MUSIC_POINTERS = 0x161A;
    private static final int SFX_BANK = 0x0F8000, SFX_POINTERS = 0x167E;

    record StockSample(int note, int rate, int table, int descriptor, int data, byte[] pcm) {}
    record Song(int bank, int offset) {
        int romOffset() { return bank * WINDOW + offset; }
    }

    final byte[] ram;
    final byte[] deltas;
    final List<Span> free;
    final List<StockSample> samples;
    final Set<Integer> alternateVoiceSlots = new TreeSet<>();
    final Set<Integer> usedNotes = new TreeSet<>();

    RetailLayoutAudit(byte[] rom) throws IOException {
        ram = driverRam(rom);
        deltas = Arrays.copyOfRange(ram, DAC_DELTAS, DAC_DELTAS + 16);
        samples = stockSamples(rom);
        var lockedOn = new LinkedHashSet<Song>();
        for (int i = 0; i < MUSIC_COUNT; i++)
            lockedOn.add(new Song(u8(ram, MUSIC_BANKS + i), bankOffset(word(ram, MUSIC_POINTERS + 2 * i))));
        var standalone = new LinkedHashSet<Song>();
        for (int i = 0; i < 50; i++) {
            int bank = 0x50 | (u8(rom, S3 + S3_MUSIC_BANKS + i) & 15);
            standalone.add(new Song(bank, bankOffset(word(rom, S3 + S3_MUSIC_POINTERS + 2 * i))));
        }
        var live = new BitSet(rom.length);
        for (var song : lockedOn) {
            var layout = stockSong(rom, song, "S&K");
            reserve(live, song, layout);
            usedNotes.addAll(layout.stockDacNotes());
            alternateVoiceSlots.addAll(layout.alternateVoiceSlots());
        }
        // The $33 table entry is also a SFX pointer, though runtime dispatch
        // handles that request as the credits. Scan it as well for safety.
        byte[] sfxBank = Arrays.copyOfRange(rom, SFX_BANK, SFX_BANK + WINDOW);
        for (int id = 0x33; id < 0xE0; id++) {
            int pointer = word(ram, SFX_POINTERS + 2 * (id - 0x33));
            if (pointer == 0) continue;
            var layout = SongLayout.sfx(sfxBank, bankOffset(pointer));
            usedNotes.addAll(layout.stockDacNotes());
            alternateVoiceSlots.addAll(layout.alternateVoiceSlots());
        }
        reserveDac(live, samples);
        var candidates = new BitSet(rom.length);
        var ordered = standalone.stream().sorted(Comparator.comparingInt(Song::romOffset)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            var song = ordered.get(i);
            if (lockedOn.contains(song)) continue;
            var layout = stockSong(rom, song, "S3");
            reserve(candidates, song, layout);
            // In the verified image's S3 listing, successive headers within
            // these music banks are consecutive includes. The next header
            // delimits the preceding include even when it has unused voices,
            // unreachable stop bytes, or alignment at its end.
            if (i + 1 < ordered.size() && ordered.get(i + 1).bank == song.bank)
                candidates.set(song.romOffset(), ordered.get(i + 1).romOffset());
        }
        free = fragments(candidates, live);
    }

    private static SongLayout stockSong(byte[] rom, Song song, String driver) {
        int start = song.bank * WINDOW;
        try {
            return SongLayout.music(Arrays.copyOfRange(rom, start, start + WINDOW),
                    WINDOW, song.offset, -1, false);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("%s song at ROM 0x%06X: %s"
                    .formatted(driver, song.romOffset(), failure.getMessage()), failure);
        }
    }

    private static void reserve(BitSet map, Song song, SongLayout layout) {
        int start = song.bank * WINDOW;
        var occupied = layout.occupied();
        map.set(start + occupied.nextSetBit(0), start + occupied.length());
    }

    private List<StockSample> stockSamples(byte[] rom) {
        var result = new ArrayList<StockSample>();
        for (int note = DAC_FIRST; note <= DAC_LAST; note++) {
            int bank = u8(ram, DAC_BANKS + note - 0x80) * WINDOW;
            if (bank == 0) continue;
            int entry = bank + 2 * (note - DAC_FIRST);
            int pointer = word(rom, entry);
            if (pointer == 0) continue;
            int descriptor = bank + bankOffset(pointer);
            int data = bank + bankOffset(word(rom, descriptor + 3));
            int length = word(rom, descriptor + 1);
            if (descriptor + 5 > bank + WINDOW || length == 0 || data + length > bank + WINDOW)
                throw new IllegalArgumentException("Invalid retail DAC descriptor for note 0x%02X".formatted(note));
            result.add(new StockSample(note, u8(rom, descriptor), entry, descriptor, data,
                    Dpcm.decode(Arrays.copyOfRange(rom, data, data + length), deltas)));
        }
        return List.copyOf(result);
    }

    private static void reserveDac(BitSet live, List<StockSample> samples) {
        for (var sample : samples) {
            live.set(sample.table, sample.table + 2);
            live.set(sample.descriptor, sample.descriptor + 5);
            live.set(sample.data, sample.data + sample.pcm.length / 2);
        }
    }

    static List<Span> fragments(BitSet candidates, BitSet live) {
        var available = (BitSet) candidates.clone();
        available.andNot(live);
        var result = new ArrayList<Span>();
        for (int start = available.nextSetBit(0); start >= 0; ) {
            int end = Math.min(available.nextClearBit(start), (start / WINDOW + 1) * WINDOW);
            result.add(new Span(start, end));
            start = available.nextSetBit(end);
        }
        return List.copyOf(result);
    }

    static int bankOffset(int pointer) {
        if (pointer < WINDOW) throw new IllegalArgumentException("Expected a bank-window pointer");
        return pointer - WINDOW;
    }

}

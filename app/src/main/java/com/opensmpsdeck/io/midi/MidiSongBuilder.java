package com.opensmpsdeck.io.midi;

import com.opensmps.smps.SmpsCoordFlags;
import com.opensmpsdeck.model.*;

import java.util.*;

/** Assembles the final Song from a confirmed MidiImportSpec. */
public final class MidiSongBuilder {

    private static final int DAC_CHANNEL = 5;
    private static final int NOISE_CHANNEL = 9;
    private static final int DEFAULT_DAC_RATE = 0x0C;
    private static final int NOISE_MODE_BYTE = 0xE4; // white noise, high fixed rate
    private static final String[] DAC_SLOT_NAMES = {"Kick", "Snare", "Tom"};

    private MidiSongBuilder() {}

    public static Song build(MidiImportSpec spec) {
        Song song = new Song();
        song.setName(spec.songName());
        song.setSmpsMode(spec.mode());
        song.setTempo(spec.tempoByte());
        song.setDividingTiming(spec.dividingTiming());
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);

        HierarchicalArrangement arr = song.getHierarchicalArrangement();
        PhraseLibrary lib = arr.getPhraseLibrary();
        Map<String, Integer> dedup = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        Set<Integer> replacedOriginals = new HashSet<>();

        // melodic lines
        for (MidiImportSpec.LineAssignment a : spec.lines()) {
            ChannelType type = ChannelType.fromChannelIndex(a.targetChannel());
            var params = new MidiPhraseEncoder.EncodeParams(
                    spec.unitsPerSixteenth(), spec.stepsPerBar(), spec.barsPerPhrase(),
                    a.octaveShift());
            var quantized = NoteQuantizer.quantize(a.line().notes(), a.ppq());
            List<ChainEntry> entries = MidiPhraseEncoder.encodeLine(quantized, type,
                    params, lib, dedup,
                    a.stemName() + "-" + a.line().rank(), warnings);
            if (entries.isEmpty()) continue;

            prefixInstrument(lib, entries.get(0), a, song, replacedOriginals);
            Chain chain = arr.getChain(a.targetChannel());
            chain.getEntries().addAll(entries);
        }

        // drums
        buildDrumChannel(spec, spec.dacHits(), arr, lib, dedup, song, true, replacedOriginals);
        buildDrumChannel(spec, spec.noiseHits(), arr, lib, dedup, song, false, replacedOriginals);

        // Cleanup runs after ALL channels are built: the dedup index may hand a
        // replaced original's id to a later channel, in which case it is still
        // referenced and must stay in the library.
        removeOrphanedOriginals(arr, lib, replacedOriginals);

        // loop points
        if (spec.loopWholeSong()) {
            for (Chain c : arr.getChains()) {
                if (!c.getEntries().isEmpty()) c.setLoopEntryIndex(0);
            }
        }
        return song;
    }

    /** Prepends EF/F5 to the first phrase of a channel; registers the voice in the bank. */
    private static void prefixInstrument(PhraseLibrary lib, ChainEntry firstEntry,
                                         MidiImportSpec.LineAssignment a, Song song,
                                         Set<Integer> replacedOriginals) {
        byte[] prefix;
        ChannelType type = ChannelType.fromChannelIndex(a.targetChannel());
        if (type == ChannelType.FM && a.voice() != null) {
            // FmVoice has no equals override, so indexOf() would be reference
            // equality — dedup the bank by voice data instead, so two assignments
            // carrying data-equal FmVoice instances share one bank slot.
            int idx = -1;
            for (int i = 0; i < song.getVoiceBank().size(); i++) {
                if (Arrays.equals(song.getVoiceBank().get(i).getDataUnsafe(),
                                  a.voice().getDataUnsafe())) { idx = i; break; }
            }
            if (idx < 0) { song.getVoiceBank().add(a.voice()); idx = song.getVoiceBank().size() - 1; }
            // EF's index byte is unsigned; the bank stays structurally small here
            // (at most one voice per line assignment, well under 128), so the
            // narrowing cast is safe.
            prefix = new byte[]{(byte) SmpsCoordFlags.SET_VOICE, (byte) idx};
        } else if (type == ChannelType.PSG_TONE && a.psgEnvelopeId() > 0) {
            prefix = new byte[]{(byte) SmpsCoordFlags.PSG_INSTRUMENT, (byte) a.psgEnvelopeId()};
        } else {
            return;
        }
        prependToPhrase(lib, firstEntry, prefix, type, replacedOriginals);
    }

    private static void prependToPhrase(PhraseLibrary lib, ChainEntry entry, byte[] prefix,
                                        ChannelType type, Set<Integer> replacedOriginals) {
        Phrase original = lib.getPhrase(entry.getPhraseId());
        byte[] data = original.getData();
        byte[] combined = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(data, 0, combined, prefix.length, data.length);
        // A prefixed phrase is channel-specific: always a fresh phrase, never deduped.
        // Other entries sharing the original phrase INTENTIONALLY keep the
        // unprefixed version — the instrument is set once at chain start and must
        // not be re-emitted when the same musical material recurs later.
        Phrase prefixed = lib.createPhrase(original.getName() + "+ins", type);
        prefixed.setData(combined);
        entry.setPhraseId(prefixed.getId());
        replacedOriginals.add(original.getId());
    }

    /**
     * Removes replaced originals that no chain entry references anymore —
     * without this, every single-use first phrase leaves an orphaned twin
     * in the library after prefixing.
     */
    private static void removeOrphanedOriginals(HierarchicalArrangement arr, PhraseLibrary lib,
                                                Set<Integer> replacedOriginals) {
        if (replacedOriginals.isEmpty()) return;
        Set<Integer> referenced = new HashSet<>();
        for (Chain c : arr.getChains()) {
            for (ChainEntry e : c.getEntries()) referenced.add(e.getPhraseId());
        }
        for (int id : replacedOriginals) {
            if (!referenced.contains(id)) lib.removePhrase(id);
        }
    }

    private static void buildDrumChannel(MidiImportSpec spec, List<GmDrumMapper.DrumHit> hits,
                                         HierarchicalArrangement arr, PhraseLibrary lib,
                                         Map<String, Integer> dedup, Song song, boolean dac,
                                         Set<Integer> replacedOriginals) {
        if (hits.isEmpty()) return;
        int channel = dac ? DAC_CHANNEL : NOISE_CHANNEL;
        ChannelType type = ChannelType.fromChannelIndex(channel);

        // drum hits → pseudo quantized notes whose "pitch" yields the right note byte:
        // DAC: noteByte = 0x81 + slot → pitch = slot + 12; noise: fixed NOISE_NOTE.
        // lengthSteps is in sixteenth-grid steps: GmDrumMapper clips NOISE_SHORT
        // hits to lengthSteps=1, i.e. exactly one sixteenth of sounding time.
        List<NoteQuantizer.QuantizedNote> notes = new ArrayList<>();
        Set<Integer> usedSlots = new TreeSet<>();
        for (GmDrumMapper.DrumHit h : hits) {
            int pitch;
            if (dac) {
                usedSlots.add(h.target().dacSlot);
                pitch = h.target().dacSlot + 12;
            } else {
                pitch = MidiPhraseEncoder.NOISE_NOTE - 0x81 + 12;
            }
            notes.add(new NoteQuantizer.QuantizedNote(h.startStep(), h.lengthSteps(), pitch, 100));
        }
        // drum hits never sustain into each other: re-sort and clip overlaps
        notes.sort(Comparator.comparingInt(NoteQuantizer.QuantizedNote::startStep));

        var params = new MidiPhraseEncoder.EncodeParams(spec.unitsPerSixteenth(),
                spec.stepsPerBar(), spec.barsPerPhrase(), 0);
        // drum hits are already quantized step-space notes — encodeLine takes them directly
        List<ChainEntry> entries = MidiPhraseEncoder.encodeLine(notes, type, params, lib, dedup,
                dac ? "Drums-DAC" : "Drums-Noise", new ArrayList<>());
        if (entries.isEmpty()) return;

        if (!dac) {
            prependToPhrase(lib, entries.get(0),
                    new byte[]{(byte) SmpsCoordFlags.PSG_NOISE, (byte) NOISE_MODE_BYTE}, type,
                    replacedOriginals);
        } else {
            for (int slot : usedSlots) {
                while (song.getDacSamples().size() <= slot) {
                    int s = song.getDacSamples().size();
                    DacSample override = spec.dacSampleOverrides().get(s);
                    song.getDacSamples().add(override != null ? override
                            : new DacSample(DAC_SLOT_NAMES[Math.min(s, 2)], new byte[0],
                                            DEFAULT_DAC_RATE));
                }
            }
        }
        arr.getChain(channel).getEntries().addAll(entries);
    }
}

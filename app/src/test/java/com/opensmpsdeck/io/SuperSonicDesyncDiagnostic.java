package com.opensmpsdeck.io;

import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.AbstractSmpsData;
import com.opensmps.smps.SmpsSequencer;
import com.opensmps.smps.SmpsSequencerConfig;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Desync diagnostic: plays the ORIGINAL raw .sm2 rip and the IMPORTED+RECOMPILED
 * version through the real sequencer, records per-channel note events, and
 * reports the first divergence per channel.
 */
public class SuperSonicDesyncDiagnostic {

    record NoteEvent(long tick, int note, int pos) {}

    /** Wraps a raw SMPSPlay rip; pointers are Z80-absolute relative to seqBase. */
    static class RawRipData extends AbstractSmpsData {
        private byte[][] envelopes;

        RawRipData(byte[] data, int seqBase) {
            super(data, seqBase);
        }

        void setEnvelopes(byte[][] envelopes) { this.envelopes = envelopes; }

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
            int base = voicePtr - z80StartAddress;
            int offset = base + voiceId * 25;
            if (offset < 0 || offset + 25 > data.length) return null;
            return Arrays.copyOfRange(data, offset, offset + 25);
        }

        @Override
        public byte[] getPsgEnvelope(int id) {
            if (envelopes == null || id < 0 || id >= envelopes.length) return null;
            return envelopes[id];
        }

        @Override
        public int read16(int offset) {
            if (offset + 2 > data.length) return 0;
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }

        @Override
        public int getBaseNoteOffset() { return 1; } // S2

        @Override
        public int getPsgBaseNoteOffset() { return 0; }
    }

    public static void main(String[] args) throws Exception {
        File f = new File("../docs/SMPS-rips/Sonic The Hedgehog 2/2-13 Super Sonic.sm2");
        if (!f.exists()) f = new File("docs/SMPS-rips/Sonic The Hedgehog 2/2-13 Super Sonic.sm2");
        byte[] raw = Files.readAllBytes(f.toPath());

        // Determine seqBase exactly as the importer does
        int fmCount = raw[2] & 0xFF, psgCount = raw[3] & 0xFF;
        int voicePtr = le16(raw, 0);
        int[] fmPtrs = new int[fmCount];
        int[] psgPtrs = new int[psgCount];
        int off = 6;
        for (int i = 0; i < fmCount; i++, off += 4) fmPtrs[i] = le16(raw, off);
        for (int i = 0; i < psgCount; i++, off += 6) psgPtrs[i] = le16(raw, off);
        int seqBase = SmpsImporter.guessSeqBase(raw, fmCount, psgCount, voicePtr, fmPtrs, psgPtrs);
        System.out.printf("seqBase=0x%04X voicePtr=0x%04X fm=%d psg=%d%n",
                seqBase, voicePtr, fmCount, psgCount);

        // PSG envelopes shared by both sides
        byte[][] envs = null;
        File psgLst = new File(f.getParentFile(), "PSG.lst");
        if (psgLst.exists()) {
            List<PsgEnvelope> envelopes = SmpsImporter.parsePsgLst(Files.readAllBytes(psgLst.toPath()));
            envs = new byte[envelopes.size()][];
            for (int i = 0; i < envs.length; i++) envs[i] = envelopes.get(i).getData();
        }

        // Side A: original raw rip
        RawRipData rawData = new RawRipData(raw, seqBase);
        rawData.setEnvelopes(envs);
        Map<String, List<NoteEvent>> eventsA = capture(rawData, 60);

        // Side B: imported + recompiled (same as the app's playback path)
        Song song = new SmpsImporter().importFile(f);
        byte[] compiled = new PatternCompiler().compile(song);
        SimpleSmpsData compiledData = new SimpleSmpsData(compiled, 1, 0);
        if (envs != null) compiledData.setPsgEnvelopes(envs);
        Map<String, List<NoteEvent>> eventsB = capture(compiledData, 60);

        // Compare per channel
        Set<String> keys = new TreeSet<>();
        keys.addAll(eventsA.keySet());
        keys.addAll(eventsB.keySet());
        for (String key : keys) {
            List<NoteEvent> a = eventsA.getOrDefault(key, List.of());
            List<NoteEvent> b = eventsB.getOrDefault(key, List.of());
            int n = Math.min(a.size(), b.size());
            int firstDiff = -1;
            for (int i = 0; i < n; i++) {
                NoteEvent ea = a.get(i), eb = b.get(i);
                if (ea.tick != eb.tick || ea.note != eb.note) { firstDiff = i; break; }
            }
            if (firstDiff < 0 && a.size() != b.size()) firstDiff = n;
            if (firstDiff < 0) {
                System.out.printf("%-6s OK (%d events)%n", key, a.size());
            } else {
                System.out.printf("%-6s DIVERGES at event %d (of %d/%d)%n", key, firstDiff, a.size(), b.size());
                for (int i = Math.max(0, firstDiff - 3); i < Math.min(n, firstDiff + 4); i++) {
                    NoteEvent ea = i < a.size() ? a.get(i) : null;
                    NoteEvent eb = i < b.size() ? b.get(i) : null;
                    System.out.printf("    [%3d] orig=%s  recompiled=%s%n", i, fmt(ea), fmt(eb));
                }
            }
        }
    }

    private static String fmt(NoteEvent e) {
        if (e == null) return "<none>";
        return String.format("t=%-6d note=%02X pos=%04X", e.tick, e.note & 0xFF, e.pos);
    }

    private static int le16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    /** Play for the given number of seconds, recording per-channel note events. */
    private static Map<String, List<NoteEvent>> capture(AbstractSmpsData data, int seconds) {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{0x16, 0, 1, 2, 4, 5, 6})
                .psgChannelOrder(new int[]{0x80, 0xA0, 0xC0})
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();
        SmpsSequencer seq = new SmpsSequencer(data, null, driver, config);
        driver.addSequencer(seq, false);

        Map<String, List<NoteEvent>> events = new LinkedHashMap<>();
        Map<String, Integer> lastNote = new HashMap<>();

        short[] buf = new short[256];
        long totalSamples = 44100L * seconds * 2;
        long rendered = 0;
        while (rendered < totalSamples) {
            driver.read(buf);
            rendered += buf.length;
            long tick = seq.getTotalTicksElapsed();
            for (SmpsSequencer.Track t : seq.getTracks()) {
                String key = t.type + "" + t.channelId;
                int note = t.active ? t.note : -1;
                Integer prev = lastNote.get(key);
                if (prev == null || prev != note) {
                    lastNote.put(key, note);
                    events.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new NoteEvent(tick, note, t.pos));
                }
            }
        }
        return events;
    }
}

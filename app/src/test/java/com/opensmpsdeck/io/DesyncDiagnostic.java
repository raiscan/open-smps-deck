package com.opensmpsdeck.io;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.SmpsMode;
import com.opensmpsdeck.model.Song;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.AbstractSmpsData;
import com.opensmps.smps.SmpsSequencer;
import com.opensmps.smps.SmpsSequencerConfig;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Desync diagnostic for any rip: plays the ORIGINAL raw file and the
 * IMPORTED+RECOMPILED version through the real sequencer (using the same
 * per-game profile as the app), records per-channel note events, and reports
 * the first divergence.
 *
 * <p>Usage: pass a path relative to docs/SMPS-rips as the first argument,
 * e.g. {@code "Sonic The Hedgehog 3/01 Angel Island 1.8000.s3k"}.
 * Defaults to the Sonic 2 Super Sonic theme.
 */
public class DesyncDiagnostic {

    record NoteEvent(long tick, int note, int pos) {}

    /** Wraps a raw SMPSPlay rip; pointers are absolute relative to seqBase. */
    static class RawRipData extends AbstractSmpsData {
        private final boolean bigEndian;
        private final int fmBaseNoteOffset;
        private byte[][] envelopes;
        private byte[][] modEnvelopes;

        RawRipData(byte[] data, int seqBase, boolean bigEndian, int fmBaseNoteOffset) {
            super(data, seqBase);
            this.bigEndian = bigEndian;
            this.fmBaseNoteOffset = fmBaseNoteOffset;
            parseHeader(); // re-parse: super ran before bigEndian was set
        }

        void setEnvelopes(byte[][] envelopes) { this.envelopes = envelopes; }

        void setModEnvelopes(byte[][] envelopes) { this.modEnvelopes = envelopes; }

        @Override
        public byte[] getModEnvelope(int id) {
            if (modEnvelopes == null || id < 1 || id > modEnvelopes.length) return null;
            return modEnvelopes[id - 1];
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
            if (bigEndian) {
                return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            }
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }

        @Override
        public int getBaseNoteOffset() { return fmBaseNoteOffset; }

        @Override
        public int getPsgBaseNoteOffset() { return 0; }
    }

    public static void main(String[] args) throws Exception {
        String rel = args.length > 0 ? args[0]
                : "Sonic The Hedgehog 2/2-13 Super Sonic.sm2";
        File f = new File("../docs/SMPS-rips/" + rel);
        if (!f.exists()) f = new File("docs/SMPS-rips/" + rel);
        byte[] raw = Files.readAllBytes(f.toPath());

        String lower = f.getName().toLowerCase();
        SmpsMode mode = lower.endsWith(".s3k") ? SmpsMode.S3K
                : lower.endsWith(".smp") ? SmpsMode.S1 : SmpsMode.S2;
        boolean bigEndian = (mode == SmpsMode.S1);
        int fmBase = (mode == SmpsMode.S2) ? 1 : 0;

        // Determine seqBase exactly as the importer does
        int fmCount = raw[2] & 0xFF, psgCount = raw[3] & 0xFF;
        int voicePtr = ptr16(raw, 0, bigEndian);
        int[] fmPtrs = new int[fmCount];
        int[] psgPtrs = new int[psgCount];
        int off = 6;
        for (int i = 0; i < fmCount; i++, off += 4) fmPtrs[i] = ptr16(raw, off, bigEndian);
        for (int i = 0; i < psgCount; i++, off += 6) psgPtrs[i] = ptr16(raw, off, bigEndian);
        int seqBase = SmpsImporter.parseFilenameOffset(f.getName());
        if (seqBase < 0) {
            seqBase = SmpsImporter.guessSeqBase(raw, fmCount, psgCount, voicePtr, fmPtrs, psgPtrs);
        }
        System.out.printf("%s mode=%s seqBase=0x%04X fm=%d psg=%d%n",
                rel, mode, seqBase, fmCount, psgCount);

        // PSG envelopes shared by both sides
        byte[][] envs = null;
        File psgLst = new File(f.getParentFile(), "PSG.lst");
        if (!psgLst.exists() && f.getParentFile() != null) {
            psgLst = new File(f.getParentFile().getParentFile(), "PSG.lst");
        }
        if (psgLst.exists()) {
            List<PsgEnvelope> envelopes = SmpsImporter.parsePsgLst(Files.readAllBytes(psgLst.toPath()));
            envs = new byte[envelopes.size()][];
            for (int i = 0; i < envs.length; i++) envs[i] = envelopes.get(i).getData();
        }

        // Modulation envelopes shared by both sides
        byte[][] modEnvs = null;
        File modLst = new File(f.getParentFile(), "Modulat.lst");
        if (!modLst.exists() && f.getParentFile() != null) {
            modLst = new File(f.getParentFile().getParentFile(), "Modulat.lst");
        }
        if (modLst.exists()) {
            List<PsgEnvelope> envelopes = SmpsImporter.parsePsgLst(Files.readAllBytes(modLst.toPath()));
            modEnvs = new byte[envelopes.size()][];
            for (int i = 0; i < modEnvs.length; i++) modEnvs[i] = envelopes.get(i).getData();
        }

        // Side A: original raw rip
        RawRipData rawData = new RawRipData(raw, seqBase, bigEndian, fmBase);
        rawData.setEnvelopes(envs);
        rawData.setModEnvelopes(modEnvs);
        Map<String, List<NoteEvent>> eventsA = capture(rawData, mode, 60);

        // Side B: imported + recompiled (same as the app's playback path)
        Song song = new SmpsImporter().importFile(f);
        byte[] compiled = new PatternCompiler().compile(song);
        SimpleSmpsData compiledData = new SimpleSmpsData(compiled, fmBase, 0, bigEndian);
        if (envs != null) compiledData.setPsgEnvelopes(envs);
        if (modEnvs != null) compiledData.setModEnvelopes(modEnvs);
        Map<String, List<NoteEvent>> eventsB = capture(compiledData, mode, 60);

        // Compare per channel
        Set<String> keys = new TreeSet<>();
        keys.addAll(eventsA.keySet());
        keys.addAll(eventsB.keySet());
        boolean anyDiverged = false;
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
                anyDiverged = true;
                System.out.printf("%-6s DIVERGES at event %d (of %d/%d)%n", key, firstDiff, a.size(), b.size());
                for (int i = Math.max(0, firstDiff - 3); i < Math.min(Math.max(a.size(), b.size()), firstDiff + 4); i++) {
                    NoteEvent ea = i < a.size() ? a.get(i) : null;
                    NoteEvent eb = i < b.size() ? b.get(i) : null;
                    System.out.printf("    [%3d] orig=%s  recompiled=%s%n", i, fmt(ea), fmt(eb));
                }
            }
        }
        System.exit(anyDiverged ? 1 : 0);
    }

    private static String fmt(NoteEvent e) {
        if (e == null) return "<none>";
        return String.format("t=%-6d note=%02X pos=%04X", e.tick, e.note & 0xFF, e.pos);
    }

    private static int ptr16(byte[] d, int o, boolean be) {
        if (be) return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    /** Play for the given number of seconds, recording per-channel note events. */
    private static Map<String, List<NoteEvent>> capture(AbstractSmpsData data, SmpsMode mode, int seconds) {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencerConfig config = PlaybackEngine.buildConfig(mode);
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

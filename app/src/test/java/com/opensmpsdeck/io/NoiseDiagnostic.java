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

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Compares PSG noise behavior (per-track noiseMode/noiseParam/channelId
 * transitions) between an original rip and its imported+recompiled binary.
 */
public class NoiseDiagnostic {

    record NoiseEvent(long tick, int channelId, boolean noiseMode, int noiseParam, int note) {}

    public static void main(String[] args) throws Exception {
        String rel = args.length > 0 ? args[0] : "Sonic The Hedgehog 2/2-03 Casino Night Zone.sm2";
        File f = new File("../docs/SMPS-rips/" + rel);
        if (!f.exists()) f = new File("docs/SMPS-rips/" + rel);
        byte[] raw = Files.readAllBytes(f.toPath());

        String lower = f.getName().toLowerCase();
        SmpsMode mode = lower.endsWith(".s3k") ? SmpsMode.S3K
                : lower.endsWith(".smp") ? SmpsMode.S1 : SmpsMode.S2;
        boolean bigEndian = (mode == SmpsMode.S1);
        int fmBase = (mode == SmpsMode.S2) ? 1 : 0;

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

        byte[][] envs = loadEnvs(f);

        DesyncDiagnostic.RawRipData rawData = new DesyncDiagnostic.RawRipData(raw, seqBase, bigEndian, fmBase);
        rawData.setEnvelopes(envs);
        Map<Integer, List<NoiseEvent>> a = capture(rawData, mode);

        Song song = new SmpsImporter().importFile(f);
        byte[] compiled = new PatternCompiler().compile(song);
        SimpleSmpsData compiledData = new SimpleSmpsData(compiled, fmBase, 0, bigEndian);
        compiledData.setVoiceOperatorSwap(mode != SmpsMode.S2);
        if (envs != null) compiledData.setPsgEnvelopes(envs);
        Map<Integer, List<NoiseEvent>> b = capture(compiledData, mode);

        System.out.println(rel);
        Set<Integer> chans = new TreeSet<>(a.keySet());
        chans.addAll(b.keySet());
        for (int ch : chans) {
            List<NoiseEvent> ea = a.getOrDefault(ch, List.of());
            List<NoiseEvent> eb = b.getOrDefault(ch, List.of());
            int n = Math.min(ea.size(), eb.size());
            int diff = -1;
            for (int i = 0; i < n; i++) {
                NoiseEvent x = ea.get(i), y = eb.get(i);
                if (x.tick != y.tick || x.noiseMode != y.noiseMode
                        || x.noiseParam != y.noiseParam || x.note != y.note
                        || x.channelId != y.channelId) {
                    diff = i;
                    break;
                }
            }
            if (diff < 0 && ea.size() != eb.size()) diff = n;
            System.out.printf("PSG ch%d: orig=%d events, recompiled=%d events %s%n",
                    ch, ea.size(), eb.size(), diff < 0 ? "MATCH" : "DIFF@" + diff);
            if (diff >= 0) {
                for (int i = Math.max(0, diff - 2); i < Math.min(Math.max(ea.size(), eb.size()), diff + 4); i++) {
                    System.out.printf("    [%3d] orig=%s  recompiled=%s%n",
                            i, fmt(i < ea.size() ? ea.get(i) : null),
                            fmt(i < eb.size() ? eb.get(i) : null));
                }
            }
        }
    }

    private static String fmt(NoiseEvent e) {
        if (e == null) return "<none>";
        return String.format("t=%-5d ch=%d noise=%s param=%02X note=%02X",
                e.tick, e.channelId, e.noiseMode ? "Y" : "n", e.noiseParam, e.note & 0xFF);
    }

    private static byte[][] loadEnvs(File f) throws Exception {
        File psgLst = new File(f.getParentFile(), "PSG.lst");
        if (!psgLst.exists()) psgLst = new File(f.getParentFile().getParentFile(), "PSG.lst");
        if (!psgLst.exists()) return null;
        List<PsgEnvelope> envelopes = SmpsImporter.parsePsgLst(Files.readAllBytes(psgLst.toPath()));
        byte[][] envs = new byte[envelopes.size()][];
        for (int i = 0; i < envs.length; i++) envs[i] = envelopes.get(i).getData();
        return envs;
    }

    private static Map<Integer, List<NoiseEvent>> capture(AbstractSmpsData data, SmpsMode mode) {
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer seq = new SmpsSequencer(data, null, driver, PlaybackEngine.buildConfig(mode));
        driver.addSequencer(seq, false);

        Map<Integer, List<NoiseEvent>> events = new LinkedHashMap<>();
        Map<Integer, NoiseEvent> last = new HashMap<>();
        short[] buf = new short[256];
        long total = 44100L * 30 * 2;
        long done = 0;
        int psgIndex;
        while (done < total) {
            driver.read(buf);
            done += buf.length;
            psgIndex = 0;
            for (SmpsSequencer.Track t : seq.getTracks()) {
                if (t.type != SmpsSequencer.TrackType.PSG) continue;
                int key = psgIndex++;
                NoiseEvent cur = new NoiseEvent(seq.getTotalTicksElapsed(), t.channelId,
                        t.noiseMode, t.psgNoiseParam & 0xFF, t.active ? t.note : -1);
                NoiseEvent prev = last.get(key);
                if (prev == null || prev.channelId != cur.channelId || prev.noiseMode != cur.noiseMode
                        || prev.noiseParam != cur.noiseParam || prev.note != cur.note) {
                    last.put(key, cur);
                    events.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(cur);
                }
            }
        }
        return events;
    }

    private static int ptr16(byte[] d, int o, boolean be) {
        return be ? ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF)
                  : (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }
}

package com.opensmpsdeck.io;

import com.opensmpsdeck.audio.PlaybackEngine;
import com.opensmpsdeck.audio.SimpleSmpsData;
import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.SmpsMode;
import com.opensmpsdeck.model.Song;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.SmpsSequencer;

import java.io.File;
import java.util.*;

/**
 * For songs whose compiled output is not byte-stable under re-import, check
 * whether the two compilations at least PLAY identically (benign
 * restructuring) or actually diverge (a real re-import bug).
 */
public class ReimportEquivalence {

    record NoteEvent(long tick, int note) {}

    public static void main(String[] args) throws Exception {
        File ripsRoot = new File("../docs/SMPS-rips");
        if (!ripsRoot.exists()) ripsRoot = new File("docs/SMPS-rips");

        List<String> rels = Arrays.asList(args.length > 0 ? args : new String[]{
            "Sonic & Knuckles/Proto_1994-05-17_Sonic3C/13 Lava Reef 1.s3k",
            "Sonic The Hedgehog 3/1F Knuckles' Theme.s3k",
            "Sonic The Hedgehog 3/2E Act 1 Boss.s3k",
            "Sonic The Hedgehog 2/2-13 Super Sonic.sm2",
            "Sonic The Hedgehog 2/2-0D Boss.sm2",
            "Sonic The Hedgehog 3/0B IceCap 1.s3k",
            "Sonic The Hedgehog 3/28 Continue, Competition Results.EC7B.s3k",
        });

        for (String rel : rels) {
            File f = new File(ripsRoot, rel);
            if (!f.exists()) {
                System.out.println(rel + " : MISSING");
                continue;
            }
            Song song1 = new SmpsImporter().importFile(f);
            byte[] compile1 = new PatternCompiler().compile(song1);

            Song song2 = new SmpsImporter().importData(compile1, song1.getName(), 0, song1.getSmpsMode());
            song2.setSmpsMode(song1.getSmpsMode());
            song2.setDacChannelFm6(song1.isDacChannelFm6());
            song2.getModEnvelopes().addAll(song1.getModEnvelopes());
            song2.getPsgEnvelopes().addAll(song1.getPsgEnvelopes());
            byte[] compile2 = new PatternCompiler().compile(song2);

            Map<String, List<NoteEvent>> a = capture(compile1, song1);
            Map<String, List<NoteEvent>> b = capture(compile2, song1);

            List<String> bad = new ArrayList<>();
            Set<String> keys = new TreeSet<>(a.keySet());
            keys.addAll(b.keySet());
            for (String k : keys) {
                List<NoteEvent> ea = a.getOrDefault(k, List.of());
                List<NoteEvent> eb = b.getOrDefault(k, List.of());
                int n = Math.min(ea.size(), eb.size());
                int diff = -1;
                for (int i = 0; i < n; i++) {
                    if (ea.get(i).tick != eb.get(i).tick || ea.get(i).note != eb.get(i).note) {
                        diff = i;
                        break;
                    }
                }
                if (diff < 0 && ea.size() != eb.size()) diff = n;
                if (diff >= 0) {
                    NoteEvent x = diff < ea.size() ? ea.get(diff) : null;
                    NoteEvent y = diff < eb.size() ? eb.get(diff) : null;
                    bad.add(k + "@" + diff + " (" + fmt(x) + " vs " + fmt(y) + ")");
                }
            }
            System.out.println(rel + " : " + (bad.isEmpty() ? "PLAYS IDENTICAL" : "PLAYBACK DIFFERS " + bad));
        }
    }

    private static String fmt(NoteEvent e) {
        return e == null ? "<none>" : String.format("t=%d n=%02X", e.tick, e.note & 0xFF);
    }

    private static Map<String, List<NoteEvent>> capture(byte[] compiled, Song song) {
        boolean be = song.getSmpsMode() == SmpsMode.S1;
        int fmBase = song.getSmpsMode() == SmpsMode.S2 ? 1 : 0;
        SimpleSmpsData data = new SimpleSmpsData(compiled, fmBase, 0, be);
        data.setVoiceOperatorSwap(song.getSmpsMode() != SmpsMode.S2);
        if (!song.getPsgEnvelopes().isEmpty()) {
            byte[][] envs = new byte[song.getPsgEnvelopes().size()][];
            for (int i = 0; i < envs.length; i++) envs[i] = song.getPsgEnvelopes().get(i).getData();
            data.setPsgEnvelopes(envs);
        }
        if (!song.getModEnvelopes().isEmpty()) {
            byte[][] envs = new byte[song.getModEnvelopes().size()][];
            for (int i = 0; i < envs.length; i++) envs[i] = song.getModEnvelopes().get(i).getData();
            data.setModEnvelopes(envs);
        }
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencer seq = new SmpsSequencer(data, null, driver, PlaybackEngine.buildConfig(song.getSmpsMode()));
        driver.addSequencer(seq, false);

        Map<String, List<NoteEvent>> events = new LinkedHashMap<>();
        Map<String, Integer> last = new HashMap<>();
        short[] buf = new short[256];
        long total = 44100L * 45 * 2;
        long done = 0;
        while (done < total) {
            driver.read(buf);
            done += buf.length;
            for (SmpsSequencer.Track t : seq.getTracks()) {
                String key = t.type + "" + t.channelId;
                int note = t.active ? t.note : -1;
                Integer prev = last.get(key);
                if (prev == null || prev != note) {
                    last.put(key, note);
                    events.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new NoteEvent(seq.getTotalTicksElapsed(), note));
                }
            }
        }
        return events;
    }
}

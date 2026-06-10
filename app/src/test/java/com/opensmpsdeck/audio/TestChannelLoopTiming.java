package com.opensmpsdeck.audio;

import com.opensmpsdeck.codec.PatternCompiler;
import com.opensmpsdeck.model.ArrangementMode;
import com.opensmpsdeck.model.ChainEntry;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.SmpsMode;
import com.opensmpsdeck.model.Song;
import com.opensmps.driver.SmpsDriver;
import com.opensmps.smps.SmpsCoordFlags;
import com.opensmps.smps.SmpsSequencer;
import com.opensmps.smps.SmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A channel loop (F6 JUMP back to the loop entry) must repeat the loop body
 * with an exact period: every iteration plays the same notes at the same
 * relative ticks. Regression test for the reported off-by-one when the loop
 * point lands on a row that has a note.
 */
class TestChannelLoopTiming {

    private record NoteEvent(long tick, int note) {}

    /** Build a looping one-channel song: 4 distinct notes, 6 ticks each, loop at entry 0. */
    private Song buildLoopingSong() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S2);
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);
        song.setTempo(0xFF);
        song.setDividingTiming(1);
        song.getVoiceBank().add(new FmVoice("V0", new byte[FmVoice.VOICE_SIZE]));

        var hier = song.getHierarchicalArrangement();
        Phrase phrase = hier.getPhraseLibrary().createPhrase("Body", ChannelType.FM);
        phrase.setData(new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA1, 0x06, // C
            (byte) 0xA3, 0x06, // D
            (byte) 0xA5, 0x06, // E
            (byte) 0xA6, 0x06, // F
        });
        var chain = hier.getChain(0);
        chain.getEntries().add(new ChainEntry(phrase.getId()));
        chain.setLoopEntryIndex(0); // loop lands on the same row as the first note
        return song;
    }

    private List<NoteEvent> captureNotes(Song song, int iterations) {
        byte[] smps = new PatternCompiler().compile(song);
        SimpleSmpsData data = new SimpleSmpsData(smps, 1, 0);
        SmpsDriver driver = new SmpsDriver();
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .tempoModBase(0x100)
                .fmChannelOrder(new int[]{0x16, 0, 1, 2, 4, 5, 6})
                .psgChannelOrder(new int[]{0x80, 0xA0, 0xC0})
                .tempoMode(SmpsSequencerConfig.TempoMode.OVERFLOW2)
                .build();
        SmpsSequencer seq = new SmpsSequencer(data, null, driver, config);
        driver.addSequencer(seq, false);

        List<NoteEvent> events = new ArrayList<>();
        int lastNote = -1;
        short[] buf = new short[128];
        long maxTicks = 24L * iterations + 8;
        // Bound by rendered samples as well: a stopped (non-looping) song no
        // longer advances ticks, which would otherwise spin forever
        long samplesRendered = 0;
        long maxSamples = 44100L * 2 * 10;
        while (seq.getTotalTicksElapsed() < maxTicks && samplesRendered < maxSamples) {
            driver.read(buf);
            samplesRendered += buf.length;
            for (SmpsSequencer.Track t : seq.getTracks()) {
                if (t.type != SmpsSequencer.TrackType.FM || t.channelId != 0) continue;
                if (t.note != lastNote && t.note >= 0x81) {
                    lastNote = t.note;
                    events.add(new NoteEvent(seq.getTotalTicksElapsed(), t.note));
                }
            }
        }
        return events;
    }

    /** Intro phrase + looped body phrase: loop entry 1 starts on a note row. */
    private Song buildMidChainLoopSong() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S2);
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);
        song.setTempo(0xFF);
        song.setDividingTiming(1);
        song.getVoiceBank().add(new FmVoice("V0", new byte[FmVoice.VOICE_SIZE]));

        var hier = song.getHierarchicalArrangement();
        Phrase intro = hier.getPhraseLibrary().createPhrase("Intro", ChannelType.FM);
        intro.setData(new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0x95, 0x06, // intro note
            (byte) 0x97, 0x06,
        });
        Phrase body = hier.getPhraseLibrary().createPhrase("Body", ChannelType.FM);
        body.setData(new byte[]{
            (byte) 0xA1, 0x06, // C — loop target row
            (byte) 0xA3, 0x06, // D
            (byte) 0xA5, 0x06, // E
            (byte) 0xA6, 0x06, // F
        });
        var chain = hier.getChain(0);
        chain.getEntries().add(new ChainEntry(intro.getId()));
        chain.getEntries().add(new ChainEntry(body.getId()));
        chain.setLoopEntryIndex(1); // loop back to the body, not the intro
        return song;
    }

    @Test
    void midChainLoopOnNoteRowRepeatsWithExactPeriod() {
        List<NoteEvent> events = captureNotes(buildMidChainLoopSong(), 6);

        StringBuilder log = new StringBuilder();
        for (NoteEvent e : events) {
            log.append(String.format("t=%d note=%02X%n", e.tick, e.note));
        }

        // Intro: 95 97 (12 ticks), then body C D E F looping with 24-tick period
        assertTrue(events.size() >= 14, "Expected intro + 3 body iterations:\n" + log);
        assertEquals(0x95, events.get(0).note, "Intro first note:\n" + log);
        assertEquals(0x97, events.get(1).note, "Intro second note:\n" + log);

        int[] bodyNotes = {0xA1, 0xA3, 0xA5, 0xA6};
        for (int i = 2; i < 14; i++) {
            int bodyIndex = (i - 2) % 4;
            int iteration = (i - 2) / 4;
            NoteEvent e = events.get(i);
            assertEquals(bodyNotes[bodyIndex], e.note,
                    "Body note sequence broken at event " + i + ":\n" + log);
            long expectedTick = events.get(2 + bodyIndex).tick + iteration * 24L;
            assertEquals(expectedTick, e.tick,
                    "Body loop period broken at event " + i + ":\n" + log);
        }
    }

    /** Chain entry with repeatCount 3 followed by a distinct end marker note. */
    private Song buildRepeatCountSong() {
        Song song = new Song();
        song.setSmpsMode(SmpsMode.S2);
        song.setArrangementMode(ArrangementMode.HIERARCHICAL);
        song.setTempo(0xFF);
        song.setDividingTiming(1);
        song.getVoiceBank().add(new FmVoice("V0", new byte[FmVoice.VOICE_SIZE]));

        var hier = song.getHierarchicalArrangement();
        Phrase body = hier.getPhraseLibrary().createPhrase("Body", ChannelType.FM);
        body.setData(new byte[]{
            (byte) SmpsCoordFlags.SET_VOICE, 0x00,
            (byte) 0xA1, 0x06, // C
            (byte) 0xA3, 0x06, // D
        });
        Phrase end = hier.getPhraseLibrary().createPhrase("End", ChannelType.FM);
        end.setData(new byte[]{
            (byte) 0xB5, 0x06, // distinct end marker
        });
        var chain = hier.getChain(0);
        ChainEntry repeated = new ChainEntry(body.getId());
        repeated.setRepeatCount(3);
        chain.getEntries().add(repeated);
        chain.getEntries().add(new ChainEntry(end.getId()));
        // no loop point: song stops after the end marker
        return song;
    }

    @Test
    void repeatCountPlaysExactlyThatManyTimes() {
        List<NoteEvent> events = captureNotes(buildRepeatCountSong(), 6);

        StringBuilder log = new StringBuilder();
        for (NoteEvent e : events) {
            log.append(String.format("t=%d note=%02X%n", e.tick, e.note));
        }

        // Expect C D ×3 then the end marker: exactly 7 note events
        long bodyCount = events.stream().filter(e -> e.note == 0xA1).count();
        assertEquals(3, bodyCount, "repeatCount=3 should play the body exactly 3 times:\n" + log);
        assertEquals(0xB5, events.get(events.size() - 1).note,
                "End marker should follow the repeats:\n" + log);
        assertEquals(7, events.size(), "C D ×3 + end marker = 7 events:\n" + log);
    }

    @Test
    void loopOnNoteRowRepeatsWithExactPeriod() {
        List<NoteEvent> events = captureNotes(buildLoopingSong(), 5);

        // Expect C D E F | C D E F | ... with a strict 24-tick period
        int[] expectedNotes = {0xA1, 0xA3, 0xA5, 0xA6};
        assertTrue(events.size() >= 16,
                "Should capture at least 4 iterations of 4 notes, got " + events.size());

        StringBuilder log = new StringBuilder();
        for (NoteEvent e : events) {
            log.append(String.format("t=%d note=%02X%n", e.tick, e.note));
        }

        for (int i = 0; i < 16; i++) {
            NoteEvent e = events.get(i);
            assertEquals(expectedNotes[i % 4], e.note,
                    "Note sequence broken at event " + i + ":\n" + log);
            long expectedTick = events.get(i % 4).tick + (i / 4) * 24L;
            assertEquals(expectedTick, e.tick,
                    "Loop period broken at event " + i + " (expected tick " + expectedTick
                            + ", got " + e.tick + "):\n" + log);
        }
    }
}

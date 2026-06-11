package com.opensmpsdeck.io.midi;

import javax.sound.midi.*;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** Parses a .mid file into a MidiStem via javax.sound.midi. */
public final class MidiReader {

    private static final int META_TEMPO = 0x51;
    private static final int META_TIME_SIG = 0x58;
    private static final int META_TRACK_NAME = 0x03;
    private static final int DRUM_CHANNEL = 9;

    private MidiReader() {}

    public static MidiStem read(File file) throws IOException {
        Sequence seq;
        try {
            seq = MidiSystem.getSequence(file);
        } catch (InvalidMidiDataException e) {
            throw new IOException("Not a valid MIDI file: " + file.getName(), e);
        }
        if (seq.getDivisionType() != Sequence.PPQ) {
            throw new IOException("SMPTE-division MIDI is not supported: " + file.getName());
        }

        List<MidiStem.TempoEvent> tempoMap = new ArrayList<>();
        MidiStem.TimeSignature timeSig = null;
        String name = stripExtension(file.getName());
        List<MidiStem.MidiNoteTrack> noteTracks = new ArrayList<>();

        for (Track track : seq.getTracks()) {
            // key = channel << 8 | pitch  → (startTick, velocity)
            Map<Integer, long[]> active = new HashMap<>();
            List<NoteEvent> notes = new ArrayList<>();
            Set<Integer> programs = new TreeSet<>();
            boolean drum = false;
            long lastTick = 0;

            for (int i = 0; i < track.size(); i++) {
                MidiEvent ev = track.get(i);
                lastTick = Math.max(lastTick, ev.getTick());
                MidiMessage msg = ev.getMessage();
                if (msg instanceof MetaMessage meta) {
                    if (meta.getType() == META_TEMPO && meta.getData().length == 3) {
                        byte[] d = meta.getData();
                        int usPerQ = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
                        tempoMap.add(new MidiStem.TempoEvent(ev.getTick(), usPerQ));
                    } else if (meta.getType() == META_TIME_SIG && timeSig == null
                            && meta.getData().length >= 2) {
                        byte[] d = meta.getData();
                        timeSig = new MidiStem.TimeSignature(d[0] & 0xFF, 1 << (d[1] & 0xFF));
                    }
                } else if (msg instanceof ShortMessage sm) {
                    int cmd = sm.getCommand();
                    int ch = sm.getChannel();
                    if (cmd == ShortMessage.PROGRAM_CHANGE) {
                        programs.add(sm.getData1());
                    } else if (cmd == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        if (ch == DRUM_CHANNEL) drum = true;
                        int key = (ch << 8) | sm.getData1();
                        long[] prev = active.put(key, new long[]{ev.getTick(), sm.getData2()});
                        if (prev != null) { // same-pitch overlap: close previous at this tick
                            addNote(notes, prev, sm.getData1(), ev.getTick());
                        }
                    } else if (cmd == ShortMessage.NOTE_OFF
                            || (cmd == ShortMessage.NOTE_ON && sm.getData2() == 0)) {
                        int key = (ch << 8) | sm.getData1();
                        long[] on = active.remove(key);
                        if (on != null) addNote(notes, on, sm.getData1(), ev.getTick());
                    }
                }
            }
            // dangling note-ons close at track end
            for (Map.Entry<Integer, long[]> e : active.entrySet()) {
                addNote(notes, e.getValue(), e.getKey() & 0xFF, lastTick);
            }
            if (!notes.isEmpty()) {
                notes.sort(Comparator.comparingLong(NoteEvent::startTick)
                        .thenComparing(Comparator.comparingInt(NoteEvent::pitch).reversed()));
                noteTracks.add(new MidiStem.MidiNoteTrack(drum, programs, List.copyOf(notes)));
            }
        }

        if (timeSig == null) timeSig = new MidiStem.TimeSignature(4, 4);
        tempoMap.sort(Comparator.comparingLong(MidiStem.TempoEvent::tick));
        return new MidiStem(name, seq.getResolution(), List.copyOf(tempoMap), timeSig,
                List.copyOf(noteTracks));
    }

    private static void addNote(List<NoteEvent> notes, long[] on, int pitch, long offTick) {
        long dur = Math.max(1, offTick - on[0]);
        notes.add(new NoteEvent(on[0], dur, pitch, (int) on[1]));
    }

    private static String stripExtension(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}

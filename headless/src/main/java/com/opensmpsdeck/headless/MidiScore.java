package com.opensmpsdeck.headless;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/** A PPQ MIDI score on a common microsecond timeline. Programs are zero-based GM numbers. */
record MidiScore(List<MidiScore.Part> parts, List<MidiScore.Note> notes, List<String> warnings) {

    private static final int TRACK_NAME = 0x03;
    private static final int SET_TEMPO = 0x51;
    private static final int DEFAULT_TEMPO = 500_000;
    private static final int DRUM_CHANNEL = 9;

    private static final List<String> GM_NAMES = List.of(
            "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano",
            "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
            "Celesta", "Glockenspiel", "Music Box", "Vibraphone", "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
            "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ", "Reed Organ", "Accordion",
            "Harmonica", "Tango Accordion",
            "Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)", "Electric Guitar (clean)",
            "Electric Guitar (muted)", "Overdriven Guitar", "Distortion Guitar", "Guitar Harmonics",
            "Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
            "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
            "Violin", "Viola", "Cello", "Contrabass", "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
            "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
            "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
            "Trumpet", "Trombone", "Tuba", "Muted Trumpet", "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
            "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax", "Oboe", "English Horn", "Bassoon", "Clarinet",
            "Piccolo", "Flute", "Recorder", "Pan Flute", "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
            "Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
            "Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass and lead)",
            "Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
            "Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
            "FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
            "FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
            "Sitar", "Banjo", "Shamisen", "Koto", "Kalimba", "Bag Pipe", "Fiddle", "Shanai",
            "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock", "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
            "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet", "Telephone Ring", "Helicopter", "Applause", "Gunshot");

    MidiScore {
        parts = List.copyOf(parts);
        notes = List.copyOf(notes);
        warnings = List.copyOf(warnings);
    }

    record Part(int id, String name, int program, boolean drums) {}

    record Note(int partId, int pitch, int velocity, long startUs, long endUs) {}

    private record Instrument(int channel, int program) {}

    private record Key(int channel, int pitch) {}

    private record ActiveNote(int partId, int velocity, long tick) {}

    /** Accumulated fractional microseconds are carried across tempo boundaries. */
    private record TempoPoint(long tick, long micros, long remainder, int tempo) {
        TempoPoint advance(long nextTick, int nextTempo, int resolution) {
            long delta = nextTick - tick;
            long numerator = (delta % resolution) * tempo + remainder;
            long wholeMicros = Math.multiplyExact(delta / resolution, tempo);
            long nextMicros = Math.addExact(micros, Math.addExact(wholeMicros, numerator / resolution));
            return new TempoPoint(nextTick, nextMicros, numerator % resolution, nextTempo);
        }
    }

    /**
     * Reads type 0 or 1 PPQ MIDI, using the last encountered tempo for events sharing a tick.
     * Parts are allocated on the first attack per track, channel and program. Releases close the
     * oldest attack for the same track, channel and pitch, even if the program has changed.
     * Attacks left open at the end of a track are omitted and reported in warnings.
     */
    static MidiScore read(Path path) throws IOException {
        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(path.toFile());
        } catch (InvalidMidiDataException e) {
            throw new IOException("Invalid MIDI file: " + path, e);
        }
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IOException("Unsupported MIDI SMPTE timing: " + path);
        }
        int resolution = sequence.getResolution();
        if (resolution <= 0) {
            throw new IOException("Invalid MIDI PPQ resolution: " + resolution);
        }

        var parts = new ArrayList<Part>();
        var notes = new ArrayList<Note>();
        var warnings = new ArrayList<String>();
        Track[] tracks = sequence.getTracks();
        try {
            var tempos = tempoMap(tracks, resolution);
            for (int i = 0; i < tracks.length; i++) {
                readTrack(tracks[i], i, resolution, tempos, parts, notes, warnings);
            }
        } catch (ArithmeticException e) {
            throw new IOException("MIDI timeline exceeds the supported microsecond range: " + path, e);
        }
        notes.sort(Comparator.comparingLong(Note::startUs)
                .thenComparingInt(Note::partId)
                .thenComparingInt(Note::pitch));
        return new MidiScore(parts, notes, warnings);
    }

    record GridMetadata(boolean defaultTempo, int meter) {}

    static GridMetadata gridMetadata(Path path) throws IOException {
        try {
            int tempoEvents = 0;
            boolean defaultValue = true;
            int meter = 0;
            for (Track track : MidiSystem.getSequence(path.toFile()).getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    var event = track.get(i);
                    if (!(event.getMessage() instanceof MetaMessage meta)) continue;
                    byte[] data = meta.getData();
                    if (meta.getType() == SET_TEMPO && data.length == 3) {
                        int tempo = (data[0] & 255) << 16 | (data[1] & 255) << 8 | data[2] & 255;
                        if (tempo > 0) {
                            tempoEvents++;
                            defaultValue &= tempo == DEFAULT_TEMPO;
                        }
                    } else if (meta.getType() == 0x58 && event.getTick() == 0 && data.length >= 2
                            && data[1] == 2) {
                        meter = Byte.toUnsignedInt(data[0]);
                    }
                }
            }
            return new GridMetadata(tempoEvents <= 1 && defaultValue, meter);
        } catch (InvalidMidiDataException e) {
            throw new IOException("Invalid MIDI file: " + path, e);
        }
    }

    /** Quarter-note anchors for an explicitly requested warp of a trusted MIDI grid. */
    static ReconstructionBeatMap beatMap(Path path, long durationUs, int meter) throws IOException {
        try {
            var sequence = MidiSystem.getSequence(path.toFile());
            int resolution = sequence.getResolution();
            var tempos = tempoMap(sequence.getTracks(), resolution);
            var beats = new ArrayList<Double>();
            long time = 0;
            for (long tick = 0; time < durationUs; tick += resolution) {
                time = micros(tempos, tick, resolution);
                beats.add(time / 1e6);
                if (beats.size() > 4100)
                    throw new IllegalArgumentException("MIDI tempo produces too many beats for a steady grid");
            }
            return new ReconstructionBeatMap(List.copyOf(beats), 0, meter == 0 ? 4 : meter, 1);
        } catch (InvalidMidiDataException e) {
            throw new IOException("Invalid MIDI file: " + path, e);
        }
    }

    /** Boundaries on the PPQ grid, integrating the same tempo map used for note times. */
    static List<ReconstructionStructure.Bar> bars(Path path, long durationUs) throws IOException {
        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(path.toFile());
        } catch (InvalidMidiDataException e) {
            throw new IOException("Invalid MIDI file: " + path, e);
        }
        int resolution = sequence.getResolution();
        Track[] tracks = sequence.getTracks();
        var tempos = tempoMap(tracks, resolution);
        var meters = new TreeMap<Long, Meter>();
        meters.put(0L, new Meter(4, 4));
        for (Track track : tracks) for (int i = 0; i < track.size(); i++) {
            var event = track.get(i);
            if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x58) {
                byte[] data = meta.getData();
                if (data.length >= 2 && Byte.toUnsignedInt(data[0]) > 0 && Byte.toUnsignedInt(data[1]) <= 7)
                    meters.put(event.getTick(), new Meter(Byte.toUnsignedInt(data[0]), 1 << data[1]));
            }
        }
        var bars = new ArrayList<ReconstructionStructure.Bar>();
        long tick = 0;
        long time = 0;
        while (time < durationUs) {
            if (bars.size() >= S3kRenderer.MAX_PRESENTATIONS)
                throw new IllegalArgumentException("MIDI meter and tempo produce too many bars for the requested interval");
            var meter = meters.floorEntry(tick);
            long unit = (long) resolution * meter.getValue().beats() * 4;
            int denominator = meter.getValue().denominator();
            // Round each boundary from the meter origin to avoid cumulative PPQ rounding.
            long barsSinceMeter = Math.round((tick - meter.getKey()) * (double) denominator / unit);
            long natural = meter.getKey() + Math.max(tick - meter.getKey() + 1,
                    ((barsSinceMeter + 1) * unit + denominator / 2) / denominator);
            Long nextMeter = meters.higherKey(tick);
            long next = nextMeter == null ? natural : Math.min(natural, nextMeter);
            long end = micros(tempos, next, resolution);
            if (end <= time) throw new IllegalArgumentException("MIDI bar duration is below one microsecond");
            bars.add(new ReconstructionStructure.Bar("b" + (bars.size() + 1), time / 1_000_000.0,
                    Math.min(end, durationUs) / 1_000_000.0, end <= durationUs && next == natural));
            tick = next;
            time = end;
        }
        return List.copyOf(bars);
    }

    private record Meter(int beats, int denominator) {}

    private static TreeMap<Long, TempoPoint> tempoMap(Track[] tracks, int resolution) {
        var changes = new TreeMap<Long, Integer>();
        changes.put(0L, DEFAULT_TEMPO);
        for (Track track : tracks) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof MetaMessage meta && meta.getType() == SET_TEMPO) {
                    byte[] data = meta.getData();
                    if (data.length == 3) {
                        int tempo = ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                        if (tempo > 0) {
                            changes.put(event.getTick(), tempo);
                        }
                    }
                }
            }
        }

        var tempos = new TreeMap<Long, TempoPoint>();
        var current = new TempoPoint(0, 0, 0, DEFAULT_TEMPO);
        for (var change : changes.entrySet()) {
            current = current.advance(change.getKey(), change.getValue(), resolution);
            tempos.put(change.getKey(), current);
        }
        return tempos;
    }

    private static void readTrack(Track track, int index, int resolution, TreeMap<Long, TempoPoint> tempos,
                                  List<Part> parts, List<Note> notes, List<String> warnings) {
        String trackName = trackName(track);
        var programs = new int[16];
        var instruments = new HashMap<Instrument, Integer>();
        var active = new LinkedHashMap<Key, ArrayDeque<ActiveNote>>();
        for (int i = 0; i < track.size(); i++) {
            MidiEvent event = track.get(i);
            if (!(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            int channel = message.getChannel();
            int command = message.getCommand();
            int pitch = message.getData1();
            int velocity = message.getData2();
            if (command == ShortMessage.PROGRAM_CHANGE) {
                programs[channel] = message.getData1();
            } else if (command == ShortMessage.NOTE_ON && velocity > 0) {
                var instrument = new Instrument(channel, programs[channel]);
                int partId = instruments.computeIfAbsent(instrument, key -> {
                    int id = parts.size();
                    boolean drums = channel == DRUM_CHANNEL;
                    String name = trackName.isEmpty() ? (drums ? "Drums" : GM_NAMES.get(key.program())) : trackName;
                    parts.add(new Part(id, name, key.program(), drums));
                    return id;
                });
                active.computeIfAbsent(new Key(channel, pitch), key -> new ArrayDeque<>())
                        .addLast(new ActiveNote(partId, velocity, event.getTick()));
            } else if (command == ShortMessage.NOTE_OFF || command == ShortMessage.NOTE_ON) {
                var key = new Key(channel, pitch);
                var attacks = active.get(key);
                if (attacks == null) {
                    continue;
                }
                ActiveNote attack = attacks.removeFirst();
                if (attacks.isEmpty()) {
                    active.remove(key);
                }
                notes.add(new Note(attack.partId(), pitch, attack.velocity(),
                        micros(tempos, attack.tick(), resolution), micros(tempos, event.getTick(), resolution)));
            }
        }
        for (Map.Entry<Key, ArrayDeque<ActiveNote>> entry : active.entrySet()) {
            Key key = entry.getKey();
            for (ActiveNote attack : entry.getValue()) {
                warnings.add("Unclosed MIDI note in track %d, channel %d, pitch %d at tick %d"
                        .formatted(index + 1, key.channel() + 1, key.pitch(), attack.tick()));
            }
        }
    }

    private static long micros(TreeMap<Long, TempoPoint> tempos, long tick, int resolution) {
        TempoPoint point = tempos.floorEntry(tick).getValue();
        return point.advance(tick, point.tempo(), resolution).micros();
    }

    private static String trackName(Track track) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == TRACK_NAME) {
                String name = new String(meta.getData(), UTF_8).strip();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        return "";
    }
}

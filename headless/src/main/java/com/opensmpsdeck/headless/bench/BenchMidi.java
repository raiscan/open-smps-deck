package com.opensmpsdeck.headless.bench;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

final class BenchMidi {
    enum Profile {
        clean, merged, noisy, @com.google.gson.annotations.SerializedName("suno-like") sunoLike;

        @Override public String toString() { return this == sunoLike ? "suno-like" : name(); }
    }
    record Event(String track, boolean drum, double start, double end, int pitch) {}
    private record Message(long tick, int order, int pitch, int channel) {}

    private static final long SEED = 0x53334bL;
    private static final int PPQ = 5_000; // At the MIDI default 120 BPM, one tick is 100 µs.

    private BenchMidi() {}

    static List<Event> events(BenchTimeline.Truth truth, BenchDrums drums, Profile profile, double seconds, int song) {
        return events(truth, drums, profile, seconds, song, Map.of());
    }

    static List<Event> events(BenchTimeline.Truth truth, BenchDrums drums, Profile profile, double seconds, int song,
                              Map<String, String> stemByLane) {
        var target = new ArrayList<Event>();
        var melodic = new ArrayList<Event>();
        for (var lane : truth.channels()) {
            String hardware = lane.kind().equals("noise") ? "PSG3" : lane.kind();
            String track = profile == Profile.clean ? hardware : hardware.startsWith("FM") ? "FM" : "PSG";
            track = stemByLane.getOrDefault(lane.kind(), track);
            for (var note : lane.notes()) {
                if (lane.kind().equals("noise")) {
                    String drumTrack = stemByLane.getOrDefault(lane.kind(), profile == Profile.clean ? hardware : "Drums");
                    target.add(new Event(drumTrack, true, note.start(), note.end(), BenchDrums.Drum.HAT.gm));
                } else {
                    melodic.add(new Event(track, false, note.start(), note.end(), note.pitch()));
                }
            }
            for (var hit : lane.hits()) {
                String drumTrack = stemByLane.getOrDefault(lane.kind(), profile == Profile.clean ? hardware : "Drums");
                target.add(new Event(drumTrack, true, hit.time(), Math.min(seconds, hit.time() + .045),
                        drums.classify(hit.sampleHash(), hit.slot()).gm));
            }
        }
        if (profile != Profile.noisy && profile != Profile.sunoLike) {
            target.addAll(melodic);
        } else {
            var random = new Random(SEED + song);
            var order = new ArrayList<>(melodic);
            java.util.Collections.shuffle(order, random);
            int changes = (int) Math.round(order.size() * .05);
            int doubles = 0;
            for (int i = changes; i < order.size(); i++) {
                var event = order.get(i);
                double shift = random.nextDouble() * .030 - .015;
                double start = Math.clamp(event.start() + shift, 0, Math.max(0, seconds - .0001));
                double end = Math.clamp(event.end() + shift, start + .0001, seconds);
                target.add(new Event(event.track(), false, start, end, event.pitch()));
                if (doubles < changes && event.pitch() <= 115) {
                    target.add(new Event(event.track(), false, start, end, event.pitch() + 12));
                    doubles++;
                }
            }
        }
        return List.copyOf(target);
    }

    static int offsetFrames(int song, double barSeconds, int frames) {
        return 1 + new Random(SEED ^ song).nextInt(Math.max(1, Math.min(frames - 1,
                (int) Math.ceil(barSeconds * 60) - 1)));
    }

    static List<Event> shift(List<Event> events, int offsetFrames, double seconds) {
        double delay = offsetFrames / 60.0;
        return events.stream().filter(e -> e.start() + delay <= seconds - .0001)
                .map(e -> new Event(e.track(), e.drum(), e.start() + delay,
                        Math.min(seconds, e.end() + delay), e.pitch())).toList();
    }

    static void write(List<Event> events, Profile profile, Path target) throws IOException {
        var expectedTracks = profile == Profile.clean
                ? List.of("FM1", "FM2", "FM3", "FM4", "FM5", "FM6-DAC", "PSG1", "PSG2", "PSG3")
                : List.of("FM", "PSG", "Drums");
        write(events, profile, target, expectedTracks, false);
    }

    static void write(List<Event> events, Profile profile, Path target, List<String> expectedTracks,
                      boolean fixedOrder) throws IOException {
        try {
            var midi = new Sequence(Sequence.PPQ, PPQ);
            if (profile == Profile.sunoLike) {
                var tempo = new byte[]{0x07, (byte) 0xa1, 0x20};
                midi.createTrack().add(new MidiEvent(new MetaMessage(0x51, tempo, tempo.length), 0));
            }
            var tracks = new LinkedHashMap<String, List<Message>>();
            if (fixedOrder) expectedTracks.forEach(track -> tracks.put(track, new ArrayList<>()));
            for (var event : events) {
                int channel = event.drum() ? 9 : 0;
                var messages = tracks.computeIfAbsent(event.track(), ignored -> new ArrayList<>());
                long start = Math.round(event.start() * 2 * PPQ);
                long end = Math.max(start + 1, Math.round(event.end() * 2 * PPQ));
                messages.add(new Message(start, 1, event.pitch(), channel));
                messages.add(new Message(end, 0, event.pitch(), channel));
            }
            for (String track : expectedTracks) tracks.computeIfAbsent(track, ignored -> new ArrayList<>());
            for (var entry : tracks.entrySet()) {
                var track = midi.createTrack();
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                track.add(new MidiEvent(new MetaMessage(3, name, name.length), 0));
                entry.getValue().sort(Comparator.comparingLong(Message::tick).thenComparingInt(Message::order)
                        .thenComparingInt(Message::channel).thenComparingInt(Message::pitch));
                for (var event : entry.getValue()) {
                    int command = event.order() == 1 ? ShortMessage.NOTE_ON : ShortMessage.NOTE_OFF;
                    track.add(new MidiEvent(new ShortMessage(command, event.channel(), event.pitch(),
                            event.order() == 1 ? 96 : 0), event.tick()));
                }
            }
            if (MidiSystem.write(midi, 1, target.toFile()) == 0) throw new IOException("Cannot write MIDI: " + target);
        } catch (InvalidMidiDataException failure) {
            throw new IOException("Invalid benchmark MIDI: " + target, failure);
        }
    }
}

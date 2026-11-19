package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

final class S3kReconstruction {
    private static final String NOISE = "psg-noise";
    private static final int FM_LANES = 5;
    private static final int PSG_LANES = 3;
    private static final int CLIP_TICKS = 8 * PRESENTATIONS_PER_SECOND;
    private static final long OCTAVE_START_WINDOW_US = 10_000;
    private static final long OCTAVE_END_WINDOW_US = 35_000;

    enum Change {
        OCTAVE_DOUBLE, PITCH_OUT_OF_RANGE, NO_MATCH, FM_BUSY, PSG_BUSY, SHORTENED, CUT_AT_END,
        DAC_SIMULTANEOUS, DAC_TAIL, HARMONY_THINNED, ROLE_OVERFLOW
    }

    record Reduction(MidiScore.Note note, Change change, @ReconstructionStages.Nullable String id,
                     @ReconstructionStages.Nullable String reason) {
        Reduction(MidiScore.Note note, Change change) { this(note, change, null, null); }
    }
    record Score(List<MidiScore.Part> parts, List<MidiScore.Note> notes, List<Reduction> reductions) {}
    record Result(StudioProject project, int fmNotes, int psgNotes, int dacHits, int echoNotes,
                  List<Reduction> reductions) {}

    private record Event(MidiScore.Note note, int start, int end, int priority, String assetId, int volume) {}

    private static final class Placement {
        final Event event;
        int end;
        Placement(Event event) { this.event = event; end = event.end(); }
    }

    private S3kReconstruction() {}

    static Score prepare(MidiScore input, long durationUs, boolean keepOctaves) {
        List<Reduction> discarded = new ArrayList<>();
        List<MidiScore.Note> prefix = new ArrayList<>();
        for (var note : input.notes()) {
            if (note.startUs() >= durationUs) break;
            if (note.endUs() > durationUs) {
                discarded.add(new Reduction(note, Change.CUT_AT_END));
                prefix.add(new MidiScore.Note(note.partId(), note.pitch(), note.velocity(), note.startUs(), durationUs));
            } else {
                prefix.add(note);
            }
        }
        Set<MidiScore.Note> octaves = keepOctaves ? Set.of() : octaveDoubles(input.parts(), prefix);
        var parts = indexParts(input.parts());
        List<MidiScore.Note> playable = new ArrayList<>();
        for (var note : prefix) {
            if (octaves.contains(note)) {
                discarded.add(new Reduction(note, Change.OCTAVE_DOUBLE));
            } else if (!parts.get(note.partId()).drums() && (note.pitch() < 12 || note.pitch() > 106)) {
                discarded.add(new Reduction(note, Change.PITCH_OUT_OF_RANGE));
            } else {
                playable.add(note);
            }
        }
        return new Score(input.parts(), playable, discarded);
    }

    private static Set<MidiScore.Note> octaveDoubles(List<MidiScore.Part> parts, List<MidiScore.Note> notes) {
        Map<Integer, List<MidiScore.Note>> byPart = new HashMap<>();
        for (var note : notes) byPart.computeIfAbsent(note.partId(), ignored -> new ArrayList<>()).add(note);
        Set<MidiScore.Note> doubles = new HashSet<>();
        for (var lead : parts) {
            if (!isLead(lead)) continue;
            List<MidiScore.Note> leadNotes = byPart.getOrDefault(lead.id(), List.of());
            Map<Integer, List<MidiScore.Note>> byPitch = new HashMap<>();
            for (var note : leadNotes) byPitch.computeIfAbsent(note.pitch(), ignored -> new ArrayList<>()).add(note);
            for (var bass : parts) {
                if (!isBass(bass)) continue;
                var bassNotes = byPart.getOrDefault(bass.id(), List.of());
                Set<MidiScore.Note> pairs = new HashSet<>();
                for (var note : bassNotes) {
                    var octave = byPitch.getOrDefault(note.pitch() + 12, List.of());
                    var partner = simultaneousOctave(octave, note);
                    if (partner != null) pairs.add(partner);
                }
                if (pairs.size() >= 16 && pairs.size() * 5L >= bassNotes.size() * 4L) doubles.addAll(pairs);
            }
        }
        return doubles;
    }

    private static MidiScore.Note simultaneousOctave(List<MidiScore.Note> candidates, MidiScore.Note note) {
        long earliest = note.startUs() - OCTAVE_START_WINDOW_US;
        int low = 0, high = candidates.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (candidates.get(middle).startUs() < earliest) low = middle + 1;
            else high = middle;
        }
        for (int i = low; i < candidates.size(); i++) {
            MidiScore.Note candidate = candidates.get(i);
            if (candidate.startUs() > note.startUs() + OCTAVE_START_WINDOW_US) break;
            if (Math.abs(candidate.endUs() - note.endUs()) <= OCTAVE_END_WINDOW_US) return candidate;
        }
        return null;
    }

    static Result arrange(String name, int ticksPerBeat, int frames, Score score, InstrumentCatalogue catalogue,
                          Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> choices,
                          Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> spillChoices) {
        return arrange(name, ticksPerBeat, frames, score, catalogue, choices, spillChoices, Map.of());
    }

    static Result arrange(String name, int ticksPerBeat, int frames, Score score, InstrumentCatalogue catalogue,
                          Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> choices,
                          Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> spillChoices,
                          Map<Integer, ReconstructionEcho.Estimate> echoes) {
        var parts = indexParts(score.parts());
        var reductions = new ArrayList<>(score.reductions());
        var tones = new ArrayList<Event>();
        var drumHits = new ArrayList<Event>();
        Map<String, Voice> voices = new LinkedHashMap<>();
        Map<String, DacSample> samples = new LinkedHashMap<>();
        Map<String, Envelope> envelopes = new LinkedHashMap<>();
        for (var note : score.notes()) {
            var part = parts.get(note.partId());
            var fallback = choices.get(new ReconstructionVoices.Key(part.id(), part.drums() ? note.pitch() : null));
            var choice = part.drums() ? fallback : choices.getOrDefault(ReconstructionVoices.Key.section(note), fallback);
            if (choice == null || choice.assetId() == null) {
                reductions.add(new Reduction(note, Change.NO_MATCH));
                continue;
            }
            var asset = catalogue.asset(choice.assetId());
            int start = Math.min(frames - 1, toTicks(note.startUs()));
            int end = Math.min(frames, Math.max(start + 1, toTicks(note.endUs())));
            if (part.drums()) {
                String sampleId = asset.id() + "-" + choice.sampleRate();
                samples.computeIfAbsent(sampleId, id -> DacSample.of(id, choice.name(),
                        choice.sampleRate(), asset.bytes()));
                drumHits.add(new Event(note, start, start + 1, drumPriority(note.pitch()), sampleId, 0));
            } else {
                voices.computeIfAbsent(asset.id(), id -> {
                    return new Voice(id, part.name() + " / " + choice.name(), unsigned(asset.bytes()));
                });
                tones.add(new Event(note, start, end, tonePriority(part, note.pitch()), asset.id(), volume(part, note)));
            }
        }
        List<Event> unplacedFm = new ArrayList<>();
        List<List<Placement>> fm = allocateTones(tones, FM_LANES, reductions, unplacedFm);
        List<Event> spill = new ArrayList<>();
        for (var event : unplacedFm) {
            var choice = spillChoices.get(new ReconstructionVoices.Key(event.note().partId(), null));
            if (choice == null || choice.assetId() == null || event.note().pitch() < 45) {
                reductions.add(new Reduction(event.note(), Change.FM_BUSY));
                continue;
            }
            String id = choice.assetId();
            envelopes.computeIfAbsent(id, key -> new Envelope(key, choice.name(), Envelope.PSG_VOLUME,
                    unsigned(catalogue.asset(key).bytes())));
            spill.add(new Event(event.note(), event.start(), event.end(), event.priority(), id,
                    Math.max(1, event.volume() - 24)));
        }
        var unplacedPsg = new ArrayList<Event>();
        List<List<Placement>> psg = allocateTones(spill, PSG_LANES, reductions, unplacedPsg);
        unplacedPsg.forEach(event -> reductions.add(new Reduction(event.note(), Change.PSG_BUSY)));
        int fmNotes = fm.stream().mapToInt(List::size).sum();
        int echoNotes = addEchoes(fm, echoes, frames, 0);
        List<Event> dac = allocateDac(drumHits, reductions);
        if (fm.stream().allMatch(List::isEmpty) && dac.isEmpty())
            throw new IllegalArgumentException("No MIDI notes had a playable catalogue match in the requested interval");
        Set<String> usedVoices = new HashSet<>();
        for (var lane : fm) for (var placement : lane) usedVoices.add(placement.event.assetId());
        Set<String> usedSamples = new HashSet<>();
        for (var hit : dac) usedSamples.add(hit.assetId());
        List<Sequence> sequences = new ArrayList<>();
        List<Clip> clips = new ArrayList<>();
        var repeats = ReconstructionClipLinks.from(null);
        for (int i = 0; i < fm.size(); i++) writeToneLane("FM", "FM" + (i + 1), fm.get(i), parts, sequences, clips, repeats);
        for (int i = 0; i < psg.size(); i++) writeToneLane("PSG", "PSG" + (i + 1), psg.get(i), parts, sequences, clips, repeats);
        writeDacLane(dac, sequences, clips, repeats);
        StudioProject project = new StudioProject(4, name, "S3K", ticksPerBeat, 0,
                voices.values().stream().filter(voice -> usedVoices.contains(voice.id())).toList(),
                sequences, clips, List.copyOf(envelopes.values()),
                samples.values().stream().filter(sample -> usedSamples.contains(sample.id())).toList());
        return new Result(project, fmNotes, psg.stream().mapToInt(List::size).sum(),
                dac.size(), echoNotes, List.copyOf(reductions));
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes) {
        var parts = indexParts(score.parts());
        var reductions = new ArrayList<>(score.reductions());
        var fm = ReconstructionAllocation.FM.stream().map(lane -> new ArrayList<Placement>()).toList();
        var psg = ReconstructionAllocation.PSG.stream().map(lane -> new ArrayList<Placement>()).toList();
        var dac = new ArrayList<Event>();
        Map<String, Voice> voices = new LinkedHashMap<>();
        Map<String, DacSample> samples = new LinkedHashMap<>();
        Map<String, Envelope> envelopes = new LinkedHashMap<>();
        for (var entry : allocation.notes()) {
            var note = entry.note().midi();
            var part = parts.get(note.partId());
            String lane = entry.lane();
            if (entry.outcome() != ReconstructionAllocation.Outcome.kept) {
                reductions.add(new Reduction(note, allocationChange(entry), entry.note().id(), entry.reason()));
            }
            if (lane == null) continue;
            int start = entry.startTick();
            int end = entry.endTick();
            if (entry.role() == ReconstructionRoles.Role.drums && lane.equals("PSG3")) {
                var pitched = new MidiScore.Note(note.partId(), 81, note.velocity(), note.startUs(), note.endUs());
                psg.get(2).add(new Placement(new Event(pitched, start, end, 0, NOISE, volume(part, note))));
                continue;
            }
            var choices = matches.getOrDefault(lane, Map.of());
            var fallback = choices.get(new ReconstructionVoices.Key(part.id(), part.drums() ? note.pitch() : null));
            var choice = part.drums() ? fallback : choices.getOrDefault(ReconstructionVoices.Key.section(note), fallback);
            if (choice == null || choice.assetId() == null) {
                reductions.add(new Reduction(note, Change.NO_MATCH, entry.note().id(), "no-playable-catalogue-match"));
                continue;
            }
            var asset = catalogue.asset(choice.assetId());
            if (lane.equals("DAC")) {
                String id = asset.id() + "-" + choice.sampleRate();
                samples.computeIfAbsent(id, key -> DacSample.of(key, choice.name(), choice.sampleRate(), asset.bytes()));
                dac.add(new Event(note, start, end, drumPriority(note.pitch()), id, 0));
            } else if (lane.startsWith("FM")) {
                voices.computeIfAbsent(asset.id(), id -> new Voice(id, part.name() + " / " + choice.name(), unsigned(asset.bytes())));
                fm.get(ReconstructionAllocation.FM.indexOf(lane)).add(
                        new Placement(new Event(note, start, end, tonePriority(part, note.pitch()), asset.id(), volume(part, note))));
            } else {
                envelopes.computeIfAbsent(asset.id(), id -> new Envelope(id, choice.name(), Envelope.PSG_VOLUME, unsigned(asset.bytes())));
                psg.get(ReconstructionAllocation.PSG.indexOf(lane)).add(
                        new Placement(new Event(note, start, end, tonePriority(part, note.pitch()), asset.id(),
                                Math.max(1, volume(part, note) - 24))));
            }
        }
        int fmNotes = fm.stream().mapToInt(List::size).sum();
        int psgNotes = psg.stream().mapToInt(List::size).sum();
        if (fmNotes + psgNotes + dac.size() == 0)
            throw new IllegalArgumentException("No allocated notes had a playable catalogue match in the requested interval");
        int echoNotes = addEchoes(fm, echoes, frames, 2);
        List<Sequence> sequences = new ArrayList<>();
        List<Clip> clips = new ArrayList<>();
        var repeats = ReconstructionClipLinks.from(structure);
        for (int i = 0; i < fm.size(); i++)
            writeToneLane("FM", ReconstructionAllocation.FM.get(i), fm.get(i), parts, sequences, clips, repeats);
        for (int i = 0; i < psg.size(); i++)
            writeToneLane("PSG", ReconstructionAllocation.PSG.get(i), psg.get(i), parts, sequences, clips, repeats);
        writeDacLane(dac, sequences, clips, repeats);
        var project = new StudioProject(4, name, "S3K", ticksPerBeat, 0, List.copyOf(voices.values()),
                sequences, clips, List.copyOf(envelopes.values()), List.copyOf(samples.values()));
        return new Result(project, fmNotes, psgNotes, dac.size(), echoNotes, List.copyOf(reductions));
    }

    private static Change allocationChange(ReconstructionAllocation.Entry entry) {
        if (entry.outcome() == ReconstructionAllocation.Outcome.shortened) return Change.SHORTENED;
        return switch (entry.reason()) {
            case "rapid-inner-tone-reattack", "isolated-harmony-octave-double" -> Change.HARMONY_THINNED;
            case "simultaneous-dac-hit" -> Change.DAC_SIMULTANEOUS;
            case "protect-dac-tail" -> Change.DAC_TAIL;
            case "fm-lanes-busy" -> Change.FM_BUSY;
            case "fm-and-psg-lanes-busy", "psg-noise-lane-busy" -> Change.PSG_BUSY;
            default -> Change.ROLE_OVERFLOW;
        };
    }

    private static int addEchoes(List<? extends List<Placement>> lanes, Map<Integer, ReconstructionEcho.Estimate> estimates,
                                 int frames, int firstEchoLane) {
        if (estimates.values().stream().noneMatch(e -> e.delayTicks() != null)) return 0;
        var dry = lanes.stream().flatMap(List::stream).sorted(Comparator.comparingInt(p -> p.event.start())).toList();
        int count = 0;
        for (var original : dry) {
            if (count >= 128) break;
            Event event = original.event;
            var estimate = estimates.get(event.note().partId());
            int duration = original.end - event.start();
            if (estimate == null || estimate.delayTicks() == null || duration < 2
                    || duration >= estimate.delayTicks() - 1) continue;
            int start = event.start() + estimate.delayTicks();
            int end = Math.min(start + duration, frames);
            if (end <= start) continue;
            int attenuation = (int) Math.round(-20 * Math.log10(estimate.amplitudeRatio()) / .75);
            var echo = new Event(event.note(), start, end, event.priority(), event.assetId(),
                    Math.max(1, event.volume() - attenuation));
            for (var lane : lanes.subList(firstEchoLane, lanes.size()).reversed()) {
                int low = 0, high = lane.size();
                while (low < high) {
                    int mid = (low + high) >>> 1;
                    if (lane.get(mid).event.start() < start) low = mid + 1;
                    else high = mid;
                }
                if (low > 0 && lane.get(low - 1).end + 1 >= start) continue;
                if (low < lane.size() && lane.get(low).event.start() <= end + 1) continue;
                lane.add(low, new Placement(echo));
                count++;
                break;
            }
        }
        return count;
    }

    private static final Comparator<Event> ORDER = Comparator.comparingInt(Event::start)
            .thenComparing(Comparator.comparingInt(Event::priority).reversed())
            .thenComparing(Comparator.comparingInt((Event e) -> e.note().velocity()).reversed())
            .thenComparingInt(e -> e.note().partId()).thenComparingInt(e -> e.note().pitch());

    private static List<List<Placement>> allocateTones(List<Event> tones, int laneCount,
                                                        List<Reduction> reductions, List<Event> unplaced) {
        List<List<Placement>> lanes = new ArrayList<>();
        for (int i = 0; i < laneCount; i++) lanes.add(new ArrayList<>());
        tones.sort(ORDER);
        for (var note : tones) {
            List<Placement> free = null;
            List<Placement> replace = null;
            for (var lane : lanes) {
                Placement prev = lane.isEmpty() ? null : lane.getLast();
                if (prev == null || prev.end <= note.start()) {
                    if (free == null) free = lane;
                    if (prev != null && prev.event.assetId().equals(note.assetId())) {
                        free = lane;
                        break;
                    }
                } else if (prev.event.start() < note.start() && prev.event.priority() < note.priority()
                        && (replace == null || prev.event.priority() < replace.getLast().event.priority())) {
                    replace = lane;
                }
            }
            if (free != null) {
                free.add(new Placement(note));
            } else if (replace != null) {
                Placement prev = replace.getLast();
                prev.end = note.start();
                reductions.add(new Reduction(prev.event.note(), Change.SHORTENED));
                replace.add(new Placement(note));
            } else {
                unplaced.add(note);
            }
        }
        return lanes;
    }

    private static List<Event> allocateDac(List<Event> hits, List<Reduction> reductions) {
        List<Event> picked = new ArrayList<>();
        hits.sort(ORDER);
        for (var hit : hits) {
            var previous = picked.isEmpty() ? null : picked.getLast();
            if (previous != null && hit.start() == previous.start()) {
                reductions.add(new Reduction(hit.note(), Change.DAC_SIMULTANEOUS));
            } else if (previous != null && previous.priority() >= 2 && hit.priority() < previous.priority()
                    && hit.start() - previous.start() < 5) {
                reductions.add(new Reduction(hit.note(), Change.DAC_TAIL));
            } else {
                picked.add(hit);
            }
        }
        return picked;
    }

    private static void writeToneLane(String chip, String lane, List<Placement> notes,
                                      Map<Integer, MidiScore.Part> parts, List<Sequence> sequences, List<Clip> clips,
                                      ReconstructionClipLinks links) {
        boolean fm = chip.equals("FM");
        NativeCommand selectAsset = fm ? NativeCommand.FM_VOICE : NativeCommand.PSG_ENVELOPE;
        var spans = links.indexes(notes.size(), i -> notes.get(i).event.start(), i -> notes.get(i).end);
        int start = 0;
        while (start < notes.size()) {
            Event first = notes.get(start).event;
            int from = first.start();
            int end = start + 1;
            var span = spans.get(start);
            boolean noise = NOISE.equals(first.assetId());
            while (end < notes.size() && spans.get(end) == span
                    && (span != null || notes.get(end).event.start() - from < CLIP_TICKS)
                    && noise == NOISE.equals(notes.get(end).event.assetId())) end++;
            int length = notes.get(end - 1).end - from;
            String id = lane + "-" + from;
            var events = new ArrayList<NoteEvent>();
            var commands = new ArrayList<CommandEvent>();
            String activeAsset = first.assetId();
            int volume = first.volume();
            if (noise) commands.add(new CommandEvent("noise", 0, NativeCommand.PSG_NOISE, List.of(0xe7)));
            for (int i = start; i < end; i++) {
                Placement placement = notes.get(i);
                Event note = placement.event;
                int offset = note.start() - from;
                if (!activeAsset.equals(note.assetId())) {
                    activeAsset = note.assetId();
                    commands.add(new CommandEvent("asset-" + offset, offset, selectAsset, List.of(), activeAsset));
                }
                if (volume != note.volume()) {
                    volume = note.volume();
                    commands.add(new CommandEvent("volume-" + offset, offset, NativeCommand.VOLUME, List.of(volume)));
                }
                events.add(new NoteEvent("note-" + offset, offset, placement.end - note.start(), note.note().pitch()));
            }
            var names = notes.subList(start, end).stream()
                    .map(p -> parts.get(p.event.note().partId()).name()).distinct().toList();
            var source = new Sequence(id, String.join(" / ", names), chip, length, fm ? first.assetId() : null,
                    first.volume(), events, commands, "RESET", fm || noise ? null : first.assetId(), null);
            links.add(source, lane, from, span, sequences, clips);
            start = end;
        }
    }

    private static void writeDacLane(List<Event> hits, List<Sequence> sequences, List<Clip> clips,
                                     ReconstructionClipLinks links) {
        var spans = links.indexes(hits.size(), i -> hits.get(i).start(), i -> hits.get(i).end());
        int start = 0;
        while (start < hits.size()) {
            int from = hits.get(start).start();
            int end = start + 1;
            var span = spans.get(start);
            while (end < hits.size() && spans.get(end) == span
                    && (span != null || hits.get(end).start() - from < CLIP_TICKS)) end++;
            int length = hits.get(end - 1).end() - from;
            String id = "DAC-" + from;
            var triggers = hits.subList(start, end).stream()
                    .map(hit -> new SampleTrigger("hit-" + (hit.start() - from), hit.start() - from, hit.assetId())).toList();
            var source = new Sequence(id, "Drums", "DAC", length, null, 127,
                    List.of(), List.of(), "RESET", null, null, triggers);
            links.add(source, "DAC", from, span, sequences, clips);
            start = end;
        }
    }

    private static List<Integer> unsigned(byte[] bytes) {
        return IntStream.range(0, bytes.length).mapToObj(i -> Byte.toUnsignedInt(bytes[i])).toList();
    }

    static int toTicks(long micros) {
        return (int) ((micros * PRESENTATIONS_PER_SECOND + 500_000) / 1_000_000);
    }

    private static Map<Integer, MidiScore.Part> indexParts(List<MidiScore.Part> parts) {
        Map<Integer, MidiScore.Part> result = new HashMap<>();
        for (var part : parts) result.put(part.id(), part);
        return result;
    }

    private static boolean isBass(MidiScore.Part part) {
        return !part.drums() && part.program() >= 32 && part.program() <= 39;
    }

    private static boolean isLead(MidiScore.Part part) {
        return !part.drums() && part.program() >= 80 && part.program() <= 87;
    }

    private static int tonePriority(MidiScore.Part part, int pitch) {
        if (isBass(part)) return 400 - pitch;
        if (isLead(part)) return (pitch >= 60 ? 210 : 100) + pitch;
        return 200 + pitch;
    }

    static int drumPriority(int pitch) {
        return switch (pitch) {
            case 35, 36 -> 3;
            case 38, 40 -> 2;
            default -> 1;
        };
    }

    private static int volume(MidiScore.Part part, MidiScore.Note note) {
        int attenuation = (int) Math.round(-20.0 * Math.log10(note.velocity() / 127.0) / 1.5) * 2;
        int headroom = isBass(part) ? 4 : isLead(part) ? 6 : 10;
        return Math.max(1, 127 - attenuation - headroom);
    }
}

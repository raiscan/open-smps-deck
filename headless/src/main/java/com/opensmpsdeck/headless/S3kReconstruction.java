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
                  List<Reduction> reductions, List<ReconstructionBendEncoding.Applied> bends,
                  List<VoicedGate> voicedGates, int assignmentClipCost) {
        Result(StudioProject project, int fmNotes, int psgNotes, int dacHits, int echoNotes,
               List<Reduction> reductions, List<ReconstructionBendEncoding.Applied> bends, List<VoicedGate> voicedGates) {
            this(project, fmNotes, psgNotes, dacHits, echoNotes, reductions, bends, voicedGates, 0);
        }
    }
    record VoicedGate(String id, String lane, int pitch, int startTick, int endTick) {}

    private record Event(MidiScore.Note note, int start, int end, int priority, String assetId, int volume) {}

    private record Tone(String asset, int volume, ReconstructionVoicing.Vibrato vibrato) {}

    private static final class Placement {
        final Event event;
        int end;
        int detune;
        ReconstructionVoicing.PsgPercussion hatClock;
        int volumeFloor;
        boolean assignedInstrument;
        ReconstructionVoicing.Vibrato vibrato;
        ReconstructionBendEncoding.Fit bend;
        boolean pitchedNoise;
        java.util.NavigableMap<Integer, Tone> releaseTones = java.util.Collections.emptyNavigableMap();
        java.util.NavigableMap<Integer, Tone> scopedTones = java.util.Collections.emptyNavigableMap();

        Tone baseTone() { return new Tone(event.assetId(), event.volume(), vibrato); }
        Tone attackTone() { return scopedTones.getOrDefault(event.start(), baseTone()); }
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
                    psgAttenuation(catalogue.asset(key).bytes())));
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
                dac.size(), echoNotes, List.copyOf(reductions), List.of(), List.of());
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes) throws java.io.IOException {
        return arrangeAllocated(name, ticksPerBeat, frames, score, allocation, structure, catalogue,
                matches, echoes, Map.of(), false);
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes,
                                   Map<String, ReconstructionVoicing.Lane> voicing, boolean copies) throws java.io.IOException {
        return arrangeAllocated(name, ticksPerBeat, frames, score, allocation, structure, catalogue,
                matches, echoes, voicing, copies, false, Map.of(), Map.of(), Map.of());
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes,
                                   Map<String, ReconstructionVoicing.Lane> voicing, boolean copies, boolean bends,
                                   Map<String, List<ReconstructionOverrides.Span>> scopes, Map<String, String> origins,
                                   Map<String, Double> scopedDrumGains)
            throws java.io.IOException {
        return arrangeAllocated(name, ticksPerBeat, frames, score, allocation, structure, catalogue,
                matches, echoes, voicing, copies, bends, scopes, origins, scopedDrumGains, null);
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes,
                                   Map<String, ReconstructionVoicing.Lane> voicing, boolean copies, boolean bends,
                                   Map<String, List<ReconstructionOverrides.Span>> scopes, Map<String, String> origins,
                                   Map<String, Double> scopedDrumGains, ReconstructionClipLinks.Layout layout)
            throws java.io.IOException {
        return arrangeAllocated(name, ticksPerBeat, frames, score, allocation, structure, catalogue,
                matches, echoes, voicing, copies, bends, scopes, origins, scopedDrumGains, layout, Map.of());
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes,
                                   Map<String, ReconstructionVoicing.Lane> voicing, boolean copies, boolean bends,
                                   Map<String, List<ReconstructionOverrides.Span>> scopes, Map<String, String> origins,
                                   Map<String, Double> scopedDrumGains, ReconstructionClipLinks.Layout layout,
                                   Map<String, ReconstructionVoicePlan.Assignment> assignments)
            throws java.io.IOException {
        var applied = new ArrayList<ReconstructionBendEncoding.Applied>();
        var gates = new ArrayList<VoicedGate>();
        var parts = indexParts(score.parts());
        var reductions = new ArrayList<>(score.reductions());
        var fm = ReconstructionAllocation.FM.stream().map(lane -> new ArrayList<Placement>()).toList();
        var psg = ReconstructionAllocation.PSG.stream().map(lane -> new ArrayList<Placement>()).toList();
        var dac = new ArrayList<Event>();
        Map<String, Voice> voices = new LinkedHashMap<>();
        Map<String, DacSample> samples = new LinkedHashMap<>();
        Map<String, Envelope> envelopes = new LinkedHashMap<>();
        var psg3 = voicing.get("PSG3");
        var percussion = psg3 == null || psg3.psgPercussion() == null
                ? ReconstructionVoicing.PSG_PERCUSSION : psg3.psgPercussion();
        var hatClock = percussion.withHatDefaults();
        for (var entry : allocation.notes()) {
            var note = entry.note().midi();
            var part = parts.get(note.partId());
            String lane = entry.lane();
            if (entry.outcome() != ReconstructionAllocation.Outcome.kept) {
                reductions.add(new Reduction(note, allocationChange(entry), entry.note().id(), entry.reason()));
            }
            if (lane == null) continue;
            var voice = voicing.get(lane);
            int start = entry.startTick();
            int end = entry.endTick();
            String noteId = entry.note().id();
            String origin = origins.getOrDefault(noteId, noteId);
            var assignment = assignments.get(origin);
            if (entry.role() == ReconstructionRoles.Role.drums && lane.equals("PSG3")) {
                boolean decaying = assignment == null ? ReconstructionCymbals.voiced(entry)
                        : !assignment.instrument().equals("fixed");
                boolean closedHat = decaying && (assignment == null ? ReconstructionCymbals.closedHat(note.pitch())
                        : assignment.instrument().equals("closed-hat"));
                int pitch = decaying ? ReconstructionCymbals.PITCH : 81;
                var pitched = new MidiScore.Note(note.partId(), pitch, note.velocity(), note.startUs(), note.endUs());
                int level = closedHat ? ReconstructionCymbals.HAT_LEVEL : ReconstructionCymbals.NATIVE_LEVEL;
                int volume = decaying ? level << 3 : volume(part, note);
                var envelope = decaying ? ReconstructionCymbals.envelope(closedHat ? 42 : 46) : null;
                String asset = envelope == null ? NOISE : envelope.id();
                if (envelope != null) envelopes.putIfAbsent(asset, envelope);
                if (voice != null) volume = psgDrumVolume(volume, voice.drumGain(note.pitch()));
                var placed = new Placement(new Event(pitched, start, end, 0, asset, volume));
                placed.assignedInstrument = assignment != null;
                if (closedHat) {
                    placed.detune = ReconstructionCymbals.detune(hatClock.hatAttackPeriod());
                    placed.hatClock = hatClock;
                }
                psg.get(2).add(placed);
                gates.add(new VoicedGate(entry.note().id(), lane, pitched.pitch(), start, end));
                continue;
            }
            var choices = matches.getOrDefault(lane, Map.of());
            var fallback = choices.get(new ReconstructionVoices.Key(part.id(), part.drums() ? note.pitch() : null));
            var choice = assignment == null
                    ? (part.drums() ? fallback : choices.getOrDefault(ReconstructionVoices.Key.section(note), fallback))
                    : assignedChoice(catalogue, assignment);
            if (choice == null || choice.assetId() == null) {
                reductions.add(new Reduction(note, Change.NO_MATCH, entry.note().id(), "no-playable-catalogue-match"));
                continue;
            }
            if (lane.equals("DAC")) {
                var slice = choice.slice();
                String id = slice == null ? choice.assetId() + "-" + choice.sampleRate() : slice.id();
                double gain = scopedDrumGains.getOrDefault(entry.note().id(),
                        voice == null ? 1 : voice.drumGain(note.pitch()));
                String gainedId = gain == 1 ? id : id + "-gain-" + Double.toHexString(gain);
                samples.computeIfAbsent(gainedId, key -> {
                    if (gain == 1 && slice != null) return slice;
                    byte[] pcm = slice == null ? catalogue.asset(choice.assetId()).bytes() : slice.pcm();
                    return DacSample.of(key, choice.name(), choice.sampleRate(), scalePcm(pcm, gain));
                });
                dac.add(new Event(note, start, end, drumPriority(note.pitch()), gainedId, 0));
                gates.add(new VoicedGate(entry.note().id(), lane, note.pitch(), start, end));
            } else {
                var asset = catalogue.asset(choice.assetId());
                boolean isFm = lane.startsWith("FM");
                var articulation = voice == null ? ReconstructionVoicing.WRITTEN : voice.articulation();
                String id = asset.id();
                if (isFm) {
                    byte[] patch = assignment == null ? ReconstructionVoicing.patchBytes(asset, articulation) : asset.bytes();
                    id = ReconstructionVoicing.patchId(asset, patch);
                    voices.computeIfAbsent(id, key -> new Voice(key, part.name() + " / " + choice.name(), unsigned(patch)));
                } else {
                    envelopes.computeIfAbsent(id, key -> new Envelope(key, choice.name(), Envelope.PSG_VOLUME, psgAttenuation(asset.bytes())));
                }
                int duration = voicedDuration(entry, articulation);
                int volume = Math.max(1, volume(part, note) - (isFm ? 0 : 24));
                int floor = isFm && voice != null ? ReconstructionEcho.fmVolumeFloor(asset.bytes()) : 0;
                if (voice != null) volume = Math.max(floor, ReconstructionEcho.scaleVolume(volume, voice.gainFor(part.id())));
                boolean pitchedNoise = entry.laneType() == ReconstructionLaneType.NOISE;
                var playedNote = pitchedNoise ? new MidiScore.Note(note.partId(), ReconstructionCymbals.PITCH,
                        note.velocity(), note.startUs(), note.endUs()) : note;
                var placed = new Placement(new Event(playedNote, start, start + duration,
                        tonePriority(part, note.pitch()), id, volume));
                placed.pitchedNoise = pitchedNoise;
                placed.assignedInstrument = assignment != null;
                if (voice != null) {
                    placed.volumeFloor = floor;
                    placed.detune = voice.effects().detune();
                    placed.vibrato = voice.effects().vibrato();
                }
                if (pitchedNoise) placed.detune = ReconstructionPitchedNoise.detune(note.pitch());
                var spans = scopes.get(origin);
                if (spans != null) {
                    var attack = spans.stream().filter(span -> span.start() <= start && span.end() > start
                            && span.settings().gate() != null).findFirst().orElse(null);
                    if (attack != null) {
                        duration = new ReconstructionVoicing.Articulation("scoped", attack.settings().gate(), 0)
                                .duration(end - start);
                        placed.end = start + duration;
                    }
                    placed.scopedTones = new java.util.TreeMap<>();
                    placed.releaseTones = new java.util.TreeMap<>();
                    var changes = ReconstructionOverrides.changes(spans, placed.end);
                    var active = changes.floorEntry(start);
                    if (active != null && active.getValue() != null) changes.put(start, active.getValue());
                    for (var change : changes.tailMap(start, true).entrySet()) {
                        int boundary = change.getKey();
                        var tones = boundary < placed.end ? placed.scopedTones : placed.releaseTones;
                        var scoped = change.getValue();
                        if (scoped == null) {
                            tones.put(boundary, placed.baseTone());
                            continue;
                        }
                        var selected = assignment != null || scoped.variantId() == null ? asset : catalogue.match(scoped.variantId()).asset();
                        String selectedId = selected.id();
                        if (isFm) {
                            var activeArt = scoped.minimumReleaseRate() == null ? articulation
                                    : new ReconstructionVoicing.Articulation("scoped", articulation.gate(), scoped.minimumReleaseRate());
                            var patch = assignment == null ? ReconstructionVoicing.patchBytes(selected, activeArt) : selected.bytes();
                            selectedId = ReconstructionVoicing.patchId(selected, patch);
                            voices.putIfAbsent(selectedId, new Voice(selectedId, selected.name(), unsigned(patch)));
                        } else {
                            envelopes.putIfAbsent(selectedId, new Envelope(selectedId, selected.name(),
                                    Envelope.PSG_VOLUME, psgAttenuation(selected.bytes())));
                        }
                        int scopedVolume = volume;
                        if (scoped.gain() != null || !selected.id().equals(asset.id())) {
                            int selectedFloor = isFm ? ReconstructionEcho.fmVolumeFloor(selected.bytes()) : 0;
                            double gain = scoped.gain() == null ? voice.gain() : scoped.gain();
                            int raw = Math.max(1, volume(part, note) - (isFm ? 0 : 24));
                            scopedVolume = Math.max(selectedFloor, ReconstructionEcho.scaleVolume(raw, voice.gainFor(part.id(), gain)));
                        }
                        var vibrato = scoped.changesModulation() ? scoped.vibrato() : placed.vibrato;
                        tones.put(boundary, new Tone(selectedId, scopedVolume, vibrato));
                    }
                }
                var curve = entry.note().bend();
                if (bends && curve != null && (lane.equals("FM1") || isFm && curve.editReason() != null)) {
                    var changes = new java.util.TreeMap<Integer, ReconstructionVoicing.Vibrato>();
                    var current = placed.attackTone().vibrato();
                    for (var change : placed.scopedTones.tailMap(start, false).entrySet()) {
                        var next = change.getValue().vibrato();
                        if (!java.util.Objects.equals(current, next)) changes.put(change.getKey() - start, next);
                        current = next;
                    }
                    placed.bend = ReconstructionBendEncoding.fit(entry.note(), start, duration, placed.detune,
                            voices.get(placed.attackTone().asset()), placed.attackTone().vibrato(), changes);
                    if (placed.bend != null) applied.add(new ReconstructionBendEncoding.Applied(entry.note().id(),
                            lane, start, placed.end, placed.bend.mode(), placed.bend.rmseCents(),
                            curve.editReason() == null ? ReconstructionBends.REASON : curve.editReason()));
                }
                // Record after scoped gate overrides so the trace reports the voiced length.
                gates.add(new VoicedGate(entry.note().id(), lane, note.pitch(), start, placed.end));
                if (isFm) fm.get(ReconstructionAllocation.FM.indexOf(lane)).add(placed);
                else psg.get(ReconstructionAllocation.PSG.indexOf(lane)).add(placed);
            }
        }
        int fmNotes = fm.stream().mapToInt(List::size).sum();
        int psgNotes = psg.stream().mapToInt(List::size).sum();
        if (fmNotes + psgNotes + dac.size() == 0)
            throw new IllegalArgumentException("No allocated notes had a playable catalogue match in the requested interval");
        int echoNotes = addEchoes(fm, echoes, frames, 2);
        if (copies) echoNotes += addVoicingCopies(fm, voicing, frames);
        List<Sequence> sequences = new ArrayList<>();
        List<Clip> clips = new ArrayList<>();
        var repeats = ReconstructionClipLinks.from(structure, layout);
        int assignmentClipCost = 0;
        for (int i = 0; i < fm.size(); i++)
            assignmentClipCost += writeToneLane("FM", ReconstructionAllocation.FM.get(i), fm.get(i), parts, sequences, clips, repeats);
        for (int i = 0; i < psg.size(); i++)
            assignmentClipCost += writeToneLane("PSG", ReconstructionAllocation.PSG.get(i), psg.get(i), parts, sequences, clips, repeats);
        writeDacLane(dac, sequences, clips, repeats);
        if (!scopes.isEmpty()) {
            var used = new HashSet<String>();
            for (var sequence : sequences) {
                used.add(sequence.voiceId());
                sequence.commands().stream().filter(command -> command.command() == NativeCommand.FM_VOICE)
                        .forEach(command -> used.add(command.assetId()));
            }
            voices.keySet().retainAll(used);
            if (voices.size() > 128) {
                throw new IllegalArgumentException("voicing.json: scoped voicing requires " + voices.size()
                        + " distinct FM voices (S3K local bank limit is 128)");
            }
        }
        var project = new StudioProject(4, name, "S3K", ticksPerBeat, 0, List.copyOf(voices.values()),
                sequences, clips, List.copyOf(envelopes.values()), List.copyOf(samples.values()));
        return new Result(project, fmNotes, psgNotes, dac.size(), echoNotes, List.copyOf(reductions), List.copyOf(applied), List.copyOf(gates), assignmentClipCost);
    }

    private static ReconstructionVoices.Choice assignedChoice(InstrumentCatalogue catalogue,
                                                               ReconstructionVoicePlan.Assignment assignment) {
        var match = catalogue.match(assignment.instrument());
        return new ReconstructionVoices.Choice(match.asset().id(), match.variant().id(), match.asset().name(),
                match.variant().recipe().rate(), "voice-assignment", List.of(), List.of());
    }

    static int voicedDuration(ReconstructionAllocation.Entry entry, ReconstructionVoicing.Articulation articulation) {
        int ticks = entry.endTick() - entry.startTick();
        var bend = entry.note().bend();
        if (bend != null && Boolean.TRUE.equals(bend.glide()) || "DAC".equals(entry.lane()) || ("PSG3".equals(entry.lane()) && entry.role() == ReconstructionRoles.Role.drums)
                || entry.note().source().equals(ReconstructionFastRuns.REASON)
                || entry.note().source().equals(ReconstructionRetriggers.REASON)) return ticks;
        return articulation.duration(ticks);
    }

    private static int addVoicingCopies(List<ArrayList<Placement>> fm,
                                      Map<String, ReconstructionVoicing.Lane> voicing, int frames) {
        int count = 0;
        for (String lane : ReconstructionAllocation.FM) {
            var voice = voicing.get(lane);
            if (voice == null || voice.effects().copy() == null) continue;
            var copy = voice.effects().copy();
            var destination = fm.get(ReconstructionAllocation.FM.indexOf(copy.lane()));
            var source = fm.get(ReconstructionAllocation.FM.indexOf(lane));
            for (var note : source) {
                int start = note.event.start() + copy.delay();
                if (start >= frames) break;
                int end = Math.min(frames, (note.scopedTones.isEmpty() ? note.end : note.event.end()) + copy.delay());
                var event = note.event;
                var placed = new Placement(new Event(event.note(), start, end, event.priority(), event.assetId(),
                        Math.max(note.volumeFloor, ReconstructionEcho.scaleVolume(event.volume(), copy.gain()))));
                placed.assignedInstrument = note.assignedInstrument;
                placed.detune = copy.detune();
                placed.vibrato = copy.vibrato();
                destination.add(placed);
                count++;
            }
        }
        return count;
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

    private static int writeToneLane(String chip, String lane, List<Placement> notes,
                                      Map<Integer, MidiScore.Part> parts, List<Sequence> sequences, List<Clip> clips,
                                      ReconstructionClipLinks links) {
        boolean fm = chip.equals("FM");
        NativeCommand selectAsset = fm ? NativeCommand.FM_VOICE : NativeCommand.PSG_ENVELOPE;
        var chunks = links.chunks(lane, notes.size(), i -> notes.get(i).event.start(), i -> clipEnd(notes, i),
                i -> noiseKind(notes.get(i)), CLIP_TICKS);
        int defaultCount = chunks.size();
        if (notes.stream().anyMatch(note -> note.assignedInstrument)) {
            var modes = new ArrayList<ClipMode>();
            int run = 0;
            Placement prior = null;
            for (var current : notes) {
                if (prior != null && (prior.assignedInstrument || current.assignedInstrument)
                        && !prior.attackTone().asset().equals(current.attackTone().asset())) run++;
                modes.add(new ClipMode(noiseKind(current), run));
                prior = current;
            }
            chunks = links.chunks(lane, notes.size(), i -> notes.get(i).event.start(), i -> clipEnd(notes, i),
                    modes::get, CLIP_TICKS);
        }
        for (var chunk : chunks) {
            int start = chunk.first();
            int end = chunk.limit();
            int from = chunk.tick();
            PsgMode noise = noiseKind(notes.get(start));
            int length = clipEnd(notes, end - 1) - from;
            String id = lane + "-" + from;
            var events = new ArrayList<NoteEvent>();
            var commands = new ArrayList<CommandEvent>();
            var initial = notes.get(start).attackTone();
            String activeAsset = initial.asset();
            int volume = initial.volume();
            int detune = 0;
            ReconstructionVoicing.Vibrato vibrato = null;
            boolean bendModulation = false;
            if (noise != PsgMode.TONE) {
                int mode = noise == PsgMode.PITCHED_NOISE
                        ? ReconstructionPitchedNoise.COMMAND : ReconstructionVoicing.PSG_PERCUSSION.noiseByte();
                commands.add(new CommandEvent("noise", 0, NativeCommand.PSG_NOISE, List.of(mode)));
            }
            for (int i = start; i < end; i++) {
                Placement placement = notes.get(i);
                Event note = placement.event;
                int offset = note.start() - from;
                Tone attack = placement.attackTone();
                emitTone(commands, offset, selectAsset, activeAsset, volume, attack);
                activeAsset = attack.asset();
                volume = attack.volume();
                if (placement.bend != null) {
                    if (placement.bend.mode() == ReconstructionBendEncoding.Mode.E7
                            && attack.vibrato() == null && (vibrato != null || bendModulation)) {
                        commands.add(new CommandEvent("bend-reset-" + offset, offset, NativeCommand.MODULATION_OFF, List.of()));
                    }
                    vibrato = placement.bend.endingVibrato();
                    bendModulation = placement.bend.modulatingAtEnd();
                    for (var command : placement.bend.commands()) {
                        commands.add(new CommandEvent(command.id() + "-" + offset, offset + command.tick(),
                                command.command(), command.arguments()));
                        if (command.command() == NativeCommand.DETUNE) detune = (byte) (int) command.arguments().getFirst();
                    }
                    for (var step : placement.bend.notes()) {
                        events.add(new NoteEvent(step.id() + "-" + offset, offset + step.tick(), step.duration(), step.pitch()));
                    }
                } else {
                    if (detune != placement.detune) {
                        detune = placement.detune;
                        commands.add(new CommandEvent("detune-" + offset, offset, NativeCommand.DETUNE, List.of(detune & 255)));
                    }
                    if (bendModulation || !java.util.Objects.equals(vibrato, attack.vibrato())) {
                        bendModulation = false;
                        vibrato = attack.vibrato();
                        emitVibrato(commands, offset, vibrato);
                    }
                }
                int noteStart = note.start();
                if (placement.hatClock != null) {
                    int boundary = noteStart + placement.hatClock.hatSwitchTick();
                    int decayDetune = ReconstructionCymbals.detune(placement.hatClock.hatDecayPeriod());
                    if (boundary < placement.end && decayDetune != detune) {
                        events.add(new NoteEvent("note-" + offset, offset, boundary - noteStart, note.note().pitch()));
                        int tail = boundary - from;
                        detune = decayDetune;
                        commands.add(new CommandEvent("detune-" + tail, tail, NativeCommand.DETUNE, List.of(detune & 255)));
                        // Hold advances the envelope while updating the tone-3 clock without another attack.
                        commands.add(new CommandEvent("hold-" + tail, tail, NativeCommand.HOLD, List.of()));
                        noteStart = boundary;
                    }
                }
                Tone current = attack;
                for (var change : placement.scopedTones.tailMap(note.start(), false).entrySet()) {
                    int boundary = change.getKey();
                    Tone next = change.getValue();
                    emitTone(commands, boundary - from, selectAsset, activeAsset, volume, next);
                    activeAsset = next.asset();
                    volume = next.volume();
                    if (placement.bend == null && !java.util.Objects.equals(current.vibrato(), next.vibrato())) {
                        if (next.vibrato() != null) {
                            // A fresh setup needs an attack to load live S3K counters.
                            events.add(new NoteEvent("note-" + (noteStart - from), noteStart - from,
                                    boundary - noteStart, note.note().pitch()));
                            noteStart = boundary;
                        }
                        emitVibrato(commands, boundary - from, next.vibrato());
                        vibrato = next.vibrato();
                        bendModulation = false;
                    }
                    current = next;
                }
                if (placement.bend == null)
                    events.add(new NoteEvent("note-" + (noteStart - from), noteStart - from,
                            placement.end - noteStart, note.note().pitch()));
                for (var change : releaseTones(notes, i).entrySet()) {
                    int tick = change.getKey() - from;
                    var next = change.getValue();
                    emitTone(commands, tick, selectAsset, activeAsset, volume, next);
                    activeAsset = next.asset();
                    volume = next.volume();
                    if (bendModulation || !java.util.Objects.equals(vibrato, next.vibrato())) {
                        bendModulation = false;
                        vibrato = next.vibrato();
                        emitVibrato(commands, tick, vibrato);
                    }
                }
            }
            var names = notes.subList(start, end).stream()
                    .map(p -> parts.get(p.event.note().partId()).name()).distinct().toList();
            var source = new Sequence(id, String.join(" / ", names), chip, length, fm ? initial.asset() : null,
                    initial.volume(), events, commands, "RESET", fm || noise == PsgMode.FIXED_NOISE ? null : initial.asset(), null);
            links.add(source, lane, from, chunk.span(), sequences, clips);
        }
        return chunks.size() - defaultCount;
    }

    private record ClipMode(PsgMode mode, int instrumentRun) {}

    private enum PsgMode { TONE, FIXED_NOISE, CYMBAL_NOISE, PITCHED_NOISE }

    private static PsgMode noiseKind(Placement placement) {
        if (placement.pitchedNoise) return PsgMode.PITCHED_NOISE;
        String assetId = placement.event.assetId();
        if (NOISE.equals(assetId)) return PsgMode.FIXED_NOISE;
        return ReconstructionCymbals.isEnvelope(assetId) ? PsgMode.CYMBAL_NOISE : PsgMode.TONE;
    }

    private static java.util.NavigableMap<Integer, Tone> releaseTones(List<Placement> notes, int index) {
        var changes = notes.get(index).releaseTones;
        return index + 1 == notes.size() ? changes : changes.headMap(notes.get(index + 1).event.start(), false);
    }

    private static int clipEnd(List<Placement> notes, int index) {
        var release = releaseTones(notes, index);
        return release.isEmpty() ? notes.get(index).end : release.lastKey() + 1;
    }

    private static void emitTone(List<CommandEvent> commands, int tick, NativeCommand selectAsset,
                                 String activeAsset, int volume, Tone next) {
        if (!activeAsset.equals(next.asset()))
            commands.add(new CommandEvent("asset-" + tick, tick, selectAsset, List.of(), next.asset()));
        if (volume != next.volume())
            commands.add(new CommandEvent("volume-" + tick, tick, NativeCommand.VOLUME, List.of(next.volume())));
    }

    private static void emitVibrato(List<CommandEvent> commands, int tick, ReconstructionVoicing.Vibrato vibrato) {
        commands.add(new CommandEvent("vibrato-" + tick, tick,
                vibrato == null ? NativeCommand.MODULATION_OFF : NativeCommand.MODULATION,
                vibrato == null ? List.of() : vibrato.bytes()));
    }

    private static void writeDacLane(List<Event> hits, List<Sequence> sequences, List<Clip> clips,
                                     ReconstructionClipLinks links) {
        var chunks = links.chunks("DAC", hits.size(), i -> hits.get(i).start(), i -> hits.get(i).end(),
                i -> PsgMode.TONE, CLIP_TICKS);
        for (var chunk : chunks) {
            int start = chunk.first();
            int end = chunk.limit();
            int from = chunk.tick();
            int length = hits.get(end - 1).end() - from;
            String id = "DAC-" + from;
            var triggers = hits.subList(start, end).stream()
                    .map(hit -> new SampleTrigger("hit-" + (hit.start() - from), hit.start() - from, hit.assetId())).toList();
            var source = new Sequence(id, "Drums", "DAC", length, null, 127,
                    List.of(), List.of(), "RESET", null, null, triggers);
            links.add(source, "DAC", from, chunk.span(), sequences, clips);
        }
    }

    static List<Integer> psgAttenuation(byte[] bytes) {
        var values = new ArrayList<Integer>(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            int value = Byte.toUnsignedInt(bytes[i]);
            // zUpdatePSGTrack adds the envelope to the lane attenuation and
            // ORs that sum into VoiceControl. Offset plus envelope at most
            // 30 can only address the intended channel, even before bit 4
            // is checked for silence. Native banks keep their original bytes.
            values.add(value < 0x80 ? Math.min(value, 15) : value);
            if ((value == 0x82 || value == 0x84) && i + 1 < bytes.length)
                values.add(Byte.toUnsignedInt(bytes[++i]));
        }
        return List.copyOf(values);
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
            default -> ReconstructionCymbals.eligible(pitch) ? 0 : 1;
        };
    }

    private static int psgDrumVolume(int volume, double gain) {
        if (gain == 1) return volume;
        if (gain == 0) return 0;
        int shift = (int) Math.round(10 * Math.log10(gain));
        int level = Math.clamp((volume >> 3) + shift, 0, 15);
        return level << 3;
    }

    private static byte[] scalePcm(byte[] pcm, double gain) {
        if (gain == 1) return pcm;
        for (int i = 0; i < pcm.length; i++) {
            int amplitude = (pcm[i] & 0xff) - 128;
            pcm[i] = (byte) Math.clamp(Math.round(amplitude * gain) + 128, 0, 255);
        }
        return pcm;
    }

    static int volume(MidiScore.Part part, MidiScore.Note note) {
        int attenuation = (int) Math.round(-20.0 * Math.log10(note.velocity() / 127.0) / 1.5) * 2;
        int headroom = isBass(part) ? 4 : isLead(part) ? 6 : 10;
        return Math.max(1, 127 - attenuation - headroom);
    }
}

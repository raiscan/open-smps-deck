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
        DAC_SIMULTANEOUS, DAC_TAIL, HARMONY_THINNED, ROLE_OVERFLOW, CHORD_ABSORBED, STEM_FRAGMENT
    }

    record Reduction(MidiScore.Note note, Change change, @ReconstructionStages.Nullable String id,
                     @ReconstructionStages.Nullable String reason) {
        Reduction(MidiScore.Note note, Change change) { this(note, change, null, null); }
    }
    record Score(List<MidiScore.Part> parts, List<MidiScore.Note> notes, List<Reduction> reductions) {}
    record Result(StudioProject project, int fmNotes, int psgNotes, int dacHits, int echoNotes,
                  List<Reduction> reductions, List<ReconstructionBendEncoding.Applied> bends,
                  List<VoicedGate> voicedGates, int assignmentClipCost, Integer arpeggioExtraNoteBytes,
                  List<ReconstructionLanePolicies.Coverage> policyCoverage,
                  List<ReconstructionLayers.Result> layers, List<CopyWindow> lanePolicyCopies) {
        Result(StudioProject project, int fmNotes, int psgNotes, int dacHits, int echoNotes,
               List<Reduction> reductions, List<ReconstructionBendEncoding.Applied> bends, List<VoicedGate> voicedGates) {
            this(project, fmNotes, psgNotes, dacHits, echoNotes, reductions, bends, voicedGates, 0, null, List.of(), List.of(), List.of());
        }
    }
    record CopyWindow(String lane, int startTick, int endTick) {}

    record VoicedGate(String id, String lane, int pitch, int startTick, int endTick, String evidence) {
        VoicedGate(String id, String lane, int pitch, int startTick, int endTick) {
            this(id, lane, pitch, startTick, endTick, null);
        }
    }

    private record Event(MidiScore.Note note, int start, int end, int priority, String assetId, int volume) {}

    private record Tone(String asset, int volume, ReconstructionVoicing.Vibrato vibrato) {}

    private static final class Placement {
        final Event event;
        int end;
        int detune;
        ReconstructionVoicing.PsgPercussion hatClock;
        int volumeFloor;
        boolean assignedInstrument;
        ReconstructionVoicing.Copy copy;
        boolean chord;
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
        return arrangeAllocated(name, ticksPerBeat, frames, score, allocation, structure, catalogue, matches,
                echoes, voicing, copies, bends, scopes, origins, scopedDrumGains, layout, assignments, null);
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes,
                                   Map<String, ReconstructionVoicing.Lane> voicing, boolean copies, boolean bends,
                                   Map<String, List<ReconstructionOverrides.Span>> scopes, Map<String, String> origins,
                                   Map<String, Double> scopedDrumGains, ReconstructionClipLinks.Layout layout,
                                   Map<String, ReconstructionVoicePlan.Assignment> assignments,
                                   Map<String, ReconstructionVoicing.SourceSound> sourceInstruments)
            throws java.io.IOException {
        return arrangeAllocated(name, ticksPerBeat, frames, score, allocation, structure, catalogue, matches, echoes,
                voicing, copies, bends, scopes, origins, scopedDrumGains, layout, assignments, sourceInstruments,
                List.of(), null);
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes,
                                   Map<String, ReconstructionVoicing.Lane> voicing, boolean copies, boolean bends,
                                   Map<String, List<ReconstructionOverrides.Span>> scopes, Map<String, String> origins,
                                   Map<String, Double> scopedDrumGains, ReconstructionClipLinks.Layout layout,
                                   Map<String, ReconstructionVoicePlan.Assignment> assignments,
                                   Map<String, ReconstructionVoicing.SourceSound> sourceInstruments,
                                   List<ReconstructionLanePolicies.Scoped> policies, ReconstructionLanePolicies.Levels levels)
            throws java.io.IOException {
        return arrangeAllocated(name, ticksPerBeat, frames, score, allocation, structure, catalogue, matches,
                echoes, voicing, copies, bends, scopes, origins, scopedDrumGains, layout, assignments, sourceInstruments,
                policies, levels, Map.of(), false);
    }

    static Result arrangeAllocated(String name, int ticksPerBeat, int frames, Score score,
                                   ReconstructionAllocation.Result allocation, ReconstructionStructure.Result structure,
                                   InstrumentCatalogue catalogue,
                                   Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> matches,
                                   Map<Integer, ReconstructionEcho.Estimate> echoes,
                                   Map<String, ReconstructionVoicing.Lane> voicing, boolean copies, boolean bends,
                                   Map<String, List<ReconstructionOverrides.Span>> scopes, Map<String, String> origins,
                                   Map<String, Double> scopedDrumGains, ReconstructionClipLinks.Layout layout,
                                   Map<String, ReconstructionVoicePlan.Assignment> assignments,
                                   Map<String, ReconstructionVoicing.SourceSound> sourceInstruments,
                                   List<ReconstructionLanePolicies.Scoped> policies, ReconstructionLanePolicies.Levels levels,
                                   Map<String, ReconstructionLayers.Request> layers, boolean fmOnly)
            throws java.io.IOException {
        var candidates = new ArrayList<PolicySource>();
        var applied = new ArrayList<ReconstructionBendEncoding.Applied>();
        var gates = new ArrayList<VoicedGate>();
        Integer arpeggioExtraNoteBytes = null;
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
            boolean isFm = lane.startsWith("FM");
            var sourceSound = part.drums() ? null : ReconstructionVoicing.sourceSound(sourceInstruments, voice, part.id(), isFm);
            boolean translatePitch = !isFm && sourceSound != null && voice.effects().equals(ReconstructionVoicing.DRY);
            if (sourceSound != null) voice = sourceSound.settings(voice, isFm);
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
                    return DacSample.of(key, choice.name(), choice.sampleRate(), DacSample.applyGain(pcm, gain));
                });
                dac.add(new Event(note, start, end, drumPriority(note.pitch()), gainedId, 0));
                gates.add(new VoicedGate(entry.note().id(), lane, note.pitch(), start, end));
            } else {
                var asset = catalogue.asset(choice.assetId());
                int psgOffset = !isFm && sourceSound != null && assignment == null ? sourceSound.psgVolumeOffset() : 0;
                var spans = scopes.get(origin);
                var chord = allocation.chords().get(noteId);
                if (chord != null) {
                    var selected = spans == null ? null : ReconstructionOverrides.changes(spans, end).floorEntry(start);
                    var settings = selected == null ? null : selected.getValue();
                    var toneLane = isFm ? fm.get(ReconstructionAllocation.FM.indexOf(lane))
                            : psg.get(ReconstructionAllocation.PSG.indexOf(lane));
                    int extra = placeChord(chord, entry, part, voice, asset, settings, catalogue, toneLane,
                            voices, envelopes, gates, assignment != null, psgOffset);
                    if (chord.arpeggio())
                        arpeggioExtraNoteBytes = extra + (arpeggioExtraNoteBytes == null ? 0 : arpeggioExtraNoteBytes);
                    continue;
                }
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
                if (voice != null) volume = voicedVolume(volume, floor, voice.gainFor(part.id()), psgOffset);
                boolean pitchedNoise = entry.laneType() == ReconstructionLaneType.NOISE;
                var playedNote = pitchedNoise ? new MidiScore.Note(note.partId(), ReconstructionCymbals.PITCH,
                        note.velocity(), note.startUs(), note.endUs()) : note;
                var placed = new Placement(new Event(playedNote, start, start + duration,
                        tonePriority(part, note.pitch()), id, volume));
                placed.pitchedNoise = pitchedNoise;
                placed.assignedInstrument = assignment != null;
                if (voice != null) {
                    placed.volumeFloor = floor;
                    if (isFm) placed.copy = voice.effects().copy();
                    placed.detune = voice.effects().detune();
                    placed.vibrato = voice.effects().vibrato();
                }
                if (translatePitch) {
                    placed.detune = psgPitchOffset(note.pitch(), placed.detune);
                    var vibrato = placed.vibrato;
                    if (vibrato != null) {
                        int delta = psgPitchOffset(note.pitch(), vibrato.delta());
                        placed.vibrato = delta == 0 ? null : new ReconstructionVoicing.Vibrato(
                                vibrato.delay(), vibrato.rate(), delta, vibrato.steps());
                    }
                }
                if (pitchedNoise) placed.detune = ReconstructionPitchedNoise.detune(note.pitch());
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
                            scopedVolume = voicedVolume(raw, selectedFloor, voice.gainFor(part.id(), gain),
                                    selected.id().equals(asset.id()) ? psgOffset : 0);
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
                if (!policies.isEmpty() || layers.containsKey(origin))
                    candidates.add(new PolicySource(entry, origin, placed, gates.size(), assignment != null));
                if (layers.containsKey(origin)) placed.copy = null;
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
        int[] overlappingTone = policies.isEmpty() ? null : overlappingToneFrames(fm, psg, frames);
        var layerResults = applyNoteLayers(layers, frames, fmOnly, fm, psg, candidates, voices, envelopes,
                levels, catalogue, sourceInstruments);
        int echoNotes = addEchoes(fm, echoes, frames, 2);
        if (copies) echoNotes += addVoicingCopies(fm, frames);
        var policySources = layers.isEmpty() ? candidates
                : candidates.stream().filter(source -> !layers.containsKey(source.origin())).toList();
        var lanePolicyCopies = new ArrayList<CopyWindow>();
        var coverage = applyLanePolicies(policies, levels, frames, fm, psg, policySources, voices, envelopes, gates,
                catalogue, sourceInstruments, parts, overlappingTone, lanePolicyCopies);
        if (!coverage.isEmpty() || !layerResults.isEmpty()) {
            fmNotes = fm.stream().mapToInt(List::size).sum() - echoNotes;
            psgNotes = psg.stream().mapToInt(List::size).sum();
        }
        List<Sequence> sequences = new ArrayList<>();
        List<Clip> clips = new ArrayList<>();
        var repeats = ReconstructionClipLinks.from(structure, layout);
        int assignmentClipCost = 0;
        for (int i = 0; i < fm.size(); i++)
            assignmentClipCost += writeToneLane("FM", ReconstructionAllocation.FM.get(i), fm.get(i), parts, sequences, clips, repeats);
        for (int i = 0; i < psg.size(); i++)
            assignmentClipCost += writeToneLane("PSG", ReconstructionAllocation.PSG.get(i), psg.get(i), parts, sequences, clips, repeats);
        writeDacLane(dac, sequences, clips, repeats);
        if (!scopes.isEmpty() || !allocation.chords().isEmpty() || !policies.isEmpty() || !layers.isEmpty()) {
            var used = new HashSet<String>();
            for (var sequence : sequences) {
                used.add(sequence.voiceId());
                sequence.commands().stream().filter(command -> command.command() == NativeCommand.FM_VOICE)
                        .forEach(command -> used.add(command.assetId()));
            }
            voices.keySet().retainAll(used);
            if (voices.size() > 128) {
                String context = allocation.chords().isEmpty() ? "voicing.json: scoped voicing" : "Edits and scoped voicing";
                throw new IllegalArgumentException(context + " requires " + voices.size()
                        + " distinct FM voices (S3K local bank limit is 128)");
            }
        }
        var project = new StudioProject(4, name, "S3K", ticksPerBeat, 0, List.copyOf(voices.values()),
                sequences, clips, List.copyOf(envelopes.values()), List.copyOf(samples.values()));
        return new Result(project, fmNotes, psgNotes, dac.size(), echoNotes,
                List.copyOf(reductions), List.copyOf(applied), List.copyOf(gates), assignmentClipCost, arpeggioExtraNoteBytes,
                coverage, layerResults, List.copyOf(lanePolicyCopies));
    }

    private static ReconstructionVoices.Choice assignedChoice(InstrumentCatalogue catalogue,
                                                               ReconstructionVoicePlan.Assignment assignment) {
        var match = catalogue.match(assignment.instrument());
        return new ReconstructionVoices.Choice(match.asset().id(), match.variant().id(), match.asset().name(),
                match.variant().recipe().rate(), "voice-assignment", List.of(), List.of());
    }

    private static int placeChord(ReconstructionChords.Group chord, ReconstructionAllocation.Entry anchor,
                                  MidiScore.Part part, ReconstructionVoicing.Lane voice, InstrumentCatalogue.Asset asset,
                                  ReconstructionOverrides.Settings settings, InstrumentCatalogue catalogue,
                                  List<Placement> lane, Map<String, Voice> voices, Map<String, Envelope> envelopes,
                                  List<VoicedGate> gates, boolean assigned, int psgOffset) {
        int start = anchor.startTick(), end = anchor.endTick();
        boolean fm = anchor.lane().startsWith("FM");
        var articulation = voice == null ? ReconstructionVoicing.WRITTEN : voice.articulation();
        if (!assigned && settings != null && settings.variantId() != null) {
            asset = catalogue.match(settings.variantId()).asset();
            psgOffset = 0;
        }
        if (settings != null && settings.minimumReleaseRate() != null)
            articulation = new ReconstructionVoicing.Articulation("scoped", articulation.gate(), settings.minimumReleaseRate());
        var note = anchor.note().midi();
        int level = Math.max(1, volume(part, note) - (fm ? 0 : 24));
        if (voice != null) {
            double gain = settings != null && settings.gain() != null ? settings.gain() : voice.gain();
            level = voicedVolume(level, 0, voice.gainFor(part.id(), gain), psgOffset);
        }
        String label = chord.label();
        if (chord.arpeggio()) {
            String id = asset.id();
            if (fm) {
                byte[] patch = assigned ? asset.bytes() : ReconstructionVoicing.patchBytes(asset, articulation);
                id = ReconstructionVoicing.patchId(asset, patch);
                voices.putIfAbsent(id, new Voice(id, asset.name(), unsigned(patch)));
                if (voice != null) level = Math.max(level, ReconstructionEcho.fmVolumeFloor(patch));
            } else {
                envelopes.putIfAbsent(id, new Envelope(id, asset.name(), Envelope.PSG_VOLUME, psgAttenuation(asset.bytes())));
            }
            boolean pitchedNoise = anchor.laneType() == ReconstructionLaneType.NOISE;
            int bytes = 0;
            for (var step : ReconstructionChords.steps(chord, start, end)) {
                var member = step.member();
                int pitch = pitchedNoise ? ReconstructionCymbals.PITCH : member.pitch();
                int detune = pitchedNoise ? ReconstructionPitchedNoise.detune(member.pitch()) : 0;
                var placement = chordPlacement(note, pitch, step.start(), step.end(), id, level, detune);
                placement.pitchedNoise = pitchedNoise;
                lane.add(placement);
                gates.add(new VoicedGate(member.id(), anchor.lane(), member.pitch(), step.start(), step.end(), label));
                bytes += noteBytes(step.end() - step.start());
            }
            return bytes - noteBytes(end - start);
        }
        var source = ReconstructionVoicing.patchBytes(asset, articulation);
        var tuning = chord.tuning();
        var active = new java.util.TreeSet<Integer>();
        var releases = new java.util.TreeSet<Integer>();
        for (int i = 0; i < chord.members().size(); i++) {
            var member = chord.members().get(i);
            active.add(i);
            int release = Math.min(member.endTick(), end);
            releases.add(release);
            gates.add(new VoicedGate(member.id(), anchor.lane(), member.pitch(), start, release, label));
        }
        for (int release : releases) {
            var patch = ReconstructionChords.patch(asset.name(), source, tuning, active);
            voices.putIfAbsent(patch.id(), patch);
            int floor = patch.bytes().subList(21, 25).stream().filter(tl -> (tl & 128) != 0)
                    .mapToInt(tl -> tl & 127).max().orElse(0);
            lane.add(chordPlacement(note, tuning.note(), start, release, patch.id(), Math.max(level, floor), tuning.detune()));
            start = release;
            active.removeIf(i -> chord.members().get(i).endTick() <= release);
        }
        return 0;
    }

    private static Placement chordPlacement(MidiScore.Note anchor, int pitch, int start, int end,
                                            String assetId, int level, int detune) {
        var note = new MidiScore.Note(anchor.partId(), pitch, anchor.velocity(), anchor.startUs(), anchor.endUs());
        var placement = new Placement(new Event(note, start, end, 0, assetId, level));
        placement.detune = detune;
        placement.chord = true;
        return placement;
    }

    private static int noteBytes(int ticks) { return 2 * ((ticks + 126) / 127); }

    static int voicedDuration(ReconstructionAllocation.Entry entry, ReconstructionVoicing.Articulation articulation) {
        int ticks = entry.endTick() - entry.startTick();
        var bend = entry.note().bend();
        if (bend != null && Boolean.TRUE.equals(bend.glide()) || "DAC".equals(entry.lane()) || ("PSG3".equals(entry.lane()) && entry.role() == ReconstructionRoles.Role.drums)
                || entry.note().source().equals(ReconstructionFastRuns.REASON)
                || entry.note().source().equals(ReconstructionRetriggers.REASON)) return ticks;
        return articulation.duration(ticks);
    }

    private static int psgPitchOffset(int pitch, int fmOffset) {
        // An increasing FM F-number raises the pitch, whereas an increasing PSG period lowers it.
        double hz = 440 * Math.pow(2, (pitch - 69) / 12.0);
        double period = 3_579_545 / (32 * hz);
        double fnum = LeadEffectMatcher.fmFnum(pitch);
        return Math.clamp((int) Math.round(-period * fmOffset / (fnum + fmOffset)), -128, 127);
    }

    private static int voicedVolume(int raw, int floor, double gain, int offset) {
        return Math.clamp(ReconstructionEcho.scaleVolume(raw, gain) + (gain == 0 ? 0 : offset), floor, 127);
    }

    private static int addVoicingCopies(List<ArrayList<Placement>> fm, int frames) {
        var sources = fm.stream().flatMap(List::stream).filter(note -> note.copy != null && !note.chord)
                .sorted(Comparator.comparingInt(note -> note.event.start() + note.copy.delay())).toList();
        int count = 0;
        for (var note : sources) {
            var copy = note.copy;
            var destination = fm.get(ReconstructionAllocation.FM.indexOf(copy.lane()));
            int start = note.event.start() + copy.delay();
            if (start >= frames || !destination.isEmpty() && destination.getLast().end > start) continue;
            int end = Math.min(frames, (note.scopedTones.isEmpty() ? note.end : note.event.end()) + copy.delay());
            var placed = fmCopy(note, start, end, note.baseTone(),
                    ReconstructionEcho.scaleVolume(note.event.volume(), copy.gain()), copy.detune(), copy.vibrato());
            destination.add(placed);
            count++;
        }
        return count;
    }

    private static Placement fmCopy(Placement source, int start, int end, Tone tone, int volume, int detune,
                                    ReconstructionVoicing.Vibrato vibrato) {
        var event = source.event;
        var placed = new Placement(new Event(event.note(), start, end, event.priority(), tone.asset(),
                Math.max(source.volumeFloor, volume)));
        placed.assignedInstrument = source.assignedInstrument;
        placed.detune = detune;
        placed.vibrato = vibrato;
        return placed;
    }

    private record PolicySource(ReconstructionAllocation.Entry entry, String origin, Placement placed, int gate,
                                boolean assigned) {}

    private record FreeSlot(List<Placement> lane, String id, int index) {}
    private static final Envelope LAYER_PSG = new Envelope("note-layer-psg", "Note layer", Envelope.PSG_VOLUME,
            List.of(0, 0x81));

    private static List<ReconstructionLayers.Result> applyNoteLayers(
            Map<String, ReconstructionLayers.Request> layers, int frames, boolean fmOnly,
            List<ArrayList<Placement>> fm, List<ArrayList<Placement>> psg, List<PolicySource> sources,
            Map<String, Voice> voices, Map<String, Envelope> envelopes, ReconstructionLanePolicies.Levels levels,
            InstrumentCatalogue catalogue, Map<String, ReconstructionVoicing.SourceSound> instruments) throws java.io.IOException {
        if (layers.isEmpty()) return List.of();
        var results = new ArrayList<ReconstructionLayers.Result>();
        var placedIds = new HashSet<String>();
        var ordered = sources.stream().filter(source -> layers.containsKey(source.origin()))
                .filter(source -> layers.get(source.origin()).settings().mode() != ReconstructionLayers.Mode.off)
                .sorted(Comparator.comparingInt(source -> source.placed().event.start()
                        + layers.get(source.origin()).settings().delayFrames())).toList();
        for (var source : ordered) {
            String id = source.origin();
            var request = layers.get(id);
            var settings = request.settings();
            var dry = source.placed();
            var note = dry.event.note();
            boolean targetFm = settings.laneType() == ReconstructionLaneType.FM;
            int pitch = note.pitch() + 12 * settings.octave();
            int start = dry.event.start() + settings.delayFrames();
            int end = Math.min(frames, dry.end + settings.delayFrames());
            var names = settings.laneType().lanes();
            String reason = null;
            ReconstructionLayers.Curve curve = null;
            FreeSlot slot = null;
            if (!targetFm && fmOnly) reason = "PSG disabled by FM-only reconstruction";
            else if (start >= end) reason = "Layer starts outside the render";
            else if (noiseKind(dry) != PsgMode.TONE) reason = "Source note uses a noise lane";
            else if (dry.bend == null && (pitch < (targetFm ? 12 : 45) || pitch > (targetFm ? 106 : 117)))
                reason = "Pitch is outside the target chip range";
            else {
                slot = freeSlot(targetFm ? fm : psg, names, source.entry().lane(), start, end);
                if (slot == null) reason = "No free " + settings.laneType() + " lane for the full layer gate";
            }
            if (slot != null) {
                var tone = dry.attackTone();
                var fmVoice = voices.get(tone.asset());
                var psgVoice = envelopes.get(tone.asset());
                var layerNote = new MidiScore.Note(note.partId(), pitch, note.velocity(), note.startUs(), note.endUs());
                var layer = targetFm
                        ? fmLayer(dry, tone, fmVoice, layerNote, settings, start, end, voices, catalogue, instruments)
                        : psgLayer(dry, tone, fmVoice, psgVoice, layerNote, settings, start, end, envelopes, levels);
                if (layer != null) {
                    curve = copyBend(dry, layer, fmVoice, targetFm, settings.detune());
                    slot.lane().add(slot.index(), layer);
                } else reason = targetFm ? "No usable FM instrument" : "Source has no measurable PSG level match";
            }
            results.add(ReconstructionLayers.Result.of(id, request,
                    reason == null ? ReconstructionLayers.Status.applied : ReconstructionLayers.Status.skipped,
                    reason, reason == null ? slot.id() : null, pitch, start, end > start ? end : null, curve));
            placedIds.add(id);
        }
        layers.forEach((id, request) -> {
            if (request.settings().mode() == ReconstructionLayers.Mode.off)
                results.add(ReconstructionLayers.Result.of(id, request, ReconstructionLayers.Status.off,
                        "Per-note layers and lane policies disabled for this note", null, null, null, null));
            else if (!placedIds.contains(id))
                results.add(ReconstructionLayers.Result.of(id, request, ReconstructionLayers.Status.skipped,
                        "Source has no independent playable tonal gate", null, null, null, null));
        });
        return List.copyOf(results);
    }

    private static ReconstructionLayers.Curve copyBend(Placement dry, Placement layer, Voice source,
                                                       boolean targetFm, int detune) throws java.io.IOException {
        if (dry.bend == null) return null;
        var copy = ReconstructionLayerPitch.copy(dry.bend, source, targetFm, layer.end - layer.event.start(),
                dry.event.note().pitch(), layer.event.note().pitch(), detune);
        layer.bend = copy.fit();
        return copy.curve();
    }

    private static Placement fmLayer(Placement dry, Tone tone, Voice voice, MidiScore.Note note,
                                     ReconstructionLayers.Settings settings, int start, int end, Map<String, Voice> voices,
                                     InstrumentCatalogue catalogue, Map<String, ReconstructionVoicing.SourceSound> instruments) {
        int baseVolume = tone.volume();
        var vibrato = settings.mode() == ReconstructionLayers.Mode.chorus ? null : tone.vibrato();
        int detune = dry.detune;
        if (voice == null) {
            var fitted = instruments == null ? null : instruments.get(ReconstructionVoicePlan.SourceInstrument.id(note.partId()));
            var profile = fitted == null ? null : fitted.fm();
            var asset = profile == null || profile.variantId() == null ? catalogue.assets().stream()
                    .filter(candidate -> candidate.kind() == InstrumentCatalogue.Kind.FM_VOICE).findFirst().orElse(null)
                    : catalogue.match(profile.variantId()).asset();
            if (asset == null) return null;
            var patch = profile == null ? asset.bytes() : ReconstructionVoicing.patchBytes(asset, profile.articulation());
            String id = ReconstructionVoicing.patchId(asset, patch);
            voice = voices.computeIfAbsent(id, key -> new Voice(key, asset.name(), unsigned(patch)));
            baseVolume = voicedVolume(Math.min(127, tone.volume() + 24), 0,
                    profile == null ? 1 : profile.gainFor(note.partId()), 0);
            detune = profile == null ? 0 : profile.effects().detune();
            vibrato = null;
        }
        var patch = new byte[voice.bytes().size()];
        for (int i = 0; i < patch.length; i++) patch[i] = voice.bytes().get(i).byteValue();
        int floor = ReconstructionEcho.fmVolumeFloor(patch);
        int volume = fmLayerVolume(baseVolume, floor, settings.attenuation());
        var layer = new Placement(new Event(note, start, end, dry.event.priority(), voice.id(), volume));
        layer.detune = Math.clamp(detune + settings.detune(), -128, 127);
        layer.vibrato = vibrato;
        placeLayerDecay(layer, settings, baseVolume, floor, start, end, true);
        return layer;
    }

    private static Placement psgLayer(Placement dry, Tone tone, Voice fm, Envelope psg, MidiScore.Note note,
                                      ReconstructionLayers.Settings settings, int start, int end, Map<String, Envelope> envelopes,
                                      ReconstructionLanePolicies.Levels levels) throws java.io.IOException {
        int volume = levels.volume(fm, psg, dry.event.note().pitch(), tone.volume(), dry.bend == null ? note.pitch() : Math.clamp(note.pitch(), 45, 115),
                LAYER_PSG,
                end - start, -settings.attenuation() * ReconstructionLayers.DB_PER_TL);
        if (volume == 0) return null;
        envelopes.putIfAbsent(LAYER_PSG.id(), LAYER_PSG);
        var layer = new Placement(new Event(note, start, end, dry.event.priority(), LAYER_PSG.id(), volume));
        int baseDetune = psg == null ? psgPitchOffset(note.pitch(), dry.detune) : dry.detune;
        layer.detune = Math.clamp(baseDetune + psgPitchOffset(note.pitch(), settings.detune()), -128, 127);
        placeLayerDecay(layer, settings, volume, 0, start, end, false);
        return layer;
    }

    private static void placeLayerDecay(Placement layer, ReconstructionLayers.Settings settings, int baseVolume,
                                        int floor, int start, int end, boolean fm) {
        if (settings.decay().isEmpty()) return;
        layer.scopedTones = new java.util.TreeMap<>();
        for (var step : settings.decay()) {
            if (start + step.frame() >= end) break;
            int decrease = fm ? step.attenuation()
                    : 8 * (int) Math.round((step.attenuation() - settings.attenuation()) * ReconstructionLayers.DB_PER_TL / 2);
            layer.scopedTones.put(start + step.frame(),
                    new Tone(layer.event.assetId(), fmLayerVolume(baseVolume, floor, decrease), layer.vibrato));
        }
    }

    private static int fmLayerVolume(int sourceVolume, int carrierFloor, int attenuation) {
        return Math.max(carrierFloor, sourceVolume - attenuation);
    }

    private record FreedWindow(int lane, int start, int end) {}
    private record PolicyKey(ReconstructionLanePolicies.Kind kind, ReconstructionLanePolicies.Mode mode) {}

    private static List<ReconstructionLanePolicies.Coverage> applyLanePolicies(
            List<ReconstructionLanePolicies.Scoped> policies, ReconstructionLanePolicies.Levels levels, int frames,
            List<ArrayList<Placement>> fm, List<ArrayList<Placement>> psg, List<PolicySource> sources,
            Map<String, Voice> voices, Map<String, Envelope> envelopes, List<VoicedGate> gates,
            InstrumentCatalogue catalogue, Map<String, ReconstructionVoicing.SourceSound> instruments,
            Map<Integer, MidiScore.Part> parts, int[] overlappingTone, List<CopyWindow> copies) throws java.io.IOException {
        if (policies.isEmpty()) return List.of();
        var coverage = new ArrayList<ReconstructionLanePolicies.Coverage>();
        var claimed = new HashMap<PolicyKey, Set<String>>();
        var moved = new HashSet<Placement>();
        for (var scoped : policies) {
            var rule = scoped.rule();
            var kind = rule.policy();
            boolean pads = kind == ReconstructionLanePolicies.Kind.pads;
            boolean fmDouble = kind == ReconstructionLanePolicies.Kind.fmDouble;
            var mode = fmDouble && rule.mode() == null ? ReconstructionLanePolicies.Mode.chorus : rule.mode();
            boolean chorus = mode == ReconstructionLanePolicies.Mode.chorus;
            var envelope = switch (kind) {
                case pads -> ReconstructionLanePolicies.PAD;
                case leadEcho -> ReconstructionLanePolicies.ECHO;
                case psgDouble -> ReconstructionLanePolicies.DOUBLE;
                case fmDouble -> null;
            };
            var selected = claimed.computeIfAbsent(new PolicyKey(kind, mode), ignored -> new HashSet<>());
            var freed = new ArrayList<FreedWindow>();
            var byLane = new LinkedHashMap<String, Integer>();
            var bendLayers = new ArrayList<ReconstructionLanePolicies.BendLayer>();
            int eligible = 0, count = 0, busy = 0, incompatible = 0, movedFromFm = 0;
            for (var source : sources) {
                var entry = source.entry();
                var dry = source.placed();
                String id = entry.note().id();
                boolean solo = overlappingTone[dry.event.start()] == overlappingTone[dry.end];
                if (moved.contains(dry) || !scoped.selects(entry, source.origin(), solo) || !selected.add(id)) continue;
                eligible++;
                boolean fromFm = entry.lane().startsWith("FM");
                int pitch = rule.pitch(dry.event.note().pitch());
                int start = dry.event.start() + rule.delay();
                int end = Math.min(frames, dry.end + rule.delay());
                String incompatibleReason = null;
                if (end <= start) incompatibleReason = "Layer starts outside the render";
                else if (pads && dry.bend != null) incompatibleReason = "Pad routing preserves the dry FM bend";
                else if (!fmDouble && dry.bend == null && (pitch < 45 || pitch > 117))
                    incompatibleReason = "Pitch is outside the target chip range";
                else if (pads && (source.assigned() || entry.pinnedLane() != null || entry.laneType() != null
                        || noiseKind(dry) != PsgMode.TONE || dry.copy != null
                        || !dry.scopedTones.isEmpty() || !dry.releaseTones.isEmpty()))
                    incompatibleReason = "Pad source requires its original lane or voicing";
                if (incompatibleReason != null) {
                    incompatible++;
                    if (dry.bend != null) bendLayers.add(new ReconstructionLanePolicies.BendLayer(
                            id, start, end, null, null, incompatibleReason));
                    continue;
                }
                var original = fromFm ? fm.get(ReconstructionAllocation.FM.indexOf(entry.lane()))
                        : psg.get(ReconstructionAllocation.PSG.indexOf(entry.lane()));
                FreeSlot slot;
                if (pads && !fromFm) {
                    // Keep already spilled notes in place while giving them
                    // the same timbre as pads transferred from FM.
                    slot = new FreeSlot(original, entry.lane(), original.indexOf(dry));
                } else {
                    slot = freeSlot(fmDouble ? fm : psg, fmDouble ? ReconstructionAllocation.FM : ReconstructionAllocation.PSG,
                            fmDouble ? entry.lane() : null, start, end);
                }
                if (slot == null) {
                    busy++;
                    if (dry.bend != null) bendLayers.add(new ReconstructionLanePolicies.BendLayer(
                            id, start, end, null, null, "No free lane for the full layer gate"));
                    continue;
                }
                Placement placed;
                var tone = dry.attackTone();
                if (fmDouble) {
                    int detune = Math.clamp(dry.detune + rule.fmDetune(), -128, 127);
                    placed = fmCopy(dry, start, end, tone, fmLayerVolume(tone.volume(), dry.volumeFloor, rule.fmAttenuation()),
                            detune, chorus ? null : tone.vibrato());
                    if (!chorus && rule.volumeSteps() != null) {
                        placed.scopedTones = new java.util.TreeMap<>();
                        for (var step : rule.volumeSteps()) {
                            if (step.tick() >= end - start) break;
                            int volume = fmLayerVolume(tone.volume(), dry.volumeFloor, step.attenuation());
                            placed.scopedTones.put(start + step.tick(), new Tone(tone.asset(), volume, tone.vibrato()));
                        }
                    }
                } else {
                    int volume = levels.volume(voices.get(tone.asset()), envelopes.get(tone.asset()),
                            dry.event.note().pitch(), tone.volume(), dry.bend == null ? pitch : Math.clamp(pitch, 45, 115),
                            envelope, end - start, rule.db());
                    if (volume == 0) {
                        incompatible++;
                        if (dry.bend != null) bendLayers.add(new ReconstructionLanePolicies.BendLayer(
                                id, start, end, null, null, "Source has no measurable PSG level match"));
                        continue;
                    }
                    envelopes.putIfAbsent(envelope.id(), envelope);
                    var note = dry.event.note();
                    var pitched = new MidiScore.Note(note.partId(), pitch, note.velocity(), note.startUs(), note.endUs());
                    placed = new Placement(new Event(pitched, start, end, dry.event.priority(), envelope.id(), volume));
                }
                if (dry.bend != null) {
                    var curve = copyBend(dry, placed, voices.get(tone.asset()), fmDouble, fmDouble ? rule.fmDetune() : 0);
                    bendLayers.add(new ReconstructionLanePolicies.BendLayer(id, start, end, slot.id(), curve, null));
                }
                if (pads && slot.lane() == original) slot.lane().set(slot.index(), placed);
                else {
                    slot.lane().add(slot.index(), placed);
                    if (pads) original.remove(dry);
                }
                if (pads) {
                    moved.add(dry);
                    if (fromFm) {
                        movedFromFm++;
                        freed.add(new FreedWindow(ReconstructionAllocation.FM.indexOf(entry.lane()), start, end));
                    }
                    gates.set(source.gate(), new VoicedGate(id, slot.id(), pitch, start, end, "pads"));
                } else copies.add(new CopyWindow(slot.id(), start, end));
                byLane.merge(slot.id(), 1, Integer::sum);
                count++;
            }
            int promoted = promoteCounters(scoped, sources, freed, moved, fm, psg, voices, gates, catalogue, instruments, parts);
            coverage.add(new ReconstructionLanePolicies.Coverage(scoped.index(), kind, mode, eligible, count,
                    busy, incompatible, movedFromFm, promoted, byLane, bendLayers.isEmpty() ? null : List.copyOf(bendLayers)));
        }
        return List.copyOf(coverage);
    }

    // Count frames with multiple dry tonal placements. Ignore drums and generated copies.
    private static int[] overlappingToneFrames(List<ArrayList<Placement>> fm, List<ArrayList<Placement>> psg, int frames) {
        var changes = new int[frames + 1];
        for (var lanes : List.of(fm, psg)) {
            for (var lane : lanes) for (var dry : lane) {
                if (noiseKind(dry) != PsgMode.TONE) continue;
                changes[dry.event.start()]++;
                changes[dry.end]--;
            }
        }
        int active = 0, overlapping = 0;
        for (int tick = 0; tick < frames; tick++) {
            active += changes[tick];
            changes[tick] = overlapping;
            if (active > 1) overlapping++;
        }
        changes[frames] = overlapping;
        return changes;
    }

    private static int promoteCounters(ReconstructionLanePolicies.Scoped scope, List<PolicySource> sources,
                                       List<FreedWindow> freed, Set<Placement> moved,
                                       List<ArrayList<Placement>> fm, List<ArrayList<Placement>> psg,
                                       Map<String, Voice> voices, List<VoicedGate> gates, InstrumentCatalogue catalogue,
                                       Map<String, ReconstructionVoicing.SourceSound> instruments,
                                       Map<Integer, MidiScore.Part> parts) {
        if (freed.isEmpty() || instruments == null) return 0;
        int promoted = 0;
        for (var source : sources) {
            var entry = source.entry();
            var dry = source.placed();
            var note = dry.event.note();
            if (entry.role() != ReconstructionRoles.Role.counter || !entry.lane().startsWith("PSG")
                    || entry.startTick() < scope.start() || entry.startTick() >= scope.end() || source.assigned() || entry.pinnedLane() != null || entry.laneType() != null
                    || moved.contains(dry) || dry.pitchedNoise || !dry.scopedTones.isEmpty() || !dry.releaseTones.isEmpty()
                    || note.pitch() > 106)
                continue;
            var sound = instruments.get(ReconstructionVoicePlan.SourceInstrument.id(note.partId()));
            if (sound == null || sound.fm().variantId() == null) continue;
            int start = dry.event.start();
            for (var window : freed) {
                if (start >= window.end() || dry.end <= window.start()) continue;
                var lane = fm.get(window.lane());
                int at = insertion(lane, start, dry.end);
                if (at < 0) continue;
                var choice = catalogue.match(sound.fm().variantId());
                var asset = choice.asset();
                var patch = ReconstructionVoicing.patchBytes(asset, sound.fm().articulation());
                var id = ReconstructionVoicing.patchId(asset, patch);
                var part = parts.get(note.partId());
                voices.putIfAbsent(id, new Voice(id, part.name() + " / " + asset.name(), unsigned(patch)));
                int level = voicedVolume(volume(part, note), ReconstructionEcho.fmVolumeFloor(patch),
                        sound.fm().gainFor(note.partId()), 0);
                var placed = new Placement(new Event(note, start, dry.end, dry.event.priority(), id, level));
                placed.detune = sound.fm().effects().detune();
                placed.vibrato = sound.fm().effects().vibrato();
                lane.add(at, placed);
                psg.get(ReconstructionAllocation.PSG.indexOf(entry.lane())).remove(dry);
                moved.add(dry);
                gates.set(source.gate(), new VoicedGate(entry.note().id(), ReconstructionAllocation.FM.get(window.lane()),
                        note.pitch(), start, dry.end, "pads-freed-fm"));
                promoted++;
                break;
            }
        }
        return promoted;
    }

    private static FreeSlot freeSlot(List<ArrayList<Placement>> lanes, List<String> names, String exclude, int start, int end) {
        for (int i = 0; i < lanes.size(); i++) {
            if (names.get(i).equals(exclude)) continue;
            var lane = lanes.get(i);
            int at = insertion(lane, start, end);
            if (at >= 0) return new FreeSlot(lane, names.get(i), at);
        }
        return null;
    }

    private static int insertion(List<Placement> lane, int start, int end) {
        int low = 0, high = lane.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (lane.get(mid).event.start() < start) low = mid + 1;
            else high = mid;
        }
        if (low > 0 && clipEnd(lane, low - 1) > start) return -1;
        if (low < lane.size() && lane.get(low).event.start() < end) return -1;
        return low;
    }

    private static Change allocationChange(ReconstructionAllocation.Entry entry) {
        if (entry.outcome() == ReconstructionAllocation.Outcome.shortened) return Change.SHORTENED;
        return switch (entry.reason()) {
            case "rapid-inner-tone-reattack", "isolated-harmony-octave-double", "covered-source-unison" -> Change.HARMONY_THINNED;
            case "absorbed-into-arpeggio", "absorbed-into-chord-instrument" -> Change.CHORD_ABSORBED;
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

    static int volume(MidiScore.Part part, MidiScore.Note note) {
        int attenuation = (int) Math.round(-20.0 * Math.log10(note.velocity() / 127.0) / 1.5) * 2;
        int headroom = isBass(part) ? 4 : isLead(part) ? 6 : 10;
        return Math.max(1, 127 - attenuation - headroom);
    }
}

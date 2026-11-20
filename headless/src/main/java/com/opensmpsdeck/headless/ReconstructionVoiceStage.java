package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionVoicing.*;
import com.opensmpsdeck.headless.VoicePatchShortlist.RankedPatch;

final class ReconstructionVoiceStage {
    private static final double ARTICULATION_MARGIN = .004;
    private static final double EFFECT_MARGIN = .012;

    private record Model(String id, Role role, ReconstructionAllocation.Result packed, int stem, ReconstructionAllocation.Result stemNotes,
                         List<RankedPatch> patches, List<Instrument> instruments) {}

    private ReconstructionVoiceStage() {}

    static Result fit(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
                      ReconstructionAllocation.Result allocation, Result previous) throws IOException {
        var byLane = byLane(allocation);
        var source = new S3kReconstruction.Score(score.source().parts(), List.of(), List.of());
        var options = score.inputs().options(score.durationUs());
        var mix = Path.of(score.inputs().wav());
        var tonalInstruments = matchPsg(score, catalogue, byLane, mix, options.stems());
        var drumInstruments = ReconstructionDrums.match(score, catalogue, byLane.getOrDefault("DAC", List.of()),
                mix, options.stems());
        var windows = new VoiceWindows(score.frames(), byLane, previous == null ? null : previous.fitWindows());
        var screening = new VoicePatchShortlist(catalogue);
        var context = byLane.values().stream().flatMap(List::stream).toList();
        var references = new ArrayList<VoiceWindows.Reference>();
        references.add(windows.read(mix, 1));
        var stems = new LinkedHashMap<Path, Integer>();
        for (var path : options.stems().values()) {
            if (path.equals(mix) || stems.containsKey(path)) continue;
            stems.put(path, references.size());
            references.add(windows.read(path, 1.4));
        }
        var models = new ArrayList<Model>();
        var initial = new ArrayList<Lane>();
        var incumbents = new ArrayList<String>();
        for (var entry : byLane.entrySet()) {
            String lane = entry.getKey();
            var notes = entry.getValue();
            var incumbent = lane.startsWith("FM") ? incumbent(catalogue, previous, lane) : null;
            var patches = lane.startsWith("FM")
                    ? screening.rank(notes, context, mix, options.stems(), windows, incumbent) : List.<RankedPatch>of();
            incumbents.add(incumbent == null ? null : incumbent.id());
            var instruments = lane.startsWith("FM") ? List.<Instrument>of() : fixedInstruments(lane, notes, tonalInstruments, drumInstruments);
            Role role = dominantRole(notes);
            var packed = windows.pack(notes);
            // A lane can carry more than one MIDI part. Use its most represented
            // stem for fitting, with only the notes belonging to that stem.
            var stem = stemFor(notes, options.stems(), stems);
            var stemNotes = stem == null ? null : windows.pack(notes.stream()
                    .filter(note -> stem.equals(options.stems().get(note.note().part()))).toList());
            models.add(new Model(lane, role, packed, stems.getOrDefault(stem, 0), stemNotes, patches, instruments));
            initial.add(settings(role, patches.isEmpty() ? null : patches.getFirst().id(), WRITTEN, DRY, instruments));
        }
        var candidates = new ArrayList<List<VoiceFit.Candidate>>();
        for (int i = 0; i < models.size(); i++) {
            var model = models.get(i);
            var lane = initial.get(i);
            var trials = new ArrayList<VoiceFit.Candidate>();
            if (model.patches().isEmpty()) {
                trials.add(render(windows, references, source, catalogue, model, "", lane));
            } else {
                for (var patch : model.patches()) {
                    var setting = settings(model.role(), patch.id(), WRITTEN, DRY, model.instruments());
                    trials.add(render(windows, references, source, catalogue, model, patch.id(), setting));
                }
            }
            candidates.add(trials);
        }

        var fixed = new java.util.HashSet<Integer>();
        var startingPatches = new ArrayList<VoiceFit.Candidate>();
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).role() == Role.drums) fixed.add(i);
            String incumbent = incumbents.get(i);
            var trials = candidates.get(i);
            startingPatches.add(trials.stream().filter(trial -> trial.id().equals(incumbent)).findFirst()
                    .orElse(trials.getFirst()));
        }
        var fit = new VoiceFit(windows.vector(references, null), windows.split(references), startingPatches, fixed);
        // Fresh selection retains the shortlist order. Refit ties use stable variant IDs.
        var comparisons = previous == null ? candidates : candidates.stream().map(trials -> trials.stream()
                .sorted(Comparator.comparing(VoiceFit.Candidate::id)).toList()).toList();
        fit.patches(comparisons, incumbents);
        var patchIds = new ArrayList<String>();
        var patchScores = new ArrayList<List<ScoredPatch>>();
        for (int i = 0; i < models.size(); i++) {
            var model = models.get(i);
            var selected = fit.selection(i).candidate().id();
            patchIds.add(selected.isEmpty() ? null : selected);
            var scores = new ArrayList<ScoredPatch>();
            for (int j = 0; j < model.patches().size(); j++) {
                var trial = fit.trial(i, candidates.get(i).get(j));
                var patch = model.patches().get(j);
                scores.add(new ScoredPatch(patch.id(), patch.score(), trial.fit(), trial.check()));
            }
            scores.sort(Comparator.comparing((ScoredPatch patch) -> !patch.variantId().equals(selected))
                    .thenComparingDouble(ScoredPatch::fit).thenComparing(ScoredPatch::variantId));
            patchScores.add(List.copyOf(scores));
        }

        String free = ReconstructionAllocation.FM.reversed().stream().filter(lane -> !byLane.containsKey(lane))
                .findFirst().orElse(null);
        var result = new LinkedHashMap<String, Lane>();
        for (int i = 0; i < models.size(); i++) {
            var model = models.get(i);
            var role = model.role();
            String patch = patchIds.get(i);
            var art = articulation(role);
            var effects = DRY;
            String artReason = DEFAULT;
            String effectReason = DEFAULT;
            if (role != Role.drums && !model.packed().notes().isEmpty()) {
                var articulations = new LinkedHashMap<String, Articulation>();
                articulations.put(art.preset(), art);
                articulations.put(WRITTEN.preset(), WRITTEN);
                articulations.put("short", new Articulation("short", .72, 0));
                if (patch != null) articulations.put("fast-release", new Articulation("fast-release", art.gate(), 9));
                boolean changed = choose(windows, references, source, catalogue, model, fit, i, articulations,
                        (name, setting) -> settings(role, patch, setting, DRY, model.instruments()), ARTICULATION_MARGIN);
                art = articulations.get(fit.selection(i).candidate().id());
                if (changed) artReason = EVIDENCE;

                var idioms = effects(role, role == Role.melody && model.id().startsWith("FM") ? free : null);
                final var selectedArticulation = art;
                if (idioms.size() > 1) {
                    changed = choose(windows, references, source, catalogue, model, fit, i, idioms,
                            (name, setting) -> settings(role, patch, selectedArticulation, setting, model.instruments()),
                            EFFECT_MARGIN);
                    effects = idioms.get(fit.selection(i).candidate().id());
                    if (changed) effectReason = EVIDENCE;
                }
                if (effects.copy() != null) free = null;
            }
            double gain = role == Role.drums ? 1 : fit.selection(i).gain();
            String incumbent = incumbents.get(i);
            String patchReason = EVIDENCE;
            if (patch == null) {
                patchReason = model.instruments().isEmpty() ? "not applicable" : "catalogue matcher";
            } else if (patch.equals(initial.get(i).variantId()) && (incumbent == null || patch.equals(incumbent))) {
                patchReason = "shortlist leader (no validated improvement)";
            } else if (patch.equals(incumbent)) {
                patchReason = "previous variant (no material validated improvement)";
            }
            result.put(model.id(), new Lane(role, patch, patchScores.get(i), gain, art, effects,
                    patchReason, artReason, effectReason, model.instruments()));
        }
        return new Result(ReconstructionStages.VERSION, result, windows.fitWindows());
    }

    private static InstrumentCatalogue.Variant incumbent(InstrumentCatalogue catalogue, Result previous, String id) {
        var lane = previous == null ? null : previous.lanes().get(id);
        if (lane == null || lane.variantId() == null) return null;
        InstrumentCatalogue.Match match;
        try {
            match = catalogue.match(lane.variantId());
        } catch (IllegalArgumentException removedFromCatalogue) {
            return null;
        }
        return match.asset().kind() == Kind.FM_VOICE ? match.variant() : null;
    }

    private static <T> boolean choose(VoiceWindows windows, List<VoiceWindows.Reference> references,
                                      S3kReconstruction.Score source, InstrumentCatalogue catalogue, Model model,
                                      VoiceFit fit, int lane, Map<String, T> settings,
                                      java.util.function.BiFunction<String, T, Lane> toLane, double margin) throws IOException {
        var trials = new ArrayList<VoiceFit.Candidate>();
        for (var setting : settings.entrySet())
            trials.add(render(windows, references, source, catalogue, model, setting.getKey(),
                    toLane.apply(setting.getKey(), setting.getValue())));
        var first = trials.getFirst();
        fit.replace(lane, new VoiceFit.Selection(first, fit.selection(lane).gain()));
        fit.replace(lane, fit.trial(lane, first).selection());
        return fit.select(lane, trials.subList(1, trials.size()), margin);
    }

    private static VoiceFit.Candidate render(VoiceWindows windows, List<VoiceWindows.Reference> references,
                                             S3kReconstruction.Score score, InstrumentCatalogue catalogue,
                                             Model model, String id, Lane lane) throws IOException {
        var mix = windows.render(score, catalogue, model.packed(), model.id(), lane);
        var spectra = new HashMap<Integer, float[][]>();
        spectra.put(0, mix);
        if (model.stem() != 0) {
            var stem = model.stemNotes().equals(model.packed()) ? mix
                    : windows.render(score, catalogue, model.stemNotes(), model.id(), lane);
            spectra.put(model.stem(), stem);
        }
        return new VoiceFit.Candidate(id, windows.vector(references, spectra));
    }

    private static Lane settings(Role role, String variant, Articulation articulation, Effects effects,
                                 List<Instrument> instruments) {
        return new Lane(role, variant, List.of(), 1, articulation, effects, DEFAULT, DEFAULT, DEFAULT, instruments);
    }

    private static Map<String, Effects> effects(Role role, String free) {
        var table = new LinkedHashMap<String, Effects>();
        var idiom = idiom(role, free);
        table.put(idiom.preset(), idiom);
        table.put(DRY.preset(), DRY);
        if (role == Role.melody) {
            table.put("mild-vibrato", idiom(role, null));
            table.put("expressive-vibrato", new Effects("expressive-vibrato", 0, new Vibrato(7, 1, 3, 6), null));
            if (free != null) {
                table.put("echo", new Effects("echo", 0, null, new Copy(free, 13, .28, 0, null)));
                table.put("chorus", new Effects("chorus", -2, idiom.vibrato(),
                        new Copy(free, 0, .25, 5, new Vibrato(1, 4, 1, 12))));
            }
        }
        return table;
    }

    private static Path stemFor(List<ReconstructionAllocation.Entry> notes, Map<Integer, Path> parts, Map<Path, Integer> stems) {
        var counts = new LinkedHashMap<Path, Integer>();
        for (var note : notes) {
            var path = parts.get(note.note().part());
            if (stems.containsKey(path)) counts.merge(path, 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static Role dominantRole(List<ReconstructionAllocation.Entry> entries) {
        var counts = new EnumMap<Role, Integer>(Role.class);
        for (var note : entries) counts.merge(note.role(), 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    private static Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> matchPsg(
            ReconstructionStages.Score score, InstrumentCatalogue catalogue,
            Map<String, List<ReconstructionAllocation.Entry>> lanes, Path mix, Map<Integer, Path> stems) throws IOException {
        var psg = lanes.entrySet().stream().filter(e -> e.getKey().startsWith("PSG")).flatMap(e -> e.getValue().stream())
                .filter(e -> e.role() != Role.drums).map(e -> e.note().part()).collect(java.util.stream.Collectors.toSet());
        var notes = score.notes().stream().filter(note -> psg.contains(note.part())).map(ReconstructionStages.Note::midi).toList();
        return ReconstructionVoices.select(catalogue, mix, stems, score.source(), notes,
                score.durationUs(), Kind.PSG_VOLUME);
    }

    private static List<Instrument> fixedInstruments(String lane, List<ReconstructionAllocation.Entry> notes,
            Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> tonal,
            Map<ReconstructionVoices.Key, ReconstructionDrums.Selection> drums) {
        boolean dac = lane.equals("DAC");
        return notes.stream().filter(note -> dac || note.role() != Role.drums)
                .map(note -> new ReconstructionVoices.Key(note.note().part(), dac ? note.note().pitch() : null))
                .distinct().map(key -> {
                    if (dac) {
                        var selection = drums.get(key);
                        return new Instrument(key.partId(), key.drumPitch(), selection.variantId(), selection);
                    }
                    var choice = tonal.get(key);
                    return new Instrument(key.partId(), null, choice == null ? null : choice.variantId());
                }).toList();
    }

    static Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> choices(
            InstrumentCatalogue catalogue, Lane lane, List<ReconstructionAllocation.Entry> notes) {
        var choices = new LinkedHashMap<ReconstructionVoices.Key, ReconstructionVoices.Choice>();
        if (lane.variantId() != null) {
            var choice = choice(catalogue, lane.variantId(), null, lane.patchReason(), lane.shortlist());
            for (var note : notes)
                choices.putIfAbsent(new ReconstructionVoices.Key(note.note().part(), null), choice);
        }
        for (var instrument : lane.instruments())
            choices.put(new ReconstructionVoices.Key(instrument.part(), instrument.drumPitch()),
                    instrument.drumSelection() == null
                            ? choice(catalogue, instrument.variantId(), instrument.drumPitch(), lane.patchReason(), List.of())
                            : drumChoice(instrument.drumSelection()));
        return choices;
    }

    private static ReconstructionVoices.Choice drumChoice(ReconstructionDrums.Selection selection) {
        var candidate = selection.evidence().catalogue();
        var slice = selection.slice();
        String asset = slice != null ? slice.id() : candidate == null ? null : candidate.assetId();
        String name = slice != null ? slice.name() : candidate == null ? null : candidate.name();
        return new ReconstructionVoices.Choice(asset, selection.variantId(), name, selection.rate(),
                selection.evidence().reason(), List.of(), List.of(), "perceptual-drum", slice, selection.evidence());
    }

    private static ReconstructionVoices.Choice choice(InstrumentCatalogue catalogue, String variant, Integer drumPitch,
                                                      String reason, List<ScoredPatch> scores) {
        if (variant == null) return new ReconstructionVoices.Choice(null, null, null, null, "no-match", List.of(), List.of());
        var match = catalogue.match(variant);
        var shortlist = scores.stream().map(score -> {
            var candidate = catalogue.match(score.variantId());
            return new ReconstructionVoices.Candidate(candidate.asset().id(), score.variantId(), candidate.variant().name(),
                    0, score.shortlistScore(), score.fit());
        }).toList();
        return new ReconstructionVoices.Choice(match.asset().id(), variant, match.variant().name(),
                drumPitch == null ? null : match.variant().recipe().rate(), reason, List.of(), shortlist);
    }
}

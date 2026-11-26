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
import com.opensmpsdeck.project.TargetGame;

final class ReconstructionVoiceStage {
    private static final double ARTICULATION_MARGIN = .004;
    private static final double EFFECT_MARGIN = .012;
    // Squared weighted cosine from the attack shortlist. Below this bound,
    // no catalogue candidate explains enough of the exposed attack spectrum.
    private static final double MIN_EXPOSED_ATTACK_MATCH = .16;

    static final String VOCAL_SYNTH = "bright sustained vocal lead synth";
    // Challengers from each source that reach the paired attack comparison.
    private static final int CHALLENGER_LIMIT = 4;

    private record Model(String id, Role role, ReconstructionAllocation.Result packed, int stem, ReconstructionAllocation.Result stemNotes,
                         List<RankedPatch> patches, List<Instrument> instruments, boolean shared,
                         List<RankedPatch> challengers, Path challengeStem) {
        Model withPatch(RankedPatch patch) {
            var extended = new ArrayList<>(patches);
            extended.add(patch);
            return new Model(id, role, packed, stem, stemNotes, List.copyOf(extended), instruments, shared, List.of(), null);
        }
    }

    private ReconstructionVoiceStage() {}

    static Result fit(ReconstructionStages.Score score, InstrumentCatalogue catalogue,
                      ReconstructionAllocation.Result sourceAllocation, ReconstructionAllocation.Result projectAllocation,
                      Result previous, java.util.Set<String> targets,
                      InstrumentCatalogue.Variant chosen) throws IOException {
        String chosenLane = chosen == null ? null : targets.iterator().next();
        boolean shared = chosen == null && targets.isEmpty()
                && (previous == null || previous.sourceInstruments() != null)
                && catalogue.variants().stream().anyMatch(v -> catalogue.asset(v.assetId()).kind() == Kind.FM_VOICE);
        var byLane = byLane(sourceAllocation);
        var noise = byLane.getOrDefault("PSG3", List.of());
        var cymbalGains = ReconstructionCymbals.gains(score, noise);
        // Fit stem-backed cymbals separately. Their noise must not influence
        // patch or gain choices for the tonal parts in a shared PSG3 lane.
        var fitPsg3 = noise.stream().filter(n -> !ReconstructionCymbals.automatic(n.reason())
                || !cymbalGains.containsKey(n.note().pitch())).toList();
        if (!noise.isEmpty() && fitPsg3.isEmpty()) byLane.remove("PSG3");
        else if (fitPsg3.size() != noise.size()) byLane.put("PSG3", fitPsg3);
        var source = new S3kReconstruction.Score(score.source().parts(), List.of(), List.of(), score.profile());
        // General MIDI guitar programs. Synth and keyboard stems can have
        // layered or deliberately evolving attacks that are poor pick targets.
        var guitarParts = source.parts().stream().filter(part -> part.program() >= 24 && part.program() < 32)
                .map(MidiScore.Part::id).collect(java.util.stream.Collectors.toSet());
        var options = score.inputs().options(score.durationUs());
        var mix = Path.of(score.inputs().wav());
        var tonalInstruments = shared ? Map.<ReconstructionVoices.Key, ReconstructionVoices.Choice>of()
                : matchPsg(score, catalogue, byLane, mix, options.stems());
        var drumInstruments = ReconstructionDrums.match(score, catalogue, byLane.getOrDefault("DAC", List.of()),
                mix, options.stems());
        var context = byLane.values().stream().flatMap(List::stream).toList();
        var fitGroups = shared ? bySourceInstrument(context) : byLane;
        Integer lead = score.inputs().leadPart();
        String vocalGroup = lead == null || !ReconstructionVocalLead.isVocal(score.inputs(), lead)
                ? null : fitGroups.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(chosenLane) && entry.getValue().stream().anyMatch(note ->
                        "FM1".equals(note.lane()) && note.note().part() == lead))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        var priors = new HashMap<String, StockVoicePrior>();
        fitGroups.forEach((id, notes) -> {
            if (id.equals(chosenLane) || !(id.startsWith("FM") || shared && id.startsWith("part-"))) return;
            var role = shared && notes.stream().anyMatch(n -> n.role() == Role.melody)
                    ? Role.melody : dominantRole(notes);
            var prior = StockVoicePrior.forNotes(score, notes, mix, options.stems(), role);
            if (prior != null) priors.put(id, prior);
        });
        var foreground = new LinkedHashMap<String, List<ReconstructionAllocation.Entry>>();
        if (shared) fitGroups.forEach((id, notes) -> {
            var line = exposedLine(notes);
            if (!id.equals(vocalGroup) && !line.isEmpty()) foreground.put(id, line);
        });
        var savedWindows = previous == null ? null : previous.fitWindows();
        var windows = fitWindows(score.frames(), byLane, savedWindows, foreground, fitGroups);
        var holds = ReconstructionHeldNotes.measure(score, context.stream()
                .filter(n -> n.role() != Role.drums).map(ReconstructionAllocation.Entry::note).toList());
        var timeline = ReconstructionTimeMap.of(score);
        var stockSustain = new HashMap<String, ReconstructionHeldNotes.StockScreen>();
        if (!priors.isEmpty()) {
            // Acoustic fit windows use source time. Decay probes need the gate
            // durations actually played on the project clock.
            var projectFm = projectAllocation.notes().stream()
                    .filter(n -> n.lane() != null && n.lane().startsWith("FM") && n.role() != Role.drums)
                    .toList();
            var projectGroups = shared ? bySourceInstrument(projectFm) : byLane(projectAllocation);
            var sourceHolds = timeline.warped()
                    ? ReconstructionHeldNotes.measure(score, projectFm.stream()
                            .map(n -> timeline.sourceNote(n.note())).toList())
                    : holds;
            for (var id : priors.keySet()) {
                var screen = ReconstructionHeldNotes.stockScreen(catalogue,
                        projectGroups.getOrDefault(id, List.of()), sourceHolds, timeline);
                if (screen != null) stockSustain.put(id, screen);
            }
        }
        var sustained = new java.util.HashSet<String>();
        var screening = new VoicePatchShortlist(catalogue, options.target());
        var leadPatches = new HashMap<String, VoicePatchShortlist.Ranking>();
        // Only an S3K source part with a stem of its own has attacks to settle a challenge.
        java.util.function.Predicate<String> challenged = id -> shared && options.target().s3kRules()
                && challengeable(id, vocalGroup, fitGroups.get(id).getFirst().note().part(), options.stems(), mix);
        var unsupported = new ArrayList<String>();
        for (var focus : foreground.entrySet()) {
            var candidates = screening.rank(focus.getValue(), context, mix, options.stems(), windows,
                    incumbent(catalogue, previous, focus.getKey()), true, priors.get(focus.getKey()), null,
                    challenged.test(focus.getKey()));
            // Challengers are scored on the same attacks and stay out of this decision.
            if (candidates.clearestMatch() >= MIN_EXPOSED_ATTACK_MATCH)
                leadPatches.put(focus.getKey(), candidates);
            else unsupported.add(focus.getKey());
        }
        if (!unsupported.isEmpty()) {
            unsupported.forEach(foreground::remove);
            windows = fitWindows(score.frames(), byLane, savedWindows, foreground, fitGroups);
            leadPatches.clear(); // The held-out windows may have moved.
        }
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
        String vocalSynth = null;
        for (var entry : fitGroups.entrySet()) {
            String lane = entry.getKey();
            var notes = foreground.getOrDefault(lane, entry.getValue());
            boolean instrument = shared && lane.startsWith("part-");
            boolean fm = instrument || lane.startsWith("FM");
            var incumbent = lane.equals(chosenLane) ? chosen
                    : fm ? incumbent(catalogue, previous, lane) : null;
            var ranking = lane.equals(chosenLane) ? null : leadPatches.get(lane);
            var patches = ranking == null ? null : ranking.clearest();
            if (lane.equals(chosenLane)) patches = List.of(new RankedPatch(chosen.id(), 0));
            var prior = priors.get(lane);
            boolean pinnedVocal = false;
            if (lane.equals(vocalGroup)) {
                var savedLane = savedPatch(previous, lane);
                if (incumbent != null && savedLane != null && confirmedVocalPatch(savedLane.patchReason())
                        && (prior == null || explicitPatch(savedLane.patchReason()))) {
                    patches = List.of(new RankedPatch(incumbent.id(), 0));
                    pinnedVocal = true;
                    priors.remove(lane);
                    stockSustain.remove(lane);
                    prior = null;
                    if (VOCAL_SYNTH.equals(savedLane.patchReason())) vocalSynth = incumbent.id();
                } else if (prior == null) {
                    var synth = screening.leadSynth();
                    if (synth != null) {
                        vocalSynth = synth.id();
                        patches = List.of(synth);
                    }
                }
            }
            boolean exposed = foreground.containsKey(lane);
            if (patches == null && fm) {
                ranking = screening.rank(notes, context, mix, options.stems(), windows, incumbent, exposed, prior, null,
                        challenged.test(lane));
                patches = ranking.clearest();
            } else if (patches == null) patches = List.of();
            // A pinned or explicit patch has no ranking, so it meets no challengers.
            var pool = new ArrayList<RankedPatch>();
            if (ranking != null && patches == ranking.clearest())
                ranking.typical().stream().limit(CHALLENGER_LIMIT).forEach(pool::add);
            Path challengeStem = null;
            if (fm && !pinnedVocal && (!lane.equals(vocalGroup) || prior != null) && !lane.equals(chosenLane)) {
                var examples = ReconstructionHeldNotes.examples(entry.getValue(), holds);
                if (!examples.isEmpty()) {
                    sustained.add(lane);
                    patches = ReconstructionHeldNotes.fittingPatches(catalogue, score.profile(), patches, examples);
                    if (!pool.isEmpty()) {
                        var both = new ArrayList<>(patches);
                        both.addAll(pool);
                        pool.retainAll(ReconstructionHeldNotes.fittingPatches(catalogue, score.profile(), both, examples));
                    }
                }
                if (instrument && options.target().s3kRules()) {
                    int part = entry.getValue().getFirst().note().part();
                    var stem = options.stems().get(part);
                    if (stem != null && !mix.equals(stem)) {
                        var fitting = screening.fittingAttacks(patches, entry.getValue(), stem, windows, guitarParts.contains(part));
                        // Full-volume attacks can misjudge a quiet part, so the rejected patches may
                        // challenge. The sung lead and a shared stem keep the screen and raise no one.
                        if (challenged.test(lane)) {
                            patches.stream().filter(patch -> !fitting.contains(patch)).limit(CHALLENGER_LIMIT).forEach(pool::add);
                            challengeStem = stem;
                        }
                        patches = fitting;
                    }
                }
                var sustain = stockSustain.get(lane);
                if (sustain != null) {
                    sustained.add(lane);
                    var eligible = new ArrayList<RankedPatch>();
                    for (var patch : patches) if (sustain.loss(patch.id()) == 0) eligible.add(patch);
                    // Preserve the acoustic finalists when possible. If all fail, search
                    // the full catalogue before accepting a patch with inadequate sustain.
                    patches = eligible.isEmpty()
                            ? screening.rank(notes, context, mix, options.stems(), windows, incumbent, exposed, prior, sustain,
                                    false).clearest()
                            : List.copyOf(eligible);
                    for (var patch : List.copyOf(pool)) if (sustain.loss(patch.id()) > 0) pool.remove(patch);
                }
            }
            incumbents.add(incumbent == null ? null : incumbent.id());
            var instruments = fm ? List.<Instrument>of() : fixedInstruments(lane, notes, tonalInstruments, drumInstruments);
            var toneContext = notes;
            if (lane.equals("PSG3") && notes.stream().anyMatch(n -> n.role() != Role.drums))
                toneContext = notes.stream().filter(n -> !ReconstructionCymbals.automatic(n.reason())).toList();
            Role role = instrument && toneContext.stream().anyMatch(n -> n.role() == Role.melody)
                    ? Role.melody : dominantRole(toneContext);
            var packed = windows.pack(notes);
            // A lane can carry more than one MIDI part. Use its most represented
            // stem for fitting, with only the notes belonging to that stem.
            var stem = stemFor(toneContext, options.stems(), stems);
            var stemNotes = stem == null ? null : windows.pack(toneContext.stream()
                    .filter(note -> stem.equals(options.stems().get(note.note().part()))).toList());
            var kept = patches;
            pool.removeIf(kept::contains);
            if (challengeStem == null) pool.clear();
            models.add(new Model(lane, role, packed, stems.getOrDefault(stem, 0), stemNotes, patches, instruments, instrument,
                    List.copyOf(pool), challengeStem));
            var settings = settings(role, patches.isEmpty() ? null : patches.getFirst().id(), WRITTEN, DRY, instruments);
            initial.add(lane.equals("PSG3") && !cymbalGains.isEmpty() ? settings.withDrumGains(cymbalGains) : settings);
        }
        var candidates = new ArrayList<List<VoiceFit.Candidate>>();
        for (int i = 0; i < models.size(); i++) {
            var model = models.get(i);
            var lane = initial.get(i);
            var trials = new ArrayList<VoiceFit.Candidate>();
            if (model.patches().isEmpty()) {
                trials.add(render(windows, references, source, catalogue, model, "", lane));
            } else {
                for (var patch : model.patches())
                    trials.add(patchTrial(windows, references, source, catalogue, model, patch,
                            priors.get(model.id()), stockSustain.get(model.id())));
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
        if (chosen != null) {
            var allowed = new ArrayList<>(comparisons);
            for (int i = 0; i < models.size(); i++)
                if (models.get(i).id().equals(chosenLane)) allowed.set(i, List.of(startingPatches.get(i)));
            comparisons = allowed;
        }
        fit.patches(comparisons, incumbents);
        if (chosen == null) {
            // The decision above stands, on every lane. A challenger whose attacks are closer to
            // the stem meets the patch that decision selected, in that same arrangement, and
            // must improve both losses by the incumbent margin. No other lane moves.
            var replaced = new LinkedHashMap<Integer, VoiceFit.Selection>();
            for (int i = 0; i < models.size(); i++) {
                var model = models.get(i);
                if (model.challengers().isEmpty()) continue;
                var selection = fit.selection(i);
                String held = selection.candidate().id();
                var holder = model.patches().stream().filter(patch -> patch.id().equals(held)).findFirst().orElse(null);
                if (holder == null) continue;
                var winner = screening.attackWinner(model.challengers(), holder, fitGroups.get(model.id()),
                        model.challengeStem(), windows);
                if (winner == null) continue;
                var trial = patchTrial(windows, references, source, catalogue, model, winner,
                        priors.get(model.id()), stockSustain.get(model.id()));
                candidates.get(i).add(trial);
                models.set(i, model.withPatch(winner));
                if (fit.challenge(i, trial, VoiceFit.INCUMBENT_MARGIN)) replaced.put(i, selection);
            }
            // Each replacement must still earn its place beside the selections that remain.
            fit.settle(replaced, VoiceFit.INCUMBENT_MARGIN);
        }
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

        var reservedCopies = new java.util.HashSet<String>();
        if (previous != null && !targets.isEmpty()) {
            previous.lanes().forEach((id, lane) -> {
                if (!targets.contains(id) && lane.effects().copy() != null) reservedCopies.add(lane.effects().copy().lane());
            });
            if (previous.sourceInstruments() != null) previous.sourceInstruments().values().forEach(sound -> {
                if (sound.fm().effects().copy() != null) reservedCopies.add(sound.fm().effects().copy().lane());
            });
        }
        String free = ReconstructionAllocation.FM.reversed().stream()
                .filter(lane -> !byLane.containsKey(lane) && !reservedCopies.contains(lane)).findFirst().orElse(null);
        var result = new LinkedHashMap<String, Lane>();
        for (int i = 0; i < models.size(); i++) {
            var drumGains = initial.get(i).drumGains();
            var model = models.get(i);
            var role = model.role();
            String patch = patchIds.get(i);
            var art = articulation(role);
            var effects = DRY;
            String artReason = DEFAULT;
            String effectReason = DEFAULT;
            if (model.id().equals(vocalGroup)) {
                effects = vocalVibrato(score, lead, mix, options.stems());
                effectReason = "vocal lead synth";
            } else if (role != Role.drums && !model.packed().notes().isEmpty()) {
                var articulations = new LinkedHashMap<String, Articulation>();
                articulations.put(art.preset(), art);
                articulations.put(WRITTEN.preset(), WRITTEN);
                if (!sustained.contains(model.id()))
                    articulations.put("short", new Articulation("short", .72, 0));
                boolean changed = choose(windows, references, source, catalogue, model, fit, i, articulations,
                        (name, setting) -> settings(role, patch, setting, DRY, model.instruments())
                                .withDrumGains(drumGains), ARTICULATION_MARGIN);
                art = articulations.get(fit.selection(i).candidate().id());
                if (changed) artReason = EVIDENCE;

                boolean singleFm = model.shared() ? singleFm(fitGroups.get(model.id())) : model.id().startsWith("FM");
                var idioms = effects(role, role == Role.melody && singleFm ? free : null);
                final var selectedArticulation = art;
                if (idioms.size() > 1) {
                    changed = choose(windows, references, source, catalogue, model, fit, i, idioms,
                            (name, setting) -> settings(role, patch, selectedArticulation, setting, model.instruments())
                                    .withDrumGains(drumGains),
                            EFFECT_MARGIN);
                    effects = idioms.get(fit.selection(i).candidate().id());
                    if (changed) effectReason = EVIDENCE;
                }
                if (effects.copy() != null && (targets.isEmpty() || targets.contains(model.id()))) free = null;
            }
            double gain = role == Role.drums ? 1 : fit.selection(i).gain();
            if (foreground.containsKey(model.id())) {
                // The line determines the level. Account for the backing that
                // will also sound at that level so its energy cannot inflate it.
                var wholePart = windows.pack(fitGroups.get(model.id()));
                var contextModel = new Model(model.id(), model.role(), wholePart, model.stem(),
                        wholePart, model.patches(), model.instruments(), model.shared(), List.of(), null);
                var full = render(windows, references, source, catalogue, contextModel, "",
                        settings(role, patch, art, effects, model.instruments()));
                gain = Math.min(gain, fit.trial(i, full).selection().gain());
            }
            String incumbent = incumbents.get(i);
            String patchReason = EVIDENCE;
            if (model.id().equals(chosenLane)) {
                patchReason = "explicit variant with fitted levels";
            } else if (model.id().equals(vocalGroup) && patch != null && patch.equals(vocalSynth)) {
                patchReason = VOCAL_SYNTH;
            } else if (model.id().equals(vocalGroup) && !priors.containsKey(model.id()) && patch != null && patch.equals(incumbent)) {
                patchReason = savedPatch(previous, model.id()).patchReason();
            } else if (patch == null) {
                patchReason = model.instruments().isEmpty() ? "not applicable" : "catalogue matcher";
            } else if (patch.equals(initial.get(i).variantId()) && (incumbent == null || patch.equals(incumbent))) {
                patchReason = "shortlist leader (no validated improvement)";
            } else if (patch.equals(incumbent)) {
                patchReason = "previous variant (no material validated improvement)";
            }
            var prior = priors.get(model.id());
            if (prior != null && patch != null) {
                patchReason = prior.reason(catalogue, patch, patchScores.get(i).getFirst().fit(), "joint fit");
                var sustain = stockSustain.get(model.id());
                if (sustain != null && sustain.loss(patch) > 0)
                    patchReason += ReconstructionHeldNotes.StockScreen.FALLBACK;
            }
            result.put(model.id(), new Lane(role, patch, patchScores.get(i), gain, art, effects,
                    patchReason, artReason, effectReason, model.instruments()).withDrumGains(drumGains));
        }
        // Refine low-register patches after the joint fit so this local timbre
        // objective cannot shift the lead's patch or articulation.
        for (int i = 0; i < models.size(); i++) {
            var model = models.get(i);
            var lane = result.get(model.id());
            if (model.id().equals(vocalGroup) || model.id().equals(chosenLane)
                    || lane.variantId() == null || model.stem() == 0) continue;
            var notes = foreground.getOrDefault(model.id(), fitGroups.get(model.id()));
            if (model.shared() && !singleFm(notes)) continue;
            var stem = stemFor(notes, options.stems(), stems);
            if (!LowRegisterSpectrum.lowRegister(notes)) continue;
            var isolated = notes.stream().filter(n -> stem.equals(options.stems().get(n.note().part()))).toList();
            var stemContext = context.stream().filter(n -> stem.equals(options.stems().get(n.note().part()))).toList();
            var candidatesForBass = screening.lowCandidates(isolated, stemContext, stem, windows, priors.get(model.id()));
            if (sustained.contains(model.id())) {
                var allowed = model.patches().stream().map(RankedPatch::id).collect(java.util.stream.Collectors.toSet());
                candidatesForBass = candidatesForBass.stream().filter(patch -> allowed.contains(patch.id())).toList();
            }
            var selected = LowRegisterFit.refine(source, catalogue, stem, notes.getFirst().lane(), lane,
                    notes, isolated, candidatesForBass);
            if (selected == null) continue;
            var choice = selected.patch();
            var evidence = new ArrayList<ScoredPatch>();
            evidence.add(new ScoredPatch(choice.id(), choice.score(), selected.fit(), selected.check()));
            lane.shortlist().stream().filter(patch -> !patch.variantId().equals(choice.id()))
                    .limit(lane.shortlist().size() - 1L).forEach(evidence::add);
            var prior = priors.get(model.id());
            String reason = prior == null ? "low-register stem harmonics"
                    : prior.reason(catalogue, choice.id(), selected.fit(), "low-register harmonics");
            result.put(model.id(), new Lane(lane.role(), choice.id(), List.copyOf(evidence), lane.gain(),
                    lane.articulation(), lane.effects(), reason, lane.articulationReason(),
                    lane.effectReason(), lane.instruments()));
        }
        if (shared) {
            var instruments = new LinkedHashMap<String, SourceSound>();
            for (var model : models) if (model.shared()) {
                var fm = result.remove(model.id());
                var spilled = fitGroups.get(model.id()).stream().filter(n -> n.lane().startsWith("PSG")).toList();
                int partId = fitGroups.get(model.id()).getFirst().note().part();
                var part = source.parts().stream().filter(item -> item.id() == partId).findFirst().orElseThrow();
                if (fm.variantId() == null) continue;
                var psg = ReconstructionSpill.match(catalogue, fm, part, spilled, score.inputs().profile(), holds);
                instruments.put(model.id(), new SourceSound(fm, psg.variantId(), psg.volumeOffset()));
            }
            byLane.forEach((lane, notes) -> result.putIfAbsent(lane,
                    settings(dominantRole(notes), null, WRITTEN, DRY, List.of())));
            if (!noise.isEmpty() && fitPsg3.isEmpty())
                result.put("PSG3", settings(Role.drums, null, WRITTEN, DRY, List.of()).withDrumGains(cymbalGains));
            return new Result(ReconstructionStages.VERSION, result, windows.fitWindows()).withSourceInstruments(instruments);
        }
        if (!noise.isEmpty() && fitPsg3.isEmpty())
            result.put("PSG3", settings(Role.drums, null, WRITTEN, DRY, List.of()).withDrumGains(cymbalGains));
        return new Result(ReconstructionStages.VERSION, result, windows.fitWindows());
    }

    /**
     * Whether a fit group may raise challengers: a source part that is not the sung lead
     * and whose stem is a real file no other part and not the mix points at. An
     * unreadable path counts as shared.
     */
    static boolean challengeable(String id, String vocalGroup, int part, Map<Integer, Path> stems, Path mix) {
        if (!id.startsWith("part-") || id.equals(vocalGroup)) return false;
        var stem = stems.get(part);
        if (stem == null) return false;
        try {
            if (sameFile(stem, mix)) return false;
            for (var other : stems.entrySet())
                if (other.getKey() != part && sameFile(stem, other.getValue())) return false;
        } catch (IOException unreadable) {
            return false;
        }
        return true;
    }

    private static boolean sameFile(Path first, Path second) throws IOException {
        // A missing or unreadable path throws, and the caller counts that as shared.
        return first.equals(second) || java.nio.file.Files.isSameFile(first, second);
    }

    private static VoiceFit.Candidate patchTrial(VoiceWindows windows, List<VoiceWindows.Reference> references,
            S3kReconstruction.Score source, InstrumentCatalogue catalogue, Model model, RankedPatch patch,
            StockVoicePrior prior, ReconstructionHeldNotes.StockScreen sustain) throws IOException {
        var setting = settings(model.role(), patch.id(), WRITTEN, DRY, model.instruments());
        var candidate = render(windows, references, source, catalogue, model, patch.id(), setting);
        if (prior == null || sustain != null && sustain.loss(patch.id()) > 0) return candidate;
        var evidence = prior.evidence(catalogue, catalogue.match(patch.id()).variant());
        return new VoiceFit.Candidate(candidate.id(), candidate.spectrum(), evidence.preference());
    }

    private static boolean confirmedVocalPatch(String reason) {
        return VOCAL_SYNTH.equals(reason) || EVIDENCE.equals(reason)
                || explicitPatch(reason);
    }

    private static boolean explicitPatch(String reason) {
        return "explicit variant with fitted levels".equals(reason);
    }

    private static Effects vocalVibrato(ReconstructionStages.Score score, int part, Path mix,
                                         Map<Integer, Path> stems) throws IOException {
        var notes = score.notes().stream().filter(note -> note.part() == part)
                .map(ReconstructionStages.Note::midi).toList();
        var identity = score.source().parts().stream().filter(item -> item.id() == part).findFirst().orElseThrow();
        var estimate = ReconstructionVibrato.detect(new MidiScore(List.of(identity), notes, List.of()),
                mix, stems, score.durationUs()).get(part);
        if (estimate == null) return idiom(Role.melody, null);
        int[] pitches = notes.stream().mapToInt(MidiScore.Note::pitch).sorted().toArray();
        int pitch = pitches[pitches.length / 2];
        var modulation = ReconstructionEditing.vibrato(pitch, 0, estimate.rateHz(), estimate.depthCents());
        return new Effects("vocal-stem-vibrato", 0,
                new Vibrato(7, modulation.rate(), modulation.delta(), modulation.steps()), null);
    }

    private static VoiceWindows fitWindows(int frames, Map<String, List<ReconstructionAllocation.Entry>> byLane,
                                           FitWindows previous, Map<String, List<ReconstructionAllocation.Entry>> foreground,
                                           Map<String, List<ReconstructionAllocation.Entry>> fitGroups) {
        return new VoiceWindows(frames, byLane, previous, foreground.entrySet().stream()
                .map(entry -> new VoiceWindows.Focus(entry.getValue(), fitGroups.get(entry.getKey()))).toList());
    }

    static List<ReconstructionAllocation.Entry> exposedLine(List<ReconstructionAllocation.Entry> notes) {
        var melody = notes.stream().filter(n -> n.role() == Role.melody).toList();
        // A handful of isolated notes cannot stand in for the whole source part.
        return melody.size() >= 4 && melody.size() < notes.size() ? melody : List.of();
    }

    private static Map<String, List<ReconstructionAllocation.Entry>> bySourceInstrument(
            List<ReconstructionAllocation.Entry> notes) {
        var groups = new LinkedHashMap<String, List<ReconstructionAllocation.Entry>>();
        notes.stream().sorted(Comparator.comparingInt(ReconstructionAllocation.Entry::startTick))
                .forEach(note -> {
                    String id = note.role() == Role.drums ? note.lane()
                            : ReconstructionVoicePlan.SourceInstrument.id(note.note().part());
                    groups.computeIfAbsent(id, ignored -> new ArrayList<>()).add(note);
                });
        return groups;
    }

    private static boolean singleFm(List<ReconstructionAllocation.Entry> notes) {
        String lane = notes.getFirst().lane();
        return lane.startsWith("FM") && notes.stream().allMatch(n -> lane.equals(n.lane()));
    }

    private static Lane savedPatch(Result previous, String id) {
        if (previous == null) return null;
        var source = previous.sourceInstruments() == null ? null : previous.sourceInstruments().get(id);
        return source == null ? previous.lanes().get(id) : source.fm();
    }

    private static InstrumentCatalogue.Variant incumbent(InstrumentCatalogue catalogue, Result previous, String id) {
        var lane = savedPatch(previous, id);
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
        var mix = windows.render(score, catalogue, model.packed(), lane, model.shared());
        var spectra = new HashMap<Integer, float[][]>();
        spectra.put(0, mix);
        if (model.stem() != 0) {
            var stem = model.stemNotes().equals(model.packed()) ? mix
                    : windows.render(score, catalogue, model.stemNotes(), lane, model.shared());
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

    static Role dominantRole(List<ReconstructionAllocation.Entry> entries) {
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
                score.durationUs(), Kind.PSG_VOLUME, ReconstructionStemParts.addedParts(score.inputs()),
                score.inputs().targetGame());
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

    static ReconstructionVoices.Choice drumChoice(ReconstructionDrums.Selection selection) {
        var candidate = selection.evidence().catalogue();
        var slice = selection.slice();
        String asset = slice != null ? slice.id() : candidate == null ? null : candidate.assetId();
        String name = slice != null ? slice.name() : candidate == null ? null : candidate.name();
        return new ReconstructionVoices.Choice(asset, selection.variantId(), name, selection.rate(),
                selection.evidence().reason(), List.of(), List.of(), "perceptual-drum", slice, selection.evidence());
    }

    static ReconstructionVoices.Choice choice(InstrumentCatalogue catalogue, String variant, Integer drumPitch,
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

package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetProfile;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.opensmpsdeck.headless.InstrumentCatalogue.Kind.FM_VOICE;
import static com.opensmpsdeck.headless.VoiceSpectra.SAMPLES_PER_TICK;
import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

/** Lane-specific screening using pitch-aligned attack spectra and shared raw patch probes. */
final class VoicePatchShortlist {
    private static final int ATTACK_TICKS = 7;
    private static final int PROBE_LIMIT = 5;
    private static final int SHORTLIST_LIMIT = 18;
    private static final int TYPICAL_LIMIT = 9;
    private static final int TYPICAL_CHOICE = 8;
    private static final int CACHE_LIMIT = 24_000;
    private static final double VOLUME_STEP_DB = .75;
    // Compare in 3 dB steps down to -36 dB so equal-level probes share templates.
    private static final int LEVEL_STEPS = 4;
    private static final int MAX_LEVEL_STEPS = 12;
    private static final int PAIRED_ATTACKS = 24;
    private static final double PAIRED_BLOCK_SECONDS = 4;
    private static final int PAIRED_BLOCKS = 3;
    private static final int PAIRED_LEVELS = 3;
    // The challenger must be closer in most blocks, and by this much attack loss on average.
    private static final double PAIRED_MARGIN = .01;
    private static final int FLOOR_SAMPLES = VoiceSpectra.RATE / 50;
    // The key-on must rise 6 dB over the 20 ms before it for its level to be its own.
    private static final double CLEAR_ONSET_RATIO = 2;

    record RankedPatch(String id, double score) {}
    private record Key(String asset, int pitch, TargetGame target, int volume) {}
    private record Audibility(double peak, double tailRms) {}
    private record LowProbe(int pitch, LowRegisterSpectrum.Profile profile) {}
    private record Probe(int pitch, float[] target, float[] weights, double exposure) {}
    private record AttackProbe(int pitch, VoiceAttackShape.Profile shape, VoiceSpectralBalance.Profile spectrum) {}
    private record Template(float[] spectrum, LowRegisterSpectrum.Profile lowProfile, VoiceAttackShape.Profile attack) {}
    private static final Map<Key, Template> TEMPLATES = new ConcurrentHashMap<>();

    private final InstrumentCatalogue catalogue;
    private final TargetGame target;
    private final List<InstrumentCatalogue.Variant> variants;
    private final Map<Key, Audibility> audibility = new java.util.HashMap<>();
    private final Map<Key, VoiceSpectralBalance.Profile> balances = new java.util.HashMap<>();
    private final VoiceSpectra spectra = new VoiceSpectra();
    private final LowRegisterSpectrum lowSpectrum = new LowRegisterSpectrum();
    private final VoiceAttackShape attackShape = new VoiceAttackShape();
    private final VoiceSpectralBalance spectralBalance = new VoiceSpectralBalance();

    VoicePatchShortlist(InstrumentCatalogue catalogue, TargetGame target) {
        this.catalogue = catalogue;
        this.target = target;
        variants = stockVoices(catalogue, target);
    }

    /** One variant of each stock FM patch the target can play, in a stable order. */
    static List<InstrumentCatalogue.Variant> stockVoices(InstrumentCatalogue catalogue, TargetGame target) {
        var seen = target == TargetGame.S3K
                ? new HashSet<>(CatalogueMatcher.inferredAzureLakeSlots(catalogue)) : new HashSet<String>();
        return catalogue.variants().stream().sorted(Comparator.comparing(InstrumentCatalogue.Variant::id))
                .filter(variant -> variant.target() == target)
                .filter(variant -> catalogue.asset(variant.assetId()).kind() == FM_VOICE && seen.add(variant.assetId()))
                .toList();
    }

    RankedPatch leadSynth() throws IOException {
        final int ticks = 24, pitch = 69;
        RankedPatch best = null;
        var measured = new java.util.HashSet<String>();
        for (var variant : variants) {
            if (!measured.add(variant.assetId())) continue;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("lead synth selection cancelled");
            var project = CataloguePlayback.matchProbe(catalogue, variant.id(), pitch, ticks, true, target);
            float[] audio = VoiceWindows.renderMono(project, ticks);
            double early = energy(audio, audio.length / 8, audio.length / 2);
            double held = energy(audio, audio.length / 2, audio.length);
            if (held < 1e-8) continue;
            double fundamental = harmonic(audio, 440);
            double overtones = harmonic(audio, 880) + harmonic(audio, 1320) + harmonic(audio, 1760);
            // Favour audible, on-pitch sustained patches with a distinctive
            // upper spectrum. Impulses, sub-oscillators and noise score poorly.
            double pitched = Math.min(1, (fundamental + overtones) / held);
            double brightness = Math.min(1, overtones / Math.max(1e-8, fundamental));
            double sustain = Math.min(1, held / Math.max(1e-8, early));
            double quality = sustain * pitched * (.35 + .65 * brightness)
                    * Math.min(1, fundamental / (held * .25));
            if (best == null || quality > best.score) best = new RankedPatch(variant.id(), quality);
        }
        return best == null && !variants.isEmpty() ? new RankedPatch(variants.getFirst().id(), 0) : best;
    }

    private static double energy(float[] audio, int from, int to) {
        double total = 0;
        for (int i = from; i < to; i++) total += audio[i] * audio[i];
        return total / (to - from);
    }

    private static double harmonic(float[] audio, double hz) {
        int from = audio.length / 2;
        double re = 0, im = 0;
        for (int i = from; i < audio.length; i++) {
            double phase = Math.TAU * hz * i / VoiceSpectra.RATE;
            re += audio[i] * Math.cos(phase);
            im += audio[i] * Math.sin(phase);
        }
        double length = audio.length - from;
        return 2 * (re * re + im * im) / (length * length);
    }

    /**
     * Candidates for one part. {@code clearest} is the shortlist from the
     * clearest attacks, at most {@value #SHORTLIST_LIMIT} entries including
     * the incumbent. {@code typical} holds up to {@value #TYPICAL_LIMIT} more
     * patches that best explain an attack from each stretch of the part.
     * They are challengers: scores are on the clearest attacks, so they
     * cannot change whether those attacks support the part.
     */
    record Ranking(List<RankedPatch> clearest, List<RankedPatch> typical) {
        double clearestMatch() {
            return clearest.stream().mapToDouble(RankedPatch::score).max().orElse(0);
        }
    }

    Ranking rank(List<ReconstructionAllocation.Entry> notes, List<ReconstructionAllocation.Entry> context,
                 Path mix, Map<Integer, Path> stems, VoiceWindows windows,
                 InstrumentCatalogue.Variant incumbent, boolean foreground, StockVoicePrior prior,
                 ReconstructionHeldNotes.StockScreen sustain, boolean challengers) throws IOException {
        var available = new ArrayList<>(notes.stream().filter(n -> windows.trainingAttack(n.startTick(), ATTACK_TICKS)).toList());
        var occupied = new java.util.HashMap<String, int[]>();
        available.sort(Comparator.comparingInt((ReconstructionAllocation.Entry note) -> {
            // Prefer attacks with few other attacks or sustaining notes in the mix.
            String stem = stems.containsKey(note.note().part()) ? stems.get(note.note().part()).toString() : "";
            int[] coverage = occupied.computeIfAbsent(stem, ignored -> coverage(context, stems, stem, windows.songFrames()));
            return coverage[note.startTick()];
        }).thenComparing(Comparator.comparingInt((ReconstructionAllocation.Entry n) ->
                Math.min(foreground ? 18 : ATTACK_TICKS, n.endTick() - n.startTick())).reversed())
                .thenComparingInt(n -> foreground ? -n.note().velocity() : 0));
        var measured = new java.util.IdentityHashMap<ReconstructionAllocation.Entry, java.util.Optional<Probe>>();
        var durations = new java.util.HashMap<Path, Long>();
        var chosen = new ArrayList<ReconstructionAllocation.Entry>();
        var probes = new ArrayList<Probe>();
        for (var note : available) {
            if (probes.size() == PROBE_LIMIT) break;
            addProbe(note, chosen, probes, measured, durations, mix, stems, foreground);
        }
        var ranked = new ArrayList<RankedPatch>();
        var availableVariants = probes.isEmpty() && prior == null && sustain == null
                ? variants.stream().limit(1).toList() : variants;
        for (var variant : availableVariants) ranked.add(rank(variant, probes));
        // The incumbent can use another variant of an asset deduplicated from the pool.
        if (sustain != null && incumbent != null && !variants.contains(incumbent))
            ranked.add(rank(incumbent, probes));
        var shortlist = shortlist(ranked, prior, SHORTLIST_LIMIT, sustain);
        if (incumbent != null && (prior == null || eligible(incumbent, prior))
                && (sustain == null || sustain.loss(incumbent.id()) == 0)
                && shortlist.stream().noneMatch(patch -> patch.id().equals(incumbent.id()))) {
            if (shortlist.size() == SHORTLIST_LIMIT) shortlist.removeLast();
            shortlist.add(rank(incumbent, probes));
        }
        if (!challengers || probes.isEmpty()) return new Ranking(List.copyOf(shortlist), List.of());
        // The clearest attacks favour long notes and can all come from one
        // passage. One attack from each stretch of the part can name patches
        // they miss.
        var parts = stretches(available, PROBE_LIMIT);
        // Nothing to add when the clearest attacks already come from nearly every stretch.
        if (parts.stream().filter(stretch -> stretch.stream().anyMatch(chosen::contains)).count() >= PROBE_LIMIT - 1)
            return new Ranking(List.copyOf(shortlist), List.of());
        var typical = new ArrayList<ReconstructionAllocation.Entry>();
        var typicalProbes = new ArrayList<Probe>();
        var rendered = new HashSet<Integer>();
        for (var note : chosen) rendered.add(probePitch(note));
        for (var stretch : parts) {
            // Among a stretch's best attacks, one at a pitch already rendered costs no new renders.
            var reuse = stretch.stream().limit(TYPICAL_CHOICE).filter(note -> rendered.contains(probePitch(note))).findFirst();
            if (reuse.isPresent() && addProbe(reuse.get(), typical, typicalProbes, measured, durations, mix, stems, foreground))
                continue;
            for (var note : stretch) {
                if (!addProbe(note, typical, typicalProbes, measured, durations, mix, stems, foreground)) continue;
                rendered.add(probePitch(note));
                break;
            }
        }
        if (typicalProbes.isEmpty() || new HashSet<>(chosen).containsAll(typical))
            return new Ranking(List.copyOf(shortlist), List.of());
        var spread = new ArrayList<RankedPatch>();
        for (var variant : variants) spread.add(rank(variant, typicalProbes));
        var onClearest = new java.util.HashMap<String, RankedPatch>();
        for (var patch : ranked) onClearest.put(patch.id(), patch);
        var admitted = new ArrayList<RankedPatch>();
        for (var patch : shortlist(spread, prior, TYPICAL_LIMIT, sustain))
            if (shortlist.stream().noneMatch(kept -> kept.id().equals(patch.id()))) admitted.add(onClearest.get(patch.id()));
        return new Ranking(List.copyOf(shortlist), List.copyOf(admitted));
    }

    /** Splits quality-ordered notes into equal runs of the timeline, each kept in quality order. */
    static <T extends ReconstructionAllocation.Entry> List<List<T>> stretches(List<T> ordered, int count) {
        var byTime = ordered.stream().sorted(Comparator.comparingInt(ReconstructionAllocation.Entry::startTick)).toList();
        var rank = new java.util.IdentityHashMap<T, Integer>();
        for (int i = 0; i < ordered.size(); i++) rank.put(ordered.get(i), i);
        var result = new ArrayList<List<T>>();
        for (int i = 0; i < count; i++) {
            var stretch = new ArrayList<>(byTime.subList(i * byTime.size() / count, (i + 1) * byTime.size() / count));
            stretch.sort(Comparator.comparingInt(rank::get));
            if (!stretch.isEmpty()) result.add(stretch);
        }
        return result;
    }

    private int probePitch(ReconstructionAllocation.Entry note) {
        return Math.min(note.note().pitch(), TargetProfile.of(target).fm().high());
    }

    private boolean addProbe(ReconstructionAllocation.Entry note, List<ReconstructionAllocation.Entry> chosen,
                             List<Probe> probes, Map<ReconstructionAllocation.Entry, java.util.Optional<Probe>> measured,
                             Map<Path, Long> durations, Path mix, Map<Integer, Path> stems,
                             boolean foreground) throws IOException {
        if (chosen.stream().anyMatch(n -> Math.abs(n.startTick() - note.startTick()) < 25)) return false;
        if (chosen.stream().filter(n -> n.note().pitch() == note.note().pitch()).count() >= 2) return false;
        var probe = measured.get(note);
        if (probe == null) {
            Path path = stems.getOrDefault(note.note().part(), mix);
            Long end = durations.get(path);
            if (end == null) {
                end = ReferenceAudio.durationMicros(path);
                durations.put(path, end);
            }
            probe = java.util.Optional.ofNullable(probe(path, end, note, foreground));
            measured.put(note, probe);
        }
        if (probe.isEmpty()) return false;
        chosen.add(note);
        probes.add(probe.get());
        return true;
    }

    /**
     * Keep viable attacks for the longer contextual fit. The spectral shortlist
     * uses 85 ms windows and can hide a slow rise or a brief burst of harmonics.
     */
    List<RankedPatch> fittingAttacks(List<RankedPatch> candidates,
                                    List<ReconstructionAllocation.Entry> part,
                                    Path stem, VoiceWindows windows, boolean guitar) throws IOException {
        if (candidates.size() < 2 || stem == null) return candidates;
        double duration = VoiceAttackShape.SAMPLES / (double) VoiceSpectra.RATE;
        var exposed = exposedAttacks(part, stem, windows);
        var probes = new ArrayList<AttackProbe>();
        var pitches = new java.util.HashMap<Integer, Integer>();
        // Interleave the timeline so a fast run cannot supply all examples.
        int stride = Math.max(1, exposed.size() / 8);
        for (int i = 0; i < exposed.size() && probes.size() < 8; i += stride) {
            var note = exposed.get(i).note();
            int pitch = note.pitch();
            var range = TargetProfile.of(target).fm();
            if (pitch < range.low() || pitch > range.high() || pitches.getOrDefault(pitch, 0) >= 2) continue;
            var audio = ReferenceAudio.read(stem, note.start(), duration);
            var shape = attackShape.measure(VoiceSpectra.resample(audio, VoiceAttackShape.SAMPLES));
            if (shape.peak() < .003) continue;
            var spectral = audio.sampleRate() >= 16000
                    ? spectralBalance.measure(audio.samples(), 0, audio.sampleRate(), fundamental(pitch)) : null;
            probes.add(new AttackProbe(pitch, shape, spectral));
            pitches.merge(pitch, 1, Integer::sum);
        }
        if (probes.size() < 3) return candidates;

        var losses = new double[candidates.size()];
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < candidates.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("attack fitting cancelled");
            var variant = catalogue.match(candidates.get(i).id(), target).variant();
            for (var probe : probes) losses[i] += attackLoss(variant, probe, 127) / probes.size();
            best = Math.min(best, losses[i]);
        }
        // Leave the contextual fit a choice when attacks differ only slightly.
        var result = new ArrayList<RankedPatch>();
        for (int i = 0; i < candidates.size(); i++)
            if (losses[i] <= best + (guitar ? .10 : .20)) result.add(candidates.get(i));
        return List.copyOf(result);
    }

    /** Key-ons with no other note of the part sounding on the stem, in time order. */
    private List<ReconstructionAllocation.Entry> exposedAttacks(List<ReconstructionAllocation.Entry> part,
                                                                Path stem, VoiceWindows windows) throws IOException {
        double duration = VoiceAttackShape.SAMPLES / (double) VoiceSpectra.RATE;
        double audioEnd = ReferenceAudio.durationMicros(stem) / 1e6;
        var ordered = part.stream().sorted(Comparator.comparingDouble(n -> n.note().start())).toList();
        var exposed = new ArrayList<ReconstructionAllocation.Entry>();
        double previousEnd = 0;
        for (int i = 0; i < ordered.size(); i++) {
            var entry = ordered.get(i);
            var note = entry.note();
            double nextStart = i + 1 < ordered.size() ? ordered.get(i + 1).note().start()
                    : Double.POSITIVE_INFINITY;
            // Reject chords and continuing notes on the same stem. A small
            // boundary tolerance permits adjacent gates with score rounding.
            if (previousEnd <= note.start() + .005 && nextStart >= note.start() + duration
                    && note.run() == null && entry.lane().startsWith("FM")
                    && entry.endTick() - entry.startTick() >= 3
                    && note.end() - note.start() >= duration && note.start() + duration <= audioEnd
                    && windows.trainingAttack(entry.startTick(), 3)) exposed.add(entry);
            previousEnd = Math.max(previousEnd, note.end());
        }
        return exposed;
    }

    private double attackLoss(InstrumentCatalogue.Variant variant, AttackProbe probe, int volume) throws IOException {
        double loss = probe.shape().distance(template(variant, probe.pitch(), volume).attack());
        if (probe.spectrum() == null) return loss;
        var stock = spectralProbe(variant, probe.pitch(), volume);
        return loss + .012 * probe.spectrum().distance(stock) + .025 * probe.spectrum().excess(stock);
    }

    private record PairedAttack(int block, AttackProbe probe, boolean clearOnset) {}

    /**
     * The challenger whose attacks match the stem better than the holder's,
     * or null. Each patch plays at the track volume that reaches the stem's
     * attack level, because several carriers at full volume clip the channel
     * and a quiet part then does not match its own patch. That level is an
     * estimate. The win must also hold one 3 dB step to the other side of the
     * measurement, and at full volume wherever the onset does not stand
     * clear of what was already sounding. No evidence keeps the holder.
     */
    RankedPatch attackWinner(List<RankedPatch> challengers, RankedPatch holder,
                             List<ReconstructionAllocation.Entry> part, Path stem, VoiceWindows windows)
            throws IOException {
        if (challengers.isEmpty() || stem == null) return null;
        var exposed = exposedAttacks(part, stem, windows);
        double lead = FLOOR_SAMPLES / (double) VoiceSpectra.RATE;
        double duration = VoiceAttackShape.SAMPLES / (double) VoiceSpectra.RATE;
        var attacks = new ArrayList<PairedAttack>();
        var range = TargetProfile.of(target).fm();
        // The part's own previous note explains sound before a legato key-on.
        var ends = new java.util.TreeMap<Double, Double>();
        double latest = 0;
        for (var entry : part.stream().sorted(Comparator.comparingDouble(n -> n.note().start())).toList()) {
            ends.put(entry.note().start(), latest);
            latest = Math.max(latest, entry.note().end());
        }
        int stride = Math.max(1, exposed.size() / PAIRED_ATTACKS);
        for (int i = 0; i < exposed.size() && attacks.size() < PAIRED_ATTACKS; i += stride) {
            var note = exposed.get(i).note();
            if (note.pitch() < range.low() || note.pitch() > range.high() || note.start() < lead) continue;
            var audio = ReferenceAudio.read(stem, note.start() - lead, lead + duration);
            var mono = VoiceSpectra.resample(audio, FLOOR_SAMPLES + VoiceAttackShape.SAMPLES);
            var shape = attackShape.measure(java.util.Arrays.copyOfRange(mono, FLOOR_SAMPLES, mono.length));
            if (shape.peak() < .003) continue;
            int from = (int) Math.round(lead * audio.sampleRate());
            var spectral = audio.sampleRate() >= 16000 && from + VoiceSpectralBalance.SIZE <= audio.samples().length
                    ? spectralBalance.measure(audio.samples(), from, audio.sampleRate(), fundamental(note.pitch())) : null;
            attacks.add(new PairedAttack((int) (note.start() / PAIRED_BLOCK_SECONDS),
                    new AttackProbe(note.pitch(), shape, spectral),
                    shape.peak() >= CLEAR_ONSET_RATIO * level(mono, 0, FLOOR_SAMPLES)
                            || ends.get(note.start()) >= note.start() - lead));
        }
        var blocks = new java.util.TreeMap<Integer, List<Integer>>();
        for (int i = 0; i < attacks.size(); i++)
            blocks.computeIfAbsent(attacks.get(i).block(), ignored -> new ArrayList<>()).add(i);
        blocks.values().removeIf(members -> members.size() < 2);
        if (blocks.size() < PAIRED_BLOCKS) return null;

        var holderVariant = catalogue.match(holder.id(), target).variant();
        var held = new double[PAIRED_LEVELS][];
        RankedPatch winner = null;
        double best = -PAIRED_MARGIN;
        for (var challenger : challengers) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("attack comparison cancelled");
            var variant = catalogue.match(challenger.id(), target).variant();
            double worst = Double.NEGATIVE_INFINITY;
            // Most challengers fail at the estimated level, so the other levels wait for one that does not.
            for (int level = 0; level < PAIRED_LEVELS && worst < best; level++) {
                if (held[level] == null) held[level] = pairedLosses(holderVariant, attacks, level);
                var losses = pairedLosses(variant, attacks, level);
                double total = 0;
                int closer = 0;
                for (var members : blocks.values()) {
                    double difference = 0;
                    for (int attack : members) difference += (losses[attack] - held[level][attack]) / members.size();
                    total += difference / blocks.size();
                    if (difference < 0) closer++;
                }
                // One level that fails to show a win in most blocks settles it.
                worst = Math.max(worst, 2 * closer > blocks.size() ? total : 0);
            }
            if (worst < best) {
                best = worst;
                winner = challenger;
            }
        }
        return winner;
    }

    /**
     * Attack losses under one level hypothesis: 0 the estimated level, 1 one step
     * to its other side, 2 full volume wherever the onset is unclear.
     */
    private double[] pairedLosses(InstrumentCatalogue.Variant variant, List<PairedAttack> attacks, int level)
            throws IOException {
        var losses = new double[attacks.size()];
        for (int i = 0; i < attacks.size(); i++) {
            var attack = attacks.get(i);
            var probe = attack.probe();
            int volume = attackMatchedVolume(variant, probe.pitch(), probe.shape().peak());
            if (level == 1) {
                double peak = template(variant, probe.pitch(), volume).attack().peak();
                volume = Math.clamp(volume + (probe.shape().peak() > peak ? LEVEL_STEPS : -LEVEL_STEPS),
                        127 - LEVEL_STEPS * MAX_LEVEL_STEPS, 127);
            } else if (level == 2 && !attack.clearOnset()) volume = 127;
            losses[i] = attackLoss(variant, probe, volume);
        }
        return losses;
    }

    private static double level(float[] audio, int from, int to) {
        double sum = 0, squares = 0;
        for (int i = from; i < to; i++) {
            sum += audio[i];
            squares += audio[i] * (double) audio[i];
        }
        double mean = sum / (to - from);
        return Math.sqrt(Math.max(0, squares / (to - from) - mean * mean));
    }

    /**
     * The track volume at which this patch's attack peak reaches the peak
     * measured on the stem, in 3 dB steps. It estimates the level of one
     * key-on. It is not the volume the part will finally be written at.
     */
    int attackMatchedVolume(InstrumentCatalogue.Variant variant, int pitch, double stemPeak) throws IOException {
        int volume = 127;
        // Clipping hides the true level difference, so measure again from the lower volume.
        for (int pass = 0; pass < 3; pass++) {
            double peak = template(variant, pitch, volume).attack().peak();
            if (peak <= 0 || stemPeak <= 0) break;
            int steps = (int) Math.round(-20 * Math.log10(stemPeak / peak) / VOLUME_STEP_DB / LEVEL_STEPS);
            int next = Math.clamp(volume - LEVEL_STEPS * steps, 127 - LEVEL_STEPS * MAX_LEVEL_STEPS, 127);
            if (next == volume) break;
            volume = next;
        }
        return volume;
    }

    private VoiceSpectralBalance.Profile spectralProbe(InstrumentCatalogue.Variant variant, int pitch, int volume) throws IOException {
        var key = new Key(variant.assetId(), pitch, target, volume);
        var cached = balances.get(key);
        if (cached != null) return cached;
        var project = CataloguePlayback.leveledProbe(catalogue, variant.id(), pitch, 3, target, volume);
        var mono = new float[3 * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION];
        int[] offset = {0};
        TargetPlayback.prepare(project).render(3, pcm -> {
            for (int i = 0; i < pcm.length; i += 2)
                mono[offset[0]++] = (pcm[i] + pcm[i + 1]) / 65536f;
        });
        var profile = spectralBalance.measure(mono, 0, S3kRenderer.SAMPLE_RATE, fundamental(pitch));
        balances.put(key, profile);
        return profile;
    }

    private static double fundamental(int pitch) {
        return 440 * Math.pow(2, (pitch - 69) / 12.0);
    }

    List<RankedPatch> lowCandidates(List<ReconstructionAllocation.Entry> notes, List<ReconstructionAllocation.Entry> context,
                          Path stem, VoiceWindows windows, StockVoicePrior prior)
            throws IOException {
        if (!LowRegisterSpectrum.lowRegister(notes)) return List.of();
        long end = ReferenceAudio.durationMicros(stem);
        double duration = ATTACK_TICKS / (double) PRESENTATIONS_PER_SECOND;
        var occupied = coverage(context, Map.of(), "", windows.songFrames());
        var candidates = notes.stream()
                .filter(n -> n.note().pitch() <= LowRegisterSpectrum.MAX_PITCH
                        && n.endTick() - n.startTick() >= ATTACK_TICKS - 2
                        && n.note().start() + duration <= end / 1e6
                        && windows.trainingAttack(n.startTick(), ATTACK_TICKS))
                .sorted(Comparator.comparingInt((ReconstructionAllocation.Entry n) -> occupied[n.startTick()])
                        .thenComparing(Comparator.comparingInt((ReconstructionAllocation.Entry n) ->
                                Math.min(ATTACK_TICKS, n.endTick() - n.startTick())).reversed())).toList();
        var probes = new ArrayList<LowProbe>();
        var times = new ArrayList<Integer>();
        for (var note : candidates) {
            int pitch = note.note().pitch();
            if (times.stream().anyMatch(t -> Math.abs(t - note.startTick()) < 25)
                    || probes.stream().filter(p -> p.pitch() == pitch).count() >= 2) continue;
            var audio = VoiceSpectra.resample(ReferenceAudio.read(stem, note.note().start(), duration),
                    ATTACK_TICKS * SAMPLES_PER_TICK);
            var profile = lowSpectrum.profile(audio, pitch);
            if (profile == null) continue;
            probes.add(new LowProbe(pitch, profile));
            times.add(note.startTick());
            if (probes.size() == PROBE_LIMIT) break;
        }
        if (probes.size() < 2) return List.of();
        var ranked = new ArrayList<RankedPatch>();
        for (var variant : variants) ranked.add(new RankedPatch(variant.id(), 1 / (1 + lowDistance(variant, probes) / 100)));
        return List.copyOf(shortlist(ranked, prior, 8, null));
    }

    ArrayList<RankedPatch> shortlist(List<RankedPatch> ranked, StockVoicePrior prior, int limit,
                                     ReconstructionHeldNotes.StockScreen sustain)
            throws IOException {
        ranked.sort(Comparator.comparingDouble((RankedPatch patch) -> prior == null ? patch.score()
                : patch.score() + prior.evidence(catalogue, catalogue.match(patch.id()).variant()).preference())
                .reversed().thenComparing(RankedPatch::id));
        var retained = new ArrayList<RankedPatch>();
        RankedPatch fallback = null;
        double bestLoss = Double.POSITIVE_INFINITY;
        for (var patch : ranked) {
            if (sustain != null) {
                double loss = sustain.loss(patch.id());
                if (loss < bestLoss || loss == bestLoss && betterAttack(patch, fallback)) {
                    bestLoss = loss;
                    fallback = patch;
                }
                if (loss > 0) continue;
            }
            if (prior == null || eligible(catalogue.match(patch.id()).variant(), prior)) retained.add(patch);
            if (retained.size() == limit) break;
        }
        if (retained.isEmpty() && fallback != null) retained.add(fallback);
        return retained;
    }

    private static boolean betterAttack(RankedPatch patch, RankedPatch other) {
        return patch.score() > other.score() || patch.score() == other.score() && patch.id().compareTo(other.id()) < 0;
    }

    private boolean eligible(InstrumentCatalogue.Variant variant, StockVoicePrior prior) throws IOException {
        var labels = InstrumentCatalogue.labels(catalogue.asset(variant.assetId()));
        if (labels.stream().anyMatch(label -> label.text().equals("Silent Bass")
                || prior.sustained && label.text().equals("Wood Block"))) return false;
        var key = new Key(variant.assetId(), prior.pitch, target, 127);
        var probe = audibility.get(key);
        if (probe == null) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("patch screening cancelled");
            int ticks = 24;
            var project = CataloguePlayback.matchProbe(catalogue, variant.id(), prior.pitch, ticks, true, target);
            var rendered = VoiceWindows.renderMono(project, ticks);
            double peak = 0;
            for (float sample : rendered) peak = Math.max(peak, Math.abs(sample));
            probe = new Audibility(peak, Math.sqrt(energy(rendered, rendered.length * 3 / 4, rendered.length)));
            audibility.put(key, probe);
        }
        return audible(probe.peak(), probe.tailRms(), prior.sustained);
    }

    static boolean audible(double peak, double tailRms, boolean sustained) {
        // Below -60 dBFS peak the raw patch offers little usable signal.
        // Exclude near-impulses on held lines. The stem-backed sustain
        // screen handles less extreme decay against the actual duration.
        return peak >= .001 && (!sustained || tailRms >= Math.min(.0001, peak * .01));
    }

    private double lowDistance(InstrumentCatalogue.Variant variant, List<LowProbe> probes) throws IOException {
        double error = 0;
        for (var probe : probes) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("patch shortlist cancelled");
            error += LowRegisterSpectrum.distance(probe.profile(), template(variant, probe.pitch()).lowProfile());
        }
        return error / probes.size();
    }

    private RankedPatch rank(InstrumentCatalogue.Variant variant, List<Probe> probes) throws IOException {
        double score = 0, exposure = 0;
        for (var probe : probes) {
            score += probe.exposure() * similarity(probe, template(variant, probe.pitch()).spectrum());
            exposure += probe.exposure();
        }
        return new RankedPatch(variant.id(), exposure == 0 ? 0 : score / exposure);
    }

    private static int[] coverage(List<ReconstructionAllocation.Entry> notes, Map<Integer, Path> stems,
                                  String stem, int frames) {
        int[] activity = new int[frames + 1];
        for (var note : notes) {
            if (!stem.isEmpty() && (!stems.containsKey(note.note().part())
                    || !stem.equals(stems.get(note.note().part()).toString()))) continue;
            // Weight simultaneous attacks more heavily than continuing notes.
            int start = Math.max(0, note.startTick() - ATTACK_TICKS / 2);
            int end = Math.min(frames, note.startTick() + ATTACK_TICKS / 2);
            activity[start] += 4;
            activity[end] -= 4;
            activity[note.startTick()]++;
            activity[note.endTick()]--;
        }
        for (int i = 1; i <= frames; i++) activity[i] += activity[i - 1];
        return activity;
    }

    private Probe probe(Path path, long audioEnd, ReconstructionAllocation.Entry note, boolean foreground)
            throws IOException {
        double start = note.note().start();
        double duration = ATTACK_TICKS / (double) PRESENTATIONS_PER_SECOND;
        if (start + duration > audioEnd / 1e6) return null;
        int samples = ATTACK_TICKS * SAMPLES_PER_TICK;
        var target = spectra.average(VoiceSpectra.resample(ReferenceAudio.read(path, start, duration), samples));
        if (start >= duration) {
            var before = spectra.average(VoiceSpectra.resample(ReferenceAudio.read(path, start - duration, duration), samples));
            for (int i = 0; i < target.length; i++) target[i] = Math.max(0, target[i] - .7f * before[i]);
        }
        float peak = 0;
        for (var value : target) peak = Math.max(peak, value);
        if (peak < .01) return null;
        var weights = new float[target.length];
        for (int i = 0; i < target.length; i++)
            weights[i] = 1 / (target[i] + .03f * peak);
        double exposure = foreground ? Math.sqrt(peak * Math.min(ATTACK_TICKS, note.endTick() - note.startTick())) : 1;
        // Shared instruments probe PSG spill on a surrogate FM channel. Use the
        // same upper limit as VoiceWindows without changing the actual PSG note.
        int pitch = Math.min(note.note().pitch(), TargetProfile.of(this.target).fm().high());
        return new Probe(pitch, target, weights, exposure);
    }

    private Template template(InstrumentCatalogue.Variant variant, int pitch) throws IOException {
        return template(variant, pitch, 127);
    }

    private Template template(InstrumentCatalogue.Variant variant, int pitch, int volume) throws IOException {
        var key = new Key(variant.assetId(), pitch, target, volume);
        var cached = TEMPLATES.get(key);
        if (cached != null) return cached;
        var project = CataloguePlayback.leveledProbe(catalogue, variant.id(), pitch, ATTACK_TICKS, target, volume);
        var rendered = VoiceWindows.renderMono(project, ATTACK_TICKS);
        var features = new Template(spectra.average(rendered),
                pitch <= LowRegisterSpectrum.MAX_PITCH ? lowSpectrum.profile(rendered, pitch) : null,
                attackShape.measure(rendered));
        if (TEMPLATES.size() < CACHE_LIMIT) {
            var existing = TEMPLATES.putIfAbsent(key, features);
            if (existing != null) return existing;
        }
        return features;
    }

    private static double similarity(Probe probe, float[] template) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("patch shortlist cancelled");
        double cross = 0, x = 0, y = 0;
        for (int i = 0; i < template.length; i++) {
            double target = probe.target()[i];
            double value = template[i];
            double weight = probe.weights()[i];
            cross += target * value * weight;
            x += value * value * weight;
            y += target * target * weight;
        }
        return x > 0 && y > 0 ? cross * cross / (x * y) : 0;
    }
}

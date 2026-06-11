package com.opensmpsdeck.audio.match;

import com.opensmpsdeck.model.FmVoice;

import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Genetic search over 4-op FM patch space, scored against spectral targets by
 * rendering through the real YM2612. Searched genes: algorithm, feedback,
 * per-op MUL/TL/AR/D1R/SL(D1L)/RR/DT. D2R/RS/AM stay at the seed's values.
 */
public final class FmPatchSearch {

    public record Config(int population, int maxGenerations, long budgetMillis,
                         long seed, int topN) {
        public static Config defaults() { return new Config(32, 40, 10_000, 1234L, 5); }
    }

    public record Target(SpectralTarget spectral, int midiPitch, double keyOnSec,
                         ModulationDetector.Modulation modulation) {

        /** Unmodulated target (steady tone). */
        public Target(SpectralTarget spectral, int midiPitch, double keyOnSec) {
            this(spectral, midiPitch, keyOnSec, ModulationDetector.Modulation.NONE);
        }
    }

    public record ScoredVoice(FmVoice voice, double score) {}

    private static final double MUTATION_RATE = 0.1;
    private static final int TOURNAMENT = 3;

    private FmPatchSearch() {}

    public static List<ScoredVoice> search(List<Target> targets, List<FmVoice> seeds,
                                           Config cfg, LongSupplier elapsedMillis,
                                           IntConsumer progress) {
        if (seeds.isEmpty()) throw new IllegalArgumentException("seeds required");
        Random rng = new Random(cfg.seed());
        CandidateRenderer renderer = new CandidateRenderer();

        // population: seeds + mutated copies
        List<FmVoice> pop = new ArrayList<>();
        for (FmVoice s : seeds) pop.add(new FmVoice(s.getName(), s.getData()));
        while (pop.size() < cfg.population()) {
            FmVoice base = seeds.get(rng.nextInt(seeds.size()));
            pop.add(mutate(new FmVoice("cand", base.getData()), rng, 3));
        }

        List<ScoredVoice> scored = score(pop, targets, renderer);
        for (int gen = 0; gen < cfg.maxGenerations(); gen++) {
            if (elapsedMillis.getAsLong() > cfg.budgetMillis()) break;
            // Allow VoiceMatchService.shutdownNow() to cancel an in-flight search
            // promptly (e.g. when the dialog is closed).
            if (Thread.currentThread().isInterrupted()) break;
            progress.accept(gen);

            List<FmVoice> next = new ArrayList<>();
            next.add(scored.get(0).voice()); // elitism
            while (next.size() < cfg.population()) {
                FmVoice a = tournament(scored, rng);
                FmVoice b = tournament(scored, rng);
                next.add(mutate(crossover(a, b, rng), rng, 1));
            }
            scored = score(next, targets, renderer);
        }

        // hill-climb the leaders
        List<ScoredVoice> polished = new ArrayList<>();
        for (ScoredVoice sv : scored.subList(0, Math.min(cfg.topN(), scored.size()))) {
            if (elapsedMillis.getAsLong() > cfg.budgetMillis()
                    || Thread.currentThread().isInterrupted()) { polished.add(sv); continue; }
            polished.add(hillClimb(sv, targets, renderer));
        }
        polished.sort(Comparator.comparingDouble(ScoredVoice::score));
        return dedupByData(polished);
    }

    private static List<ScoredVoice> score(List<FmVoice> pop, List<Target> targets,
                                           CandidateRenderer renderer) {
        List<ScoredVoice> out = new ArrayList<>(pop.size());
        for (FmVoice v : pop) out.add(new ScoredVoice(v, fitness(v, targets, renderer)));
        out.sort(Comparator.comparingDouble(ScoredVoice::score));
        return out;
    }

    private static final int SAMPLE_RATE = 44100;
    /** Weight of the post-key-off ring penalty in the fitness sum. */
    private static final double RING_WEIGHT = 0.3;

    /**
     * Mean spectral distance across all target windows, plus a ring penalty:
     * the target window is key-held audio, so the candidate's post-key-off tail
     * has no counterpart in the spectral comparison — without this term a voice
     * with no release (RR=0) goes unpenalized and rings forever in playback.
     * The candidate's tail level is allowed up to the target's own ending
     * envelope level (sustained pads tolerate ring; plucky targets do not).
     */
    static double fitness(FmVoice v, List<Target> targets, CandidateRenderer renderer) {
        double sum = 0;
        for (Target t : targets) {
            float[] audio = renderer.render(v.getData(), t.midiPitch(), t.keyOnSec(), 0.25,
                    t.modulation());
            // fingerprint only the key-on portion: the spectral comparison must
            // see what the target window saw (key-held audio), not the release
            int keyOnSamples = (int) (t.keyOnSec() * SAMPLE_RATE);
            float[] held = java.util.Arrays.copyOfRange(audio, 0, Math.min(keyOnSamples, audio.length));
            SpectralTarget cand = SpectralTarget.extract(held, SAMPLE_RATE,
                    t.spectral().fundamentalHz());
            sum += SpectralTarget.distance(t.spectral(), cand);
            sum += RING_WEIGHT * ringPenalty(audio, keyOnSamples, t.spectral());
        }
        return sum / targets.size();
    }

    /** Squared excess of post-key-off tail level over the target's ending level. */
    private static double ringPenalty(float[] audio, int keyOnSamples, SpectralTarget target) {
        int tailFrom = Math.min(audio.length, keyOnSamples + SAMPLE_RATE / 10);  // 0.1 s after off
        int tailTo = Math.min(audio.length, keyOnSamples + SAMPLE_RATE / 4);     // .. 0.25 s
        int susFrom = Math.max(0, keyOnSamples - SAMPLE_RATE / 5);               // last 0.2 s held
        double tail = rms(audio, tailFrom, tailTo);
        double sustain = rms(audio, susFrom, keyOnSamples);
        if (sustain < 1e-6) return 0;
        double candTail = Math.min(1.5, tail / sustain);
        double[] env = target.rmsEnvelope();
        double targetEnd = 0;
        for (int i = env.length - 8; i < env.length; i++) targetEnd += env[i];
        targetEnd /= 8;
        double excess = Math.max(0, candTail - targetEnd);
        return excess * excess;
    }

    private static double rms(float[] a, int from, int to) {
        if (to <= from) return 0;
        double s = 0;
        for (int i = from; i < to; i++) s += a[i] * a[i];
        return Math.sqrt(s / (to - from));
    }

    private static FmVoice tournament(List<ScoredVoice> scored, Random rng) {
        ScoredVoice best = null;
        for (int i = 0; i < TOURNAMENT; i++) {
            ScoredVoice c = scored.get(rng.nextInt(scored.size()));
            if (best == null || c.score() < best.score()) best = c;
        }
        return best.voice();
    }

    /** Uniform per-operator crossover; algorithm/feedback from a random parent. */
    static FmVoice crossover(FmVoice a, FmVoice b, Random rng) {
        FmVoice child = new FmVoice("cand", (rng.nextBoolean() ? a : b).getData());
        for (int op = 0; op < 4; op++) {
            FmVoice src = rng.nextBoolean() ? a : b;
            child.setMul(op, src.getMul(op));   child.setDt(op, src.getDt(op));
            child.setTl(op, src.getTl(op));     child.setAr(op, src.getAr(op));
            child.setD1r(op, src.getD1r(op));   child.setD1l(op, src.getD1l(op));
            child.setRr(op, src.getRr(op));
        }
        return child;
    }

    /** Mutates `count` random genes by a random step within each parameter's range. */
    static FmVoice mutate(FmVoice v, Random rng, int count) {
        for (int i = 0; i < Math.max(1, poisson(rng, count * MUTATION_RATE * 10)); i++) {
            int gene = rng.nextInt(2 + 4 * 7); // algo, fb, 4 ops × 7 params
            if (gene == 0) v.setAlgorithm(rng.nextInt(8));
            else if (gene == 1) v.setFeedback(rng.nextInt(8));
            else {
                int op = (gene - 2) / 7;
                switch ((gene - 2) % 7) {
                    case 0 -> v.setMul(op, rng.nextInt(16));
                    case 1 -> v.setDt(op, rng.nextInt(8));
                    case 2 -> v.setTl(op, rng.nextInt(128));
                    case 3 -> v.setAr(op, rng.nextInt(32));
                    case 4 -> v.setD1r(op, rng.nextInt(32));
                    case 5 -> v.setD1l(op, rng.nextInt(16));
                    case 6 -> v.setRr(op, rng.nextInt(16));
                }
            }
        }
        return v;
    }

    private static int poisson(Random rng, double mean) {
        double l = Math.exp(-mean), p = 1;
        int k = 0;
        do { k++; p *= rng.nextDouble(); } while (p > l);
        return k - 1;
    }

    /** ±1 step on each gene, keep improvements, two sweeps. */
    static ScoredVoice hillClimb(ScoredVoice start, List<Target> targets,
                                 CandidateRenderer renderer) {
        FmVoice best = new FmVoice(start.voice().getName(), start.voice().getData());
        double bestScore = start.score();
        for (int sweep = 0; sweep < 2; sweep++) {
            for (int op = 0; op < 4; op++) {
                for (int dir : new int[]{-1, 1}) {
                    // TL is the most sensitive gene — climb it per operator
                    FmVoice trial = new FmVoice("cand", best.getData());
                    int tl = Math.max(0, Math.min(127, trial.getTl(op) + dir * 2));
                    trial.setTl(op, tl);
                    double s = fitness(trial, targets, renderer);
                    if (s < bestScore) { best = trial; bestScore = s; }
                }
            }
        }
        return new ScoredVoice(best, bestScore);
    }

    private static List<ScoredVoice> dedupByData(List<ScoredVoice> in) {
        Map<String, ScoredVoice> seen = new LinkedHashMap<>();
        for (ScoredVoice sv : in) {
            seen.putIfAbsent(java.util.HexFormat.of().formatHex(sv.voice().getData()), sv);
        }
        return List.copyOf(seen.values());
    }
}

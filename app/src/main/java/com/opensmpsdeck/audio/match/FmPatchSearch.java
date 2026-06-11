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

    public record Target(SpectralTarget spectral, int midiPitch, double keyOnSec) {}

    public record ScoredVoice(FmVoice voice, double score) {}

    private static final double MUTATION_RATE = 0.1;
    private static final int TOURNAMENT = 3;

    private FmPatchSearch() {}

    public static List<ScoredVoice> search(List<Target> targets, List<FmVoice> seeds,
                                           Config cfg, LongSupplier elapsedMillis,
                                           IntConsumer progress) {
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
            if (elapsedMillis.getAsLong() > cfg.budgetMillis()) { polished.add(sv); continue; }
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

    /** Mean spectral distance across all target windows. */
    static double fitness(FmVoice v, List<Target> targets, CandidateRenderer renderer) {
        double sum = 0;
        for (Target t : targets) {
            float[] audio = renderer.render(v.getData(), t.midiPitch(), t.keyOnSec(), 0.25);
            SpectralTarget cand = SpectralTarget.extract(audio, 44100,
                    440.0 * Math.pow(2, (t.midiPitch() - 69) / 12.0));
            sum += SpectralTarget.distance(t.spectral(), cand);
        }
        return sum / targets.size();
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

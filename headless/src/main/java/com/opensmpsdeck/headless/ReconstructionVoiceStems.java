package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionVoiceProposal.Attack;
import static com.opensmpsdeck.headless.ReconstructionVoiceProposal.Group;

/** Optional acoustic evidence when the score texture alone does not establish a split. */
final class ReconstructionVoiceStems {
    private static final int MIN_SUPPORT = 8;
    private static final int MAX_PROBES_PER_SECTION = 48;
    private static final int RATE = VoiceSpectra.RATE;
    private static final int WINDOW = 768;
    private static final double WINDOW_SECONDS = WINDOW / (double) RATE;
    private static final double SETTLE = .015;
    private static final double MIN_LEVEL = .003;
    private static final double STEM_MARGIN = 4;
    private static final double TIMBRE_MARGIN_DB = 9;
    private static final double[] HANN = IntStream.range(0, WINDOW)
            .mapToDouble(i -> .5 - .5 * Math.cos(Math.TAU * i / (WINDOW - 1))).toArray();

    private record Stem(int part, Path path, List<Attack> notes) {}
    private record Tone(double level, double brightness) {}
    private record ToneKey(Path path, double onset, int pitch) {}
    private record Probe(Attack attack, Stem stem, Tone tone, boolean isolated) {}

    private final Map<Integer, Stem> mapped = new HashMap<>();
    private final List<Stem> stems;
    private final LeadEffectMatcher.Spectrum spectrum = new LeadEffectMatcher.Spectrum(2048);

    ReconstructionVoiceStems(ReconstructionStages.Score score, Map<Integer, List<Attack>> notes) {
        var byPath = new LinkedHashMap<Path, List<Attack>>();
        var paths = new TreeMap<Integer, Path>();
        if (score.inputs() != null) for (var part : score.source().parts()) {
            var path = score.inputs().stems().get(part.id());
            if (part.drums() || path == null || path.equals(score.inputs().wav())) continue;
            var stem = Path.of(path).normalize();
            paths.put(part.id(), stem);
            byPath.computeIfAbsent(stem, ignored -> new ArrayList<>())
                    .addAll(notes.getOrDefault(part.id(), List.of()));
        }
        var distinct = new LinkedHashMap<Path, Stem>();
        paths.forEach((part, path) -> mapped.put(part, distinct.computeIfAbsent(path,
                key -> new Stem(part, key, byPath.get(key)))));
        stems = List.copyOf(distinct.values());
    }

    List<Group> split(List<Attack> attacks) {
        var primary = mapped.get(attacks.getFirst().part());
        if (primary == null || attacks.size() < 2 * MIN_SUPPORT) return List.of();

        var sections = new LinkedHashMap<String, List<Attack>>();
        for (var attack : attacks)
            sections.computeIfAbsent(attack.section(), ignored -> new ArrayList<>()).add(attack);
        if (stems.size() < 2 && sections.size() < 2) return List.of();

        var sampled = new LinkedHashMap<String, List<Probe>>();
        for (var entry : sections.entrySet()) {
            var eligible = entry.getValue().stream().filter(ReconstructionVoiceStems::eligible).toList();
            int count = Math.min(eligible.size(), MAX_PROBES_PER_SECTION);
            var probes = new ArrayList<Probe>();
            for (int i = 0; i < count; i++) {
                var probe = probe(eligible.get(i * eligible.size() / count), primary);
                if (probe != null) probes.add(probe);
            }
            sampled.put(entry.getKey(), probes);
        }
        var separated = splitStems(sections, sampled, primary);
        return separated.isEmpty() ? splitTimbres(sections, sampled, primary) : separated;
    }

    private List<Group> splitStems(Map<String, List<Attack>> sections, Map<String, List<Probe>> sampled, Stem primary) {
        var counts = sampled.values().stream().flatMap(List::stream)
                .collect(Collectors.groupingBy(Probe::stem, Collectors.counting()));
        if (counts.getOrDefault(primary, 0L) < MIN_SUPPORT || counts.entrySet().stream()
                .noneMatch(entry -> entry.getKey() != primary && entry.getValue() >= MIN_SUPPORT)) return List.of();

        var groups = new LinkedHashMap<Stem, List<Attack>>();
        groups.put(primary, new ArrayList<>());
        for (var entry : sections.entrySet()) {
            var probes = sampled.get(entry.getKey());
            var observed = new HashMap<String, Stem>();
            probes.forEach(probe -> observed.put(probe.attack.note().id(), probe.stem));
            var alternate = stems.stream().filter(stem -> stem != primary && counts.getOrDefault(stem, 0L) >= MIN_SUPPORT)
                    .filter(stem -> {
                        long hits = probes.stream().filter(probe -> probe.stem == stem).count();
                        long eligible = entry.getValue().stream().filter(ReconstructionVoiceStems::eligible).count();
                        return hits >= MIN_SUPPORT && 5 * hits >= 4 * Math.min(eligible, MAX_PROBES_PER_SECTION);
                    }).findFirst().orElse(null);
            for (var attack : entry.getValue()) {
                Stem stem = observed.get(attack.note().id());
                if (stem == null) stem = alternate;
                if (stem == null && eligible(attack)) {
                    var probe = probe(attack, primary);
                    if (probe != null) stem = probe.stem;
                }
                if (stem == null || counts.getOrDefault(stem, 0L) < MIN_SUPPORT) stem = primary;
                groups.computeIfAbsent(stem, ignored -> new ArrayList<>()).add(attack);
            }
        }
        if (groups.size() < 2) return List.of();
        return groups.entrySet().stream().map(entry -> {
            var stem = entry.getKey();
            String name = stem.path.getFileName().toString();
            return new Group("stem-part-" + stem.part, "stem " + name, null,
                    "repeated pitch evidence in stem " + name, .86, entry.getValue(), name);
        }).toList();
    }

    private static List<Group> splitTimbres(Map<String, List<Attack>> sections, Map<String, List<Probe>> sampled, Stem primary) {
        var profiles = new LinkedHashMap<String, Map<Integer, Double>>();
        for (var entry : sampled.entrySet()) {
            var byPitch = new TreeMap<Integer, List<Double>>();
            for (var probe : entry.getValue())
                if (probe.stem == primary && probe.isolated)
                    byPitch.computeIfAbsent(probe.attack.note().pitch(), ignored -> new ArrayList<>()).add(probe.tone.brightness);
            if (byPitch.values().stream().mapToInt(List::size).sum() < MIN_SUPPORT) continue;
            var profile = new TreeMap<Integer, Double>();
            byPitch.forEach((pitch, values) -> {
                if (values.size() >= 2) profile.put(pitch, median(values));
            });
            if (profile.size() >= 2) profiles.put(entry.getKey(), profile);
        }
        if (profiles.size() < 2 || profiles.size() != sections.size()) return List.of();

        // Compare the same pitches across sections, so register is not a proxy for timbre.
        var common = new ArrayList<>(profiles.values().iterator().next().keySet());
        for (var profile : profiles.values()) common.retainAll(profile.keySet());
        if (common.size() < 2) return List.of();
        var brightness = new ArrayList<>(profiles.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), median(common.stream().map(entry.getValue()::get).toList())))
                .sorted(Map.Entry.comparingByValue()).toList());
        double gap = TIMBRE_MARGIN_DB;
        int boundary = -1;
        for (int i = 1; i < brightness.size(); i++) {
            double delta = brightness.get(i).getValue() - brightness.get(i - 1).getValue();
            if (delta > gap) {
                gap = delta;
                boundary = i;
            }
        }
        if (boundary < 0) return List.of();
        var mellowSections = brightness.subList(0, boundary).stream().map(Map.Entry::getKey).toList();
        var brightSections = brightness.subList(boundary, brightness.size()).stream().map(Map.Entry::getKey).toList();
        // Demand the same direction on every matched pitch, including the
        // closest pair of sections at the proposed division.
        for (int pitch : common) {
            double mellowMax = mellowSections.stream().mapToDouble(id -> profiles.get(id).get(pitch)).max().orElseThrow();
            double brightMin = brightSections.stream().mapToDouble(id -> profiles.get(id).get(pitch)).min().orElseThrow();
            if (brightMin - mellowMax < TIMBRE_MARGIN_DB) return List.of();
        }
        var mellow = mellowSections.stream().flatMap(id -> sections.get(id).stream()).toList();
        var bright = brightSections.stream().flatMap(id -> sections.get(id).stream()).toList();
        var name = primary.path.getFileName().toString();
        String basis = "distinct stem harmonic levels across sections at matching pitches (" + name + ")";
        return List.of(new Group("timbre-mellow", "mellow tone", null, basis, .82, mellow),
                new Group("timbre-bright", "bright tone", null, basis, .82, bright));
    }

    private static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return (sorted.get(mid) + sorted.get((sorted.size() - 1) / 2)) / 2;
    }

    private static boolean eligible(Attack attack) {
        return attack.role() != Role.drop && attack.note().pitch() >= 40 && attack.note().pitch() <= 84
                && attack.end() - attack.onset() >= SETTLE + WINDOW_SECONDS;
    }

    private Probe probe(Attack attack, Stem primary) {
        Stem best = null;
        Tone strongest = null;
        double runnerUp = 0;
        for (var stem : stems) {
            // Another part with the same note on this stem makes attribution ambiguous.
            if (stem.notes.stream().anyMatch(other -> other.part() != attack.part() && overlaps(attack, other)
                    && Math.abs(other.note().pitch() - attack.note().pitch()) <= 1)) continue;
            var tone = tone(stem.path, attack);
            if (tone == null) continue;
            if (strongest == null || tone.level > strongest.level) {
                if (strongest != null) runnerUp = strongest.level;
                best = stem;
                strongest = tone;
            } else {
                runnerUp = Math.max(runnerUp, tone.level);
            }
        }
        if (strongest == null || strongest.level < STEM_MARGIN * runnerUp) return null;
        boolean isolated = best == primary && primary.notes.stream()
                .noneMatch(other -> other != attack && overlaps(attack, other));
        return new Probe(attack, best, strongest, isolated);
    }

    private static boolean overlaps(Attack attack, Attack other) {
        double start = attack.onset() + SETTLE;
        return other.onset() < start + WINDOW_SECONDS && other.end() > start;
    }

    private Tone tone(Path path, Attack attack) {
        try {
            ReconstructionLiveEvidence.track(path);
            var key = new ToneKey(path, attack.onset(), attack.note().pitch());
            return ReconstructionLiveEvidence.gate(key, () -> Optional.ofNullable(measureTone(key))).orElse(null);
        } catch (IOException | IllegalArgumentException unavailable) {
            // A work directory can be reviewed without its optional source audio.
            return null;
        }
    }

    private Tone measureTone(ToneKey key) throws IOException {
        var audio = ReferenceAudio.read(key.path(), key.onset() + SETTLE, WINDOW_SECONDS);
        float[] input = VoiceSpectra.resample(audio, WINDOW);
        spectrum.transform(input, 0, HANN);
        double hz = ReconstructionLeadAudio.hz(key.pitch());
        double fundamental = peak(hz);
        double total = 0;
        for (float sample : input) total += sample * (double) sample;
        double level = 4 * Math.sqrt(fundamental) / WINDOW;
        if (level < MIN_LEVEL || fundamental < .02 * WINDOW * total) return null;
        double color = (peak(2 * hz) + peak(3 * hz) + peak(4 * hz)) / fundamental;
        return new Tone(level, 10 * Math.log10(Math.max(.0001, color)));
    }

    private double peak(double hz) {
        double bin = hz * spectrum.real.length / RATE;
        int from = Math.max(1, (int) Math.ceil(bin - Math.max(1, bin * .018)));
        int to = Math.min(spectrum.real.length / 2, (int) Math.floor(bin + Math.max(1, bin * .018)));
        double power = 0;
        for (int i = from; i <= to; i++) power = Math.max(power, spectrum.power(i));
        return power;
    }
}

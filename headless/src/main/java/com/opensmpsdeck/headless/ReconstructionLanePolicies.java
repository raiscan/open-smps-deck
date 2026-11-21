package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;

final class ReconstructionLanePolicies {
    enum Kind { pads, leadEcho, psgDouble, fmDouble }
    enum Mode { chorus, echo }

    record VolumeStep(int tick, int attenuation) {}

    record Rule(Kind policy, @Nullable String section, @Nullable List<Integer> span, @Nullable String voice,
                @Nullable Integer minimumTicks, @Nullable Integer delayTicks, @Nullable Integer octave,
                @Nullable Double levelDb, @Nullable Mode mode, @Nullable Integer detune,
                @Nullable Integer attenuation, @Nullable List<VolumeStep> volumeSteps) {
        Rule {
            String at = "voicing.json.lanePolicies";
            require(policy != null, at, "expected pads, leadEcho, psgDouble or fmDouble");
            require(section == null || !section.isBlank() && span == null, at, "use either section or span");
            require(voice == null || !voice.isBlank(), at, "voice must be an ID");
            require(span == null || span.size() == 2 && span.get(0) != null && span.get(1) != null
                            && span.get(0) >= 0 && span.get(0) < span.get(1), at, "span must be [startTick, endTick]");
            require(minimumTicks == null || policy == Kind.pads && minimumTicks >= 1 && minimumTicks <= 600,
                    at, "minimumTicks is available for pads (1–600)");
            require(mode == null || policy == Kind.fmDouble, at, "mode is available for fmDouble");
            require(delayTicks == null || (policy == Kind.leadEcho || policy == Kind.fmDouble && mode == Mode.echo)
                            && delayTicks >= 1 && delayTicks <= 600, at, "delayTicks is available for echo (1–600)");
            require(octave == null || policy != Kind.fmDouble && octave >= -2 && octave <= 2, at, "PSG octave out of range (−2–2)");
            require(levelDb == null || policy != Kind.fmDouble && Double.isFinite(levelDb) && levelDb >= -36 && levelDb <= 0,
                    at, "PSG levelDb must be between −36 and 0");
            require(detune == null || policy == Kind.fmDouble && detune >= -128 && detune <= 127,
                    at, "detune is a signed FM driver unit");
            require(attenuation == null || policy == Kind.fmDouble && attenuation >= 0 && attenuation <= 127,
                    at, "FM attenuation must be between 0 and 127 TL units");
            require(volumeSteps == null || policy == Kind.fmDouble && mode == Mode.echo,
                    at, "volumeSteps is available for fmDouble echo");
            if (volumeSteps != null) {
                int previous = 0;
                for (var step : volumeSteps) {
                    require(step != null && step.tick() > previous && step.tick() <= 600
                                    && step.attenuation() >= 0 && step.attenuation() <= 127, at,
                            "volumeSteps must have increasing relative ticks (1–600) and attenuation (0–127)");
                    previous = step.tick();
                }
                volumeSteps = List.copyOf(volumeSteps);
            }
            if (span != null) span = List.copyOf(span);
        }

        int minimum() { return minimumTicks == null ? 18 : minimumTicks; }
        int delay() {
            if (delayTicks != null) return delayTicks;
            return policy == Kind.leadEcho ? 7 : mode == Mode.echo ? 14 : 0;
        }
        int pitch(int sourcePitch) { return sourcePitch + 12 * (octave == null ? 0 : octave); }
        double db() {
            if (levelDb != null) return levelDb;
            return switch (policy) {
                case pads -> -6;
                case leadEcho -> -12;
                case psgDouble -> -10;
                case fmDouble -> throw new IllegalStateException("FM copies use TL attenuation");
            };
        }
        int fmDetune() { return detune == null ? (mode == Mode.echo ? 2 : -2) : detune; }
        int fmAttenuation() { return attenuation == null ? (mode == Mode.echo ? 7 : 1) : attenuation; }
    }

    record Scoped(int index, Rule rule, int start, int end, Set<String> notes) {
        boolean selects(ReconstructionAllocation.Entry entry, String origin) {
            if (entry.startTick() < start || entry.startTick() >= end || notes != null && !notes.contains(origin))
                return false;
            var role = entry.role();
            boolean fm = entry.lane().startsWith("FM");
            return switch (rule.policy()) {
                case pads -> (role == Role.harmony || notes != null && role == Role.counter)
                        && entry.endTick() - entry.startTick() >= rule.minimum();
                case leadEcho -> fm && role == Role.melody;
                case psgDouble -> fm && (role == Role.harmony || role == Role.counter);
                case fmDouble -> fm && (notes == null ? role == Role.melody : role != Role.drums);
            };
        }
    }

    record Coverage(int index, Kind policy, @Nullable Mode mode, int eligible, int applied,
                    int busy, int incompatible, int movedFromFm, int promotedCounters, Map<String, Integer> lanes) {}

    private ReconstructionLanePolicies() {}

    static List<Scoped> resolve(Path work, ReconstructionStages.Score score, ReconstructionStructure.Result structure,
                                List<Rule> rules) throws IOException {
        if (rules == null || rules.isEmpty()) return List.of();
        int frames = ReconstructionTimeMap.of(score).frames();
        var sections = rules.stream().anyMatch(rule -> rule != null && rule.section() != null)
                ? ReconstructionSections.load(work, score, structure) : null;
        var voicePlan = rules.stream().anyMatch(rule -> rule != null && rule.voice() != null)
                ? ReconstructionVoicePlan.saved(work) : null;
        var result = new ArrayList<Scoped>();
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            String at = "voicing.json.lanePolicies[" + i + "]";
            require(rule != null, at, "expected a policy object");
            require(!score.inputs().fmOnly() || rule.policy() == Kind.fmDouble,
                    at, "PSG policies require PSG channels");
            int start = 0, end = frames;
            if (rule.span() != null) {
                start = rule.span().getFirst();
                end = rule.span().getLast();
            }
            if (rule.section() != null) {
                var section = sections.select(rule.section());
                start = (int) Math.round(section.projectStart() * S3kRenderer.PRESENTATIONS_PER_SECOND);
                end = (int) Math.round(section.projectEnd() * S3kRenderer.PRESENTATIONS_PER_SECOND);
            }
            require(start >= 0 && start < end && end <= frames, at, "scope must fit the project render");
            Set<String> notes = null;
            if (rule.voice() != null) {
                var voice = voicePlan == null ? null : voicePlan.voices().stream()
                        .filter(v -> v.id().equals(rule.voice())).findFirst().orElse(null);
                require(voice != null, at, "unknown voice ID " + rule.voice());
                notes = Set.copyOf(voice.noteIds());
            }
            result.add(new Scoped(i, rule, start, end, notes));
        }
        // Route the dry pad first, then use spare space for copies. Echo has
        // priority over reinforcement in a combined PSG treatment.
        result.sort(java.util.Comparator.comparing(scope -> scope.rule().policy()));
        return List.copyOf(result);
    }

    // Synthetic programs based on the native envelope shapes. The pad
    // settles to a held level and the echo fades to silence. S3K's 81
    // parks without a volume write, while 83 actively silences the channel.
    static final Envelope PAD = psg("pad", List.of(3, 2, 1, 1, 0, 0, 0, 1, 1, 2, 2, 3, 0x81));
    static final Envelope ECHO = psg("echo", List.of(0, 1, 1, 2, 2, 3, 3, 4, 5, 6, 0x83));
    static final Envelope DOUBLE = psg("double", List.of(0, 0, 1, 1, 2, 2, 3, 0x81));

    private static Envelope psg(String name, List<Integer> program) {
        return new Envelope("policy-psg-" + name, "PSG " + name, Envelope.PSG_VOLUME, program);
    }

    /** Match short solo renders using the same K-weighted power as the drum balance. */
    static final class Levels {
        private record Probe(Voice fm, Envelope psg, int pitch, int ticks, int volume) {}
        private final Map<Probe, Double> cache = new HashMap<>();

        int volume(Voice fm, Envelope dryPsg, int dryPitch, int dryVolume, int pitch,
                   Envelope envelope, int duration, double db) throws IOException {
            int ticks = Math.min(duration, 36);
            double target = power(new Probe(fm, dryPsg, dryPitch, ticks, dryVolume)) * Math.pow(10, db / 10);
            if (target <= 1e-12) return 0;
            double maximum = power(new Probe(null, envelope, pitch, ticks, 127));
            if (maximum <= 1e-12) return 0;
            int step = (int) Math.round(5 * Math.log10(maximum / target));
            // Keep a sustained layer audible throughout its gate. An echo
            // may decay to the PSG's mute step sooner at quiet levels.
            int envelopePeak = envelope == ECHO ? 0
                    : envelope.bytes().stream().filter(v -> v < 0x80).mapToInt(Integer::intValue).max().orElse(0);
            return 127 - 8 * Math.clamp(step, 0, 14 - envelopePeak);
        }

        private double power(Probe probe) throws IOException {
            var saved = cache.get(probe);
            if (saved != null) return saved;
            boolean fm = probe.fm() != null;
            String asset = fm ? probe.fm().id() : probe.psg().id();
            var sequence = new Sequence("probe", "Probe", fm ? "FM" : "PSG", probe.ticks(),
                    fm ? asset : null, probe.volume(), List.of(new NoteEvent("probe-note", 0, probe.ticks(), probe.pitch())),
                    List.of(), "RESET", fm ? null : asset, null);
            var project = new StudioProject(4, "Level probe", "S3K", 30, 0,
                    fm ? List.of(probe.fm()) : List.of(), List.of(sequence),
                    List.of(new Clip("probe-clip", "probe", fm ? "FM1" : "PSG1", 0, probe.ticks())),
                    fm ? List.of() : List.of(probe.psg()), List.of());
            var loudness = new KWeightedLevel();
            S3kRenderer.render(ProjectCompiler.compileWithAssets(project), probe.ticks(), pcm -> {
                for (int i = 0; i < pcm.length; i += 2) loudness.add(pcm[i] / 32768.0, pcm[i + 1] / 32768.0);
            });
            double power = loudness.probePower();
            cache.put(probe, power);
            return power;
        }
    }
}

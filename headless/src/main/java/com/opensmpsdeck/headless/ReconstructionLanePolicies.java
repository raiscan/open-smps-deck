package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.TargetProfile;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.lang.reflect.Type;
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

    static final String S3K_LAYERING = "s3k-layering";
    static final Set<String> PRESETS = Set.of(S3K_LAYERING, "s1-layering", "s2-layering");

    static String defaultPreset(TargetGame game) {
        return game.rulebook().id() + "-layering";
    }

    private static final String FIELD = "voicing.json.lanePolicies";

    @JsonAdapter(ConfigurationJson.class)
    record Configuration(@Nullable String preset, @Nullable Integer echoAttenuation, List<Rule> rules) {
        static Configuration preset(String name) {
            return preset(name, null);
        }

        private static Configuration preset(String name, Integer echoAttenuation) {
            require(PRESETS.contains(name), FIELD + ".preset", "expected s1-layering, s2-layering or s3k-layering");
            require(echoAttenuation == null || echoAttenuation >= 0 && echoAttenuation <= 127,
                    FIELD + ".echoAttenuation", "expected 0–127 TL steps");
            int attenuation = echoAttenuation == null ? ReconstructionLayers.DEFAULT_ECHO_ATTENUATION : echoAttenuation;
            return new Configuration(name, echoAttenuation, List.of(
                    new Rule(Kind.psgDouble, null, null, null, null, null, 1, null, null, null, null, null),
                    new Rule(Kind.fmDouble, null, null, null, null, ReconstructionLayers.DEFAULT_ECHO_DELAY_FRAMES,
                            null, null, Mode.echo, null, attenuation, null)));
        }
    }

    static final class ConfigurationJson implements JsonSerializer<Configuration>, JsonDeserializer<Configuration> {
        private static final Type RULES = new TypeToken<List<Rule>>() {}.getType();

        @Override public Configuration deserialize(JsonElement value, Type type, JsonDeserializationContext context) {
            if (value.isJsonObject()) {
                var object = value.getAsJsonObject();
                require(Set.of("preset", "echoAttenuation").containsAll(object.keySet()), FIELD,
                        "expected preset and optional echoAttenuation");
                ReconstructionArtifacts.shape(FIELD + ".preset", String.class, object.get("preset"));
                Integer attenuation = null;
                if (object.has("echoAttenuation")) {
                    var field = object.get("echoAttenuation");
                    ReconstructionArtifacts.shape(FIELD + ".echoAttenuation", Integer.class, field);
                    attenuation = field.getAsInt();
                }
                return Configuration.preset(object.get("preset").getAsString(), attenuation);
            }
            ReconstructionArtifacts.shape(FIELD, RULES, value);
            return new Configuration(null, null, List.copyOf(context.<List<Rule>>deserialize(value, RULES)));
        }

        @Override public JsonElement serialize(Configuration value, Type type, JsonSerializationContext context) {
            if (value.preset() == null) return context.serialize(value.rules(), RULES);
            var object = new JsonObject();
            object.addProperty("preset", value.preset());
            if (value.echoAttenuation() != null) object.addProperty("echoAttenuation", value.echoAttenuation());
            return object;
        }
    }

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
            return policy == Kind.leadEcho ? 7 : mode == Mode.echo ? 8 : 0;
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
        int fmAttenuation() { return attenuation == null ? (mode == Mode.echo ? ReconstructionLayers.ECHO_REFERENCE_TL : 1) : attenuation; }
    }

    record Scoped(int index, Rule rule, int start, int end, Set<String> notes) {
        boolean selects(ReconstructionAllocation.Entry entry, String origin, boolean solo) {
            if (entry.startTick() < start || entry.startTick() >= end || notes != null && !notes.contains(origin))
                return false;
            var role = entry.role();
            boolean fm = entry.lane().startsWith("FM");
            // A mid-register solo figure can carry the foreground even with a bass role.
            boolean foreground = solo && role != Role.drums && (role != Role.bass || entry.note().pitch() >= 48);
            return switch (rule.policy()) {
                case pads -> (role == Role.harmony || notes != null && role == Role.counter)
                        && entry.endTick() - entry.startTick() >= rule.minimum();
                case leadEcho -> fm && role == Role.melody;
                case psgDouble -> fm && (role == Role.harmony || role == Role.counter || foreground);
                case fmDouble -> fm && (notes == null
                        ? role == Role.melody || rule.mode() == Mode.echo && foreground
                        : role != Role.drums);
            };
        }
    }

    record BendLayer(String noteId, int startTick, int endTick, @Nullable String lane,
                     @Nullable ReconstructionLayers.Curve curve, @Nullable String skipReason) {}

    record Blocker(String noteId, String lane, int startTick, int endTick) {}

    enum Status { applied, skipped }

    record Decision(String sourceNoteId, String noteId, int ruleIndex, Kind policy, @Nullable String lane,
                    int pitch, int attackTick, int gate, int delay, Status status, @Nullable String reason,
                    @Nullable List<Blocker> blockers) {
        String detail() {
            if (blockers == null || blockers.isEmpty()) return reason;
            return reason + ": " + blockers.stream().map(blocker -> blocker.noteId() + " on " + blocker.lane())
                    .collect(java.util.stream.Collectors.joining(", "));
        }
    }

    record Coverage(int index, Kind policy, @Nullable Mode mode, int eligible, int applied,
                    int busy, int incompatible, int movedFromFm, int promotedCounters, Map<String, Integer> lanes,
                    @Nullable List<BendLayer> bendLayers) {}

    private ReconstructionLanePolicies() {}

    static List<Scoped> resolve(Path work, ReconstructionStages.Score score, ReconstructionStructure.Result structure,
                                Configuration configuration) throws IOException {
        if (configuration == null || configuration.rules().isEmpty()) return List.of();
        var rules = configuration.rules();
        var timeline = ReconstructionTimeMap.of(score);
        int ticks = timeline.ticks();
        var sections = rules.stream().anyMatch(rule -> rule != null && rule.section() != null)
                ? ReconstructionSections.load(work, score, structure) : null;
        var voicePlan = rules.stream().anyMatch(rule -> rule != null && rule.voice() != null)
                ? ReconstructionVoicePlan.saved(work) : null;
        var result = new ArrayList<Scoped>();
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            String at = "voicing.json.lanePolicies[" + i + "]";
            require(rule != null, at, "expected a policy object");
            if (score.inputs().fmOnly() && configuration.preset() != null && rule.policy() == Kind.psgDouble) continue;
            require(!score.inputs().fmOnly() || rule.policy() == Kind.fmDouble,
                    at, "PSG policies require PSG channels");
            int start = 0, end = ticks;
            if (rule.span() != null) {
                start = rule.span().getFirst();
                end = rule.span().getLast();
            }
            if (rule.section() != null) {
                var section = sections.select(rule.section());
                start = timeline.tick(section.projectStart());
                end = timeline.tick(section.projectEnd());
            }
            require(start >= 0 && start < end && end <= ticks, at, "scope must fit the project render");
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
        private final TargetProfile profile;
        private final Set<String> classicEnvelopes;
        private final int maxProbes;

        Levels(TargetProfile profile) {
            this(profile, Set.of());
        }

        Levels(TargetProfile profile, Set<String> classicEnvelopes) {
            this(profile, classicEnvelopes, Integer.MAX_VALUE);
        }

        Levels(TargetProfile profile, Set<String> classicEnvelopes, int maxProbes) {
            this.profile = profile;
            this.classicEnvelopes = classicEnvelopes;
            this.maxProbes = maxProbes;
        }

        private record Probe(Voice fm, Envelope psg, int pitch, int ticks, int volume) {}
        private final Map<Probe, Double> cache = new HashMap<>();

        int volume(Voice fm, Envelope dryPsg, int dryPitch, int dryVolume, int pitch,
                   Envelope envelope, int duration, double db, boolean echo) throws IOException {
            int ticks = Math.min(duration, 36);
            double target = power(new Probe(fm, dryPsg, dryPitch, ticks, dryVolume)) * Math.pow(10, db / 10);
            if (target <= 1e-12) return 0;
            double maximum = power(new Probe(null, envelope, pitch, ticks, 127));
            if (maximum <= 1e-12) return 0;
            int step = (int) Math.round(5 * Math.log10(maximum / target));
            // Keep a sustained layer audible throughout its gate. An echo
            // may decay to the PSG's mute step sooner at quiet levels.
            int envelopePeak = envelope == null || echo ? 0
                    : envelope.bytes().stream().filter(v -> v < 0x80).mapToInt(Integer::intValue).max().orElse(0);
            return 127 - 8 * Math.clamp(step, 0, Math.max(0, 14 - envelopePeak));
        }

        private synchronized double power(Probe probe) throws IOException {
            var saved = cache.get(probe);
            if (saved != null) return saved;
            boolean fm = probe.fm() != null;
            String asset = fm ? probe.fm().id() : probe.psg() == null ? null : probe.psg().id();
            var sequence = new Sequence("probe", "Probe", fm ? "FM" : "PSG", probe.ticks(),
                    fm ? asset : null, probe.volume(), List.of(new NoteEvent("probe-note", 0, probe.ticks(), probe.pitch())),
                    List.of(), "RESET", fm ? null : asset, null);
            var project = new StudioProject(4, "Level probe", "S3K", 30, 0,
                    fm ? List.of(probe.fm()) : List.of(), List.of(sequence),
                    List.of(new Clip("probe-clip", "probe", fm ? "FM1" : "PSG1", 0, probe.ticks())),
                    probe.psg() == null ? List.of() : List.of(ReconstructionPsgEnvelopes.forProbe(probe.psg(), profile.game(),
                            probe.ticks(), classicEnvelopes.contains(asset))),
                    List.of(), profile.game(),
                    profile.clock().game().s3kRules() ? null : profile.clock().tempo());
            var loudness = new KWeightedLevel();
            TargetPlayback.prepare(project).render((int) project.clock().frameAtTick(probe.ticks()), pcm -> {
                for (int i = 0; i < pcm.length; i += 2) loudness.add(pcm[i] / 32768.0, pcm[i + 1] / 32768.0);
            });
            double power = loudness.probePower();
            if (cache.size() < maxProbes) cache.put(probe, power);
            return power;
        }
    }
}

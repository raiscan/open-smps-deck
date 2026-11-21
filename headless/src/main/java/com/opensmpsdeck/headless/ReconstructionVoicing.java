package com.opensmpsdeck.headless;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;
import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;

/** Editable contract between voice fitting and assembly. */
final class ReconstructionVoicing {
    static final String DEFAULT = "default (inconclusive)";
    static final String EVIDENCE = "evidence";

    record Result(int version, Map<String, Lane> lanes, @Nullable FitWindows fitWindows,
                  @Nullable String sustainWarning) {
        Result(int version, Map<String, Lane> lanes, FitWindows fitWindows) {
            this(version, lanes, fitWindows, null);
        }
    }
    record FitWindows(int frames, List<Integer> starts) {}
    record Lane(Role role, @Nullable String variantId, List<ScoredPatch> shortlist, double gain,
                Articulation articulation, Effects effects, String patchReason, String articulationReason,
                String effectReason, List<Instrument> instruments, @Nullable Map<Integer, Double> partGains,
                @Nullable List<ReconstructionOverrides.Settings> overrides,
                @Nullable Map<Integer, Double> drumGains) {
        Lane(Role role, String variantId, List<ScoredPatch> shortlist, double gain, Articulation articulation,
             Effects effects, String patchReason, String articulationReason, String effectReason,
             List<Instrument> instruments) {
            this(role, variantId, shortlist, gain, articulation, effects, patchReason, articulationReason,
                    effectReason, instruments, null, null, null);
        }

        Lane withPartGains(Map<Integer, Double> gains) {
            return new Lane(role, variantId, shortlist, gain, articulation, effects, patchReason, articulationReason,
                    effectReason, instruments, Collections.unmodifiableMap(new TreeMap<>(gains)), overrides, drumGains);
        }

        Lane withPreviousEdits(Lane previous, boolean refitLevels, List<ReconstructionAllocation.Entry> notes) {
            return new Lane(role, variantId, shortlist, refitLevels ? gain : previous.gain, articulation, effects,
                    patchReason, articulationReason, effectReason, instruments,
                    refitLevels ? partGains : compatibleGains(previous.partGains, notes), previous.overrides, previous.drumGains);
        }

        double gainFor(int part, double level) {
            return Math.min(4, level * (partGains == null ? 1 : partGains.getOrDefault(part, 1.0)));
        }

        double gainFor(int part) { return gainFor(part, gain); }

        double drumGain(int pitch) { return drumGains == null ? 1 : drumGains.getOrDefault(pitch, 1.0); }
    }
    record ScoredPatch(String variantId, double shortlistScore, double fit, double check) {}
    record Instrument(int part, @Nullable Integer drumPitch, @Nullable String variantId,
                      @Nullable ReconstructionDrums.Selection drumSelection) {
        Instrument(int part, Integer drumPitch, String variantId) {
            this(part, drumPitch, variantId, null);
        }
    }
    record Articulation(String preset, double gate, int minimumReleaseRate) {
        int duration(int ticks) { return Math.max(1, (int) Math.round(ticks * gate)); }
    }
    record Vibrato(int delay, int rate, int delta, int steps) {
        List<Integer> bytes() { return List.of(delay, rate, delta & 255, steps); }
    }
    record Copy(String lane, int delay, double gain, int detune, @Nullable Vibrato vibrato) {}
    record Effects(String preset, int detune, @Nullable Vibrato vibrato, @Nullable Copy copy) {}

    static final Articulation WRITTEN = new Articulation("written", 1, 0);
    static final Effects DRY = new Effects("none", 0, null, null);

    private ReconstructionVoicing() {}

    /**
     * Default articulation is the written note length for every role. Shorter gates are still
     * offered as evidence-checked alternatives in the voice stage, but as defaults they lowered
     * melody F1 on the benchmark without reducing measured release energy.
     */
    static Articulation articulation(Role role) {
        return WRITTEN;
    }

    static Effects idiom(Role role, String freeLane) {
        return switch (role) {
            case melody -> new Effects(freeLane == null ? "mild-vibrato" : "mild-vibrato-and-echo",
                    0, new Vibrato(10, 2, 2, 4),
                    freeLane == null ? null : new Copy(freeLane, 10, .20, 0, null));
            case harmony -> new Effects("light-detune", 1, null, null);
            default -> DRY;
        };
    }

    static byte[] patchBytes(InstrumentCatalogue.Asset asset, Articulation articulation) {
        byte[] bytes = asset.bytes();
        // S3K stores D1L/RR for its four operators in bytes 17–20.
        for (int i = 17; i < 21; i++)
            bytes[i] = (byte) ((bytes[i] & 0xf0) | Math.max(bytes[i] & 15, articulation.minimumReleaseRate()));
        return bytes;
    }

    static String patchId(InstrumentCatalogue.Asset asset, byte[] bytes) {
        return java.util.Arrays.equals(bytes, asset.bytes()) ? asset.id() : "fm-voice-" + InstrumentCatalogue.digest(bytes);
    }

    static Map<String, List<ReconstructionAllocation.Entry>> byLane(ReconstructionAllocation.Result allocation) {
        var lanes = new TreeMap<String, List<ReconstructionAllocation.Entry>>();
        for (var entry : allocation.notes()) if (entry.lane() != null)
            lanes.computeIfAbsent(entry.lane(), ignored -> new java.util.ArrayList<>()).add(entry);
        lanes.replaceAll((lane, notes) -> notes.stream()
                .sorted(java.util.Comparator.comparingInt(ReconstructionAllocation.Entry::startTick)).toList());
        return lanes;
    }

    record InstrumentVariant(int part, Integer drumPitch, String variantId, String sliceId, Integer rate) {}
    record Settings(String variantId, double gain, Map<Integer, Double> partGains, List<InstrumentVariant> instruments,
                    Map<Integer, Double> drumGains) {
        static Settings of(Lane lane) {
            return lane == null ? null : new Settings(lane.variantId(), lane.gain(), lane.partGains(),
                    lane.instruments().stream().map(instrument -> {
                        var drum = instrument.drumSelection();
                        return new InstrumentVariant(instrument.part(), instrument.drumPitch(), instrument.variantId(),
                                drum == null || drum.slice() == null ? null : drum.slice().id(),
                                drum == null ? null : drum.rate());
                    }).toList(), lane.drumGains());
        }
    }
    record Change(String lane, Settings before, Settings after) {
        String describe() {
            return "Voicing changed " + lane + " (" + describe(before) + " → " + describe(after) + ")";
        }

        private static String describe(Settings settings) {
            if (settings == null) return "absent";
            return "variant " + settings.variantId + ", gain " + settings.gain + ", partGains " + settings.partGains
                    + (settings.instruments.isEmpty() ? "" : ", instruments " + settings.instruments)
                    + (settings.drumGains == null ? "" : ", drumGains " + settings.drumGains);
        }
    }

    static List<Change> changes(Result before, Result after) {
        var ids = new TreeSet<String>();
        if (before != null) ids.addAll(before.lanes().keySet());
        if (after != null) ids.addAll(after.lanes().keySet());
        return ids.stream().map(id -> new Change(id, Settings.of(before == null ? null : before.lanes().get(id)),
                        Settings.of(after == null ? null : after.lanes().get(id))))
                .filter(change -> !Objects.equals(change.before, change.after)).toList();
    }

    private record Material(int part, int pitch, int velocity, int start, int end, boolean drums,
                            ReconstructionBends.Bend bend) {}

    private static List<Material> material(List<ReconstructionAllocation.Entry> notes) {
        return notes.stream().map(entry -> new Material(entry.note().part(), entry.note().pitch(), entry.note().velocity(),
                entry.startTick(), entry.endTick(), entry.role() == Role.drums, entry.note().bend())).toList();
    }

    static Set<String> changedMaterial(ReconstructionAllocation.Result previous, ReconstructionAllocation.Result current) {
        if (previous == null) return Set.of();
        var before = byLane(previous);
        var changed = new HashSet<String>();
        byLane(current).forEach((id, notes) -> {
            if (!material(before.getOrDefault(id, List.of())).equals(material(notes))) changed.add(id);
        });
        return changed;
    }

    private static Map<Integer, Double> compatibleGains(Map<Integer, Double> gains, List<ReconstructionAllocation.Entry> notes) {
        if (gains == null) return null;
        var parts = new HashSet<Integer>();
        for (var entry : notes) if (entry.role() != Role.drums) parts.add(entry.note().part());
        if (parts.containsAll(gains.keySet())) return gains;
        var kept = new TreeMap<>(gains);
        kept.keySet().retainAll(parts);
        return kept.isEmpty() ? null : kept;
    }

    private static ReconstructionVoices.Key key(Instrument instrument) {
        return new ReconstructionVoices.Key(instrument.part(), instrument.drumPitch());
    }

    private static Set<ReconstructionVoices.Key> instrumentKeys(String id, List<ReconstructionAllocation.Entry> notes) {
        var keys = new HashSet<ReconstructionVoices.Key>();
        if (!id.startsWith("FM")) for (var entry : notes) {
            if (id.equals("DAC")) keys.add(new ReconstructionVoices.Key(entry.note().part(), entry.note().pitch()));
            else if (entry.role() != Role.drums) keys.add(new ReconstructionVoices.Key(entry.note().part(), null));
        }
        return keys;
    }

    static Set<String> lanesNeedingFit(Result previous, Map<String, List<ReconstructionAllocation.Entry>> allocated) {
        var targets = new HashSet<String>();
        allocated.forEach((id, notes) -> {
            var lane = previous.lanes().get(id);
            if (lane == null || !lane.instruments().stream().map(ReconstructionVoicing::key)
                    .collect(java.util.stream.Collectors.toSet()).containsAll(instrumentKeys(id, notes))) targets.add(id);
        });
        return targets;
    }

    static Result keepCompatible(Result previous, Result fitted, Map<String, List<ReconstructionAllocation.Entry>> allocated,
                                 Consumer<String> report) {
        var lanes = new TreeMap<String, Lane>();
        var copies = new HashSet<String>();
        // Give saved copy effects first claim on free lanes.
        previous.lanes().forEach((id, lane) -> {
            if (!allocated.containsKey(id)) {
                report.accept("Dropped voicing for " + id + " (lane no longer allocated)");
                reportStaleGains(id, lane.partGains(), null, report);
                for (var instrument : lane.instruments()) reportStaleInstrument(id, instrument, report);
                return;
            }
            var notes = allocated.get(id);
            var gains = compatibleGains(lane.partGains(), notes);
            reportStaleGains(id, lane.partGains(), gains, report);
            var keys = instrumentKeys(id, notes);
            var instruments = new java.util.ArrayList<Instrument>();
            var retained = new HashSet<ReconstructionVoices.Key>();
            for (var instrument : lane.instruments()) {
                if (keys.contains(key(instrument))) {
                    instruments.add(instrument);
                    retained.add(key(instrument));
                } else {
                    reportStaleInstrument(id, instrument, report);
                }
            }
            if (!retained.containsAll(keys) && fitted != null)
                fitted.lanes().get(id).instruments().stream().filter(instrument -> !retained.contains(key(instrument)))
                        .forEach(instruments::add);
            Role role = notes.stream().anyMatch(entry -> entry.role() == lane.role())
                    ? lane.role() : ReconstructionVoiceStage.dominantRole(notes);
            var effects = compatibleCopy(id, lane.effects(), allocated, copies, report);
            lanes.put(id, new Lane(role, lane.variantId(), lane.shortlist(), lane.gain(), lane.articulation(), effects,
                    lane.patchReason(), lane.articulationReason(), lane.effectReason(), List.copyOf(instruments),
                    gains, lane.overrides(), lane.drumGains()));
        });
        if (fitted != null) fitted.lanes().forEach((id, lane) -> {
            if (lanes.containsKey(id)) return;
            var effects = compatibleCopy(id, lane.effects(), allocated, copies, report);
            lanes.put(id, new Lane(lane.role(), lane.variantId(), lane.shortlist(), lane.gain(), lane.articulation(),
                    effects, lane.patchReason(), lane.articulationReason(), lane.effectReason(), lane.instruments(),
                    lane.partGains(), lane.overrides(), lane.drumGains()));
        });
        return new Result(previous.version(), lanes, previous.fitWindows());
    }

    private static Effects compatibleCopy(String id, Effects effects, Map<String, ?> allocated, Set<String> copies,
                                          Consumer<String> report) {
        var copy = effects.copy();
        if (copy == null || !allocated.containsKey(copy.lane()) && copies.add(copy.lane())) return effects;
        report.accept("Dropped " + id + " copy effect to " + copy.lane() + " (destination is in use)");
        return new Effects(effects.preset(), effects.detune(), effects.vibrato(), null);
    }

    private static void reportStaleGains(String id, Map<Integer, Double> before, Map<Integer, Double> kept,
                                         Consumer<String> report) {
        if (before != null) for (int part : new TreeSet<>(before.keySet())) {
            if (kept == null || !kept.containsKey(part))
                report.accept("Dropped " + id + " partGains[" + part + "] (part no longer allocated)");
        }
    }

    private static void reportStaleInstrument(String id, Instrument instrument, Consumer<String> report) {
        report.accept("Dropped " + id + " instrument for part " + instrument.part()
                + (instrument.drumPitch() == null ? "" : " pitch " + instrument.drumPitch()) + " (no longer allocated)");
    }

    static Result preserveRefit(Result fitted, Result previous, Set<String> targets, boolean refitLevels,
                                Set<String> changedMaterial, Map<String, List<ReconstructionAllocation.Entry>> allocated) {
        var lanes = new java.util.LinkedHashMap<>(fitted.lanes());
        for (var entry : previous.lanes().entrySet()) {
            String id = entry.getKey();
            var before = entry.getValue();
            var after = lanes.get(id);
            boolean targeted = targets.isEmpty() || targets.contains(id);
            if (after == null) {
                require(targeted, "voicing.json.lanes." + id, "untargeted lane is missing from the new allocation");
                require(before.overrides() == null || before.overrides().isEmpty(), "voicing.json.lanes." + id,
                        "cannot refit a removed lane with scoped overrides");
                require(before.drumGains() == null || before.drumGains().isEmpty(), "voicing.json.lanes." + id,
                        "cannot refit a removed lane with drum gains");
            } else {
                lanes.put(id, targeted ? after.withPreviousEdits(before, refitLevels || changedMaterial.contains(id), allocated.getOrDefault(id, List.of())) : before);
            }
        }
        if (!targets.isEmpty()) for (String id : lanes.keySet())
            require(targets.contains(id) || previous.lanes().containsKey(id), "voicing.json.lanes." + id,
                    "new lane must be explicitly targeted");
        return new Result(fitted.version(), lanes, fitted.fitWindows());
    }

    static Map<String, List<ReconstructionOverrides.Span>> validate(Result result, ReconstructionAllocation.Result allocation, InstrumentCatalogue catalogue, int frames) {
        var allocated = byLane(allocation);
        require(result.lanes().keySet().equals(allocated.keySet()), "voicing.json.lanes", "must cover every allocated lane");
        var copies = new HashSet<String>();
        var scopes = new java.util.HashMap<String, List<ReconstructionOverrides.Span>>();
        result.lanes().forEach((id, lane) -> {
            String at = "voicing.json.lanes." + id;
            boolean fm = id.startsWith("FM");
            boolean dac = id.equals("DAC");
            require(lane.gain() >= 0 && lane.gain() <= 4, at + ".gain", "expected 0–4");
            require(allocated.get(id).stream().anyMatch(entry -> entry.role() == lane.role()), at + ".role",
                    "role must appear on the allocated lane");
            if (lane.partGains() != null) lane.partGains().forEach((part, gain) ->
                    require(!dac && gain != null && gain >= .25 && gain <= 4
                                    && allocated.get(id).stream().anyMatch(entry -> entry.note().part() == part
                                            && entry.role() != Role.drums),
                            at + ".partGains", "expected allocated tonal parts and gain in 0.25–4"));
            if (lane.drumGains() != null) lane.drumGains().forEach((pitch, gain) ->
                    require((dac || id.equals("PSG3")) && pitch >= 0 && pitch <= 127
                                    && gain != null && gain >= 0 && gain <= 4,
                            at + ".drumGains", "expected MIDI drum pitches and gains in 0–4 on DAC or PSG3"));
            var articulation = lane.articulation();
            require(articulation.gate() > 0 && articulation.gate() <= 1, at + ".articulation.gate", "expected (0,1]");
            require(articulation.minimumReleaseRate() >= 0 && articulation.minimumReleaseRate() <= (fm ? 15 : 0),
                    at + ".articulation.minimumReleaseRate", "expected 0–15 for FM or 0 for other lanes");
            if (lane.variantId() != null) {
                require(fm, at + ".variantId", "lane-wide patches are FM only");
                kind(catalogue, lane.variantId(), Kind.FM_VOICE, at + ".variantId");
            }
            for (var patch : lane.shortlist()) kind(catalogue, patch.variantId(), Kind.FM_VOICE, at + ".shortlist");
            var keys = new HashSet<List<Integer>>();
            for (var instrument : lane.instruments()) {
                int pitch = instrument.drumPitch() == null ? -1 : instrument.drumPitch();
                require(!fm && dac == (pitch != -1) && keys.add(List.of(instrument.part(), pitch))
                                && allocated.get(id).stream().anyMatch(entry -> entry.note().part() == instrument.part()
                                && (!dac || entry.note().pitch() == pitch)), at + ".instruments",
                        "expected unique allocated parts and DAC pitches");
                var drum = instrument.drumSelection();
                if (drum != null) {
                    require(dac && drum.rate() >= 0 && drum.rate() <= 255
                                    && java.util.Objects.equals(instrument.variantId(), drum.variantId()),
                            at + ".instruments", "invalid DAC sample selection");
                    if (drum.slice() != null) {
                        com.opensmpsdeck.project.DacSample.validateAll(List.of(drum.slice()));
                        require(drum.rate() == drum.slice().rate(), at + ".instruments", "invalid stem slice rate");
                    }
                }
                if (instrument.variantId() != null)
                    kind(catalogue, instrument.variantId(), dac ? Kind.DAC_SAMPLE : Kind.PSG_VOLUME, at + ".instruments");
            }
            scopes.putAll(ReconstructionOverrides.resolve(id, lane.overrides(), allocated.get(id), frames, catalogue));
            var effects = lane.effects();
            require(!dac || effects.detune() == 0 && effects.vibrato() == null, at + ".effects",
                    "DAC does not support pitch effects");
            detune(effects.detune(), at);
            vibrato(effects.vibrato(), at + ".effects.vibrato");
            var copy = effects.copy();
            if (copy != null) {
                require(fm && ReconstructionAllocation.FM.contains(copy.lane())
                                && !allocated.containsKey(copy.lane()) && copies.add(copy.lane()),
                        at + ".effects.copy.lane", "expected a free FM lane used by only one copy");
                require(copy.delay() >= 0 && copy.delay() <= 60 && copy.gain() > 0 && copy.gain() <= 1,
                        at + ".effects.copy", "expected a 0–60 tick delay and gain in (0,1]");
                detune(copy.detune(), at);
                vibrato(copy.vibrato(), at + ".effects.copy.vibrato");
            }
        });
        return scopes;
    }

    static void kind(InstrumentCatalogue catalogue, String id, Kind kind, String at) {
        try {
            require(catalogue.match(id).asset().kind() == kind, at, "expected " + kind);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(at + ": invalid " + kind + " selection " + id, invalid);
        }
    }

    private static void detune(int amount, String at) {
        require(amount >= -128 && amount <= 127, at + ".effects.detune", "expected a signed byte");
    }

    static void vibrato(Vibrato vibrato, String at) {
        if (vibrato != null) require(vibrato.delay() >= 1 && vibrato.delay() <= 255
                        && vibrato.rate() >= 1 && vibrato.rate() <= 255
                        && vibrato.delta() >= -128 && vibrato.delta() <= 127
                        && vibrato.steps() >= 2 && vibrato.steps() <= 255,
                at, "invalid native modulation arguments");
    }
}

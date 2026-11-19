package com.opensmpsdeck.headless;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionStages.Nullable;
import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;

/** Editable contract between voice fitting and assembly. */
final class ReconstructionVoicing {
    static final String DEFAULT = "default (inconclusive)";
    static final String EVIDENCE = "evidence";

    record Result(int version, Map<String, Lane> lanes) {}
    record Lane(Role role, @Nullable String variantId, List<ScoredPatch> shortlist, double gain,
                Articulation articulation, Effects effects, String patchReason, String articulationReason,
                String effectReason, List<Instrument> instruments) {}
    record ScoredPatch(String variantId, double shortlistScore, double fit, double check) {}
    record Instrument(int part, @Nullable Integer drumPitch, @Nullable String variantId) {}
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

    static void validate(Result result, ReconstructionAllocation.Result allocation, InstrumentCatalogue catalogue) {
        var allocated = byLane(allocation);
        require(result.lanes().keySet().equals(allocated.keySet()), "voicing.json.lanes", "must cover every allocated lane");
        var copies = new HashSet<String>();
        result.lanes().forEach((id, lane) -> {
            String at = "voicing.json.lanes." + id;
            boolean fm = id.startsWith("FM");
            boolean dac = id.equals("DAC");
            require(lane.gain() >= 0 && lane.gain() <= 4, at + ".gain", "expected 0–4");
            require(allocated.get(id).stream().anyMatch(entry -> entry.role() == lane.role()), at + ".role",
                    "role must appear on the allocated lane");
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
                if (instrument.variantId() != null)
                    kind(catalogue, instrument.variantId(), dac ? Kind.DAC_SAMPLE : Kind.PSG_VOLUME, at + ".instruments");
            }
            var effects = lane.effects();
            require(!dac || effects.detune() == 0 && effects.vibrato() == null, at + ".effects",
                    "DAC does not support pitch effects");
            detune(effects.detune(), at);
            vibrato(effects.vibrato(), at);
            var copy = effects.copy();
            if (copy != null) {
                require(fm && ReconstructionAllocation.FM.contains(copy.lane())
                                && !allocated.containsKey(copy.lane()) && copies.add(copy.lane()),
                        at + ".effects.copy.lane", "expected a free FM lane used by only one copy");
                require(copy.delay() >= 0 && copy.delay() <= 60 && copy.gain() > 0 && copy.gain() <= 1,
                        at + ".effects.copy", "expected a 0–60 tick delay and gain in (0,1]");
                detune(copy.detune(), at);
                vibrato(copy.vibrato(), at);
            }
        });
    }

    private static void kind(InstrumentCatalogue catalogue, String id, Kind kind, String at) {
        try {
            require(catalogue.match(id).asset().kind() == kind, at, "expected " + kind);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(at + ": invalid " + kind + " selection " + id, invalid);
        }
    }

    private static void detune(int amount, String at) {
        require(amount >= -128 && amount <= 127, at + ".effects.detune", "expected a signed byte");
    }

    private static void vibrato(Vibrato vibrato, String at) {
        if (vibrato != null) require(vibrato.delay() >= 1 && vibrato.delay() <= 255
                        && vibrato.rate() >= 1 && vibrato.rate() <= 255
                        && vibrato.delta() >= -128 && vibrato.delta() <= 127
                        && vibrato.steps() >= 2 && vibrato.steps() <= 255,
                at + ".effects.vibrato", "invalid native modulation arguments");
    }
}

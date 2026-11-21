package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.ProjectCompiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionLanePolicies.Kind;
import static com.opensmpsdeck.headless.ReconstructionLanePolicies.Mode;
import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionLanePoliciesTest {
    @Test void padUsesOneEnvelopeAcrossLanesAndFreesFmForACounter() throws Exception {
        var result = arrange(List.of(entry("pad", Role.harmony, "FM3", 8, 28, 72),
                        entry("spill", Role.harmony, "PSG2", 8, 28, 76),
                        entry("counter", Role.counter, "PSG1", 14, 22, 60)),
                scoped(rule(Kind.pads, null, null), 0, 60, Set.of("pad", "spill")));
        var coverage = result.policyCoverage().getFirst();
        assertEquals(2, coverage.applied());
        assertEquals(1, coverage.movedFromFm());
        assertEquals(1, coverage.promotedCounters());
        assertEquals(Map.of("PSG3", 1, "PSG2", 1), coverage.lanes());
        assertTrue(result.voicedGates().stream().anyMatch(g -> g.id().equals("counter") && g.lane().equals("FM3")));
        assertEquals(2, result.project().sequences().stream()
                .filter(seq -> ReconstructionLanePolicies.PAD.id().equals(seq.psgEnvelopeId())).count());
        ProjectCompiler.compile(result.project());
    }

    @Test void psgEchoSkipsFutureNoiseAndSupportsShortNotes() throws Exception {
        var entries = List.of(entry("lead", Role.melody, "FM1", 2, 8, 67),
                entry("other", Role.melody, "FM1", 20, 26, 72),
                entry("busy1", Role.counter, "PSG1", 9, 15, 65),
                entry("busy2", Role.counter, "PSG2", 9, 15, 69),
                entry("drums", Role.drums, "PSG3", 11, 16, 42));
        var result = arrange(entries, scoped(rule(Kind.leadEcho, null, null), 0, 27, null));
        var coverage = result.policyCoverage().getFirst();
        assertEquals(2, coverage.eligible());
        assertEquals(1, coverage.busy());
        assertEquals(Map.of("PSG1", 1), coverage.lanes());
        var clip = result.project().clips().stream().filter(c -> c.lane().equals("PSG1")).findFirst().orElseThrow();
        var seq = result.project().sequences().stream().filter(s -> s.id().equals(clip.sequenceId())).findFirst().orElseThrow();
        var echo = seq.notes().stream().filter(n -> n.pitch() == 72).findFirst().orElseThrow();
        assertEquals(27, clip.startTick() + echo.tick());
        assertEquals(6, echo.duration());
        assertTrue(seq.commands().stream().anyMatch(c -> c.tick() == echo.tick()
                && c.command() == NativeCommand.PSG_ENVELOPE && ReconstructionLanePolicies.ECHO.id().equals(c.assetId())));
        ProjectCompiler.compile(result.project());
    }

    @Test void nativePsgWritesHoldPadsAndSilenceTheEchoTail() throws Exception {
        var result = arrange(List.of(entry("pad", Role.harmony, "FM3", 0, 32, 74),
                        entry("lead", Role.melody, "FM1", 5, 40, 69)),
                List.of(new ReconstructionLanePolicies.Scoped(0, rule(Kind.pads, null, null), 0, 60, null),
                        new ReconstructionLanePolicies.Scoped(1, rule(Kind.leadEcho, null, null), 0, 60, null)));
        int[] volume = {15, 15, 15, 15};
        var frames = new ArrayList<int[]>();
        S3kRenderer.render(ProjectCompiler.compileWithAssets(result.project()), 60, ignored -> frames.add(volume.clone()),
                new ChipWriteObserver() {
                    @Override public void onYm2612Write(int port, int register, int value) {}

                    @Override public void onPsgWrite(int value) {
                        if ((value & 0x90) == 0x90) volume[value >> 5 & 3] = value & 15;
                    }
                });

        assertTrue(frames.get(16)[0] < 15, "PSG1 pad sustains beyond the envelope's last value");
        assertEquals(frames.get(16)[0], frames.get(27)[0], "pad holds the same attenuation");
        assertEquals(15, frames.get(34)[0], "pad releases at the end of its gate");
        assertTrue(frames.get(14)[1] < 15, "PSG2 echo sounds after the delay");
        assertEquals(15, frames.get(27)[1], "echo silences after the decay while its gate is open");
    }

    @Test void fmEchoUsesAFreeIntervalAndEmitsVolumeSteps() throws Exception {
        var rule = rule(Kind.fmDouble, Mode.echo, List.of(new ReconstructionLanePolicies.VolumeStep(3, 12)));
        var result = arrange(List.of(entry("lead", Role.melody, "FM1", 3, 13, 67),
                        entry("future", Role.harmony, "FM2", 18, 28, 60),
                        entry("past", Role.harmony, "FM3", 0, 10, 60)),
                scoped(rule, 1, 18, null));
        assertEquals(Map.of("FM3", 1), result.policyCoverage().getFirst().lanes());
        var seq = result.project().sequences().stream().filter(s -> s.id().startsWith("FM3-")).findFirst().orElseThrow();
        assertEquals(11, seq.notes().stream().filter(n -> n.pitch() == 67).findFirst().orElseThrow().tick());
        assertTrue(seq.commands().stream().anyMatch(c -> c.tick() == 11 && c.command() == NativeCommand.DETUNE
                && c.arguments().equals(List.of(2))));
        assertTrue(seq.commands().stream().anyMatch(c -> c.tick() == 14 && c.command() == NativeCommand.VOLUME));
        ProjectCompiler.compile(result.project());
    }

    @Test void presetLayersASoloOpeningWithoutPromotingDenseOrLowBass() throws Exception {
        var preset = ReconstructionLanePolicies.Configuration.preset("s3k-layering");
        var policies = new ArrayList<ReconstructionLanePolicies.Scoped>();
        for (var rule : preset.rules())
            policies.add(new ReconstructionLanePolicies.Scoped(policies.size(), rule, 0, 60, null));

        var result = arrange(List.of(entry("arp-first", Role.bass, "FM2", 0, 4, 65),
                        entry("arp-next", Role.bass, "FM2", 4, 8, 60),
                        entry("low-bass", Role.bass, "FM2", 8, 12, 36),
                        entry("dense-bass", Role.bass, "FM2", 30, 38, 60),
                        entry("lead", Role.melody, "FM1", 30, 38, 72)),
                policies);
        var psg = result.policyCoverage().getFirst();
        assertEquals(2, psg.applied());
        assertEquals(2, psg.eligible());
        assertTrue(result.project().sequences().stream()
                .filter(seq -> ReconstructionLanePolicies.DOUBLE.id().equals(seq.psgEnvelopeId()))
                .flatMap(seq -> seq.notes().stream()).anyMatch(note -> note.tick() == 0 && note.pitch() == 77));
        var fm = result.policyCoverage().getLast();
        assertEquals(3, fm.applied());
        assertEquals(3, fm.eligible());
        assertTrue(result.project().clips().stream().filter(clip -> clip.lane().equals("FM1"))
                .anyMatch(clip -> result.project().sequences().stream().filter(seq -> seq.id().equals(clip.sequenceId()))
                        .flatMap(seq -> seq.notes().stream())
                        .anyMatch(note -> clip.startTick() + note.tick() == 14 && note.pitch() == 65)));
        ProjectCompiler.compile(result.project());
    }

    @Test void lanePolicyConfigurationAcceptsPresetAndExplicitRules() {
        var path = Path.of("voicing.json");
        var base = JsonParser.parseString("""
                {"version":1,"lanes":{},"lanePolicies":{"preset":"s3k-layering"}}
                """).getAsJsonObject();
        var preset = ReconstructionArtifacts.decode(path, ReconstructionVoicing.Result.class, base).lanePolicies();
        assertEquals("s3k-layering", preset.preset());
        assertEquals(73, preset.rules().getFirst().pitch(61));
        assertEquals(14, preset.rules().getLast().delay());
        assertEquals(11, preset.rules().getLast().fmAttenuation());
        base.add("lanePolicies", JsonParser.parseString("""
                [{"policy":"fmDouble","mode":"echo","delayTicks":14}]
                """));
        var explicitEcho = ReconstructionArtifacts.decode(path, ReconstructionVoicing.Result.class, base)
                .lanePolicies().rules().getFirst();
        assertEquals(14, explicitEcho.delay());
        assertEquals(7, explicitEcho.fmAttenuation());
        base.add("lanePolicies", JsonParser.parseString("""
                {"preset":"s3k-layering","delayTicks":14}
                """));
        assertThrows(IllegalArgumentException.class,
                () -> ReconstructionArtifacts.decode(path, ReconstructionVoicing.Result.class, base));
        base.add("lanePolicies", JsonParser.parseString("""
                {"preset":"unknown"}
                """));
        assertThrows(IllegalArgumentException.class,
                () -> ReconstructionArtifacts.decode(path, ReconstructionVoicing.Result.class, base));
    }

    @Test void presetEchoAttenuationRoundTripsAndValidatesTlSteps() {
        var path = Path.of("voicing.json");
        var base = JsonParser.parseString("""
                {"version":1,"lanes":{},"lanePolicies":{"preset":"s3k-layering","echoAttenuation":15}}
                """).getAsJsonObject();
        var voicing = ReconstructionArtifacts.decode(path, ReconstructionVoicing.Result.class, base);
        var echo = voicing.lanePolicies().rules().getLast();
        assertEquals(15, echo.fmAttenuation());
        assertEquals(14, echo.delay());
        assertEquals(73, voicing.lanePolicies().rules().getFirst().pitch(61));
        assertEquals(base.get("lanePolicies"), new Gson().toJsonTree(voicing).getAsJsonObject().get("lanePolicies"));

        var policy = base.getAsJsonObject("lanePolicies");
        for (String invalid : List.of("-1", "128", "1.5", "\"11\"", "null")) {
            policy.add("echoAttenuation", JsonParser.parseString(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> ReconstructionArtifacts.decode(path, ReconstructionVoicing.Result.class, base), invalid);
        }
    }

    @Test void noteLayersUseCarrierAttenuationAndReserveSpareChannelsBeforePolicies() throws Exception {
        var entries = new ArrayList<>(List.of(entry("lead", Role.melody, "FM1", 0, 7, 67),
                entry("backing", Role.harmony, "FM3", 26, 32, 66),
                entry("dry", Role.melody, "FM1", 26, 32, 72),
                entry("busy", Role.melody, "FM1", 45, 55, 71)));
        for (var lane : List.of("FM2", "FM3", "FM4", "FM5"))
            entries.add(entry("occupies-" + lane, Role.counter, lane, 45, 55, 65));
        var policy = scoped(rule(Kind.fmDouble, Mode.echo, null), 0, 60, null);
        var result = arrange(entries, policy, Map.of(
                "lead", layer("lead", """
                        "mode":"echo","laneType":"FM","delayFrames":10,"levelDb":-9,
                        "volumeDecay":[{"frame":3,"attenuation":18}]
                        """),
                "dry", layer("dry", "\"mode\":\"off\""),
                "backing", layer("backing", """
                        "mode":"double","laneType":"PSG","octave":1
                        """),
                "busy", layer("busy", """
                        "mode":"chorus","laneType":"FM","detune":-4
                        """)));
        var byId = new LinkedHashMap<String, ReconstructionLayers.Result>();
        result.layers().forEach(layer -> byId.put(layer.noteId(), layer));
        assertEquals(List.of("lead", "backing", "busy"), result.layers().stream()
                .filter(layer -> layer.startTick() != null).map(ReconstructionLayers.Result::noteId).toList());
        var echo = byId.get("lead");
        assertEquals("FM2", echo.lane());
        assertEquals(10, echo.startTick());
        assertEquals(17, echo.endTick());
        assertEquals(12, echo.attenuation());
        assertEquals(ReconstructionLayers.Status.off, byId.get("dry").status());
        assertEquals("PSG1", byId.get("backing").lane());
        assertEquals(78, byId.get("backing").pitch());
        assertEquals(ReconstructionLayers.Status.skipped, byId.get("busy").status());
        assertTrue(byId.get("busy").reason().contains("No free FM"));
        assertEquals(0, result.policyCoverage().getFirst().eligible());
        var dry = result.project().sequences().stream().filter(s -> s.id().startsWith("FM1-")).findFirst().orElseThrow();
        var wet = result.project().sequences().stream().filter(s -> s.id().startsWith("FM2-")).findFirst().orElseThrow();
        assertEquals(dry.volume() - 12, wet.volume());
        assertTrue(wet.commands().stream().anyMatch(c -> c.tick() == 3 && c.command() == NativeCommand.VOLUME
                && c.arguments().equals(List.of(dry.volume() - 18))));
        ProjectCompiler.compile(result.project());
    }

    private static ReconstructionLayers.Request layer(String id, String fields) {
        var edit = ReconstructionEdits.parse(JsonParser.parseString("""
                {"op":"layer","id":"layer-%s","reason":"fixture","noteIds":["%s"],%s}
                """.formatted(id, id, fields)).getAsJsonObject(), "edit");
        return new ReconstructionLayers.Request(edit, ReconstructionLayers.settings(edit.json(), edit.laneType(), id));
    }

    private static ReconstructionLanePolicies.Rule rule(Kind kind, Mode mode, List<ReconstructionLanePolicies.VolumeStep> steps) {
        return new ReconstructionLanePolicies.Rule(kind, null, null, null, null, null, null, null, mode, null, null, steps);
    }

    private static List<ReconstructionLanePolicies.Scoped> scoped(ReconstructionLanePolicies.Rule rule, int start, int end,
                                                                   Set<String> notes) {
        return List.of(new ReconstructionLanePolicies.Scoped(0, rule, start, end, notes));
    }

    private static ReconstructionAllocation.Entry entry(String id, Role role, String lane, int start, int end, int pitch) {
        var note = new ReconstructionStages.Note(id, role == Role.drums ? 1 : 0, start / 60.0, end / 60.0,
                pitch, 100, "midi", 1);
        return new ReconstructionAllocation.Entry(note, role, lane, start, end,
                ReconstructionAllocation.Outcome.kept, null, null);
    }

    private static S3kReconstruction.Result arrange(List<ReconstructionAllocation.Entry> entries,
                                                     List<ReconstructionLanePolicies.Scoped> policies) throws Exception {
        return arrange(entries, policies, Map.of());
    }

    private static S3kReconstruction.Result arrange(List<ReconstructionAllocation.Entry> entries,
                                                     List<ReconstructionLanePolicies.Scoped> policies,
                                                     Map<String, ReconstructionLayers.Request> layers) throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var bytes = DemoProject.create().voices().getFirst().bytes();
        byte[] patch = new byte[bytes.size()];
        for (int i = 0; i < patch.length; i++) patch[i] = bytes.get(i).byteValue();
        var fm = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Fixture", patch);
        var psg = builder.asset(InstrumentCatalogue.Kind.PSG_VOLUME, "Plain", new byte[]{0, (byte) 0x83});
        var fmVariant = builder.variant(fm, "Fixture", InstrumentCatalogue.Recipe.plain(), null);
        var psgVariant = builder.variant(psg, "Plain", InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();
        var parts = List.of(new MidiScore.Part(0, "Tone", 81, false), new MidiScore.Part(1, "Drums", 0, true));
        var choices = new LinkedHashMap<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>>();
        var fmChoice = ReconstructionVoiceStage.choice(catalogue, fmVariant, null, "", List.of());
        var psgChoice = ReconstructionVoiceStage.choice(catalogue, psgVariant, null, "", List.of());
        for (var lane : ReconstructionAllocation.FM) choices.put(lane, Map.of(new ReconstructionVoices.Key(0, null), fmChoice));
        for (var lane : ReconstructionAllocation.PSG) choices.put(lane, Map.of(new ReconstructionVoices.Key(0, null), psgChoice));
        var sorted = new ArrayList<>(entries);
        sorted.sort(java.util.Comparator.comparingInt(ReconstructionAllocation.Entry::startTick));
        var settings = new ReconstructionVoicing.Lane(Role.melody, fmVariant, List.of(), 1,
                ReconstructionVoicing.WRITTEN, ReconstructionVoicing.DRY, "", "", "", List.of());
        var instruments = Map.of("part-0", new ReconstructionVoicing.SourceSound(settings, psgVariant, 0));
        return S3kReconstruction.arrangeAllocated("Fixture", 30, 60,
                new S3kReconstruction.Score(parts, List.of(), List.of()), new ReconstructionAllocation.Result(1, sorted),
                null, catalogue, choices, Map.of(), Map.of(), false, false, Map.of(), Map.of(), Map.of(),
                null, Map.of(), instruments, policies, new ReconstructionLanePolicies.Levels(), layers, false);
    }
}

package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.TargetProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static com.opensmpsdeck.headless.ReconstructionVoicing.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconstructionInstrumentPlaybackTest {
    private record Attack(int channel, int algorithm, int tl, int pitch, int pan) {}

    @Test void sharedInstrumentKeepsItsRegistersAcrossLanesAndAfterScopedReattack() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var demo = DemoProject.create().voices().getFirst().bytes();
        byte[] patch = new byte[demo.size()];
        for (int i = 0; i < patch.length; i++) patch[i] = demo.get(i).byteValue();
        patch[21] = (byte) 0x90;
        String sharedAsset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Shared", patch);
        String shared = builder.variant(sharedAsset, "Shared", InstrumentCatalogue.Recipe.plain(), null);
        patch[0] ^= 1;
        patch[21] = (byte) 0xA0;
        String otherAsset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Other", patch);
        String other = builder.variant(otherAsset, "Other", InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();

        var parts = List.of(new MidiScore.Part(0, "Main", 81, false), new MidiScore.Part(1, "Other", 0, false));
        var notes = List.of(entry("main", 0, "FM1", 0, 8), entry("parallel", 0, "FM3", 0, 8),
                entry("other", 1, "FM1", 8, 14), entry("return", 0, "FM1", 14, 22));
        var allocation = new ReconstructionAllocation.Result(1, notes);
        var main = settings(shared, .8, new Effects("detuned", 12, null, null));
        var alternate = settings(other, .6, DRY);
        var instruments = Map.of("part-0", new SourceSound(main, null, 0), "part-1", new SourceSound(alternate, null, 0));
        var lane = settings(null, 1, DRY);
        var voicing = Map.of("FM1", lane, "FM3", lane);
        var scoped = new ReconstructionOverrides.Settings(null, null, 4, 8, null, other, .3, null, null,
                new Vibrato(1, 1, 8, 4), null);
        var spans = ReconstructionOverrides.resolve("FM1", List.of(scoped),
                List.of(notes.get(0), notes.get(2), notes.get(3)), 24, catalogue);
        var sharedKey = new ReconstructionVoices.Key(0, null);
        var sharedChoice = ReconstructionVoiceStage.choice(catalogue, shared, null, "", List.of());
        var otherChoice = ReconstructionVoiceStage.choice(catalogue, other, null, "", List.of());
        var choices = Map.of("FM1", Map.of(sharedKey, sharedChoice, new ReconstructionVoices.Key(1, null), otherChoice),
                "FM3", Map.of(sharedKey, sharedChoice));
        var result = S3kReconstruction.arrangeAllocated("Voice boundaries", 30, 24,
                new S3kReconstruction.Score(parts, List.of(), List.of()), allocation, null,
                catalogue, choices, Map.of(), voicing, false, false, spans, Map.of(),
                Map.of(), null, Map.of(), instruments);

        var source = result.project().sequences().stream().filter(seq -> seq.id().startsWith("FM1-")).findFirst().orElseThrow();
        assertEquals(sharedAsset, source.voiceId());
        assertEquals(List.of(4, 14), source.commands().stream().filter(c -> c.command() == NativeCommand.FM_VOICE)
                .map(c -> c.tick()).toList());
        assertTrue(source.commands().stream().anyMatch(c -> c.tick() == 8 && c.command() == NativeCommand.MODULATION_OFF));
        assertEquals(4, ReconstructionCli.fmPatchSwitchBytes(result.project()));

        var compiled = ProjectCompiler.compileWithAssets(result.project());
        var registers = new HashMap<Integer, Integer>();
        var attacks = new ArrayList<Attack>();
        S3kRenderer.render(compiled, 24, ignored -> {}, new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                if (port != 0) return;
                registers.put(register, value);
                if (register != 0x28 || (value & 0xF0) == 0) return;
                int ch = value & 7;
                if (ch != 0 && ch != 2) return;
                attacks.add(new Attack(ch, registers.get(0xB0 + ch), registers.get(0x40 + ch),
                        registers.get(0xA0 + ch) | registers.get(0xA4 + ch) << 8, registers.get(0xB4 + ch)));
            }

            @Override public void onPsgWrite(int value) {}
        });

        var fm1 = attacks.stream().filter(a -> a.channel() == 0).toList();
        assertEquals(4, fm1.size());
        assertEquals(fm1.getFirst(), fm1.getLast());
        assertNotEquals(fm1.getFirst().algorithm(), fm1.get(2).algorithm());
        assertEquals(12, fm1.getFirst().pitch() - fm1.get(2).pitch(), "plain B attack restores detune and disables scoped F0");
        var expectedB = S3kReconstruction.volume(parts.get(1), notes.get(2).note().midi());
        assertEquals(0x20 + 127 - ReconstructionEcho.scaleVolume(expectedB, .6), fm1.get(2).tl());
        assertTrue(attacks.stream().allMatch(a -> a.pan() == 0xC0));
        var fm3 = attacks.stream().filter(a -> a.channel() == 2).toList();
        var first = fm1.getFirst();
        assertEquals(List.of(new Attack(2, first.algorithm(), first.tl(), first.pitch(), first.pan())), fm3);
    }

    @Test void spillRejectsMutedEnvelopesAndKeepsTheQuietestAttackAudible() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var demo = DemoProject.create().voices().getFirst().bytes();
        byte[] patch = new byte[demo.size()];
        for (int i = 0; i < patch.length; i++) patch[i] = demo.get(i).byteValue();
        String fmAsset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "FM", patch);
        String fm = builder.variant(fmAsset, "FM", InstrumentCatalogue.Recipe.plain(), null);
        var part = new MidiScore.Part(0, "Main", 81, false);
        var notes = List.of(entry("spill", 0, "PSG1", 0, 8));
        var voice = settings(fm, .001, DRY);
        psgVariant(builder, "Delayed attack", new byte[]{15, 15, 0, 0, (byte) 0x83});
        psgVariant(builder, "Mute pulse", new byte[]{0, 15, 0, (byte) 0x80});
        psgVariant(builder, "Single-frame gate", new byte[]{2, (byte) 0x83});
        assertNull(ReconstructionSpill.match(builder.build(), voice, part, notes, TargetProfile.S3K).variantId());

        byte[] audible = {4, 4, 4, (byte) 0x81};
        String variant = psgVariant(builder, "Audible attack", audible);
        var match = ReconstructionSpill.match(builder.build(), voice, part, notes, TargetProfile.S3K);
        assertEquals(variant, match.variantId());
        var rawVolume = S3kReconstruction.volume(part, notes.getFirst().note().midi());
        var level = ReconstructionEcho.scaleVolume(Math.max(1, rawVolume - 24), voice.gain());
        assertTrue(15 - (Math.clamp(level + match.volumeOffset(), 0, 127) >> 3) + audible[0] < 15);
    }

    @Test void allocationDropsOnlyCoveredAutomaticUnisonsWithinTheSameInstrument() {
        var voices = List.of(
                decision("lead", 0, 0, 20, Role.melody),
                new ReconstructionRoles.Decision(new ReconstructionStages.Note("covered", 0, 4 / 60.0,
                        16 / 60.0, 72, 96, "consensus", 1), Role.counter, null, null),
                decision("otherPart", 1, 4, 16, Role.harmony),
                decision("written", 0, 6, 14, Role.harmony),
                decision("extends", 0, 17, 24, Role.counter));
        var parts = List.of(new MidiScore.Part(0, "Main", 81, false),
                new MidiScore.Part(1, "Other", 0, false));
        var roles = new ReconstructionRoles.Result(1, 0, voices, null);
        var allocated = ReconstructionAllocation.allocate(roles, parts, 30, false, true).notes();
        var covered = allocated.stream().filter(n -> n.note().id().equals("covered")).findFirst().orElseThrow();
        assertNull(covered.lane());
        assertEquals("lead", covered.competitorNoteId());
        assertEquals(List.of("lead", "otherPart", "written", "extends"), allocated.stream().filter(n -> n.lane() != null)
                .map(n -> n.note().id()).toList());
    }

    @Test void legatoStopsAtAWrittenSameInstrumentReattackInAnotherRole() {
        var prior = decision("lead", 0, 0, 4, Role.melody).note();
        var nextLead = decision("nextLead", 0, 16, 20, Role.melody).note();
        var otherPart = decision("otherPart", 1, 5, 10, Role.harmony);
        var counter = decision("counter", 0, 8, 12, Role.counter);
        assertEquals(counter.note(), ReconstructionLeadContinuity.legatoTarget(prior, List.of(otherPart, counter), nextLead));
        assertEquals(nextLead, ReconstructionLeadContinuity.legatoTarget(prior, List.of(otherPart), nextLead));
    }

    @Test void sharedFitSelectsTheLineAndPrefersExposedSustainedWindows() {
        var notes = List.of(
                decision("line-1", 0, 2, 5, Role.melody),
                decision("line-2", 0, 6, 9, Role.melody),
                decision("line-3", 0, 12, 16, Role.melody),
                decision("line-4", 0, 70, 93, Role.melody),
                decision("chord-1", 0, 0, 34, Role.harmony),
                decision("chord-2", 0, 0, 34, Role.harmony));
        var entries = notes.stream().map(n -> new ReconstructionAllocation.Entry(n.note(), n.role(), "FM1",
                n.note().startTick(960), n.note().endTick(960), ReconstructionAllocation.Outcome.kept, null, null)).toList();
        var line = ReconstructionVoiceStage.exposedLine(entries);
        assertEquals(entries.subList(0, 4), line);
        assertTrue(ReconstructionVoiceStage.exposedLine(entries.subList(2, 6)).isEmpty());

        var windows = new VoiceWindows(960, Map.of("FM1", entries), null, List.of(new VoiceWindows.Focus(line, entries)));
        int first = windows.fitWindows().starts().getFirst();
        assertTrue(first <= 70 && first + 36 >= 93, "training window should cover the exposed sustain");
    }

    private static ReconstructionRoles.Decision decision(String id, int part, int start, int end, Role role) {
        return new ReconstructionRoles.Decision(entry(id, part, "FM1", start, end).note(), role, null, null);
    }

    private static String psgVariant(InstrumentCatalogue.Builder builder, String name, byte[] envelope) {
        String asset = builder.asset(InstrumentCatalogue.Kind.PSG_VOLUME, name, envelope);
        return builder.variant(asset, name, InstrumentCatalogue.Recipe.plain(), null);
    }

    private static Lane settings(String variant, double gain, Effects effects) {
        return new Lane(Role.melody, variant, List.of(), gain, WRITTEN, effects, "", "", "", List.of());
    }

    private static ReconstructionAllocation.Entry entry(String id, int part, String lane, int start, int end) {
        var note = new ReconstructionStages.Note(id, part, start / 60.0, end / 60.0, 72, 96, "midi", 1);
        return new ReconstructionAllocation.Entry(note, Role.melody, lane, start, end,
                ReconstructionAllocation.Outcome.kept, null, null);
    }
}

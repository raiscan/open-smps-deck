package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionVoiceFitTest {
    @Test void jointFitRecoversTwoPatchesWhenIndependentFitPicksTheWrongLead() {
        var lead = List.of(probe("wrong-lead", 0, 1, .25f), probe("lead", 1, 0, 0));
        var bass = List.of(probe("bass", 0, 1, 0), probe("wrong-bass", 0, 0, 1));
        var target = probe("mix", .5f, 1, 0).spectrum();
        var independent = VoiceFit.solve(target, 3, List.of(lead));
        assertEquals("wrong-lead", independent.getFirst().candidate().id());

        var fit = VoiceFit.solve(target, 3, List.of(lead, bass));
        assertEquals(List.of("lead", "bass"), fit.stream().map(s -> s.candidate().id()).toList());
        assertEquals(.5, fit.getFirst().gain(), .001);
        assertEquals(fit, VoiceFit.solve(target, 3, List.of(lead, bass)));
    }

    @Test void recoversTheNativeLeadAndBassFromTheirRenderedMix() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        String lead = simplePatch(builder, "lead", 1, false);
        String wrongLead = simplePatch(builder, "lead-octave", 2, false);
        String bass = simplePatch(builder, "bass", 1, true);
        String wrongBass = simplePatch(builder, "bass-octave", 2, true);
        var catalogue = builder.build();
        var fft = new VoiceSpectra();
        var fit = nativeWindow(catalogue, fft, List.of(lead, wrongLead, bass, wrongBass), 60);
        var check = nativeWindow(catalogue, fft, List.of(lead, wrongLead, bass, wrongBass), 62);
        var candidates = new java.util.ArrayList<VoiceFit.Candidate>();
        for (int i = 0; i < 5; i++) {
            var vector = java.util.Arrays.copyOf(fit.get(i), fit.get(i).length + check.get(i).length);
            System.arraycopy(check.get(i), 0, vector, fit.get(i).length, check.get(i).length);
            candidates.add(new VoiceFit.Candidate(List.of(lead, wrongLead, bass, wrongBass, "mix").get(i), vector));
        }
        var leads = List.of(candidates.get(1), candidates.get(0));
        var basses = List.of(candidates.get(3), candidates.get(2));
        var target = candidates.get(4).spectrum();
        int split = fit.getFirst().length;
        assertEquals(wrongLead, VoiceFit.solve(target, split, List.of(leads)).getFirst().candidate().id());
        var selection = VoiceFit.solve(target, split, List.of(leads, basses));
        assertEquals(List.of(lead, bass), selection.stream().map(choice -> choice.candidate().id()).toList());
    }

    private static List<float[]> nativeWindow(InstrumentCatalogue catalogue, VoiceSpectra fft,
                                              List<String> voices, int pitch) throws Exception {
        var pcm = new java.util.ArrayList<float[]>();
        for (int i = 0; i < voices.size(); i++) {
            var project = CataloguePlayback.matchProbe(catalogue, voices.get(i), pitch + (i >= 2 ? 12 : 0), 18, true);
            pcm.add(VoiceWindows.renderMono(project, 18));
        }
        var mix = new float[pcm.getFirst().length];
        for (int i = 0; i < mix.length; i++) mix[i] = .32f * pcm.get(0)[i] + pcm.get(2)[i];
        pcm.add(mix);
        return pcm.stream().map(fft::extract).toList();
    }

    private static String simplePatch(InstrumentCatalogue.Builder builder, String name, int multiple, boolean second) {
        byte[] bytes = {7, (byte) multiple, (byte) (3 * multiple), 1, 1, 31, 31, 31, 31, 0, 0, 0, 0,
                0, 0, 0, 0, 15, 15, 15, 15, (byte) 0x92, (byte) (second ? 0x9c : 127), 127, 127};
        var asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, name, bytes);
        return builder.variant(asset, name, InstrumentCatalogue.Recipe.plain(), null);
    }

    @Test void rejectsATrainingWinnerThatRegressesOnHeldOutAudio() {
        var normal = new VoiceFit.Candidate("normal", new float[]{1, .4f, 1, .4f});
        var overfit = new VoiceFit.Candidate("overfit", new float[]{1, 0, 0, 1});
        var selection = VoiceFit.solve(new float[]{1, 0, 1, .4f}, 2, List.of(List.of(normal, overfit)));
        assertEquals("normal", selection.getFirst().candidate().id());
    }

    @Test void retainsAnIncumbentThroughSmallFitChanges() {
        var prior = probe("prior", 1, .045f, 0);
        var challenger = probe("challenger", 1, 0, 0);
        var target = probe("target", 1, 0, 1).spectrum();
        var choices = List.of(List.of(challenger, prior));
        var fit = new VoiceFit(target, 3, List.of(prior), java.util.Set.of());
        fit.patches(choices, List.of("prior"));
        assertEquals("prior", fit.selection(0).candidate().id());
        assertEquals("challenger", VoiceFit.solve(target, 3, choices).getFirst().candidate().id());
    }

    @Test void replacesTheIncumbentOnlyForClearImprovementOnBothPartitions() {
        var prior = probe("prior", 1, .2f, 0);
        var challenger = probe("challenger", 1, 0, 0);
        var choices = List.of(List.of(prior, challenger));
        var fit = new VoiceFit(probe("target", 1, 0, 1).spectrum(), 3, List.of(prior), java.util.Set.of());
        fit.patches(choices, List.of("prior"));
        assertEquals("challenger", fit.selection(0).candidate().id());

        var overfit = new VoiceFit(new float[]{1, 0, 1, 1, .2f, 1}, 3, List.of(prior), java.util.Set.of());
        overfit.patches(choices, List.of("prior"));
        assertEquals("prior", overfit.selection(0).candidate().id());
    }

    @Test void reusesFitAndCheckWindowsWhenNotesMove() {
        var revised = java.util.Map.of("FM1", List.of(new ReconstructionAllocation.Entry(
                new ReconstructionStages.Note("note", 0, .8, 1.1, 72, 90, "midi", 1),
                ReconstructionRoles.Role.melody, "FM1", 48, 66,
                ReconstructionAllocation.Outcome.kept, null, null)));
        var original = new VoiceWindows(480, java.util.Map.of(), null);
        assertNotEquals(original.fitWindows(), new VoiceWindows(480, revised, null).fitWindows());
        assertEquals(original.fitWindows(), new VoiceWindows(480, revised, original.fitWindows()).fitWindows());
        assertNotEquals(original.fitWindows(), new VoiceWindows(360, revised, original.fitWindows()).fitWindows());
    }

    @Test void defaultsToDelayedLeadVibratoAndQuietEchoWhenTheLaneIsFree() {
        var effects = ReconstructionVoicing.idiom(ReconstructionRoles.Role.melody, "FM5");
        assertTrue(effects.vibrato().delay() > 0);
        assertEquals("FM5", effects.copy().lane());
        assertTrue(effects.copy().delay() > 0);
        assertTrue(effects.copy().gain() < .3);
        assertNull(ReconstructionVoicing.idiom(ReconstructionRoles.Role.melody, null).copy());
        assertEquals(ReconstructionVoicing.DRY, ReconstructionVoicing.idiom(ReconstructionRoles.Role.bass, "FM5"));
    }

    @Test void aChallengerMeetsItsHolderDirectlyAndMovesNoOtherPart() {
        var target = probe("target", 1, 1, 0).spectrum();
        var holder = probe("holder", 1, 0, .02f);
        var slight = probe("slight", 1, 0, .0199f);
        var clear = probe("clear", 1, 0, 0);
        var other = probe("other", 0, 1, .3f);
        var betterOther = probe("better-other", 0, 1, 0);

        // A joint pass over every part lets the other part move while this one is tried.
        var joint = new VoiceFit(target, 3, List.of(holder, other), java.util.Set.of());
        joint.patches(List.of(List.of(holder, slight), List.of(other, betterOther)), java.util.Arrays.asList("holder", "other"));
        assertEquals("better-other", joint.selection(1).candidate().id());

        var fit = new VoiceFit(target, 3, List.of(holder, other), java.util.Set.of());
        assertFalse(fit.challenge(0, slight, VoiceFit.INCUMBENT_MARGIN));
        assertEquals("holder", fit.selection(0).candidate().id());
        assertEquals("other", fit.selection(1).candidate().id());

        assertTrue(fit.challenge(0, clear, VoiceFit.INCUMBENT_MARGIN));
        assertEquals("clear", fit.selection(0).candidate().id());
        assertEquals("other", fit.selection(1).candidate().id());
        assertTrue(fit.beats(0, holder, VoiceFit.INCUMBENT_MARGIN));
        assertFalse(fit.beats(0, clear, VoiceFit.INCUMBENT_MARGIN));
    }

    @Test void aSlightGainOverAnIntermediatePatchIsNotAGainOverTheHolder() {
        var target = probe("target", 1, 0, 0).spectrum();
        var holder = probe("holder", 1, .1f, 0);
        var legacy = probe("legacy", 1, .08f, 0);
        var challenger = probe("challenger", 1, .0799f, 0);

        // Once a legacy patch has replaced the holder, the new-patch margin lets the challenger through.
        var chained = new VoiceFit(target, 3, List.of(legacy), java.util.Set.of());
        chained.patches(List.of(List.of(legacy, challenger)), java.util.Arrays.asList("holder"));
        assertEquals("challenger", chained.selection(0).candidate().id());

        // Against the patch the decision selected, with the incumbent margin, it must clear 0.5%.
        var direct = new VoiceFit(target, 3, List.of(legacy), java.util.Set.of());
        assertFalse(direct.challenge(0, challenger, VoiceFit.INCUMBENT_MARGIN));
        assertEquals("legacy", direct.selection(0).candidate().id());
        var original = new VoiceFit(target, 3, List.of(holder), java.util.Set.of());
        assertTrue(original.challenge(0, challenger, VoiceFit.INCUMBENT_MARGIN));
    }

    @Test void aSurvivorIsRecheckedAfterALaterLaneIsRestored() {
        var target = probe("target", 1, 1, 0).spectrum();
        var holder = probe("holder", 1, 0, 0);
        var other = probe("other", 0, 1, 0);
        // The first challenger only helps while the second supplies half of the other part.
        var first = probe("first", 1, .5f, 0);
        var second = probe("second", 0, .5f, .1f);
        var fit = new VoiceFit(target, 3, List.of(holder, other), java.util.Set.of());
        var replaced = new java.util.LinkedHashMap<Integer, VoiceFit.Selection>();
        replaced.put(0, fit.selection(0));
        replaced.put(1, fit.selection(1));
        fit.replace(0, new VoiceFit.Selection(first, 1));
        fit.replace(1, new VoiceFit.Selection(second, 1));
        // Inspected first, beside the second challenger, the first one passes.
        assertTrue(fit.beats(0, holder, VoiceFit.INCUMBENT_MARGIN));
        assertFalse(fit.beats(1, other, VoiceFit.INCUMBENT_MARGIN));

        fit.settle(replaced, VoiceFit.INCUMBENT_MARGIN);
        assertTrue(replaced.isEmpty());
        assertEquals(List.of("holder", "other"), List.of(fit.selection(0).candidate().id(), fit.selection(1).candidate().id()));
        assertEquals(1, fit.selection(0).gain(), 1e-9);
        assertEquals(1, fit.selection(1).gain(), 1e-9);
    }

    private static VoiceFit.Candidate probe(String id, float a, float b, float c) {
        return new VoiceFit.Candidate(id, new float[]{a, b, c, a, b, c});
    }
}

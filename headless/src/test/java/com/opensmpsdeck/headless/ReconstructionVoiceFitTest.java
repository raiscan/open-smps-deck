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
            pcm.add(VoiceWindows.renderMono(com.opensmpsdeck.project.ProjectCompiler.compileWithAssets(project), 18));
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

    @Test void defaultsToDelayedLeadVibratoAndQuietEchoWhenTheLaneIsFree() {
        var effects = ReconstructionVoicing.idiom(ReconstructionRoles.Role.melody, "FM5");
        assertTrue(effects.vibrato().delay() > 0);
        assertEquals("FM5", effects.copy().lane());
        assertTrue(effects.copy().delay() > 0);
        assertTrue(effects.copy().gain() < .3);
        assertNull(ReconstructionVoicing.idiom(ReconstructionRoles.Role.melody, null).copy());
        assertEquals(ReconstructionVoicing.DRY, ReconstructionVoicing.idiom(ReconstructionRoles.Role.bass, "FM5"));
    }

    private static VoiceFit.Candidate probe(String id, float a, float b, float c) {
        return new VoiceFit.Candidate(id, new float[]{a, b, c, a, b, c});
    }
}

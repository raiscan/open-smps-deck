package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoicePatchShortlistTest {
    @Test void probesComeFromEveryStretchOfAPartWhoseBestAttacksClusterAtTheStart() {
        var ordered = new ArrayList<ReconstructionAllocation.Entry>();
        // Quality order: five long opening notes, then forty short notes from the rest of the part.
        for (int i = 0; i < 5; i++) ordered.add(entry(i * 30, 18));
        for (int i = 0; i < 40; i++) ordered.add(entry(200 + i * 6, 5));

        var stretches = VoicePatchShortlist.stretches(ordered, 5);

        assertEquals(5, stretches.size());
        assertEquals(List.of(0, 224, 278, 332, 386),
                stretches.stream().map(stretch -> stretch.getFirst().startTick()).toList());
        assertEquals(45, stretches.stream().mapToInt(List::size).sum());
    }

    @Test void aQuietPartIsComparedWithItsPatchAtTheWrittenVolume() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        // Three carriers under one modulator. Their sum clips the channel at full volume.
        byte[] bytes = {0x05, 1, 1, 1, 1, 31, 31, 31, 31, 0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15,
                0x18, (byte) 0x80, (byte) 0x80, (byte) 0x80};
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Carriers", bytes);
        String id = builder.variant(asset, "Carriers", InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();
        var shape = new VoiceAttackShape();
        var quiet = shape.measure(render(catalogue, id, 103));
        var full = shape.measure(render(catalogue, id, 127));
        var shortlist = new VoicePatchShortlist(catalogue, TargetGame.S3K);
        var variant = catalogue.match(id).variant();

        int volume = shortlist.writtenVolume(variant, 72, quiet.peak());

        assertEquals(103, volume);
        assertEquals(127, shortlist.writtenVolume(variant, 72, 4 * full.peak()));
        assertTrue(quiet.distance(full) > .05, "the full-volume template misrepresents the quiet part");
        assertEquals(0, quiet.distance(shape.measure(render(catalogue, id, volume))), 1e-9);
    }

    private static float[] render(InstrumentCatalogue catalogue, String id, int volume) throws Exception {
        return VoiceWindows.renderMono(CataloguePlayback.leveledProbe(catalogue, id, 72, 7, TargetGame.S3K, volume), 7);
    }

    private static ReconstructionAllocation.Entry entry(int start, int length) {
        var note = new ReconstructionStages.Note("n" + start, 0, start / 60.0, (start + length) / 60.0, 60, 96, "test", 1);
        return new ReconstructionAllocation.Entry(note, Role.harmony, "FM2", start, start + length,
                ReconstructionAllocation.Outcome.kept, null, null);
    }
}

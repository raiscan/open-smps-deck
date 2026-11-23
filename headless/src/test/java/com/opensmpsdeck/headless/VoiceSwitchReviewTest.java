package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceSwitchReviewTest {
    @Test void theDecodeChangesPatchForALongPassageAndNotForAStrayNote() {
        var notes = new ArrayList<VoiceNoteDecode.Scored>();
        for (int i = 0; i < 60; i++) {
            // Notes 20 to 39 clearly prefer the second patch. Note 50 prefers it once, weakly.
            boolean second = i >= 20 && i < 40;
            notes.add(scored(i, i * .25, second ? .6f : i == 50 ? .78f : .9f, second ? .9f : i == 50 ? .82f : .6f));
        }

        var labels = VoiceNoteDecode.viterbi(notes, 2, false);

        for (int i = 0; i < 60; i++) assertEquals(i >= 20 && i < 40 ? 1 : 0, labels[i], "note " + i);
    }

    @Test void aRecurringFigureLendsEvidenceToANoteThatHasLittleOfItsOwn() {
        var notes = new ArrayList<VoiceNoteDecode.Scored>();
        // Twelve statements of one figure prefer the second patch. The thirteenth is nearly undecided.
        for (int i = 0; i < 13; i++) {
            boolean weak = i == 12;
            notes.add(new VoiceNoteDecode.Scored(new VoiceNoteDecode.Note("n" + i, i, i + .5, 60), 20, 1,
                    new float[]{weak ? .81f : .5f, weak ? .80f : .9f}, new float[2], new float[2]));
        }

        assertEquals(0, VoiceNoteDecode.viterbi(notes.subList(12, 13), 2, true)[0]);
        assertEquals(1, VoiceNoteDecode.viterbi(notes, 2, true)[12]);
    }

    @Test void aPassageIsAppliedOnlyWhenEveryGuardPasses() {
        var builder = new InstrumentCatalogue.Builder();
        byte[] bytes = new byte[25];
        bytes[21] = (byte) 0x80;
        String home = builder.variant(builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Home", bytes), "Home",
                InstrumentCatalogue.Recipe.plain(), null);
        bytes[1] = 2;
        String other = builder.variant(builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Other", bytes), "Other",
                InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();
        var sound = new ReconstructionVoicing.SourceSound(new ReconstructionVoicing.Lane(Role.melody, home, List.of(), 1,
                ReconstructionVoicing.WRITTEN, ReconstructionVoicing.DRY, "", "", "", List.of()), null, 0);
        var notes = new ArrayList<VoiceNoteDecode.Scored>();
        var labels = new int[120];
        var lanes = new HashMap<String, String>();
        for (int i = 0; i < 120; i++) {
            // Half a second each: 20 s on the part's patch, 15 s on the other, a stray pair, then home again.
            boolean away = i >= 40 && i < 70 || i == 90 || i == 91;
            notes.add(scored(i, i * .5, away ? .6f : .9f, away ? .9f : .6f));
            labels[i] = away ? 1 : 0;
            lanes.put("n" + i, "FM2");
        }
        var decoded = new VoiceNoteDecode.Result(List.of(home, other), notes, labels);

        var own = VoiceSwitchReview.report(1, "Lead", "lead.wav", true, 120, decoded, sound, catalogue, lanes,
                Set.of(), 4, false);
        var shared = VoiceSwitchReview.report(1, "Lead", "lead.wav", false, 120, decoded, sound, catalogue, lanes,
                Set.of(), 4, false);

        assertEquals(2, own.passages().size());
        var passage = own.passages().getFirst();
        assertEquals("high", passage.confidence(), passage.guards().toString());
        assertEquals(30, passage.onsets());
        assertEquals(15, passage.voicedSeconds(), 1e-9);
        assertEquals(25, passage.bankBytes());
        assertEquals(4, passage.switchBytes());
        // Two notes are not a change of instrument.
        assertEquals("rejected", own.passages().get(1).confidence());
        // The same evidence on a shared stem is offered for review, never applied.
        assertEquals("review", shared.passages().getFirst().confidence());
        assertTrue(shared.passages().getFirst().guards().stream().anyMatch(guard -> guard.name().equals("own stem") && !guard.pass()));
    }

    @Test void aNoteInstrumentEditCanCarryALaneGain() {
        var edit = JsonParser.parseString("{\"instrument\":\"variant-x\",\"gain\":1.5}").getAsJsonObject();
        ReconstructionNoteVoicing.validate(edit, ReconstructionLaneType.FM, "edit");
        var loud = JsonParser.parseString("{\"instrument\":\"variant-x\",\"gain\":5}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> ReconstructionNoteVoicing.validate(loud, ReconstructionLaneType.FM, "edit"));
        var alone = JsonParser.parseString("{\"gain\":1.5,\"modulationOff\":true}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> ReconstructionNoteVoicing.validate(alone, null, "edit"));
        assertFalse(edit.has("vibrato"));
        assertArrayEquals(new int[]{0}, VoiceNoteDecode.viterbi(List.of(scored(0, 0, .9f, .1f)), 2, true));
    }

    private static VoiceNoteDecode.Scored scored(int index, double start, float first, float second) {
        // Different pitches and lengths keep these notes out of one recurring figure.
        return new VoiceNoteDecode.Scored(new VoiceNoteDecode.Note("n" + index, start, start + .5, 48 + index % 24), 40, 1,
                new float[]{first, second}, new float[]{first, second}, new float[]{first, second});
    }
}

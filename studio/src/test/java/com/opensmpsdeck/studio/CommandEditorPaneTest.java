package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import com.opensmpsdeck.project.Sequence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandEditorPaneTest {
    @Test void parsesRawHexBytesWithoutSignedConversionOrClamping() {
        assertEquals(List.of(0, 127, 128, 255), CommandEditorPane.parseHexArguments("00 7f 0x80 0Xff"));
        assertEquals(List.of(0, 1, 1, 4), CommandEditorPane.parseHexArguments(" 0 01\t01\n04 "));
        assertEquals(List.of(), CommandEditorPane.parseHexArguments(" \n "));
        assertEquals("00 7F 80 FF", CommandEditorPane.hexArguments(List.of(0, 127, 128, 255)));
        for (String invalid : List.of("100", "-1", "FF,80", "0x", "GG", "0x100", "+1"))
            assertThrows(IllegalArgumentException.class, () -> CommandEditorPane.parseHexArguments(invalid), invalid);
    }

    @Test void staleCommandFormCannotOverwriteAnExternallyEditedSource() {
        StudioProject original = DemoProject.create();
        Sequence captured = original.sequences().getFirst();
        StudioProject changed = ProjectEdits.setSourceSettings(original, captured.id(), captured.voiceId(), 37);
        assertSame(original, CommandEditorPane.guardSource(original, captured));
        assertThrows(IllegalArgumentException.class, () -> CommandEditorPane.guardSource(changed, captured));
        assertEquals(37, changed.sequences().getFirst().volume());
        // A project-wide edit does not make an otherwise unchanged source stale.
        StudioProject renamed = new StudioProject(original.formatVersion(), "Renamed", original.mode(), original.ticksPerBeat(), original.tempo(), original.voices(), original.sequences(), original.clips(), TargetGame.S3K, null);
        assertSame(renamed, CommandEditorPane.guardSource(renamed, captured));
    }

    @Test void sourceSettingsKeepUnmodifiedCurrentFieldsButRejectAConflictingDraft() {
        StudioProject original = DemoProject.create();
        Sequence captured = original.sequences().getFirst();
        StudioProject external = ProjectEdits.setRepeatState(original, captured.id(), "CARRY");
        StudioProject merged = CommandEditorPane.sourceSettingsFromDraft(external, captured, captured.voiceId(), 37, captured.repeatState());
        Sequence result = merged.sequences().getFirst();
        assertEquals(37, result.volume());
        assertEquals("CARRY", result.repeatState());
        assertEquals(captured.commands(), result.commands());
        StudioProject conflicting = ProjectEdits.setSourceSettings(original, captured.id(), captured.voiceId(), 62);
        assertThrows(IllegalArgumentException.class, () -> CommandEditorPane.sourceSettingsFromDraft(conflicting, captured, captured.voiceId(), 37, captured.repeatState()));
        assertEquals(62, conflicting.sequences().getFirst().volume());
    }

    @Test void untouchedVoiceUsesTheCurrentStableIdAndSourceSettingsPreserveNativeEvents() {
        StudioProject demo = DemoProject.create();
        Sequence first = demo.sequences().getFirst();
        Voice second = new Voice("voice-other", "Other voice", demo.voices().getFirst().bytes());
        List<Voice> voices = new ArrayList<>(demo.voices());
        voices.add(second);
        StudioProject original = new StudioProject(demo.formatVersion(), demo.name(), demo.mode(), demo.ticksPerBeat(), demo.tempo(),
                voices, demo.sequences(), demo.clips(), TargetGame.S3K, null);
        original = ProjectEdits.addCommand(original, first.id(), new CommandEvent("pan", 0, NativeCommand.PAN, List.of(0xC0)));
        Sequence captured = original.sequences().getFirst();
        StudioProject external = ProjectEdits.setSourceSettings(original, first.id(), second.id(), first.volume());
        StudioProject merged = CommandEditorPane.sourceSettingsFromDraft(external, captured, captured.voiceId(), 40, captured.repeatState());
        Sequence result = merged.sequences().getFirst();
        assertEquals(second.id(), result.voiceId());
        assertEquals(40, result.volume());
        assertEquals(captured.commands(), result.commands());
        assertEquals(2, merged.formatVersion());
    }

    @Test void sourceEnvelopeDraftMergesUntouchedReferencesAndRejectsConflictingAssignments(){
        StudioProject original=ProjectEdits.addEnvelope(DemoProject.create(),new Envelope("env-a","A","PSG_VOLUME",List.of(0,0x83)));
        original=ProjectEdits.addEnvelope(original,new Envelope("env-b","B","PSG_VOLUME",List.of(0,4,0x83)));
        Sequence expected=original.sequences().getLast();
        StudioProject external=ProjectEdits.setSourceEnvelopes(original,expected.id(),"env-a",null);
        StudioProject merged=CommandEditorPane.sourceSettingsFromDraft(external,expected,null,42,expected.repeatState(),null,null);
        Sequence result=merged.sequences().getLast();assertEquals("env-a",result.psgEnvelopeId());assertEquals(42,result.volume());assertEquals(2,merged.envelopes().size());assertEquals(3,merged.formatVersion());
        assertThrows(IllegalArgumentException.class,()->CommandEditorPane.sourceSettingsFromDraft(external,expected,null,42,expected.repeatState(),"env-b",null));
    }
}

package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class VoiceEditorPaneTest {
    @Test void exactHexFormRetainsHighBitsNativeOrderAndUnsignedValues() {
        List<Integer> bytes = IntStream.range(0, 25).map(i -> (i * 11 + 128) & 255).boxed().toList();
        String formatted = VoiceEditorPane.formatVoiceBytes(bytes);
        assertEquals(bytes, VoiceEditorPane.parseVoiceBytes(formatted));
        assertEquals(bytes, VoiceEditorPane.parseVoiceBytes(" 0x" + formatted.replace(" ", "\n0X") + " "));
        assertEquals(0x80, VoiceEditorPane.parseVoiceBytes(formatted).getFirst());
        assertEquals(bytes.get(24), VoiceEditorPane.parseVoiceBytes(formatted).getLast());
    }

    @Test void invalidOrIncompleteRawBytesAreRejectedWithoutClamping() {
        String zeros = "00 ".repeat(24);
        for (String invalid : List.of("", zeros, zeros + "00 00", zeros + "100", zeros + "-1", zeros + "+1", zeros + "0x", zeros + "GG", zeros + "00,00"))
            assertThrows(IllegalArgumentException.class, () -> VoiceEditorPane.parseVoiceBytes(invalid), invalid);
        assertThrows(IllegalArgumentException.class, () -> VoiceEditorPane.parseVoiceBytes(null));
    }

    @Test void staleVoiceDraftCannotOverwriteExternalBytesOrRename() {
        StudioProject original = DemoProject.create();
        Voice captured = original.voices().getFirst();
        List<Integer> changedBytes = new ArrayList<>(captured.bytes());
        changedBytes.set(0, changedBytes.getFirst() ^ 0x80);
        StudioProject changed = ProjectEdits.updateVoice(original, new Voice(captured.id(), captured.name(), changedBytes));
        assertThrows(IllegalArgumentException.class, () -> VoiceEditorPane.guardVoice(changed, captured));
        StudioProject renamed = ProjectEdits.updateVoice(original, new Voice(captured.id(), "External rename", captured.bytes()));
        assertThrows(IllegalArgumentException.class, () -> VoiceEditorPane.guardVoice(renamed, captured));
        assertEquals(captured, original.voices().getFirst());
        assertEquals(changedBytes, changed.voices().getFirst().bytes());
    }

    @Test void unrelatedSourceChangesAllowVoiceEditsButRemovalRejectsStaleDrafts() {
        StudioProject original = DemoProject.create();
        Voice captured = original.voices().getFirst();
        var source = original.sequences().getFirst();
        StudioProject sourceEdit = ProjectEdits.setSourceSettings(original, source.id(), source.voiceId(), 37);
        assertSame(sourceEdit, VoiceEditorPane.guardVoice(sourceEdit, captured));
        Voice unused = new Voice("voice-unused", "Unused", captured.bytes());
        StudioProject withUnused = ProjectEdits.addVoice(original, unused);
        StudioProject removed = ProjectEdits.deleteVoice(withUnused, unused.id());
        assertThrows(IllegalArgumentException.class, () -> VoiceEditorPane.guardVoice(removed, unused));
        assertSame(original, VoiceEditorPane.guardVoice(original, captured));
    }
}

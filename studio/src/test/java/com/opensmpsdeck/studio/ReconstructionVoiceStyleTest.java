package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionVoicePlan.Kind;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

final class ReconstructionVoiceStyleTest {
    @TempDir Path work;

    @Test void migratesCloseLegacySwatchesAndKeepsLocalCustomTags() throws Exception {
        var ids = List.of("bass", "crash", "guitar");
        var voices = Map.of(
                "bass", new ReconstructionBulk.Voice("bass", "Bass line", Kind.tonal, null),
                "crash", new ReconstructionBulk.Voice("crash", "Drum crash", Kind.drum, null),
                "guitar", new ReconstructionBulk.Voice("guitar", "Guitar chords", Kind.tonal, null));
        var store = Preferences.userNodeForPackage(ReconstructionVoices.class)
                .node("reconstruction-voice-colors").node(key(work.toString()));
        try {
            store.putInt(key("bass"), 0x56b4e9);
            store.putInt(key("crash"), 0x77aadd);
            store.putInt(key("guitar"), 0xe69f00);
            var palette = new ReconstructionVoices.Palette(work);
            var styles = palette.styles(ids, Map.of(), voices);
            assertEquals(Color.web("#56b4e9"), styles.get("bass").color());
            assertEquals(Color.web("#e69f00"), styles.get("guitar").color());
            assertNotEquals(Color.web("#77aadd"), styles.get("crash").color());

            palette.tag("bass", "BL1");
            var reopened = new ReconstructionVoices.Palette(work);
            assertEquals(styles, reopened.styles(ids, Map.of(), voices));
            var projection = ReconstructionVoices.of(List.of(), List.of(), voices, Map.of(), reopened);
            assertEquals(List.of("BL1", "DC", "GC"), projection.entries().stream().map(ReconstructionVoices.Entry::tag).toList());
            reopened.tag("bass", "");
            assertEquals("BL", ReconstructionVoices.autoTag("Bass line"));
            assertEquals("", new ReconstructionVoices.Palette(work).customTag("bass"));
        } finally {
            store.removeNode();
        }
    }

    private static String key(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

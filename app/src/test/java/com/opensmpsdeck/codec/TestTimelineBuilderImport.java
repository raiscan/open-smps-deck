package com.opensmpsdeck.codec;

import com.opensmpsdeck.io.SmpsImporter;
import com.opensmpsdeck.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies {@link TimelineBuilder} with a real imported
 * Sonic 2 SMPS file (Emerald Hill Zone). Gracefully skipped if the SMPS rips
 * are not present on disk.
 */
class TestTimelineBuilderImport {

    private static final String EMERALD_HILL = "../docs/SMPS-rips/Sonic The Hedgehog 2/2-01 Emerald Hill Zone.sm2";

    static boolean smpsRipsAvailable() {
        return new File(EMERALD_HILL).exists();
    }

    private Song loadEmeraldHill() throws Exception {
        return new SmpsImporter().importFile(new File(EMERALD_HILL));
    }

    @Test
    @EnabledIf("smpsRipsAvailable")
    void emeraldHillZoneBuildsValidTimeline() throws Exception {
        Song song = loadEmeraldHill();
        UnrolledTimeline timeline = TimelineBuilder.build(song);

        int gridResolution = timeline.gridResolution();
        assertTrue(gridResolution >= 1 && gridResolution <= 48,
                "Grid resolution should be between 1 and 48, got " + gridResolution);

        int totalGridRows = timeline.totalGridRows();
        assertTrue(totalGridRows > 0 && totalGridRows < 10_000,
                "Total grid rows should be > 0 and < 10000, got " + totalGridRows);

        // Count active channels (channels with at least one event)
        int activeChannels = 0;
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            if (!timeline.channel(ch).events().isEmpty()) {
                activeChannels++;
            }
        }
        assertTrue(activeChannels >= 4,
                "At least 4 channels should have events, got " + activeChannels);

        // Validate per-channel event invariants
        PhraseLibrary library = song.getHierarchicalArrangement().getPhraseLibrary();

        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            UnrolledTimeline.TimelineChannel channel = timeline.channel(ch);
            List<UnrolledTimeline.TimelineEvent> events = channel.events();
            if (events.isEmpty()) continue;

            // Events must be time-ordered (startTick monotonically increasing)
            for (int i = 1; i < events.size(); i++) {
                assertTrue(events.get(i).startTick() >= events.get(i - 1).startTick(),
                        "Channel " + ch + ": events must be time-ordered. "
                                + "Event " + i + " startTick=" + events.get(i).startTick()
                                + " < event " + (i - 1) + " startTick=" + events.get(i - 1).startTick());
            }

            for (int i = 0; i < events.size(); i++) {
                UnrolledTimeline.TimelineEvent event = events.get(i);

                // All events must have positive duration
                assertTrue(event.durationTicks() > 0,
                        "Channel " + ch + " event " + i + ": durationTicks must be > 0, got "
                                + event.durationTicks());

                // All events must have non-null source and decoded
                assertNotNull(event.source(),
                        "Channel " + ch + " event " + i + ": source must not be null");
                assertNotNull(event.decoded(),
                        "Channel " + ch + " event " + i + ": decoded must not be null");

                // All SourceRef phraseIds must point to valid phrases in the library
                int phraseId = event.source().phraseId();
                assertNotNull(library.getPhrase(phraseId),
                        "Channel " + ch + " event " + i + ": phraseId " + phraseId
                                + " must exist in the phrase library");
            }

            // Active channels must have phrase spans
            assertFalse(channel.phraseSpans().isEmpty(),
                    "Channel " + ch + " has events but no phrase spans");
        }
    }

    @Test
    @EnabledIf("smpsRipsAvailable")
    void zoomLevelsAreValid() throws Exception {
        Song song = loadEmeraldHill();
        UnrolledTimeline timeline = TimelineBuilder.build(song);

        int gridResolution = timeline.gridResolution();
        List<Integer> zoomLevels = GridResolutionCalculator.zoomLevels(gridResolution);

        assertFalse(zoomLevels.isEmpty(), "Zoom levels list must not be empty");

        assertEquals(1, zoomLevels.get(0).intValue(),
                "First zoom level must be 1 (1x is always available)");

        for (int level : zoomLevels) {
            assertEquals(0, gridResolution % level,
                    "Zoom level " + level + " must cleanly divide grid resolution " + gridResolution);
        }
    }
}

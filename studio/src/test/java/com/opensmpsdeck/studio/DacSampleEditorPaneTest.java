package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DacSampleEditorPaneTest {
    @Test void compareAndSwapPreservesChangesToSamplesMadeOutsideTheForm() {
        DacSample captured = DacSample.of("kick", "Kick", 12, new byte[]{0, -1});
        StudioProject base = ProjectEdits.addSample(DemoProject.create(), captured);
        StudioProject unrelated = ProjectEdits.moveClip(base, "clip-a1", "FM2", 0);
        assertSame(unrelated, DacSampleEditorPane.guardSample(unrelated, captured));

        StudioProject changed = ProjectEdits.updateSample(base, DacSample.of("kick", "New kick", 12, captured.pcm()));
        StudioProject deleted = ProjectEdits.deleteSample(base, "kick");
        assertThrows(IllegalArgumentException.class, () -> DacSampleEditorPane.guardSample(changed, captured));
        assertThrows(IllegalArgumentException.class, () -> DacSampleEditorPane.guardSample(deleted, captured));
    }
}

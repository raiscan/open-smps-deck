package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointPlaybackTest {
    @Test void restoredPresentationsMatchContinuousRenderingAcrossExamples() throws Exception {
        try (var files = Files.list(Path.of("..", "examples"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".smps.json")).toList()) {
                var project = ProjectFiles.load(file).project();
                if (project.target() != TargetGame.S3K || project.nativeProgram() != null) continue;
                var playback = TargetPlayback.prepare(project);
                int end = ProjectRenderTiming.presentationCount(project);
                verify(playback, playback.checkpoints(), end);
            }
        }
    }

    @Test void relocatesCheckpointsBetweenCountedAndInlineEvents() throws Exception {
        var compact = TargetPlayback.prepare(DemoProject.create());
        var linear = TargetPlayback.prepare(DemoProject.create(), ProjectCompiler.Encoding.LINEAR);
        var checkpoints = compact.checkpoints();
        checkpoints.warmThrough(480);
        var relocated = checkpoints.revise(linear, 420);
        assertEquals(360, relocated.checkpointAtOrBefore(480));
        verify(linear, relocated, 510);
    }

    @Test void latePitchChangeInvalidatesItsPrefixAndKeepsEarlierClockAndChipState() throws Exception {
        var old = TargetPlayback.prepare(repeatedSong(60));
        var edited = TargetPlayback.prepare(repeatedSong(61));
        var baseline = old.checkpoints();
        baseline.warmThrough(540);
        var revision = baseline.revise(edited, 390);
        assertEquals(360, revision.checkpointAtOrBefore(540));
        int[] index = {361};
        try (var reader = revision.reader();
             var resumed = reader.bookmark(index[0]).reader()) {
            // Compare through the changed note, another held note, and both releases.
            int[] continuous = {0};
            edited.render(540, block -> {
                if (continuous[0]++ < index[0]) return;
                assertArrayEquals(block, resumed.at(index[0]++));
            });
        }
        assertEquals(540, index[0]);
        assertEquals(540, baseline.checkpointAtOrBefore(540));
        assertEquals(360, baseline.revise(edited, 540).checkpointAtOrBefore(540),
                "the executed-prefix proof must reject a cutoff after changed events");
    }

    private static StudioProject repeatedSong(int finalPitch) {
        var voice = DemoProject.create().voices().getFirst();
        var notes = new ArrayList<NoteEvent>();
        for (int i = 0; i < 40; i++)
            notes.add(new NoteEvent("note-" + i, i * 10, 8, i == 39 ? finalPitch : 60));
        var lead = new Sequence("lead", "Lead", "FM", 420, voice.id(), 108, notes);
        var psg = new Sequence("tail", "Tail", "PSG", 540, null, 100,
                List.of(new NoteEvent("held", 310, 140, 72)));
        return new StudioProject(2, "Checkpoint fixture", "S3K", 24, 0, List.of(voice), List.of(lead, psg),
                List.of(new Clip("left", "lead", "FM1", 0, 420), new Clip("right", "tail", "PSG1", 0, 540)),
                TargetGame.S3K, null);
    }

    private static void verify(TargetPlayback playback, CheckpointPlayback checkpoints, int end) throws Exception {
        var pcm = new ArrayList<short[]>();
        playback.render(end, block -> pcm.add(block.clone()));
        checkpoints.warmThrough(end);
        try (var reader = checkpoints.reader()) {
            for (int frame = end - 1; frame >= 0; frame -= 37) {
                assertArrayEquals(pcm.get(frame), reader.at(frame), "seek at presentation " + frame);
                if (frame + 1 < end) assertArrayEquals(pcm.get(frame + 1), reader.at(frame + 1));
            }
        }
    }
}

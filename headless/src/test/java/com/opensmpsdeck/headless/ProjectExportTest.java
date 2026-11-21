package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ProjectExportTest {
    @TempDir Path scratch;

    @Test void pcmMatchesDirectRenderAndVgmParsesWithDac() throws Exception {
        var project = ProjectFiles.load(Path.of("../examples/dac-percussion.smps.json")).project();
        int frames = ProjectRenderTiming.presentationCount(project);
        var wav = scratch.resolve("direct.wav");
        var exported = scratch.resolve("export.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(project), wav, frames);
        ProjectExport.write(project, null, exported, new ProjectExport.Options(ProjectExport.Format.WAV,
                null, null, null, null, false, false), (done, total, message) -> {});
        assertEquals(-1, Files.mismatch(wav, exported));
        var vgm = scratch.resolve("export.vgm");
        ProjectExport.write(project, null, vgm, new ProjectExport.Options(ProjectExport.Format.VGM,
                null, null, null, null, false, false), (done, total, message) -> {});
        int[] dacWrites = {0};
        var parsed = VgmFile.read(vgm, (at, command, register, value) -> {
            if (command == 0x52 && register == 0x2a) dacWrites[0]++;
        });
        assertTrue(dacWrites[0] > 100);
        assertEquals(project.name(), parsed.title());
    }

    @Test void detachedWorkSurvivesRunRerunAndReviewWithoutPermission() throws Exception {
        var source = ProjectFiles.load(Path.of("../examples/lantern-walk.smps.json")).project();
        var file = scratch.resolve(ReconstructionReview.ARRANGEMENT);
        var edited = source.withDetachedFromReconstruction(true).withSettings("Hand edit", source.ticksPerBeat(), source.tempo());
        var saved = ProjectFiles.save(file, edited, null);
        var revision = ReconstructionReview.revision(scratch);
        var args = new String[]{"--from", "assemble", scratch.toString()};
        assertThrows(java.io.IOException.class,
                () -> ReconstructionStages.run("run", args, System.out, System.err));
        assertThrows(java.io.IOException.class,
                () -> ReconstructionStages.rerun(scratch, scratch, java.util.List.of(), false, false, System.out, System.err));
        assertThrows(java.io.IOException.class,
                () -> ReconstructionReview.apply(scratch, revision, revision.entries(), (done, total, message) -> {}));
        assertThrows(ReconstructionReview.Changed.class,
                () -> ReconstructionReview.apply(scratch, revision, revision.entries(), "different-revision",
                        (done, total, message) -> {}));
        assertEquals(saved, ProjectFiles.load(file));
    }

    @Test void stemsZipHasIndependentNoiseAndDacOutputs() throws Exception {
        var source = Path.of("../examples/dac-percussion.smps.json");
        var zip = scratch.resolve("stems.zip");
        ProjectExport.write(ProjectFiles.load(source).project(), null, zip,
                new ProjectExport.Options(ProjectExport.Format.WAV, 0.0, 0.1, 0.05, null, true, true),
                (done, total, message) -> {});
        try (var data = new ZipFile(zip.toFile())) {
            assertEquals(10, data.size());
            assertNotNull(data.getEntry("Noise.wav"));
            assertNotNull(data.getEntry("DAC.wav"));
            assertEquals(44 + 4800 * 4, data.getEntry("DAC.wav").getSize());
        }
    }
}

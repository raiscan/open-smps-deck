package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.GroupArrangement;
import com.opensmpsdeck.project.StudioProject;

/** Presentation limit shared by project preview and source WAV export. */
public final class ProjectRenderTiming {
    private ProjectRenderTiming() {}

    public static int presentationCount(StudioProject project) {
        if (project.nativeProgram() != null) return project.nativeProgram().previewFrames();
        if (project.nativeTempo() != null) {
            var driver = PackageDriver.supported(project.target().id());
            return driver.previewFrames(project, project.nativeTempo());
        }
        long end = GroupArrangement.flatten(project).stream().mapToInt(clip -> clip.endTick()).max().orElse(0);
        end = Math.max(end, ProjectCompiler.dacEndTick(project));
        // Header-tempo phase/load allowance plus one second of chip/mixer tail.
        long presentations = (long) Math.ceil((end + 2L) * 256.0 / (256 - project.tempo())) + 60;
        if (presentations > S3kRenderer.MAX_PRESENTATIONS)
            throw new IllegalArgumentException("Song exceeds the current ten-minute preview limit; shorten it or reduce the native tempo delay");
        return (int) Math.max(1, presentations);
    }
}

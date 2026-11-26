package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.StudioProject;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Path;

/** Portable container semantics are shared. Playback and ROM lookup belong to the driver. */
interface PackageDriver {
    record Image(StockAssets assets, boolean known) {}

    String id();

    /** Game ID of the stock assets this driver references. */
    default String stockGame() { return id(); }
    void validateBase(int base);
    Image openRom(Path path) throws IOException;
    void preflight(CompiledProject project);
    void render(CompiledProject project, Path output, int frames) throws IOException;

    default ProjectCompiler.BankLayout compile(StudioProject project, int tempo) {
        return ProjectCompiler.compileArrangementLayout(project);
    }

    default int previewFrames(StudioProject project, int tempo) {
        return ProjectRenderTiming.presentationCount(project);
    }

    default int word(byte[] bytes, int offset) {
        return PortablePackageWriter.word(bytes, offset);
    }

    default byte[] voiceBytes(byte[] source) { return source; }

    default Set<Integer> musicIds() { return Sonic3kMusic.titleMap().keySet(); }

    static PackageDriver supported(String id) {
        return switch (id) {
            case "s1" -> ClassicPackageDriver.S1;
            case "s2" -> ClassicPackageDriver.S2;
            case "s3k" -> S3kPackageDriver.INSTANCE;
            case "candescence" -> S3kPackageDriver.CANDESCENCE;
            case "scd" -> throw new IllegalArgumentException("driver scd is not a music target (Sonic CD FM has no music loader)");
            default -> throw new IllegalArgumentException("Unknown package driver " + id);
        };
    }
}

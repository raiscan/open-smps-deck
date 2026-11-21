package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import java.io.IOException;
import java.nio.file.Path;

/** Portable container semantics are shared. Playback and ROM lookup belong to the driver. */
interface PackageDriver {
    record Image(StockAssets assets, boolean known) {}

    String id();
    void validateBase(int base);
    Image openRom(Path path) throws IOException;
    void preflight(CompiledProject project);
    void render(CompiledProject project, Path output, int frames) throws IOException;

    static PackageDriver supported(String id) {
        if (!id.equals("s3k")) throw new IllegalArgumentException("driver not yet supported: " + id);
        return S3kPackageDriver.INSTANCE;
    }
}

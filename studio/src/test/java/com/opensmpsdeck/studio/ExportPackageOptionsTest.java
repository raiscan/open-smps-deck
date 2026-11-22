package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ProjectExport.Format;
import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExportPackageOptionsTest {
    @TempDir Path directory;

    @Test void settingsFollowThePackageTargetAndPassThroughTheSelectedRom() {
        var s3k = new ExportPackageOptions(TargetGame.S3K, true);
        var s2 = new ExportPackageOptions(TargetGame.S2, false);
        var rom = directory.resolve("s3k.gen");

        var portable = s2.options(Format.PORTABLE, null, true, true, " MIT ", "", false, "").portable();
        assertEquals("s2", portable.driver());
        assertTrue(portable.includeSource());
        assertFalse(portable.retailPatch());
        assertEquals("MIT", portable.license());

        var mod = s3k.options(Format.MOD, rom, true, true, "", "13, 21", false, "").portable();
        assertFalse(mod.includeSource());
        assertTrue(mod.retailPatch());
        assertEquals(List.of(13, 21), mod.replacements());
        assertEquals(List.of(rom), mod.roms());
        assertThrows(IllegalArgumentException.class,
                () -> s3k.options(Format.MOD, null, true, false, "", "", false, ""));

        var kit = s3k.options(Format.DISASM, rom, true, true, "MIT", "", true, "0x81, 82");
        assertNull(kit.portable());
        assertTrue(kit.disasm().dpcmClosed());
        assertEquals(List.of(129, 130), kit.disasm().replaceDac());
        assertEquals(List.of(rom), kit.disasm().roms());
    }
}

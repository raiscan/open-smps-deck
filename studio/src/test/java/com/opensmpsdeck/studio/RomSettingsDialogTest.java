package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.rom.RomLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertNull;

class RomSettingsDialogTest {
    @TempDir Path folder;

    @Test void ignoresUnverifiedPreviousPackageSelections() throws Exception {
        var prefs = Preferences.userRoot().node("opensmpsdeck-test/" + UUID.randomUUID());
        try {
            var missing = new RomLibrary.Entry("s3k", "missing", null, null, null);
            Path invalid = Files.writeString(folder.resolve("invalid.gen"), "synthetic invalid ROM");
            prefs.put("s3k", invalid.toString());
            assertNull(RomSettingsDialog.verifiedPrevious(missing, prefs));

            prefs.put("s3k", folder.resolve("gone.gen").toString());
            assertNull(RomSettingsDialog.verifiedPrevious(missing, prefs));

            prefs.put("s3k", "relative.gen");
            assertNull(RomSettingsDialog.verifiedPrevious(missing, prefs));
        } finally {
            prefs.removeNode();
        }
    }
}

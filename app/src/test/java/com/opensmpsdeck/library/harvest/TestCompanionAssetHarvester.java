package com.opensmpsdeck.library.harvest;

import com.opensmpsdeck.library.InstrumentAssetKind;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.library.SourceReference;
import com.opensmpsdeck.library.rip.SmpsDriverDefinition;
import com.opensmpsdeck.library.rip.SmpsRipConfig;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCompanionAssetHarvester {

    @TempDir
    Path tempDir;

    @Test
    void companionHarvesterAddsEnvelopeDacAndInsSetAssets() throws Exception {
        writeLst(tempDir.resolve("PSG.lst"), "Env", new byte[]{1, 2, (byte) 0x80});
        writeLst(tempDir.resolve("Modulat.lst"), "Mod", new byte[]{3, 4, (byte) 0x80});
        Files.createDirectories(tempDir.resolve("DAC"));
        Files.write(tempDir.resolve("DAC").resolve("Kick.bin"), new byte[]{0x40});
        Files.writeString(tempDir.resolve("DAC.ini"), """
                [81]
                Compr=PCM
                File=DAC\\Kick.bin
                Rate=32
                Pan=40
                Param1=01
                Param2=02
                """);
        byte[] voice = new byte[FmVoice.VOICE_SIZE];
        voice[0] = 0x27;
        Files.write(tempDir.resolve("InsSet.17D8.bin"), voice);

        SmpsRipConfig.Section section = new SmpsRipConfig.Section(".s3k", tempDir, Map.of(
                "VolEnv", "PSG.lst",
                "ModEnv", "Modulat.lst",
                "DAC", "DAC.ini",
                "GlobalInsLib", "InsSet.17D8.bin"));
        InstrumentLibrary library = new InstrumentLibrary();

        HarvestResult result = CompanionAssetHarvester.harvest(
                library, tempDir, section,
                new SmpsDriverDefinition("Z80", "Overflow", "Default", "Bit7", false),
                SourceReference.minimal(tempDir.toString(), "config.ini", ".s3k"));

        assertEquals(4, result.addedCount());
        assertEquals(0, result.duplicateCount());
        assertEquals(0, result.skippedCount());
        assertTrue(result.warnings().isEmpty());
        assertEquals(1, library.entries(InstrumentAssetKind.PSG_ENVELOPE).size());
        assertEquals(1, library.entries(InstrumentAssetKind.MOD_ENVELOPE).size());
        assertEquals(1, library.entries(InstrumentAssetKind.DAC_SAMPLE).size());
        assertEquals(1, library.entries(InstrumentAssetKind.FM_VOICE).size());

        var dac = library.entries(InstrumentAssetKind.DAC_SAMPLE).getFirst();
        assertEquals("Kick", dac.displayName());
        assertEquals(32, dac.playbackRate());
        assertEquals("PCM", dac.compressionLabel());
        assertEquals("40", dac.pan());
        assertEquals("01", dac.param1());
        assertEquals("02", dac.param2());
        assertEquals("81", dac.dacId());
        assertEquals("DAC.ini", dac.sourceReferences().getFirst().sourceCompanionFile());
        assertEquals("81", dac.sourceReferences().getFirst().originalIndexOrId());

        assertArrayEquals(FmVoice.swapMiddleOperators(voice),
                library.entries(InstrumentAssetKind.FM_VOICE).getFirst().data());
    }

    @Test
    void missingCompanionDoesNotAbortOtherHarvesting() throws Exception {
        writeLst(tempDir.resolve("PSG.lst"), "Env", new byte[]{1, (byte) 0x80});
        SmpsRipConfig.Section section = new SmpsRipConfig.Section(".sm2", tempDir, Map.of(
                "VolEnv", "PSG.lst",
                "DAC", "MissingDAC.ini"));
        InstrumentLibrary library = new InstrumentLibrary();

        HarvestResult result = CompanionAssetHarvester.harvest(
                library, tempDir, section,
                new SmpsDriverDefinition("Z80", "Overflow", "Default", "Bit7", false),
                SourceReference.minimal(tempDir.toString(), "config.ini", ".sm2"));

        assertEquals(1, result.addedCount());
        assertEquals(1, result.skippedCount());
        assertEquals(1, library.entries(InstrumentAssetKind.PSG_ENVELOPE).size());
        assertEquals(1, result.warnings().size());
    }

    private static void writeLst(Path path, String name, byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("LST_ENV".getBytes(StandardCharsets.US_ASCII));
        out.write(1);
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        out.write(nameBytes.length);
        out.write(nameBytes);
        out.write(data.length);
        out.write(data);
        Files.write(path, out.toByteArray());
    }
}

package com.opensmpsdeck.library.scan;

import com.opensmpsdeck.io.InstrumentLibraryFile;
import com.opensmpsdeck.library.InstrumentAssetKind;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInstrumentLibraryScanner {

    private static final int LIB_BASE = 0x17D8;

    @TempDir
    Path tempDir;

    @Test
    void recursiveScanFindsNestedConfigAndIsIdempotent() throws Exception {
        Path context = tempDir.resolve("Sega").resolve("Sonic2").resolve("Proto");
        writeRipContext(context, ".sm2", "song.8000.sm2", null);
        writeLst(context.resolve("PSG.lst"), "Square", new byte[]{1, 2, (byte) 0x80});
        byte[] voice = voice(0x21);
        Files.write(context.resolve("InsSet.17D8.bin"), voice);
        Files.write(context.resolve("song.8000.sm2"), buildS2SongWithGlobalInsLib(LIB_BASE));

        InstrumentLibrary library = new InstrumentLibrary();
        InstrumentLibraryScanner scanner = new InstrumentLibraryScanner();

        ScanSummary first = scanner.scan(tempDir, library);

        assertEquals(1, first.configDirectoriesFound());
        assertEquals(1, first.fullSongImportsAttempted());
        assertEquals(1, first.fullSongImportsSucceeded());
        assertEquals(1, first.assetOnlyFoldersHarvested());
        assertEquals(2, first.newAssets());
        assertEquals(1, first.newAssetsByKind().get(InstrumentAssetKind.FM_VOICE));
        assertEquals(1, first.newAssetsByKind().get(InstrumentAssetKind.PSG_ENVELOPE));
        assertEquals(1, library.entries(InstrumentAssetKind.FM_VOICE).size());
        assertEquals(1, library.entries(InstrumentAssetKind.PSG_ENVELOPE).size());
        assertEquals("Sega/Sonic2/Proto/PSG.lst",
                library.entries(InstrumentAssetKind.PSG_ENVELOPE).getFirst()
                        .sourceReferences().getFirst().sourceCompanionFile());
        assertTrue(first.failures().isEmpty());

        library.clearDirty();
        ScanSummary second = scanner.scan(tempDir, library);

        assertEquals(1, second.configDirectoriesFound());
        assertEquals(0, second.newAssets());
        assertEquals(4, second.duplicateAssets());
        assertEquals(2, second.duplicateAssetsByKind().get(InstrumentAssetKind.FM_VOICE));
        assertEquals(2, second.duplicateAssetsByKind().get(InstrumentAssetKind.PSG_ENVELOPE));
        assertFalse(library.isDirty(), "Repeated scan should not add new source refs");
    }

    @Test
    void nonCurrentImportDialectIsAssetOnlyBeforeLayerSeven() throws Exception {
        Path context = tempDir.resolve("Sega").resolve("SonicSpinball");
        Files.createDirectories(context);
        Files.writeString(context.resolve("config.ini"), """
                [Music]
                Ext=.trs
                Driver=DefDrv.txt
                VolEnv=PSG.lst
                Song=bonus.trs
                """);
        Files.writeString(context.resolve("DefDrv.txt"), """
                PtrFmt=Z80
                TempoMode=S2
                InsMode=Default
                InsRegs=Bit7
                """);
        writeLst(context.resolve("PSG.lst"), "Spin", new byte[]{5, 6, (byte) 0x80});
        Files.write(context.resolve("bonus.trs"), buildS2SongWithGlobalInsLib(LIB_BASE));

        InstrumentLibrary library = new InstrumentLibrary();

        ScanSummary summary = new InstrumentLibraryScanner().scan(tempDir, library);

        assertEquals(1, summary.configDirectoriesFound());
        assertEquals(1, summary.assetOnlyFoldersHarvested());
        assertEquals(0, summary.fullSongImportsAttempted());
        assertEquals(0, summary.fullSongImportsSucceeded());
        assertEquals(0, summary.unsupportedSongDialects());
        assertEquals(1, summary.newAssets());
        assertEquals(1, library.entries(InstrumentAssetKind.PSG_ENVELOPE).size());
        assertTrue(summary.failures().isEmpty());
    }

    @Test
    void directInsSetHarvestAndFullImportUseSameFmVoiceLayout() throws Exception {
        Path context = tempDir.resolve("Sega").resolve("Sonic2");
        writeRipContext(context, ".sm2", "music.8000.sm2", "Commands.txt");
        byte[] voice = voice(0x41);
        Files.write(context.resolve("InsSet.17D8.bin"), voice);
        Files.write(context.resolve("music.8000.sm2"), buildS2SongWithGlobalInsLib(LIB_BASE));

        ScanSummary summary = new InstrumentLibraryScanner().scan(tempDir, new InstrumentLibrary());

        assertEquals(1, summary.fullSongImportsAttempted());
        assertEquals(1, summary.fullSongImportsSucceeded());
        assertEquals(1, summary.newAssets());
        assertEquals(1, summary.duplicateAssets());
        assertEquals(1, summary.totalLibraryCountsByKind().get(InstrumentAssetKind.FM_VOICE));

        InstrumentLibrary library = new InstrumentLibrary();
        new InstrumentLibraryScanner().scan(tempDir, library);
        assertEquals(1, library.entries(InstrumentAssetKind.FM_VOICE).size());
        assertArrayEquals(voice, library.entries(InstrumentAssetKind.FM_VOICE).getFirst().data());
    }

    @Test
    void saveLoadThenRescanIdenticalRootDoesNotDirtyLibrary() throws Exception {
        Path context = tempDir.resolve("Sega").resolve("Sonic3").resolve("Final");
        writeRipContext(context, ".sm2", "theme.8000.sm2", null);
        writeLst(context.resolve("PSG.lst"), "Lead", new byte[]{3, 4, (byte) 0x80});
        Files.write(context.resolve("InsSet.17D8.bin"), voice(0x51));
        Files.write(context.resolve("theme.8000.sm2"), buildS2SongWithGlobalInsLib(LIB_BASE));

        InstrumentLibraryScanner scanner = new InstrumentLibraryScanner();
        InstrumentLibrary original = new InstrumentLibrary();
        scanner.scan(tempDir, original);
        InstrumentLibraryFile.save(original, tempDir.resolve("library-out"));

        InstrumentLibrary loaded = InstrumentLibraryFile.load(tempDir.resolve("library-out"));
        assertFalse(loaded.isDirty());

        ScanSummary rescan = scanner.scan(tempDir, loaded);

        assertEquals(0, rescan.newAssets());
        assertEquals(4, rescan.duplicateAssets());
        assertFalse(loaded.isDirty(), "Rescanning a saved and loaded library should not dirty it");
    }

    private static void writeRipContext(Path context, String extension, String songFile, String commandsFile)
            throws Exception {
        Files.createDirectories(context);
        Files.writeString(context.resolve("config.ini"), """
                [Music]
                Ext=%s
                Driver=DefDrv.txt
                GlobalInsLib=InsSet.17D8.bin
                VolEnv=PSG.lst
                Song=%s
                %s
                """.formatted(extension, songFile,
                commandsFile == null ? "" : "Commands=" + commandsFile));
        Files.writeString(context.resolve("DefDrv.txt"), """
                PtrFmt=Z80
                TempoMode=S2
                InsMode=Default
                InsRegs=Bit7
                """);
        if (commandsFile != null) {
            Files.writeString(context.resolve(commandsFile), "F2 Stop 0\n");
        }
    }

    private static byte[] voice(int firstByte) {
        byte[] voice = new byte[FmVoice.VOICE_SIZE];
        voice[0] = (byte) firstByte;
        for (int i = 1; i < voice.length; i++) {
            voice[i] = (byte) i;
        }
        return voice;
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

    private static byte[] buildS2SongWithGlobalInsLib(int rawVoicePtr) {
        int base = 0x8000;
        int fm1Track = 6 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLE16(out, rawVoicePtr);
        out.write(1);
        out.write(0);
        out.write(0x01);
        out.write(0x80);
        writeLE16(out, base + fm1Track);
        out.write(0);
        out.write(0);
        out.write(0x85);
        out.write(0x08);
        out.write(0xF2);
        return out.toByteArray();
    }

    private static void writeLE16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}

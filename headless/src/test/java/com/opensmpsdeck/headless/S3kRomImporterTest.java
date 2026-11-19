package com.opensmpsdeck.headless;

import com.openggf.audio.smps.DacData;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class S3kRomImporterTest {
    @TempDir Path directory;

    @Test void preservesAnAddressedBankAndItsExactBindings() throws Exception {
        byte[] bank = bank();
        byte[] pulse = bytes(0x80, 0xAB);
        var dac = new DacData(Map.of(5, pulse, 6, pulse),
                Map.of(0x89, new DacData.DacEntry(5, 3), 0xA1, new DacData.DacEntry(6, 16)), 297);
        var project = S3kRomImporter.assemble("Original test song", bank, 8, new byte[25],
                Map.of(12, bytes(2, 0x83)), Map.of(5, bytes(1, 0x82, 1)),
                Map.of(5, bytes(1, 0x82, 0)), dac);
        assertArrayEquals(bank, project.nativeProgram().bank());
        assertEquals(4, project.nativeProgram().localVoiceCount());
        assertEquals(0x8000, project.nativeProgram().bankBase());
        assertEquals(8, project.nativeProgram().headerOffset());
        assertEquals(1, project.samples().size());
        assertArrayEquals(pulse, project.samples().getFirst().pcm());
        assertEquals(List.of(0x89, 0xA1), project.nativeProgram().dacSlots().stream().map(slot -> slot.note()).toList());
        assertEquals(List.of(3, 16), project.nativeProgram().dacSlots().stream().map(slot -> slot.rate()).toList());
        assertEquals(List.of(1, 0x82, 0), ProjectCompiler.compileWithAssets(project).modulationEnvelopes().get(5));
        assertTrue(project.envelopes().stream().anyMatch(item -> item.bytes().equals(List.of(1, 0x82, 1))));
        Path path = directory.resolve("import.smps.json");
        ProjectFiles.save(path, project, null);
        assertEquals(project, ProjectFiles.load(path).project());
        var analysis = NativeBankAnalysis.inspect(ProjectCompiler.compileWithAssets(project));
        assertTrue(analysis.events().stream().anyMatch(event -> event.address() == 0x8000));
    }

    @Test void rejectsLocalVoicesWhoseExtentOverlapsMusic() {
        byte[] bank = bank();
        put(bank, 8, 33, 0x80);
        var dac = new DacData(Map.of(5, bytes(0x80)),
                Map.of(0x89, new DacData.DacEntry(5, 3)), 297);
        var error = assertThrows(IllegalArgumentException.class, () -> S3kRomImporter.assemble("Bad extent",
                bank, 8, new byte[0], Map.of(), Map.of(), Map.of(), dac));
        assertTrue(error.getMessage().contains("voice table"), error::getMessage);
    }

    @Test void rejectsUnrecognizedCartridgesWithoutCreatingOutput() throws Exception {
        Path cartridge = Files.write(directory.resolve("wrong.bin"), new byte[4 * 1024 * 1024]);
        Path output = directory.resolve("song.smps.json");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(stdout); var err = new PrintStream(stderr)) {
            assertEquals(2, Main.run(new String[]{"list-rom-songs", cartridge.toString()}, out, err));
            assertEquals(2, Main.run(new String[]{"import-rom-song", cartridge.toString(), "0x01", output.toString()}, out, err));
        }
        assertTrue(stderr.toString().contains("locked-on"), stderr::toString);
        assertFalse(Files.exists(output));
        assertArrayEquals(new byte[4 * 1024 * 1024], Files.readAllBytes(cartridge));
    }

    private static byte[] bank() {
        byte[] bank = new byte[160];
        put(bank, 0, 0xEF, 3, 0x91, 2, 0xF9);
        put(bank, 8, 60, 0x80, 2, 0, 1, 0, 32, 0x80, 0, 0, 36, 0x80, 0, 0);
        put(bank, 32, 0x89, 2, 0xF2, 0, 0xF8, 0, 0x80, 0xF2);
        return bank;
    }

    private static void put(byte[] destination, int at, int... values) {
        System.arraycopy(bytes(values), 0, destination, at, values.length);
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) values[index];
        return bytes;
    }
}

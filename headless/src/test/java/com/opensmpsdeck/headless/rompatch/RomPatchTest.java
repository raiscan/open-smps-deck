package com.opensmpsdeck.headless.rompatch;

import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeProgram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;

import static com.opensmpsdeck.headless.rompatch.SongLayout.*;
import static com.opensmpsdeck.headless.rompatch.S3kRevision.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RomPatchTest {
    @Test void retailAuditReproducesFragmentsAndUnusedDacNotes() throws Exception {
        byte[] rom = retailRom();
        var retail = new RetailLayoutAudit(rom);
        assertEquals(fragments(), retail.free);
        assertEquals(FREE_DAC_NOTES, IntStream.rangeClosed(DAC_FIRST, DAC_LAST)
                .filter(note -> !retail.usedNotes.contains(note)).boxed().toList());
        assertTrue(retail.alternateVoiceSlots.isEmpty());
        for (int note : FREE_DAC_NOTES)
            assertTrue(retail.free.stream().anyMatch(span -> span.contains(dacPointer(note))
                    && span.contains(dacPointer(note) + 1)));
    }

    @Test void realImageDiffMatchesReportAndStubResolvesSong(@TempDir Path scratch) throws Exception {
        byte[] rom = retailRom();
        var compiled = new CompiledProject(hex("0000010001000a0000008004f2"), List.of());
        var patched = RomPatch.patch(rom, compiled, new RomPatch.Options(0x24, true, true, true));
        Path output = Files.write(scratch.resolve("private-patched.gen"), patched.bytes());
        byte[] bytes = Files.readAllBytes(output);
        assertEquals(rom.length, bytes.length);
        assertEquals(SHA1, RomPatch.hash("SHA-1", rom));
        assertEquals(RomPatch.hash("SHA-256", bytes), patched.report().get("outputSha256").getAsString());
        var allowed = new BitSet();
        for (var element : patched.report().getAsJsonArray("romRangesWritten")) {
            var range = element.getAsJsonObject();
            int start = range.get("start").getAsInt(), end = range.get("end").getAsInt();
            String purpose = range.get("purpose").getAsString();
            boolean expected = switch (purpose) {
                case "driver hook" -> start == 0x1326 && end == 0x132E;
                case "cartridge header checksum" -> (start == 0x18E || start == 0x20018E) && end - start == 2;
                case "title request" -> TITLE_REQUESTS.stream().anyMatch(site -> start == site.offset() + 1 && end == start + 1);
                case "demo timeout branch" -> DEMO_TIMEOUTS.stream().anyMatch(site -> start == site.offset() + 4 && end == start + 4);
                case "song", "Z80 table setup stub" ->
                        fragments().stream().anyMatch(span -> span.contains(start) && span.contains(end - 1));
                default -> false;
            };
            assertTrue(expected, "Unexpected write at " + start + "–" + end);
            allowed.set(start, end);
        }
        for (int index = 0; index < rom.length; index++)
            if (rom[index] != bytes[index]) assertTrue(allowed.get(index), "Unexpected ROM difference at " + index);

        ByteBuffer code = ByteBuffer.wrap(bytes);
        assertEquals(0x4EB9, code.getShort(0x1326) & 0xFFFF);
        int stub = code.getInt(0x1328);
        assertEquals(0, stub % 2);
        assertEquals(0x4E71, code.getShort(0x132C) & 0xFFFF);
        assertArrayEquals(hex("0839000000a1110066f6"), Arrays.copyOfRange(bytes, stub, stub + 10));
        byte[] driver = driverRam(rom);
        int at = stub + 10;
        while (code.getShort(at) == (short) 0x13FC) {
            int z80 = code.getInt(at + 4) - 0xA00000;
            assertTrue(z80 >= 0 && z80 < driver.length);
            driver[z80] = bytes[at + 3];
            at += 8;
        }
        assertArrayEquals(hex("33fc000000a112004e75"), Arrays.copyOfRange(bytes, at, at + 10));
        int bank = u8(driver, MUSIC_BANKS + 0x23), pointer = word(driver, MUSIC_POINTERS + 2 * 0x23);
        assertEquals(patched.report().get("bank").getAsInt(), bank);
        assertEquals(patched.report().get("headerPointer").getAsInt(), pointer);
        int header = bank * WINDOW + pointer - WINDOW;
        int track = bank * WINDOW + word(bytes, header + 6) - WINDOW;
        assertArrayEquals(hex("8004f6"), Arrays.copyOfRange(bytes, track, track + 3));
        assertEquals(word(bytes, header + 6), word(bytes, track + 3));
        for (int base : new int[]{0, 0x200000}) {
            int sum = 0;
            for (int offset = base + 0x200; offset < base + 0x200000; offset += 2)
                sum += code.getShort(offset) & 0xFFFF;
            assertEquals(sum & 0xFFFF, code.getShort(base + 0x18E) & 0xFFFF);
        }
    }

    @Test void kosinskiDecodesLiteralAndOverlappingShortCopy() throws Exception {
        assertArrayEquals(new byte[]{7, 7, 7}, decompress(hex("410007ff000000"), 0));
    }

    @Test void freeMapSubtractsProtectedBytesAndSplitsAtBankWindow() {
        var candidates = new BitSet();
        candidates.set(0x7FFC, 0x8004);
        var live = new BitSet();
        live.set(0x7FFD, 0x7FFE);
        assertEquals(List.of(new Span(0x7FFC, 0x7FFD), new Span(0x7FFE, 0x8000), new Span(0x8000, 0x8004)),
                RetailLayoutAudit.fragments(candidates, live));
    }

    @Test void relocatesCallLoopAndJumpOperandsAcrossFragments() {
        byte[] song = hex("0000010001000a000000f81800f700021800f615000000f20080f9");
        var layout = SongLayout.music(song, 0, 0, 0, true);
        var placement = new Placement(List.of(new Span(0x10300, 0x1030B), new Span(0x10400, 0x10410)));
        var location = placement.song(layout.pieces(), song.length);
        var relocated = location.relocate(song, 0, layout.pointers());
        assertEquals(0x8300, word(relocated, 6));
        assertEquals(0x840D, word(relocated, 11));
        assertEquals(word(relocated, 11), word(relocated, 16));
        assertEquals(0x840A, word(relocated, 19));
        assertEquals(4, layout.pointers().size());
    }

    @Test void rejectsOversizeWithoutCrossingBanks() {
        var placement = new Placement(List.of(new Span(0, 20), new Span(WINDOW, WINDOW + 20)));
        String message = assertThrows(IllegalArgumentException.class,
                () -> placement.song(List.of(new Span(0, 21)), 21)).getMessage();
        assertTrue(message.contains("21 bytes"));
        assertTrue(message.contains("20 bytes"));
        assertTrue(message.contains("32 KB"));
        assertTrue(message.contains("reduce layering"));
    }

    @Test void refusesChangedPatchPatternAndUnrecognizedImage() {
        assertThrows(IllegalArgumentException.class, () -> PatchCode.expect(new byte[32], new Site(12, "4e75")));
        var compiled = new CompiledProject(hex("0000010001000a000000f2"), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> RomPatch.patch(new byte[ROM_SIZE], compiled, new RomPatch.Options(0x25, false, false, false)));
    }

    @Test void stubWaitsForBusBeforeByteWritesAndReset() {
        assertArrayEquals(hex("0839000000a1110066f6"
                        + "13fc005a00a00b89" + "33fc000000a112004e75"),
                PatchCode.stub(java.util.Map.of(0xB89, (byte) 0x5A)));
    }

    @Test void dpcmPreservesRepresentableSamplesAndBeatsGreedy() {
        byte[] deltas = new byte[16];
        deltas[1] = 4;
        deltas[2] = -3;
        byte[] pcm = {(byte)132, (byte)129, (byte)126, (byte)130};
        assertArrayEquals(pcm, Dpcm.decode(Dpcm.encode(pcm, deltas), deltas));
        byte[] difficult = {(byte)130, (byte)136};
        byte[] greedy = Dpcm.decode(hex("01"), deltas);
        byte[] optimal = Dpcm.decode(Dpcm.encode(difficult, deltas), deltas);
        assertArrayEquals(hex("8488"), optimal);
        assertTrue(Dpcm.compare(difficult, optimal).rmsError() < Dpcm.compare(difficult, greedy).rmsError());
    }

    @Test void remapsDacAndBothPsgOperandKinds() throws Exception {
        var envelope = new Envelope("psg", "Test envelope", Envelope.PSG_VOLUME, List.of(0, 1, 0x83));
        var sample = DacSample.of("dac", "Test hit", 5, hex("8080"));
        // DAC header + PSG header, DAC note, PSG F5 and FF06.
        byte[] song = hex("000001010100100000001300000000018101f2f501ff06010ff2");
        var input = SongInput.of(new CompiledProject(song, List.of(envelope), List.of(sample)), song, List.of());
        var layout = SongLayout.music(song, 0, 0, 0, true);
        byte[] driver = new byte[0x2000];
        word(driver, PSG_MASTER, PSG_TABLE);
        for (int i = 0; i < PSG_COUNT; i++) word(driver, PSG_TABLE + 2 * i, 0x13D5 + 2 * i);
        var ram = new LinkedHashMap<Integer, Byte>();
        var edits = new RomEdits(new byte[ROM_SIZE]);
        var dac = PatchAssets.dac(input, layout, song, driver, new Placement(fragments()), edits, ram);
        assertEquals(0x89, u8(song, 16));
        assertEquals(5, u8(edits.bytes(), dac.getFirst().descriptorOffset()));
        assertEquals((byte) 0x5B, ram.get(DAC_BANKS + 9));
        var envelopes = PatchAssets.envelopes(input, layout, song, driver, ram);
        assertEquals(40, envelopes.getFirst().mappedIndex());
        assertEquals(40, u8(song, 15));
        assertEquals(40, u8(song, 20));
        assertEquals(40, u8(song, 23));
        assertEquals((byte) 0x43, ram.get(PSG_MASTER));
    }

    @Test void loopRewriteMatchesLinearRepetitionThroughPinnedRenderer() throws Exception {
        byte[] original = hex("0000010001000a00000081048004f2");
        var layout = SongLayout.music(original, 0, 0, 0, true);
        var loop = SongLoop.rewrite(original, 0, 0, layout, layout.pointers());
        assertArrayEquals(hex("0000010001000a00000081048004f60a00"), loop.bytes());
        var sample = DacSample.of("pulse", "Pulse", 20, hex("a0c0a08060406080"));
        byte[] repeat = hex("0000010001000a000000810480048104800481048004f2");
        byte[] rendered = render(repeat, sample);
        assertFalse(Arrays.equals(rendered, new byte[rendered.length]));
        assertArrayEquals(rendered, render(loop.bytes(), sample));
    }

    @Test void loopRefusesDifferentChannelDurations() {
        byte[] song = hex("0000020001000e000000110000008004f28005f2");
        var layout = SongLayout.music(song, 0, 0, 0, true);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SongLoop.rewrite(song, 0, 0, layout, layout.pointers()))
                .getMessage().contains("channel durations do not align"));
    }

    private static byte[] render(byte[] song, DacSample sample) throws Exception {
        var out = new ByteArrayOutputStream();
        var program = NativeProgram.of(0, 0, 0, 23, song, List.of(),
                List.of(new NativeProgram.DacSlot(0x81, sample.id(), sample.rate())));
        S3kRenderer.render(new CompiledProject(song, List.of(), List.of(sample), program, List.of()), 23, frame -> {
            for (short value : frame) {
                out.write(value);
                out.write(value >>> 8);
            }
        });
        return out.toByteArray();
    }

    private static byte[] retailRom() throws Exception {
        String path = System.getenv("OPENSMPSDECK_S3K_ROM");
        assumeTrue(path != null && !path.isBlank());
        byte[] rom = Files.readAllBytes(Path.of(path));
        assertEquals(SHA1, RomPatch.hash("SHA-1", rom));
        return rom;
    }

    private static byte[] hex(String text) { return HexFormat.of().parseHex(text); }
}

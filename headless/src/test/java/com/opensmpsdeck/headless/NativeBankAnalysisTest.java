package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.NativeProgram;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBankAnalysisTest {
    @Test
    void resolvesSharedPhraseBeforeHeaderAndYieldsThroughMusicLoop() {
        byte[] bank = new byte[40];
        put(bank, 2, 0x80, 3, 0xF9);
        put(bank, 8, 0, 0, 1, 0, 1, 0x20, 0x18, 0x80, 0, 0);
        put(bank, 24, 0xF8, 0x02, 0x80, 0xF6, 0x18, 0x80);

        var analysis = inspect(bank, 0x8000, 8, 0);

        assertEquals(0x8008, analysis.headerAddress());
        assertEquals(10, analysis.header().length);
        assertEquals(List.of(0x8002, 0x8003, 0x8004, 0x8018, 0x801B),
                analysis.events().stream().map(NativeBankAnalysis.Event::address).toList());
        assertArrayEquals(bank, analysis.bank());
        analysis.bank()[0] = 42;
        assertArrayEquals(bank, analysis.bank());
    }

    @Test
    void acceptsACommandOnlyCountedLoopThatTerminates() {
        var analysis = inspect(dac(0xF7, 2, 255, 10, 0, 0xF2), 0, 0, 0);
        assertEquals(List.of("Counted loop", "Stop track"),
                analysis.events().stream().map(NativeBankAnalysis.Event::meaning).toList());
    }

    @Test
    void durationOperandMayAlsoBeATargetedToken() {
        byte[] bank = new byte[22];
        put(bank, 2, 1, 1, 1, 0, 16, 0, 0, 0, 17, 0, 0, 0, 0, 0);
        put(bank, 16, 0xF2, 0x80, 3, 0xF6, 18, 0);
        var analysis = inspect(bank, 0, 0, 0);
        assertEquals(List.of(16, 17, 18, 19),
                analysis.events().stream().map(NativeBankAnalysis.Event::address).toList());
    }

    @Test
    void rejectsImmediateCyclesAndStackErrors() {
        fails("without advancing time", dac(0xF6, 10, 0));
        fails("zero loop count", dac(0xF7, 0, 0, 10, 0, 0xF2));
        fails("stack would overflow", dac(0xF8, 10, 0, 0xF2));
        fails("return without a call", dac(0xF9));
    }

    @Test
    void acceptsStandaloneSfxAdmissionStateAndBlocksPreviousSongRestore() {
        assertEquals("Sound-effect admission control",
                inspect(dac(0xE2, 1, 0xF2), 0, 0, 0).events().getFirst().meaning());
        fails("E2 FF restores a previous song", dac(0xE2, 0xFF, 0xF2));
    }

    @Test
    void rejectsBadTargetsBeforePlayback() {
        fails("outside the bank", dac(0xF6, 0xFF, 0));
        fails("enters a header", dac(0xF6, 8, 0));
        fails("enters an instruction operand", dac(0xF6, 11, 0));
        fails("enters an instruction operand", dac(0xF7, 0, 1, 12, 0, 0xF2));
        fails("truncated", dac(0xF8, 10));
    }

    @Test
    void rejectsZeroAddressEvenWhenTheByteAtBankStartIsExecutable() {
        byte[] bank = new byte[25];
        put(bank, 0, 0x80, 1, 0xF2);
        put(bank, 8, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0);
        var rootError = assertThrows(IllegalArgumentException.class, () -> inspect(bank, 0, 8, 0));
        assertTrue(rootError.getMessage().contains("zero track or control-flow pointer"));
        bank[14] = 20;
        put(bank, 20, 0xF8, 0, 0, 0xF2);
        var callError = assertThrows(IllegalArgumentException.class, () -> inspect(bank, 0, 8, 0));
        assertTrue(callError.getMessage().contains("zero track or control-flow pointer"));
    }

    @Test
    void retainsNativePanHistoriesAndGatesUnimplementedSilence() {
        fails("silence and stop requires native E3 register writes", dac(0xE3));
        byte[] bank = new byte[25];
        put(bank, 0, 0, 0, 2, 0, 1, 0, 14, 0, 0, 0, 15, 0, 0, 0);
        put(bank, 14, 0xF2, 0xE0, 0x83, 0xE0, 0x43, 0xF2);
        assertEquals(4, inspect(bank, 0, 0, 0).events().size());
        bank[18] = 0x40;
        assertEquals(4, inspect(bank, 0, 0, 0).events().size());
        bank[16] = (byte) 0x88;
        assertEquals(4, inspect(bank, 0, 0, 0).events().size());
    }

    @Test
    void rejectsMissingDurationsAndResourcesAndAcceptsNativeWrap() {
        fails("previously stored duration", dac(0x80, 0xF2));
        fails("previous note", dac(1, 0xF2));
        fails("undefined DAC sample", dac(0x81, 1, 0xF2));
        assertEquals(4, inspect(dac(0x80, 1, 2, 0xF2), 0, 0, 0).events().size());
        byte[] bank = dac(0x80, 0x40, 0xF2);
        bank[4] = 4;
        assertEquals("Duration 64 → 256", inspect(bank, 0, 0, 0).events().get(1).meaning());
        bank[4] = 3;
        assertEquals(3, inspect(bank, 0, 0, 0).events().size());
    }

    @Test
    void resolvesLocalVoiceTableAndRejectsUnboundEnvelopesAndCrossSongVoices() {
        byte[] fm = new byte[50];
        put(fm, 0, 25, 0, 2, 0, 1, 0, 14, 0, 0, 0, 15, 0, 0, 0);
        put(fm, 14, 0xF2, 0xEF, 0, 0x81, 3, 0xF2);
        assertEquals(25, inspect(fm, 0, 0, 1).voiceOffset());
        fm[16] = (byte) 0x80;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> inspect(fm, 0, 0, 1))
                .getMessage().contains("cross-song FM voice"));
        put(fm, 15, 0xE7, 0xEF, 0, 0x81, 3, 0xF2);
        assertEquals(6, inspect(fm, 0, 0, 1).events().size());
        put(fm, 15, 0xE5, 0, 1, 0xEF, 0, 0x81, 3, 0xF2);
        assertEquals(6, inspect(fm, 0, 0, 1).events().size());

        byte[] psg = new byte[20];
        put(psg, 0, 0, 0, 1, 1, 1, 0, 16, 0, 0, 0, 17, 0, 0, 0, 3, 0);
        put(psg, 16, 0xF2, 0x80, 1, 0xF2);
        fails("undefined modulation envelope 3", psg);
    }

    @Test
    void acceptsReachableHoldAndNonzeroFillOnTheSameTrack() {
        byte[] bank = new byte[25];
        put(bank, 2, 1, 1, 1, 0, 16, 0, 0, 0, 17, 0, 0, 0, 0, 0);
        put(bank, 16, 0xF2, 0xE8, 1, 0x81, 3, 0xE7, 3, 0xF2);
        assertEquals(7, inspect(bank, 0, 0, 0).events().size());
        bank[18] = 0;
        assertEquals(7, inspect(bank, 0, 0, 0).events().size());
    }

    @Test
    void preservesSeventhFmHeaderEntryWithoutExecutingIt() {
        byte[] bank = new byte[45];
        put(bank, 2, 7, 1, 1, 0);
        for (int index = 0; index < 6; index++) put(bank, 6 + index * 4, 40);
        put(bank, 30, 41);
        put(bank, 34, 40);
        put(bank, 40, 0xF2, 0xFF, 0x03);
        var analysis = inspect(bank, 0, 0, 0);
        assertEquals(40, analysis.header().length);
        assertEquals(41, analysis.header()[30]);
        assertEquals(List.of(40),
                analysis.events().stream().map(NativeBankAnalysis.Event::address).toList());
        bank[3] = 0;
        fails("seventh FM header entry requires a PSG", bank);
    }

    private static byte[] dac(int... instructions) {
        byte[] bank = new byte[10 + instructions.length];
        put(bank, 2, 1, 0, 1, 0, 10, 0, 0, 0);
        put(bank, 10, instructions);
        return bank;
    }

    private static void put(byte[] bank, int offset, int... values) {
        for (int index = 0; index < values.length; index++) bank[offset + index] = (byte) values[index];
    }

    private static NativeBankAnalysis.Analysis inspect(byte[] bank, int base, int header, int voiceCount) {
        NativeProgram program = NativeProgram.of(base, header, voiceCount, 60, bank, List.of(), List.of());
        return NativeBankAnalysis.inspect(new CompiledProject(bank, List.of(), List.of(), program, List.of()));
    }

    private static void fails(String message, byte[] bank) {
        var error = assertThrows(IllegalArgumentException.class, () -> inspect(bank, 0, 0, 0));
        assertTrue(error.getMessage().contains(message), error::getMessage);
    }
}

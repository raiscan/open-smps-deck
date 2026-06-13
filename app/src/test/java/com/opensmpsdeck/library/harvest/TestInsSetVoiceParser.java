package com.opensmpsdeck.library.harvest;

import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.library.rip.SmpsDriverDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInsSetVoiceParser {

    @TempDir
    Path tempDir;

    @Test
    void insSetParserNormalizesDefaultOrderingAndSkipsUnsupported() throws Exception {
        byte[] nativeOrder = new byte[FmVoice.VOICE_SIZE];
        for (int i = 0; i < nativeOrder.length; i++) {
            nativeOrder[i] = (byte) i;
        }
        Path insSet = tempDir.resolve("InsSet.17D8.bin");
        Files.write(insSet, nativeOrder);

        SmpsDriverDefinition defaultDefinition =
                new SmpsDriverDefinition("Z80", "Overflow", "Default", "Bit7", false);
        SmpsDriverDefinition customDefinition =
                new SmpsDriverDefinition("Z80", "Overflow", "Custom", "Bit7", false);
        SmpsDriverDefinition interleavedDefinition =
                new SmpsDriverDefinition("Z80", "Overflow", "Interleaved", "Bit7", false);
        SmpsDriverDefinition hardwareAlgoDefinition =
                new SmpsDriverDefinition("Z80", "Overflow", "Hardware", "Algo", false);
        SmpsDriverDefinition hardwareBit7Definition =
                new SmpsDriverDefinition("Z80", "Overflow", "Hardware", "Bit7", false);

        List<FmVoice> s3kVoices = InsSetVoiceParser.parse(insSet, ".s3k", defaultDefinition);
        List<FmVoice> sm2Voices = InsSetVoiceParser.parse(insSet, ".sm2", defaultDefinition);
        List<FmVoice> skippedCustom = InsSetVoiceParser.parse(insSet, ".sm2", customDefinition);
        List<FmVoice> skippedInterleaved = InsSetVoiceParser.parse(insSet, ".sm2", interleavedDefinition);
        List<FmVoice> skippedUnknownExtension = InsSetVoiceParser.parse(insSet, ".abc", defaultDefinition);
        List<FmVoice> skippedHardwareAlgo = InsSetVoiceParser.parse(insSet, ".s3k", hardwareAlgoDefinition);
        List<FmVoice> skippedHardwareBit7 = InsSetVoiceParser.parse(insSet, ".sm2", hardwareBit7Definition);

        assertEquals(1, s3kVoices.size());
        assertArrayEquals(FmVoice.swapMiddleOperators(nativeOrder), s3kVoices.getFirst().getData());
        assertEquals(1, sm2Voices.size());
        assertArrayEquals(nativeOrder, sm2Voices.getFirst().getData());
        assertTrue(skippedCustom.isEmpty());
        assertTrue(skippedInterleaved.isEmpty());
        assertTrue(skippedUnknownExtension.isEmpty());
        assertTrue(skippedHardwareAlgo.isEmpty());
        assertTrue(skippedHardwareBit7.isEmpty());
    }
}

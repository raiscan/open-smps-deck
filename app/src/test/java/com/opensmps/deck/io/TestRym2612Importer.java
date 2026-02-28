package com.opensmps.deck.io;

import com.opensmps.deck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestRym2612Importer {

    private static final String TEST_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <RYM2612Params patchName="Test Patch" category="Lead" rating="3" type="User">
              <PARAM id="Algorithm" value="4.0"/>
              <PARAM id="Feedback" value="7.0"/>
              <PARAM id="OP1MUL" value="66.59999847412109"/>
              <PARAM id="OP1DT" value="0.0"/>
              <PARAM id="OP1TL" value="40.0"/>
              <PARAM id="OP1RS" value="0.0"/>
              <PARAM id="OP1AR" value="31.0"/>
              <PARAM id="OP1AM" value="0.0"/>
              <PARAM id="OP1D1R" value="10.0"/>
              <PARAM id="OP1D2R" value="5.0"/>
              <PARAM id="OP1D2L" value="3.0"/>
              <PARAM id="OP1RR" value="7.0"/>
              <PARAM id="OP1SSGEG" value="0.0"/>
              <PARAM id="OP2MUL" value="133.19999694824219"/>
              <PARAM id="OP2DT" value="1.0"/>
              <PARAM id="OP2TL" value="50.0"/>
              <PARAM id="OP2RS" value="1.0"/>
              <PARAM id="OP2AR" value="28.0"/>
              <PARAM id="OP2AM" value="0.0"/>
              <PARAM id="OP2D1R" value="8.0"/>
              <PARAM id="OP2D2R" value="3.0"/>
              <PARAM id="OP2D2L" value="5.0"/>
              <PARAM id="OP2RR" value="6.0"/>
              <PARAM id="OP2SSGEG" value="0.0"/>
              <PARAM id="OP3MUL" value="199.80000305175781"/>
              <PARAM id="OP3DT" value="-1.0"/>
              <PARAM id="OP3TL" value="60.0"/>
              <PARAM id="OP3RS" value="0.0"/>
              <PARAM id="OP3AR" value="25.0"/>
              <PARAM id="OP3AM" value="1.0"/>
              <PARAM id="OP3D1R" value="12.0"/>
              <PARAM id="OP3D2R" value="4.0"/>
              <PARAM id="OP3D2L" value="7.0"/>
              <PARAM id="OP3RR" value="8.0"/>
              <PARAM id="OP3SSGEG" value="0.0"/>
              <PARAM id="OP4MUL" value="266.39999389648438"/>
              <PARAM id="OP4DT" value="2.0"/>
              <PARAM id="OP4TL" value="0.0"/>
              <PARAM id="OP4RS" value="2.0"/>
              <PARAM id="OP4AR" value="31.0"/>
              <PARAM id="OP4AM" value="0.0"/>
              <PARAM id="OP4D1R" value="15.0"/>
              <PARAM id="OP4D2R" value="6.0"/>
              <PARAM id="OP4D2L" value="9.0"/>
              <PARAM id="OP4RR" value="10.0"/>
              <PARAM id="OP4SSGEG" value="0.0"/>
            </RYM2612Params>
            """;

    private File writeTestFile(Path tempDir) throws Exception {
        File file = tempDir.resolve("test.rym2612").toFile();
        Files.writeString(file.toPath(), TEST_XML);
        return file;
    }

    @Test
    void importParsesAlgorithmAndFeedback(@TempDir Path tempDir) throws Exception {
        FmVoice voice = Rym2612Importer.importFile(writeTestFile(tempDir));
        assertEquals(4, voice.getAlgorithm());
        assertEquals(7, voice.getFeedback());
        assertEquals("Test Patch", voice.getName());
    }

    @Test
    void importParsesOperatorParams(@TempDir Path tempDir) throws Exception {
        FmVoice voice = Rym2612Importer.importFile(writeTestFile(tempDir));
        // OP1 in display order = displayIndex 0, SMPS index = displayToSmps(0) = 0
        int smpsOp = FmVoice.displayToSmps(0);
        assertEquals(1, voice.getMul(smpsOp));
        assertEquals(0, voice.getDt(smpsOp));
        assertEquals(40, voice.getTl(smpsOp));
        assertEquals(31, voice.getAr(smpsOp));
        assertEquals(10, voice.getD1r(smpsOp));
        assertEquals(5, voice.getD2r(smpsOp));
        assertEquals(3, voice.getD1l(smpsOp));
        assertEquals(7, voice.getRr(smpsOp));
    }

    @Test
    void importHandlesNegativeDetune(@TempDir Path tempDir) throws Exception {
        FmVoice voice = Rym2612Importer.importFile(writeTestFile(tempDir));
        // OP3 DT=-1 -> register value 5 (bit 2 set: 4 + abs(-1) = 5)
        int smpsOp3 = FmVoice.displayToSmps(2);
        assertEquals(5, voice.getDt(smpsOp3));
        // OP4 DT=2 -> register value 2
        int smpsOp4 = FmVoice.displayToSmps(3);
        assertEquals(2, voice.getDt(smpsOp4));
    }

    @Test
    void importVoiceDataIs25Bytes(@TempDir Path tempDir) throws Exception {
        FmVoice voice = Rym2612Importer.importFile(writeTestFile(tempDir));
        assertEquals(25, voice.getData().length);
    }

    @Test
    void importRejectsDoctypeExternalEntities(@TempDir Path tempDir) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE data [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <RYM2612Params patchName="&xxe;">
                  <PARAM id="Algorithm" value="1.0"/>
                </RYM2612Params>
                """;
        File file = tempDir.resolve("xxe.rym2612").toFile();
        Files.writeString(file.toPath(), xml);
        assertThrows(java.io.IOException.class, () -> Rym2612Importer.importFile(file));
    }

    @Test
    void testImportCorruptXmlThrows(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("corrupt.rym2612").toFile();
        Files.writeString(file.toPath(), "not valid xml <<<");
        assertThrows(java.io.IOException.class, () -> Rym2612Importer.importFile(file));
    }

    @Test
    void testImportWrongRootElementThrows(@TempDir Path tempDir) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <NotRym2612/>
                """;
        File file = tempDir.resolve("wrong_root.rym2612").toFile();
        Files.writeString(file.toPath(), xml);
        assertThrows(java.io.IOException.class, () -> Rym2612Importer.importFile(file));
    }

    @Test
    void testImportMissingParamFieldsUseDefaults(@TempDir Path tempDir) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <RYM2612Params patchName="Minimal">
                  <PARAM id="Algorithm" value="2.0"/>
                  <PARAM id="Feedback" value="3.0"/>
                </RYM2612Params>
                """;
        File file = tempDir.resolve("minimal.rym2612").toFile();
        Files.writeString(file.toPath(), xml);

        FmVoice voice = Rym2612Importer.importFile(file);
        assertEquals(25, voice.getData().length);
        assertEquals(2, voice.getAlgorithm());
        assertEquals(3, voice.getFeedback());

        // All operator fields should default to 0 since no OP params were provided
        for (int displayOp = 0; displayOp < FmVoice.OPERATOR_COUNT; displayOp++) {
            int smpsOp = FmVoice.displayToSmps(displayOp);
            assertEquals(0, voice.getMul(smpsOp), "OP" + (displayOp + 1) + " MUL");
            assertEquals(0, voice.getDt(smpsOp), "OP" + (displayOp + 1) + " DT");
            assertEquals(0, voice.getTl(smpsOp), "OP" + (displayOp + 1) + " TL");
            assertEquals(0, voice.getAr(smpsOp), "OP" + (displayOp + 1) + " AR");
            assertEquals(0, voice.getD1r(smpsOp), "OP" + (displayOp + 1) + " D1R");
            assertEquals(0, voice.getD2r(smpsOp), "OP" + (displayOp + 1) + " D2R");
            assertEquals(0, voice.getD1l(smpsOp), "OP" + (displayOp + 1) + " D1L");
            assertEquals(0, voice.getRr(smpsOp), "OP" + (displayOp + 1) + " RR");
        }
    }

    @Test
    void testImportExtremeFloatValues(@TempDir Path tempDir) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <RYM2612Params patchName="Extreme">
                  <PARAM id="Algorithm" value="99.9"/>
                  <PARAM id="Feedback" value="99.9"/>
                  <PARAM id="OP1MUL" value="9999.0"/>
                  <PARAM id="OP1DT" value="99.0"/>
                  <PARAM id="OP1TL" value="999.0"/>
                  <PARAM id="OP1RS" value="99.0"/>
                  <PARAM id="OP1AR" value="99.0"/>
                  <PARAM id="OP1AM" value="99.0"/>
                  <PARAM id="OP1D1R" value="99.0"/>
                  <PARAM id="OP1D2R" value="99.0"/>
                  <PARAM id="OP1D2L" value="99.0"/>
                  <PARAM id="OP1RR" value="99.0"/>
                </RYM2612Params>
                """;
        File file = tempDir.resolve("extreme.rym2612").toFile();
        Files.writeString(file.toPath(), xml);

        FmVoice voice = Rym2612Importer.importFile(file);
        // Algorithm and Feedback should be clamped to 0-7
        assertTrue(voice.getAlgorithm() >= 0 && voice.getAlgorithm() <= 7,
                "Algorithm should be clamped to 0-7, was: " + voice.getAlgorithm());
        assertTrue(voice.getFeedback() >= 0 && voice.getFeedback() <= 7,
                "Feedback should be clamped to 0-7, was: " + voice.getFeedback());

        // OP1 fields should be clamped to their valid ranges
        int smpsOp = FmVoice.displayToSmps(0);
        assertTrue(voice.getMul(smpsOp) >= 0 && voice.getMul(smpsOp) <= 15,
                "MUL should be clamped to 0-15, was: " + voice.getMul(smpsOp));
        assertTrue(voice.getTl(smpsOp) >= 0 && voice.getTl(smpsOp) <= 127,
                "TL should be clamped to 0-127, was: " + voice.getTl(smpsOp));
        assertTrue(voice.getAr(smpsOp) >= 0 && voice.getAr(smpsOp) <= 31,
                "AR should be clamped to 0-31, was: " + voice.getAr(smpsOp));
        assertTrue(voice.getD1r(smpsOp) >= 0 && voice.getD1r(smpsOp) <= 31,
                "D1R should be clamped to 0-31, was: " + voice.getD1r(smpsOp));
        assertTrue(voice.getD2r(smpsOp) >= 0 && voice.getD2r(smpsOp) <= 31,
                "D2R should be clamped to 0-31, was: " + voice.getD2r(smpsOp));
        assertTrue(voice.getD1l(smpsOp) >= 0 && voice.getD1l(smpsOp) <= 15,
                "D1L should be clamped to 0-15, was: " + voice.getD1l(smpsOp));
        assertTrue(voice.getRr(smpsOp) >= 0 && voice.getRr(smpsOp) <= 15,
                "RR should be clamped to 0-15, was: " + voice.getRr(smpsOp));
    }

    @Test
    void testImportEmptyPatchName(@TempDir Path tempDir) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <RYM2612Params patchName="">
                  <PARAM id="Algorithm" value="0.0"/>
                  <PARAM id="Feedback" value="0.0"/>
                </RYM2612Params>
                """;
        File file = tempDir.resolve("empty_name.rym2612").toFile();
        Files.writeString(file.toPath(), xml);

        FmVoice voice = Rym2612Importer.importFile(file);
        assertNotNull(voice);
        assertNotNull(voice.getName());
        assertEquals("", voice.getName());
        assertEquals(25, voice.getData().length);
    }
}

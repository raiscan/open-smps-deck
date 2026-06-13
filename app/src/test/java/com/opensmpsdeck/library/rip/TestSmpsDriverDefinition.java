package com.opensmpsdeck.library.rip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsDriverDefinition {

    @TempDir
    Path tempDir;

    @Test
    void driverDefinitionParsesKeyProperties() throws Exception {
        Path definition = tempDir.resolve("Driver.ini");
        Files.writeString(definition, """
                PtrFmt=Z80
                TempoMode=S2
                InsMode=Global
                InsRegs=25
                PreSMPS=True
                """);

        SmpsDriverDefinition parsed = SmpsDriverDefinition.parse(definition);

        assertEquals("Z80", parsed.ptrFmt());
        assertEquals("S2", parsed.tempoMode());
        assertEquals("Global", parsed.insMode());
        assertEquals("25", parsed.insRegs());
        assertTrue(parsed.hasPreSmpsTrackHeader());
        assertEquals("PtrFmt=Z80 TempoMode=S2 InsMode=Global InsRegs=25 PreSMPS=true", parsed.summary());
    }

    @Test
    void driverDefinitionDetectsPreSmpsTrackHeaderKey() throws Exception {
        Path definition = tempDir.resolve("DefDrv.txt");
        Files.writeString(definition, """
                PtrFmt=Z80
                preSMPSTrkHdr=$80
                """);

        SmpsDriverDefinition parsed = SmpsDriverDefinition.parse(definition);

        assertTrue(parsed.hasPreSmpsTrackHeader());
    }
}

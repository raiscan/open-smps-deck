package com.opensmpsdeck.library.rip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSmpsRipConfigParser {

    @TempDir
    Path tempDir;

    @Test
    void configParserResolvesSectionKeys() throws Exception {
        Path config = tempDir.resolve("SMPS.ini");
        Files.writeString(config, """
                [Sonic 2]
                Ext=.sm2
                Dir=drivers/s2
                Def=Driver.ini
                CFlags=coord/DefCFlag.ini
                """);

        SmpsRipConfig parsed = SmpsRipConfigParser.parse(config);

        SmpsRipConfig.Section section = parsed.sections().get("Sonic 2");
        assertEquals(".sm2", section.extension());
        assertEquals(tempDir.resolve("drivers/s2").normalize(), section.directory());
        assertEquals("Driver.ini", section.value("def"));
        assertEquals(tempDir.resolve("drivers/s2/Driver.ini").normalize(), section.resolve("DEF"));
        assertEquals(tempDir.resolve("drivers/s2/coord/DefCFlag.ini").normalize(), section.resolve("cflags"));
    }
}

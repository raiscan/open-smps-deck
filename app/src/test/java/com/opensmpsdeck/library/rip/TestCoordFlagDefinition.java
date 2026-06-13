package com.opensmpsdeck.library.rip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCoordFlagDefinition {

    @TempDir
    Path tempDir;

    @Test
    void coordFlagDefinitionParsesLengthsAndJumpOffsets() throws Exception {
        Path definition = tempDir.resolve("DefCFlag.ini");
        Files.writeString(definition, """
                [Main]
                E0    cfPanningAMSFMS    Len=2
                F8    cfJumpTo           Len=3/JmpOfs=0

                [Meta]
                01    cfMetaJump         4   1
                02    cfMetaDelay        2
                """);

        CoordFlagDefinition parsed = CoordFlagDefinition.parse(definition);

        assertEquals(new CoordFlagDefinition.CoordFlagCommand(0xE0, "cfPanningAMSFMS", 2, -1),
                parsed.mainCommand(0xE0));
        assertEquals(new CoordFlagDefinition.CoordFlagCommand(0xF8, "cfJumpTo", 3, 0),
                parsed.mainCommand(0xF8));
        assertEquals(new CoordFlagDefinition.CoordFlagCommand(0x01, "cfMetaJump", 4, 1),
                parsed.metaCommand(0x01));
        assertEquals(new CoordFlagDefinition.CoordFlagCommand(0x02, "cfMetaDelay", 2, -1),
                parsed.metaCommand(0x02));
    }
}

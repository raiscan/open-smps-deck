package com.opensmpsdeck.library.harvest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDacIniParser {

    @TempDir
    Path tempDir;

    @Test
    void dacIniParserHandlesMetadataAndDpcmLabels() throws Exception {
        Path ini = tempDir.resolve("DAC_Voice.ini");
        Files.writeString(ini, """
                [81]
                Compr=DPCM
                File=DAC\\Kick.dpcm
                Rate=0x22
                Pan=40
                Param1=01
                Param2=02

                [82]
                Compr=True
                File=Snare.bin
                Rate=34

                [83]
                Compr=PCM
                File=Hat.bin
                Rate=$24
                """);

        List<DacIniParser.Entry> entries = DacIniParser.parse(ini);

        assertEquals(3, entries.size());
        assertEquals(0x81, entries.getFirst().id());
        assertEquals("DPCM", entries.getFirst().compressionLabel());
        assertEquals("DAC\\Kick.dpcm", entries.getFirst().file());
        assertEquals(0x22, entries.getFirst().rate());
        assertEquals("40", entries.getFirst().pan());
        assertEquals("01", entries.getFirst().param1());
        assertEquals("02", entries.getFirst().param2());
        assertTrue(entries.getFirst().isDpcm());

        assertEquals(0x82, entries.get(1).id());
        assertEquals("True", entries.get(1).compressionLabel());
        assertTrue(entries.get(1).isDpcm());

        assertEquals(0x83, entries.get(2).id());
        assertEquals("PCM", entries.get(2).compressionLabel());
        assertEquals(0x24, entries.get(2).rate());
    }
}

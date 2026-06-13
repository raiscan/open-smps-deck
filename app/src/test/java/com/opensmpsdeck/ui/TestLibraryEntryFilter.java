package com.opensmpsdeck.ui;

import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.SourceReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLibraryEntryFilter {

    @Test
    void blankQueryMatchesEveryEntry() {
        InstrumentLibraryEntry entry = InstrumentLibraryEntry.psgEnvelope(
                "Bright PSG",
                new byte[]{1, 2, (byte) 0x80},
                source());

        assertTrue(LibraryEntryFilter.matches(entry, ""));
        assertTrue(LibraryEntryFilter.matches(entry, "   "));
    }

    @Test
    void queryMatchesLibraryMetadataAndSourceFields() {
        InstrumentLibraryEntry entry = InstrumentLibraryEntry.psgEnvelope(
                "Bright PSG",
                new byte[]{1, 2, (byte) 0x80},
                source());

        assertTrue(LibraryEntryFilter.matches(entry, "sonic"));
        assertTrue(LibraryEntryFilter.matches(entry, "proto"));
        assertTrue(LibraryEntryFilter.matches(entry, "psg.lst"));
        assertTrue(LibraryEntryFilter.matches(entry, "z80"));
        assertTrue(LibraryEntryFilter.matches(entry, ".sm2"));
        assertTrue(LibraryEntryFilter.matches(entry, "bright"));
        assertFalse(LibraryEntryFilter.matches(entry, "streets"));
    }

    @Test
    void multipleQueryTermsMustAllMatchSomeField() {
        InstrumentLibraryEntry entry = InstrumentLibraryEntry.dacSample(
                "Kick",
                new byte[]{0x40, 0x41, 0x42},
                0x20,
                "DPCM",
                null,
                null,
                null,
                "81",
                source());

        assertTrue(LibraryEntryFilter.matches(entry, "sonic kick dpcm 32"));
        assertTrue(LibraryEntryFilter.matches(entry, "dac 3"));
        assertFalse(LibraryEntryFilter.matches(entry, "sonic snare"));
    }

    private static SourceReference source() {
        return new SourceReference(
                "C:/rips",
                "Z80",
                "Sonic 2",
                "Proto",
                ".sm2",
                "01 Emerald Hill.sm2",
                "PSG.lst",
                "81",
                "PtrFmt=Z80 InsMode=Default");
    }
}

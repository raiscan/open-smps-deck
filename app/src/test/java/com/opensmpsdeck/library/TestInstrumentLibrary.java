package com.opensmpsdeck.library;

import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInstrumentLibrary {

    @Test
    void duplicateFmVoiceMergesOneSourceReferenceOnce() {
        InstrumentLibrary library = new InstrumentLibrary();
        SourceReference source = new SourceReference(
                "C:/rips", "Z80", "Sonic 2", "", ".sm2",
                "01 Emerald Hill.sm2", "InsSet.17D8.bin", "00",
                "PtrFmt=Z80 InsMode=Default");
        byte[] data = new byte[FmVoice.VOICE_SIZE];
        data[0] = 0x27;

        InstrumentLibraryEntry first = InstrumentLibraryEntry.fmVoice("Voice A", data, source);
        InstrumentLibraryEntry second = InstrumentLibraryEntry.fmVoice("Voice B", data.clone(), source);

        AddResult firstResult = library.addOrMerge(first, Instant.parse("2026-06-13T10:00:00Z"));
        AddResult secondResult = library.addOrMerge(second, Instant.parse("2026-06-13T11:00:00Z"));

        assertTrue(firstResult.added());
        assertFalse(secondResult.added());
        assertFalse(secondResult.changed());
        assertEquals(1, library.entries(InstrumentAssetKind.FM_VOICE).size());
        InstrumentLibraryEntry stored = library.entries(InstrumentAssetKind.FM_VOICE).getFirst();
        assertEquals("Voice A", stored.displayName());
        assertEquals(1, stored.sourceReferences().size());
        assertEquals(Instant.parse("2026-06-13T10:00:00Z"), stored.updatedTimestamp());
    }

    @Test
    void identicalDacBytesWithDifferentRateAreDistinct() {
        InstrumentLibrary library = new InstrumentLibrary();
        SourceReference source = SourceReference.minimal("C:/rips", "DAC.ini", "81");
        byte[] data = new byte[]{0x10, 0x20, 0x30};

        library.addOrMerge(InstrumentLibraryEntry.dacSample(
                "Kick fast", data, 0x20, "PCM", null, null, null, "81", source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.dacSample(
                "Kick slow", data, 0x30, "PCM", null, null, null, "81", source), Instant.EPOCH);

        assertEquals(2, library.entries(InstrumentAssetKind.DAC_SAMPLE).size());
    }
}

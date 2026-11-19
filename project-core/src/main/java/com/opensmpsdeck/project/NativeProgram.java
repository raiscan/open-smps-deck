package com.opensmpsdeck.project;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact S3K bank bytes and the metadata needed to address and preview them. */
public record NativeProgram(int bankBase, int headerOffset, int localVoiceCount, int previewFrames,
                            String bankBase64, List<EnvelopeSlot> envelopeSlots, List<DacSlot> dacSlots) {
    private static final int MAX_BANK_BYTES = 0x8000;
    public record EnvelopeSlot(String assetId, int slot) {
        public EnvelopeSlot {
            requireId(assetId, "envelope assetId");
            range(slot, 1, 255, "envelope slot");
        }
    }

    public record DacSlot(int note, String sampleId, int rate) {
        public DacSlot {
            range(note, DacSample.FIRST_NOTE, 0xDF, "DAC note");
            requireId(sampleId, "DAC sampleId");
            range(rate, 0, 255, "DAC rate");
        }
    }

    public NativeProgram {
        if (bankBase != 0 && bankBase != 0x8000)
            throw new IllegalArgumentException("bankBase must be 0 or 32768");
        range(localVoiceCount, 0, 128, "localVoiceCount");
        range(previewFrames, 1, 36000, "previewFrames");
        if (bankBase64 == null || bankBase64.length() > ((MAX_BANK_BYTES + 2) / 3) * 4)
            throw new IllegalArgumentException("Native bank must contain 10..32768 bytes");
        byte[] bank = Base64.getDecoder().decode(bankBase64);
        if (!Base64.getEncoder().encodeToString(bank).equals(bankBase64))
            throw new IllegalArgumentException("Native bank must use canonical padded Base64");
        range(bank.length, 10, MAX_BANK_BYTES, "native bank length");
        if (bankBase + bank.length > 0x10000)
            throw new IllegalArgumentException("Native bank extends beyond 16-bit address space");
        range(headerOffset, 0, bank.length - 6, "headerOffset");
        int fmCount = Byte.toUnsignedInt(bank[headerOffset + 2]);
        int psgCount = Byte.toUnsignedInt(bank[headerOffset + 3]);
        range(fmCount, 1, 7, "native header FM count");
        range(psgCount, 0, 3, "native header PSG count");
        if (headerOffset + 6 + 4 * fmCount + 6 * psgCount > bank.length)
            throw new IllegalArgumentException("Native header channel entries extend beyond the bank");
        envelopeSlots = List.copyOf(envelopeSlots);
        dacSlots = List.copyOf(dacSlots);
        Set<Integer> notes = new HashSet<>();
        for (DacSlot slot : dacSlots) {
            if (!notes.add(slot.note()))
                throw new IllegalArgumentException("Duplicate native DAC note " + slot.note());
        }
    }

    public byte[] bank() {
        return Base64.getDecoder().decode(bankBase64);
    }

    public int tempo() {
        return Byte.toUnsignedInt(bank()[headerOffset + 5]);
    }

    public NativeProgram withBank(byte[] bank) {
        return of(bankBase, headerOffset, localVoiceCount, previewFrames, bank, envelopeSlots, dacSlots);
    }

    public static NativeProgram of(int bankBase, int headerOffset, int localVoiceCount, int previewFrames,
                                   byte[] bank, List<EnvelopeSlot> envelopeSlots, List<DacSlot> dacSlots) {
        return new NativeProgram(bankBase, headerOffset, localVoiceCount, previewFrames,
                Base64.getEncoder().encodeToString(bank), envelopeSlots, dacSlots);
    }

    private static void requireId(String id, String name) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException(name + " must be nonblank");
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max)
            throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
    }
}

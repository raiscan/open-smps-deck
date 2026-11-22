package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Bounds-checks the finite S1/S2 portable stream profile before entering the sequencer. */
final class ClassicPackageBank {
    private ClassicPackageBank() {}

    static void validateEnvelope(ClassicPackageDriver driver, Envelope envelope) {
        var bytes = envelope.bytes();
        driver.require(envelope.type().equals(Envelope.PSG_VOLUME) && bytes.size() >= 2
                && bytes.getLast() == 0x80 && bytes.subList(0, bytes.size() - 1).stream().allMatch(value -> value < 0x80),
                "envelope '" + envelope.id() + "' requires PSG attenuation bytes followed by a single 80 hold terminator");
    }

    static void validate(ClassicPackageDriver driver, CompiledProject project) {
        var nativeBank = project.nativeProgram();
        byte[] bytes = project.song();
        int base = nativeBank == null ? 0 : nativeBank.bankBase();
        driver.validateBase(base);
        driver.require(nativeBank == null || nativeBank.headerOffset() == 0, "bank.headerOffset must be 0");
        driver.require(project.globalVoices().isEmpty(), "global FM voices are unsupported");
        int fm = bytes[2] & 255, psg = bytes[3] & 255;
        driver.require((bytes[4] & 255) != 0, "header timing divisor must be nonzero");
        driver.validateTempo(bytes[5] & 255);
        int headerEnd = 6 + 4 * fm + 6 * psg;
        int voicePointer = driver.word(bytes, 0);
        int voices = nativeBank == null ? (voicePointer == 0 ? 0 : (bytes.length - voicePointer) / 25)
                : nativeBank.localVoiceCount();
        int streamEnd = voices == 0 ? bytes.length : voicePointer - base;
        driver.require(voices == 0 ? voicePointer == 0 : streamEnd >= headerEnd
                        && bytes.length - streamEnd == voices * 25,
                "invalid local FM voice table");

        for (var envelope : project.envelopes()) validateEnvelope(driver, envelope);
        Set<Integer> psgSlots = project.psgEnvelopes().keySet();
        Set<Integer> dacNotes = new HashSet<>();
        if (nativeBank == null) {
            for (int i = 0; i < project.samples().size(); i++) dacNotes.add(DacSample.FIRST_NOTE + i);
        } else {
            for (var slot : nativeBank.dacSlots()) dacNotes.add(slot.note());
        }
        DacSample.validateAll(project.samples());
        int[] starts = new int[fm + psg];
        var boundaries = new TreeSet<Integer>();
        boundaries.add(streamEnd);
        for (int i = 0; i < starts.length; i++) {
            int at = i < fm ? 6 + i * 4 : 6 + fm * 4 + (i - fm) * 6;
            starts[i] = driver.word(bytes, at) - base;
            driver.require(starts[i] >= headerEnd && starts[i] < streamEnd,
                    "track pointer at " + at + " is outside the stream region");
            boundaries.add(starts[i]);
            if (i >= fm) {
                driver.require(bytes[at + 4] == 0, "PSG modulation envelope in header");
                requireEnvelope(driver, psgSlots, bytes[at + 5] & 255);
            }
        }
        for (int i = 0; i < starts.length; i++) {
            scan(driver, bytes, starts[i], boundaries.higher(starts[i]), i, fm, voices, psgSlots, dacNotes);
        }
    }

    private static void scan(ClassicPackageDriver driver, byte[] bytes, int at, int end, int channel,
                             int fm, int voices, Set<Integer> psgSlots, Set<Integer> dacNotes) {
        boolean dac = channel == 0;
        boolean psg = channel >= fm;
        boolean haveNote = false;
        while (at < end) {
            int command = bytes[at++] & 255;
            if (command == 0xF2) return;
            if (command < 0x80) {
                driver.require(!dac && haveNote && command != 0, "invalid duration-only stream unit");
                continue;
            }
            if (command < 0xE0) {
                if (dac) driver.require(command == 0x80 || fm < 7 && dacNotes.contains(command),
                        "undefined or unavailable DAC note " + command);
                else if (!psg && command != 0x80) driver.require(voices > 0, "FM notes require a local voice table");
                driver.require(at < end && (bytes[at] & 255) > 0 && (bytes[at] & 255) < 0x80,
                        "note/rest requires explicit duration in 1..127");
                at++;
                haveNote = true;
                continue;
            }
            int arity = switch (command) {
                case 0xE7, 0xF4 -> 0;
                case 0xE1, 0xE8, 0xE9 -> 1;
                case 0xE0, 0xE6, 0xEF -> psg ? -1 : 1;
                case 0xEC, 0xF5 -> psg ? 1 : -1;
                case 0xF3 -> channel == fm + 2 ? 1 : -1;
                case 0xF0 -> 4;
                default -> -1;
            };
            driver.require(!dac && arity >= 0, "unsupported stream command %02X at %d".formatted(command, at - 1));
            driver.require(at + arity <= end, "truncated stream command %02X".formatted(command));
            if (command == 0xEF) driver.require((bytes[at] & 255) < voices, "undefined FM voice " + (bytes[at] & 255));
            if (command == 0xF5) requireEnvelope(driver, psgSlots, bytes[at] & 255);
            at += arity;
        }
        driver.require(false, "track has no F2 stop before the next track or voice table");
    }

    private static void requireEnvelope(ClassicPackageDriver driver, Set<Integer> slots, int slot) {
        driver.require(slot == 0 || slots.contains(slot), "undefined PSG envelope " + slot);
    }
}

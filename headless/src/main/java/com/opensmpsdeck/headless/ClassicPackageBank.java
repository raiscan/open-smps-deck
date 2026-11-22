package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/** Bounds-checks the finite S1/S2 portable stream profile before entering the sequencer. */
public final class ClassicPackageBank {
    @FunctionalInterface
    interface StreamUnit {
        void read(int offset, int count, boolean dac);
    }

    private record Branch(int at, int target) {}

    private ClassicPackageBank() {}

    /** Finds asset operands in a classic bank before the S1 cartridge engine maps its slots. */
    public static void walkS1Assets(byte[] bank, int voices, IntConsumer envelope, IntConsumer sample) {
        walk(ClassicPackageDriver.S1, bank, 0, voices, null, null, (at, count, dac) -> {
            int command = bank[at] & 255;
            if (dac && command > 0x80 && command < 0xE0) sample.accept(at);
            if (command == 0xF5) envelope.accept(at + 1);
        });
    }

    static int branchTarget(ClassicPackageDriver driver, byte[] bank, int base, int operand) {
        int word = driver.word(bank, operand);
        return driver == ClassicPackageDriver.S1 ? operand + 1 + (short) word : word - base;
    }

    static void validateEnvelope(ClassicPackageDriver driver, Envelope envelope) {
        var bytes = envelope.bytes();
        for (int value : bytes) {
            driver.require(value <= 0x80, "PSG envelope '" + envelope.id() + "' uses S3K control byte "
                    + Integer.toHexString(value).toUpperCase(java.util.Locale.ROOT) + "; PSG envelopes require a final 80 hold");
        }
        driver.require(envelope.type().equals(Envelope.PSG_VOLUME) && bytes.size() >= 2
                && bytes.getLast() == 0x80 && bytes.subList(0, bytes.size() - 1).stream().allMatch(value -> value < 0x80),
                "envelope '" + envelope.id() + "' requires PSG attenuation bytes followed by a single 80 hold terminator");
    }

    static List<Integer> validate(ClassicPackageDriver driver, CompiledProject project) {
        var nativeBank = project.nativeProgram();
        byte[] bytes = project.song();
        int base = nativeBank == null ? 0 : nativeBank.bankBase();
        driver.validateBase(base);
        driver.require(nativeBank == null || nativeBank.headerOffset() == 0, "bank.headerOffset must be 0");
        driver.require(project.globalVoices().isEmpty(), "global FM voices are unsupported");
        driver.require(bytes.length >= 6, "truncated song header");
        for (var envelope : project.envelopes()) validateEnvelope(driver, envelope);
        Set<Integer> psgSlots = project.psgEnvelopes().keySet();
        Set<Integer> dacNotes = new HashSet<>();
        if (nativeBank == null) {
            for (int i = 0; i < project.samples().size(); i++) dacNotes.add(DacSample.FIRST_NOTE + i);
        } else {
            for (var slot : nativeBank.dacSlots()) dacNotes.add(slot.note());
        }
        DacSample.validateAll(project.samples());
        int voicePointer = driver.word(bytes, 0);
        int voices = nativeBank == null ? (voicePointer == 0 ? 0 : (bytes.length - voicePointer) / 25)
                : nativeBank.localVoiceCount();
        return walk(driver, bytes, base, voices, psgSlots, dacNotes, (offset, count, dac) -> {});
    }

    static List<Integer> walk(ClassicPackageDriver driver, byte[] bytes, int base, int voices,
                     Set<Integer> psgSlots, Set<Integer> dacNotes, StreamUnit unit) {
        driver.require(bytes.length >= 6, "truncated song header");
        int fm = bytes[2] & 255, psg = bytes[3] & 255;
        driver.require((bytes[4] & 255) != 0, "header timing divisor must be nonzero");
        driver.validateTempo(bytes[5] & 255);
        int headerEnd = 6 + 4 * fm + 6 * psg;
        int voicePointer = driver.word(bytes, 0);
        int streamEnd = voices == 0 ? bytes.length : voicePointer - base;
        driver.require(voices == 0 ? voicePointer == 0 : streamEnd >= headerEnd
                        && bytes.length - streamEnd == voices * 25,
                "invalid local FM voice table");

        driver.require(fm >= 1 && fm <= 7 && psg <= 3 && headerEnd <= bytes.length,
                "invalid song header channel counts");
        int[] starts = new int[fm + psg];
        var boundaries = new TreeSet<Integer>();
        boundaries.add(streamEnd);
        var relocations = new TreeSet<Integer>();
        if (voices > 0) relocations.add(0);
        for (int i = 0; i < starts.length; i++) {
            int at = i < fm ? 6 + i * 4 : 6 + fm * 4 + (i - fm) * 6;
            relocations.add(at);
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
            new TrackScan(driver, bytes, base, i, fm, voices, psgSlots, dacNotes, unit, relocations)
                    .validate(starts[i], boundaries.higher(starts[i]));
        }
        return List.copyOf(relocations);
    }

    private record TrackScan(ClassicPackageDriver driver, byte[] bytes, int base, int channel, int fm,
                             int voices, Set<Integer> psgSlots, Set<Integer> dacNotes, StreamUnit unit,
                             Set<Integer> relocations) {
        void validate(int start, int end) {
            var entries = new TreeSet<Integer>();
            var calls = new TreeSet<Integer>();
            var jumps = new ArrayList<Branch>();
            var loops = new TreeMap<Integer, Integer>();
            var flowSites = new TreeSet<Integer>();
            int stop = scan(start, end, false, entries, calls, jumps, loops, flowSites);
            for (var jump : jumps) {
                driver.require(entries.contains(jump.target()), "F6 jump must target a forward event start");
                var loop = loops.ceilingEntry(jump.target());
                driver.require(loop == null || jump.target() <= loop.getValue(),
                        "F6 jump cannot enter the middle of an F7 loop");
                driver.require(flowSites.subSet(jump.at(), false, jump.target(), false).isEmpty(),
                        "F6 jump cannot skip another branch");
            }
            for (int call : calls) {
                driver.require(call > stop && call < end, "F8 call must target a standalone routine after F2");
                Integer next = calls.higher(call);
                scan(call, next == null ? end : next, true, null, null, null, null, null);
            }
        }

        private int scan(int start, int end, boolean routine, TreeSet<Integer> entries,
                         Set<Integer> calls, List<Branch> jumps, TreeMap<Integer, Integer> loops,
                         Set<Integer> flowSites) {
            boolean dac = channel == 0, psg = channel >= fm;
            boolean haveNote = false, hold = false, afterFlow = false;
            int eventStart = start, previousFlow = start - 1;
            for (int at = start; at < end;) {
                int pc = at;
                int command = bytes[at++] & 255;
                if (command == 0xF2 || command == 0xE3) {
                    driver.require(routine ? command == 0xE3 && haveNote : command == 0xF2,
                            routine ? "routine must contain a timed event and end with E3" : "E3 return outside a routine");
                    unit.read(pc, 1, dac);
                    return pc;
                }
                if (command >= 0xF6 && command <= 0xF8) {
                    driver.require(!routine && pc == eventStart, "nested control flow or branch inside a timed event");
                    int length = command == 0xF7 ? 5 : 3;
                    driver.require(pc + length <= end, "truncated control flow");
                    int operand = pc + length - 2;
                    int target = branchTarget(driver, bytes, base, operand);
                    driver.require(target >= start && target < end, "branch target outside the track region");
                    if (driver == ClassicPackageDriver.S2) relocations.add(operand);
                    flowSites.add(pc);
                    switch (command) {
                        case 0xF6 -> {
                            driver.require(target >= pc + length, "F6 jump must be forward in a finite track");
                            jumps.add(new Branch(pc, target));
                        }
                        case 0xF7 -> {
                            driver.require(bytes[at] == 0 && bytes[at + 1] != 0,
                                    "F7 loop requires counter 0 and a nonzero count");
                            driver.require(target > previousFlow && entries.contains(target),
                                    "F7 loop must go backward to an event start without nested control flow");
                            loops.put(pc, target);
                        }
                        case 0xF8 -> calls.add(target);
                        default -> throw new AssertionError(command);
                    }
                    unit.read(pc, length, dac);
                    previousFlow = pc;
                    at = eventStart = pc + length;
                    afterFlow = true;
                    continue;
                }
                if (command < 0xE0) {
                    if (command < 0x80) {
                        driver.require(!dac && haveNote && command != 0, "invalid duration-only stream unit");
                    } else {
                        if (dac) driver.require(command == 0x80 || fm < 7
                                        && (dacNotes == null || dacNotes.contains(command)),
                                "undefined or unavailable DAC note " + command);
                        else if (!psg && command != 0x80)
                            driver.require(voices > 0, "FM notes require a local voice table");
                        driver.require(at < end && (bytes[at] & 255) > 0 && (bytes[at] & 255) < 0x80,
                                "note/rest requires explicit duration in 1..127");
                        at++;
                    }
                    boolean standalone = command >= 0x80 && !hold;
                    driver.require(standalone || !afterFlow && (!routine || haveNote),
                            "control flow boundary requires an explicit note or rest without hold");
                    if (!routine && standalone) entries.add(eventStart);
                    unit.read(pc, at - pc, dac);
                    haveNote = true;
                    hold = afterFlow = false;
                    eventStart = at;
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
                driver.require(!dac && arity >= 0, "unsupported stream command %02X at %d".formatted(command, pc));
                driver.require(at + arity <= end, "truncated stream command %02X".formatted(command));
                if (command == 0xEF)
                    driver.require((bytes[at] & 255) < voices, "undefined FM voice " + (bytes[at] & 255));
                if (command == 0xF5) requireEnvelope(driver, psgSlots, bytes[at] & 255);
                hold |= command == 0xE7;
                unit.read(pc, arity + 1, dac);
                at += arity;
            }
            throw new IllegalArgumentException(driver.id() + (routine
                    ? ": routine has no E3 return" : ": track has no F2 stop before the next track or voice table"));
        }
    }

    private static void requireEnvelope(ClassicPackageDriver driver, Set<Integer> slots, int slot) {
        driver.require(slots == null || slot == 0 || slots.contains(slot), "undefined PSG envelope " + slot);
    }
}

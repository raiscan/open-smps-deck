package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.EnvelopeProgram;
import com.opensmpsdeck.project.NativeProgram;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Preflight for the native instructions supported by the pinned playback driver. */
public final class NativeBankAnalysis {
    private static final int GLOBAL_VOICE_ADDRESS = 0x17D8;
    private static final int VOICE_BYTES = 25;
    private static final int MAX_IMMEDIATE_COMMANDS = 4096;
    private static final int MAX_STATES = 100_000;

    public record Event(int address, List<Integer> bytes, String meaning) {
        public Event { bytes = List.copyOf(bytes); }
    }

    public record Analysis(byte[] bank, byte[] header, int headerAddress, List<Event> events,
                           int voiceOffset, int localVoiceCount, int globalVoiceOffset,
                           boolean requiresPreviousMusic) {
        public Analysis {
            bank = bank.clone();
            header = header.clone();
            events = List.copyOf(events);
        }
        @Override public byte[] bank() { return bank.clone(); }
        @Override public byte[] header() { return header.clone(); }
    }

    private enum Track { DAC, FM, PSG }

    // Native track RAM provides two return addresses. Loop index 2 aliases a
    // voice pointer byte unused by music, as in IceCap Act 2 and S&K Credits.
    private record State(int pc, boolean savedDuration,
                         List<Integer> returns, int loop0, int loop1, int loop2) {}

    private final byte[] bank;
    private final int base;
    private final int header;
    private int localVoiceCount;
    private final int globalVoiceCount;
    private final boolean allowPreviousMusicRestore;
    private final boolean discoverLocalVoices;
    private final Map<Integer, List<Integer>> psgEnvelopes;
    private final Map<Integer, List<Integer>> modulationEnvelopes;
    private final Set<Integer> checkedPsgEnvelopes = new HashSet<>();
    private final Set<Integer> checkedModulationEnvelopes = new HashSet<>();
    private final Set<Integer> dacNotes = new HashSet<>();
    private final Map<Integer, Event> events = new TreeMap<>();
    private final int[] owners;
    private final Set<Integer> branchTargets = new HashSet<>();
    private int remainingStates = MAX_STATES;
    private int divider;
    private int voiceOffset = -1;
    private int globalVoiceOffset = -1;
    private boolean restoresPreviousMusic;
    private boolean setsOtherAdmissionState;

    private NativeBankAnalysis(CompiledProject project, boolean allowPreviousMusicRestore, boolean discoverLocalVoices) {
        NativeProgram program = project.nativeProgram();
        if (program == null) throw new IllegalArgumentException("native bank is required");
        bank = program.bank();
        base = program.bankBase();
        header = program.headerOffset();
        localVoiceCount = program.localVoiceCount();
        globalVoiceCount = project.globalVoices().size();
        this.allowPreviousMusicRestore = allowPreviousMusicRestore;
        this.discoverLocalVoices = discoverLocalVoices;
        validateResources(project, program);
        psgEnvelopes = project.psgEnvelopes();
        modulationEnvelopes = project.modulationEnvelopes();
        for (var binding : program.dacSlots()) dacNotes.add(binding.note());
        owners = new int[bank.length];
        Arrays.fill(owners, -1);
    }

    public static Analysis inspect(CompiledProject project) {
        return inspect(project, false);
    }

    /** Inspect a cue's events without requiring a previous song to be loaded. */
    public static Analysis inspectForEditing(CompiledProject project) {
        return inspect(project, true);
    }

    static Analysis inspect(CompiledProject project, boolean allowPreviousMusicRestore) {
        return new NativeBankAnalysis(project, allowPreviousMusicRestore, false).inspect();
    }

    static int discoverLocalVoiceCount(CompiledProject project) {
        return new NativeBankAnalysis(project, true, true).inspect().localVoiceCount();
    }

    private static void validateResources(CompiledProject project, NativeProgram program) {
        Map<String, Envelope> envelopes = new HashMap<>();
        for (Envelope envelope : project.envelopes()) {
            if (envelope.id() == null || envelope.id().isBlank() || envelopes.putIfAbsent(envelope.id(), envelope) != null) {
                throw new IllegalArgumentException("envelope IDs must be nonblank and unique");
            }
        }
        Set<Integer> psgSlots = new HashSet<>(), modulationSlots = new HashSet<>();
        for (var binding : program.envelopeSlots()) {
            Envelope envelope = envelopes.get(binding.assetId());
            if (envelope == null) throw new IllegalArgumentException("undefined envelope " + binding.assetId());
            Set<Integer> slots = Envelope.PSG_VOLUME.equals(envelope.type()) ? psgSlots : modulationSlots;
            if (slots == modulationSlots && binding.slot() >= 128) {
                throw new IllegalArgumentException("modulation envelope slots must be in 1..127");
            }
            if (!slots.add(binding.slot())) throw new IllegalArgumentException("duplicate envelope slot " + binding.slot());
        }
        DacSample.validateAll(project.samples());
        Set<String> samples = new HashSet<>();
        for (DacSample sample : project.samples()) samples.add(sample.id());
        for (var binding : program.dacSlots()) {
            if (!samples.contains(binding.sampleId())) throw new IllegalArgumentException("undefined DAC sample " + binding.sampleId());
        }
        for (var voice : project.globalVoices()) {
            if (voice.bytes().size() != VOICE_BYTES || voice.bytes().stream().anyMatch(value -> value < 0 || value > 255)) {
                throw new IllegalArgumentException("global FM voices require 25 unsigned bytes");
            }
        }
    }

    private Analysis inspect() {
        requireBytes(header, 6);
        int fm = read(header + 2), psg = read(header + 3);
        if (fm == 7 && psg == 0) {
            throw invalid(header + 2, "seventh FM header entry requires a PSG entry to replace the inactive slot");
        }
        divider = read(header + 4);
        int headerLength = 6 + 4 * fm + 6 * psg;
        reserve(header, headerLength);
        int voicePointer = word(header);
        if (discoverLocalVoices && voicePointer >= base && voicePointer != 0) localVoiceCount = 1;
        if (localVoiceCount != 0) {
            if (voicePointer == 0) throw invalid(header, "zero voice pointer selects the global table");
            voiceOffset = address(voicePointer, header);
            reserve(voiceOffset, VOICE_BYTES * localVoiceCount);
        } else if (voicePointer == 0) {
            globalVoiceOffset = 0;
        } else if (base == 0x8000 && voicePointer >= GLOBAL_VOICE_ADDRESS
                && voicePointer < base && (voicePointer - GLOBAL_VOICE_ADDRESS) % VOICE_BYTES == 0) {
            globalVoiceOffset = (voicePointer - GLOBAL_VOICE_ADDRESS) / VOICE_BYTES;
            if (globalVoiceOffset >= globalVoiceCount) throw invalid(header, "undefined global voice table pointer");
        } else {
            throw invalid(header, "voice pointer requires an explicit local table or a resolved global table pointer");
        }

        int entry = header + 6;
        for (int index = 0; index < Math.min(fm, 6); index++, entry += 4) {
            walk(address(word(entry), entry), index == 0 ? Track.DAC : Track.FM);
        }
        // In the retail layout PSG1 initialization overwrites the hypothetical
        // seventh FM track. Its header bytes are preserved as metadata.
        entry = header + 6 + 4 * fm;
        for (int index = 0; index < psg; index++, entry += 6) {
            int modulation = read(entry + 4);
            requireEnvelope(modulation, entry + 4, Envelope.MODULATION);
            requireEnvelope(read(entry + 5), entry + 5, Envelope.PSG_VOLUME);
            walk(address(word(entry), entry), Track.PSG);
        }
        for (int target : branchTargets) requireExecutable(target);
        if (restoresPreviousMusic && setsOtherAdmissionState) {
            throw invalid(header, "contextual preview requires E2 FF without other E2 values");
        }
        return new Analysis(bank, Arrays.copyOfRange(bank, header, header + headerLength),
                base + header, List.copyOf(events.values()), voiceOffset, localVoiceCount, globalVoiceOffset,
                restoresPreviousMusic);
    }

    private void walk(int pc, Track track) {
        Map<State, Integer> visited = new HashMap<>();
        List<Integer> returns = new ArrayList<>(2);
        int[] loops = new int[3];
        int yields = 0;
        int immediate = 0;
        boolean savedDuration = false;
        while (true) {
            State state = new State(pc, savedDuration, List.copyOf(returns), loops[0], loops[1], loops[2]);
            Integer previousYield = visited.putIfAbsent(state, yields);
            if (previousYield != null) {
                if (previousYield == yields) throw invalid(pc, "control flow repeats without advancing time");
                return;
            }
            if (--remainingStates < 0) throw invalid(pc, "native bank exceeds the analysis state limit");
            if (++immediate > MAX_IMMEDIATE_COMMANDS) throw invalid(pc, "too many instructions without advancing time");
            requireExecutable(pc);
            int command = read(pc);
            if (command < 0xE0) {
                if (command < 0x80) {
                    if (!savedDuration) throw invalid(pc, "duration-only event has no previous note or rest");
                    duration(pc);
                    pc++;
                } else {
                    if (command != 0x80 && track == Track.FM) requireVoice(0, pc);
                    if (command != 0x80 && track == Track.DAC && !dacNotes.contains(command)) {
                        throw invalid(pc, "undefined DAC sample for note " + hex(command));
                    }
                    instruction(pc, 1, command == 0x80 ? "Rest" : track == Track.DAC ? "DAC sample" : "Note");
                    pc++;
                    requireExecutable(pc);
                    if (read(pc) < 0x80) {
                        duration(pc++);
                        savedDuration = true;
                    } else if (!savedDuration) {
                        throw invalid(pc, "implicit duration has no previously stored duration");
                    }
                }
                yields++;
                immediate = 0;
                continue;
            }

            switch (command) {
                case 0xF2 -> {
                    instruction(pc, 1, "Stop track");
                    return;
                }
                case 0xE3 -> throw invalid(pc, "silence and stop requires native E3 register writes");
                case 0xF6 -> {
                    instruction(pc, 3, "Jump");
                    pc = address(word(pc + 1), pc);
                }
                case 0xF7 -> {
                    instruction(pc, 5, "Counted loop");
                    int index = read(pc + 1), count = read(pc + 2);
                    if (index >= loops.length) throw invalid(pc, "music loop index must be 0, 1 or 2");
                    if (count == 0) throw invalid(pc, "zero loop count requires native 256-iteration wrap");
                    int target = address(word(pc + 3), pc);
                    branchTargets.add(target);
                    if (loops[index] == 0) loops[index] = count;
                    pc = --loops[index] == 0 ? pc + 5 : target;
                }
                case 0xF8 -> {
                    instruction(pc, 3, "Call");
                    if (returns.size() >= 2) throw invalid(pc, "native return stack would overflow");
                    returns.add(pc + 3);
                    pc = address(word(pc + 1), pc);
                }
                case 0xF9 -> {
                    instruction(pc, 1, "Return");
                    if (returns.isEmpty()) throw invalid(pc, "return without a call");
                    pc = returns.removeLast();
                }
                default -> pc += command(pc, command, track);
            }
        }
    }

    private int command(int pc, int command, Track track) {
        return switch (command) {
            case 0xE0 -> {
                if (track == Track.PSG) throw invalid(pc, "pan, AMS and FMS are unsupported on PSG");
                yield instruction(pc, 2, "Pan, AMS and FMS");
            }
            case 0xE1 -> toneCommand(pc, track, 2, "Detune");
            case 0xE2 -> {
                requireBytes(pc, 2);
                if (read(pc + 1) == 0xFF) {
                    if (!allowPreviousMusicRestore)
                        throw invalid(pc, "E2 FF restores a previous song and requires session context");
                    restoresPreviousMusic = true;
                    yield instruction(pc, 2, "Restore previous song");
                }
                // Other values affect admission of external SFX. This playback
                // session drives the selected music bank alone.
                setsOtherAdmissionState = true;
                yield instruction(pc, 2, "Sound-effect admission control");
            }
            case 0xE4 -> toneCommand(pc, track, 2, "Absolute volume");
            case 0xE5 -> toneCommand(pc, track, 3, track == Track.PSG
                    ? "Ignored FM attenuation on PSG" : "FM attenuation with unused first operand");
            case 0xE6 -> toneCommand(pc, track, 2, track == Track.PSG
                    ? "Ignored FM attenuation on PSG" : "FM attenuation");
            case 0xE7 -> toneCommand(pc, track, 1, "Prevent next attack");
            case 0xE8 -> toneCommand(pc, track, 2, "Note fill");
            case 0xEC -> trackCommand(pc, track, Track.PSG, 2, "PSG attenuation");
            case 0xED -> toneCommand(pc, track, 2, "Absolute transpose");
            case 0xEF -> {
                requireBytes(pc, 2);
                int index = read(pc + 1);
                if (track == Track.PSG) {
                    requireEnvelope(index, pc, Envelope.PSG_VOLUME);
                    yield instruction(pc, index >= 0x80 ? 3 : 2, "PSG envelope via EF");
                }
                int size = trackCommand(pc, track, Track.FM, 2, "FM voice");
                if (index >= 0x80) throw invalid(pc, "cross-song FM voice requires native bank switching support");
                requireVoice(index, pc);
                yield size;
            }
            case 0xF0 -> toneCommand(pc, track, 5, "Custom modulation");
            case 0xF1 -> {
                int size = toneCommand(pc, track, 3, "Track-specific modulation envelope");
                requireEnvelope(read(pc + (track == Track.PSG ? 1 : 2)), pc, Envelope.MODULATION);
                yield size;
            }
            case 0xF3 -> {
                int size = trackCommand(pc, track, Track.PSG, 2, "PSG noise");
                int value = read(pc + 1);
                if (value != 0 && value < 0xE0) throw invalid(pc, "PSG noise requires a native latch byte or zero");
                yield size;
            }
            case 0xF4 -> {
                int size = toneCommand(pc, track, 2, "Modulation envelope");
                requireEnvelope(read(pc + 1), pc, Envelope.MODULATION);
                yield size;
            }
            case 0xF5 -> {
                int size = toneCommand(pc, track, 2, track == Track.FM
                        ? "Ignored PSG envelope on FM" : "PSG volume envelope");
                if (track == Track.PSG) requireEnvelope(read(pc + 1), pc, Envelope.PSG_VOLUME);
                yield size;
            }
            case 0xFA -> toneCommand(pc, track, 1, "Disable custom modulation");
            case 0xFB -> toneCommand(pc, track, 2, "Transpose delta");
            case 0xFF -> {
                requireBytes(pc, 2);
                int subcommand = read(pc + 1);
                if (subcommand != 0) throw invalid(pc, "unsupported native meta command FF " + hex(subcommand));
                yield instruction(pc, 3, "Global tempo");
            }
            default -> throw invalid(pc, "unsupported native command " + hex(command));
        };
    }

    private int toneCommand(int pc, Track track, int size, String meaning) {
        if (track == Track.DAC) throw invalid(pc, meaning + " is unsupported on DAC");
        return instruction(pc, size, meaning);
    }

    private int trackCommand(int pc, Track track, Track supported, int size, String meaning) {
        if (track != supported) throw invalid(pc, meaning + " is unsupported on " + track);
        return instruction(pc, size, meaning);
    }

    private void duration(int pc) {
        int raw = read(pc);
        int scaled = (raw * divider) & 0xFF;
        instruction(pc, 1, "Duration " + raw + (scaled == raw && scaled != 0 ? "" : " → " + (scaled == 0 ? 256 : scaled)));
    }

    private void requireVoice(int index, int pc) {
        int count = localVoiceCount != 0 ? localVoiceCount : globalVoiceCount - globalVoiceOffset;
        if (discoverLocalVoices && localVoiceCount != 0 && index >= localVoiceCount) {
            int additionalVoices = index + 1 - localVoiceCount;
            reserve(voiceOffset + VOICE_BYTES * localVoiceCount, VOICE_BYTES * additionalVoices);
            localVoiceCount = index + 1;
            return;
        }
        if (index >= count) throw invalid(pc, "undefined FM voice " + index);
    }

    private void requireEnvelope(int index, int pc, String type) {
        if (index == 0) return;
        boolean psg = Envelope.PSG_VOLUME.equals(type);
        Set<Integer> checked = psg ? checkedPsgEnvelopes : checkedModulationEnvelopes;
        if (!checked.add(index)) return;
        String kind = psg ? "PSG" : "modulation";
        List<Integer> bytes = (psg ? psgEnvelopes : modulationEnvelopes).get(index);
        if (bytes == null) throw invalid(pc, "undefined " + kind + " envelope " + index);
        try {
            EnvelopeProgram.validateNativePlayback(type, bytes);
        } catch (IllegalArgumentException error) {
            throw invalid(pc, kind + " envelope " + index + ": " + error.getMessage());
        }
    }

    private int address(int pointer, int pc) {
        if (pointer == 0) throw invalid(pc, "zero track or control-flow pointer is unsupported");
        int offset = pointer - base;
        if (offset < 0 || offset >= bank.length) throw invalid(pc, "address " + hex(pointer) + " is outside the bank");
        return offset;
    }

    private void reserve(int pc, int size) {
        requireBytes(pc, size);
        for (int index = pc; index < pc + size; index++) {
            if (owners[index] != -1) throw invalid(index, "header or voice table overlaps occupied bytes");
            owners[index] = -2;
        }
    }

    private void requireExecutable(int pc) {
        requireBytes(pc, 1);
        if (owners[pc] == -2) throw invalid(pc, "execution enters a header or voice table");
        if (owners[pc] >= 0 && owners[pc] != pc) throw invalid(pc, "execution enters an instruction operand");
    }

    private int instruction(int pc, int size, String meaning) {
        requireBytes(pc, size);
        List<Integer> bytes = new ArrayList<>(size);
        for (int index = pc; index < pc + size; index++) {
            if (owners[index] != -1 && owners[index] != pc) {
                throw invalid(index, "instruction overlaps a header, voice table or another instruction");
            }
            owners[index] = pc;
            bytes.add(read(index));
        }
        events.putIfAbsent(pc, new Event(base + pc, bytes, meaning));
        return size;
    }

    private void requireBytes(int pc, int size) {
        if (pc < 0 || pc > bank.length - size) throw invalid(pc, "truncated native bank data");
    }

    private int word(int pc) { return read(pc) | (read(pc + 1) << 8); }
    private int read(int pc) { return bank[pc] & 0xFF; }
    private static String hex(int value) { return String.format("0x%02X", value); }
    private IllegalArgumentException invalid(int pc, String reason) {
        return new IllegalArgumentException(reason + " at " + hex(base + pc));
    }
}

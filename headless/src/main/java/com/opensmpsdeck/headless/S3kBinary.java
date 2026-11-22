package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.EnvelopeProgram;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Finite native-bank boundary with explicitly supplied envelope and DAC assets. */
final class S3kBinary {
    static final int MAX_BYTES = 0x8000;
    private S3kBinary() {}

    static byte[] validateAndCopy(byte[] input) {
        return validateAndCopy(input, Map.of(), Map.of());
    }

    static byte[] validateAndCopy(CompiledProject project) {
        if (project == null) fail("compiled project is required");
        if (project.nativeProgram() != null) return inspectNative(project).bank();
        validateEnvelopeDeclarations(project);
        return validateAndCopy(project.song(), project.psgEnvelopes(), project.modulationEnvelopes(), project.samples(), true);
    }

    static NativeBankAnalysis.Analysis inspectNative(CompiledProject project) {
        return NativeBankAnalysis.inspect(project);
    }

    static NativeBankAnalysis.Analysis inspectNative(CompiledProject project, boolean allowPreviousMusicRestore) {
        return NativeBankAnalysis.inspect(project, allowPreviousMusicRestore);
    }

    private static void validateEnvelopeDeclarations(CompiledProject project) {
        var ids = new HashSet<String>();
        int psgCount = 0, modulationCount = 0;
        // CompiledProject is also a public raw-input carrier. Validate every
        // declaration, including unreferenced assets.
        for (var envelope : project.envelopes()) {
            if (envelope.id() == null || envelope.id().isBlank() || !ids.add(envelope.id()))
                fail("envelope IDs must be nonblank and unique");
            if (envelope.name() == null || envelope.name().isBlank()) fail("envelope name is required");
            EnvelopeProgram.validateNativePlayback(envelope.type(), envelope.bytes());
            if (envelope.type().equals("PSG_VOLUME") && ++psgCount > 255) fail("at most 255 PSG envelopes can be addressed");
            if (envelope.type().equals("MODULATION") && ++modulationCount > 127) fail("at most 127 modulation envelopes can be addressed");
        }
    }

    static byte[] validateAndCopy(byte[] input, Map<Integer, List<Integer>> psgEnvelopes,
                                 Map<Integer, List<Integer>> modulationEnvelopes) {
        return validateAndCopy(input, psgEnvelopes, modulationEnvelopes, List.of(), false);
    }

    private static byte[] validateAndCopy(byte[] input, Map<Integer, List<Integer>> psgEnvelopes,
                                          Map<Integer, List<Integer>> modulationEnvelopes, List<DacSample> samples,
                                          boolean compiledAssets) {
        DacSample.validateAll(samples);
        validateEnvelopes(psgEnvelopes, "PSG_VOLUME", 255, compiledAssets);
        validateEnvelopes(modulationEnvelopes, "MODULATION", 127, compiledAssets);
        if (input == null || input.length < 10) fail("truncated S3K header (at least one DAC slot required)");
        if (input.length > MAX_BYTES) fail("binary exceeds one 32 KiB bank");
        byte[] data = input.clone();
        int fmCount = u8(data, 2), psgCount = u8(data, 3);
        if (fmCount < 1 || fmCount > 6 || psgCount > 3) fail("invalid channel counts: DAC + up to five FM, up to three PSG");
        int headerEnd = 6 + 4 * fmCount + 6 * psgCount;
        if (headerEnd > data.length) fail("truncated channel header");
        if (u8(data, 4) == 0) fail("dividing timing must be nonzero");
        int voiceStart = le16(data, 0);
        if (voiceStart != 0 && (voiceStart < headerEnd || voiceStart + 25 > data.length)) fail("local voice pointer is out of bounds");
        if (voiceStart != 0 && (data.length - voiceStart) % 25 != 0) fail("local voice table must contain complete 25-byte voices");
        int voices = voiceStart == 0 ? 0 : (data.length - voiceStart) / 25;
        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(data.length);
        if (voiceStart != 0) boundaries.add(voiceStart);
        ArrayList<Track> tracks = new ArrayList<>();
        for (int i = 0; i < fmCount + psgCount; i++) {
            boolean psg = i >= fmCount;
            int at = psg ? 6 + fmCount * 4 + (i - fmCount) * 6 : 6 + i * 4;
            int pointer = le16(data, at);
            if (pointer < headerEnd || pointer >= data.length || (voiceStart != 0 && pointer >= voiceStart)) fail("track pointer is outside the track-data region");
            if (psg) {
                requireEnvelope(modulationEnvelopes, u8(data, at + 4), "modulation", at + 4);
                requireEnvelope(psgEnvelopes, u8(data, at + 5), "PSG", at + 5);
            }
            boundaries.add(pointer);
            tracks.add(new Track(pointer, i == 0, psg));
        }
        for (Track track : tracks) {
            int end = boundaries.higher(track.pointer);
            scan(data, track, end, voices, psgEnvelopes, modulationEnvelopes, samples.size());
        }
        return data;
    }

    private static void scan(byte[] data, Track track, int end, int voices,
                             Map<Integer, List<Integer>> psgEnvelopes,
                             Map<Integer, List<Integer>> modulationEnvelopes, int sampleCount) {
        // A standalone arrangement can have flat, backward counted loops in its
        // main stream and calls to straight-line routines after its stop. Validate
        // the physical layout without expanding loops or executing untrusted bytes.
        BitSet starts = new BitSet(end - track.pointer);
        TreeSet<Integer> targets = new TreeSet<>();
        int position = track.pointer, lastBranchEnd = position;
        boolean timed = false, eventStart = true, inRoutine = false;
        boolean implicitDuration = false, controlFlow = false, hold = false;
        while (position < end) {
            int at = position, command = u8(data, position++);
            if (eventStart) starts.set(at - track.pointer);
            if (command == 0xF2 || command == 0xE3) {
                if (inRoutine) fail("subroutine has a stop instead of a return at " + hex(at));
                if (targets.isEmpty()) return;
                inRoutine = true;
                position = nextRoutine(targets, position);
                timed = false;
                eventStart = true;
                continue;
            }
            if (command == 0xF7 || command == 0xF8 || command == 0xF9) {
                controlFlow = true;
                if (implicitDuration || !eventStart) fail("control flow requires complete explicit-duration events at " + hex(at));
                if (command == 0xF9) {
                    if (!inRoutine || !timed) fail("return outside a timed subroutine at " + hex(at));
                    if (targets.isEmpty()) return;
                    position = nextRoutine(targets, position);
                    timed = false;
                } else if (inRoutine) {
                    fail("nested control flow in a standalone subroutine at " + hex(at));
                } else if (command == 0xF7) {
                    if (end - position < 4) fail("truncated loop operands at " + hex(at));
                    int target = le16(data, position + 2);
                    if (u8(data, position) != 0 || u8(data, position + 1) < 2 || !timed
                            || target < lastBranchEnd || target >= at || !starts.get(target - track.pointer))
                        fail("invalid standalone counted loop at " + hex(at));
                    position += 4;
                } else {
                    if (end - position < 2) fail("truncated call operands at " + hex(at));
                    int target = le16(data, position);
                    if (target < position + 2 || target >= end) fail("invalid standalone call target at " + hex(at));
                    targets.add(target);
                    position += 2;
                }
                lastBranchEnd = position;
                continue;
            }
            if (command < 0xE0) {
                if (command == 0) fail("zero-duration event is unsupported at " + hex(at));
                if (track.dac) {
                    if (command < 0x80) fail("DAC note/rest requires an explicit duration in 1..127 at " + hex(at));
                    if (command != 0x80 && command - DacSample.FIRST_NOTE >= sampleCount)
                        fail("undefined DAC sample for note " + hex(command) + " at " + hex(at)
                                + "; provide the complete asset package");
                } else if (!track.psg && command >= 0x81 && voices == 0) {
                    fail("FM notes require a local voice table");
                }
                if (command >= 0x80 && position < end && u8(data, position) > 0 && u8(data, position) < 0x80) {
                    position++;
                } else {
                    if (track.dac) fail("DAC note/rest requires an explicit duration in 1..127 at " + hex(at));
                    // E7 followed by a duration is a timed continuation.
                    if (command >= 0x80 || !hold) {
                        if (controlFlow) fail("control flow requires explicit durations at " + hex(at));
                        implicitDuration = true;
                    }
                }
                timed = true;
                eventStart = true;
                hold = false;
                continue;
            }
            eventStart = false;
            if (track.dac)
                fail("unsupported DAC event " + hex(command) + " at " + hex(at)
                        + "; use explicit notes/rests and durations, without replay, HOLD or commands");
            if (command == 0xE7) { hold = true; continue; }
            if (command == 0xFA) continue;
            if (command == 0xF0) {
                if (end - position < 4) fail("truncated modulation operands at " + hex(at));
                position += 4; // Delay, rate, signed delta, steps: all are raw bytes.
                continue;
            }
            if (command == 0xFF && !track.psg) {
                if (position >= end) fail("truncated FM meta command at " + hex(at));
                int subcommand = u8(data, position++);
                switch (subcommand) {
                    case 0x05 -> {
                        if (end - position < 4) fail("truncated SSG-EG operands at " + hex(at));
                        position += 4;
                    }
                    case 0x06 -> {
                        if (end - position < 2) fail("truncated FM volume envelope operands at " + hex(at));
                        requireEnvelope(psgEnvelopes, u8(data, position), "FM volume", at);
                        position += 2;
                    }
                    default -> fail("unsupported FM meta command " + hex(subcommand) + " at " + hex(at));
                }
                continue;
            }
            boolean both = command == 0xE1 || command == 0xE4 || command == 0xE8 || command == 0xED || command == 0xF4 || command == 0xFB;
            boolean fm = command == 0xE0 || command == 0xE6 || command == 0xEF;
            boolean psg = command == 0xEC || command == 0xF3 || command == 0xF5;
            if (!(both || (fm && !track.psg) || (psg && track.psg))) {
                fail("unsupported command " + hex(command) + " at " + hex(at)
                        + "; this renderer accepts finite tracks with resolved local resources");
            }
            if (position >= end) fail("truncated command operand at " + hex(at));
            int operand = u8(data, position++);
            if (command == 0xEF && (operand >= 128 || operand >= voices)) fail("undefined local FM voice " + operand + " at " + hex(at));
            if (command == 0xF4) requireEnvelope(modulationEnvelopes, operand, "modulation", at);
            if (command == 0xF5) requireEnvelope(psgEnvelopes, operand, "PSG", at);
        }
        fail(inRoutine ? "subroutine has no return before its data boundary at " + hex(end)
                : "track has no stop before its data boundary at " + hex(end));
    }

    private static int nextRoutine(TreeSet<Integer> targets, int position) {
        int target = targets.pollFirst();
        if (target != position) fail("call target is not a standalone subroutine at " + hex(target));
        return target;
    }

    private static void validateEnvelopes(Map<Integer, List<Integer>> envelopes, String type, int maximum, boolean compiledAssets) {
        if (envelopes == null || envelopes.size() > maximum) fail("invalid " + type + " envelope table");
        for (var entry : envelopes.entrySet()) {
            Integer index = entry.getKey();
            if (index == null || index < 1 || index > maximum) fail("invalid " + type + " envelope index " + index);
            if (compiledAssets) EnvelopeProgram.validateNativePlayback(type, entry.getValue());
            else EnvelopeProgram.validate(type, entry.getValue());
        }
    }

    private static void requireEnvelope(Map<Integer, List<Integer>> envelopes, int index, String kind, int at) {
        if (index != 0 && !envelopes.containsKey(index))
            fail("undefined " + kind + " envelope " + index + " at " + hex(at) + "; provide the complete asset package");
    }

    private static int le16(byte[] data, int at) { return u8(data, at) | u8(data, at + 1) << 8; }
    private static int u8(byte[] data, int at) { return data[at] & 255; }
    private static String hex(int value) { return String.format("0x%02X", value); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
    private record Track(int pointer, boolean dac, boolean psg) {}
}

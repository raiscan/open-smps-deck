package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.EnvelopeProgram;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import java.util.ArrayList;
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
        return validateAndCopy(project.song(), project.psgEnvelopes(), project.modulationEnvelopes(), project.samples());
    }

    static NativeBankAnalysis.Analysis inspectNative(CompiledProject project) {
        return NativeBankAnalysis.inspect(project);
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
            EnvelopeProgram.validate(envelope.type(), envelope.bytes());
            if (envelope.type().equals("PSG_VOLUME") && ++psgCount > 255) fail("at most 255 PSG envelopes can be addressed");
            if (envelope.type().equals("MODULATION") && ++modulationCount > 127) fail("at most 127 modulation envelopes can be addressed");
        }
    }

    static byte[] validateAndCopy(byte[] input, Map<Integer, List<Integer>> psgEnvelopes,
                                 Map<Integer, List<Integer>> modulationEnvelopes) {
        return validateAndCopy(input, psgEnvelopes, modulationEnvelopes, List.of());
    }

    private static byte[] validateAndCopy(byte[] input, Map<Integer, List<Integer>> psgEnvelopes,
                                          Map<Integer, List<Integer>> modulationEnvelopes, List<DacSample> samples) {
        DacSample.validateAll(samples);
        validateEnvelopes(psgEnvelopes, "PSG_VOLUME", 255);
        validateEnvelopes(modulationEnvelopes, "MODULATION", 127);
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
        if (track.dac) {
            scanDac(data, track.pointer, end, sampleCount);
            return;
        }
        int position = track.pointer;
        while (position < end) {
            int at = position, command = u8(data, position++);
            if (command == 0xF2 || command == 0xE3) return;
            if (command == 0) fail("zero-duration event is unsupported at " + hex(at));
            if (command < 0xE0) {
                if (!track.psg && command >= 0x81 && voices == 0) fail("FM notes require a local voice table");
                continue;
            }
            if (command == 0xE7 || command == 0xFA) continue; // HOLD / modulation off
            if (command == 0xF0) {
                if (end - position < 4) fail("truncated modulation operands at " + hex(at));
                position += 4; // Delay, rate, signed delta, steps: all are raw bytes.
                continue;
            }
            boolean both = command == 0xE1 || command == 0xE4 || command == 0xE8 || command == 0xED || command == 0xF4 || command == 0xFB;
            boolean fm = command == 0xE0 || command == 0xE6 || command == 0xEF;
            boolean psg = command == 0xEC || command == 0xF3 || command == 0xF5;
            if (!(both || (fm && !track.psg) || (psg && track.psg))) {
                fail("unsupported command " + hex(command) + " at " + hex(at)
                        + "; this renderer accepts finite linear tracks with resolved local resources");
            }
            if (position >= end) fail("truncated command operand at " + hex(at));
            int operand = u8(data, position++);
            if (command == 0xEF && operand >= voices) fail("undefined local FM voice " + operand + " at " + hex(at));
            if (command == 0xF4) requireEnvelope(modulationEnvelopes, operand, "modulation", at);
            if (command == 0xF5) requireEnvelope(psgEnvelopes, operand, "PSG", at);
        }
        fail("track has no stop before its data boundary at " + hex(end));
    }

    private static void scanDac(byte[] data, int position, int end, int sampleCount) {
        while (position < end) {
            int at = position, note = u8(data, position++);
            if (note == 0xF2 || note == 0xE3) return;
            if (note < 0x80 || note >= 0xE0)
                fail("unsupported DAC event " + hex(note) + " at " + hex(at)
                        + "; use explicit notes/rests and durations, without replay, HOLD or commands");
            if (note != 0x80 && note - DacSample.FIRST_NOTE >= sampleCount)
                fail("undefined DAC sample for note " + hex(note) + " at " + hex(at) + "; provide the complete asset package");
            if (position >= end) fail("truncated DAC duration at " + hex(at));
            int duration = u8(data, position++);
            if (duration < 1 || duration > 127) fail("DAC note/rest requires an explicit duration in 1..127 at " + hex(at));
        }
        fail("DAC track has no stop before its data boundary at " + hex(end));
    }

    private static void validateEnvelopes(Map<Integer, List<Integer>> envelopes, String type, int maximum) {
        if (envelopes == null || envelopes.size() > maximum) fail("invalid " + type + " envelope table");
        for (var entry : envelopes.entrySet()) {
            Integer index = entry.getKey();
            if (index == null || index < 1 || index > maximum) fail("invalid " + type + " envelope index " + index);
            EnvelopeProgram.validate(type, entry.getValue());
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

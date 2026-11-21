package com.opensmpsdeck.headless.rompatch;

import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.EnvelopeProgram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.rompatch.S3kRevision.*;
import static com.opensmpsdeck.headless.rompatch.SongLayout.*;

final class PatchAssets {
    record DacMapping(int note, int mappedNote, String sample, int rate,
                      int pointerOffset, int descriptorOffset, boolean exact, Dpcm.Metrics metrics) {}
    record EnvelopeMapping(int index, int mappedIndex, boolean exact) {}

    private PatchAssets() {}

    static List<DacMapping> dac(SongInput input, SongLayout layout, byte[] song, byte[] driver,
                                Placement placement, RomEdits edits, Map<Integer, Byte> ram) {
        int count = input.dac().size();
        if (count > FREE_DAC_NOTES.size())
            throw new IllegalArgumentException("The song needs %d DAC notes but only %d are unused in this revision. Reduce the number of drum samples."
                    .formatted(count, FREE_DAC_NOTES.size()));
        if (count == 0) {
            remapDac(layout, song, Map.of());
            return List.of();
        }
        byte[] deltas = Arrays.copyOfRange(driver, DAC_DELTAS, DAC_DELTAS + 16);
        var noteMap = new LinkedHashMap<Integer, Integer>();
        var pieces = new ArrayList<Span>();
        int byteCount = 0;
        for (var entry : input.dac().entrySet()) {
            int note = FREE_DAC_NOTES.get(noteMap.size());
            noteMap.put(entry.getKey(), note);
            // All pointer slots are reserved before packing payloads in the DAC bank.
            placement.reserve(dacPointer(note), 2);
            int end = byteCount + 5 + (entry.getValue().pcm().length + 1) / 2;
            pieces.add(new Span(byteCount, end));
            byteCount = end;
        }
        var packed = placement.packBank(DAC_BANK, pieces);
        if (packed == null)
            throw new IllegalArgumentException("DAC data needs %d bytes in bank 0x%02X. The descriptors and samples do not fit in its remaining free fragments. Shorten or reduce the drum samples."
                    .formatted(byteCount, DAC_BANK));

        var result = new ArrayList<DacMapping>();
        int index = 0;
        for (var entry : input.dac().entrySet()) {
            int sourceNote = entry.getKey(), note = noteMap.get(sourceNote);
            var sample = entry.getValue();
            byte[] encoded = Dpcm.encode(sample.pcm(), deltas);
            int payload = packed.address(pieces.get(index++).start());
            byte[] pointer = new byte[2], descriptor = new byte[5 + encoded.length];
            word(pointer, 0, WINDOW + payload % WINDOW);
            descriptor[0] = (byte) sample.rate();
            word(descriptor, 1, encoded.length);
            word(descriptor, 3, WINDOW + (payload + 5) % WINDOW);
            System.arraycopy(encoded, 0, descriptor, 5, encoded.length);
            edits.write(dacPointer(note), pointer, "DAC pointer");
            edits.write(payload, descriptor, "DAC descriptor and DPCM");
            ram.put(DAC_BANKS + note - 0x80, (byte) DAC_BANK);

            byte[] decoded = Dpcm.decode(encoded, deltas);
            result.add(new DacMapping(sourceNote, note, sample.id(), sample.rate(), dacPointer(note), payload,
                    Arrays.equals(sample.pcm(), decoded), Dpcm.compare(sample.pcm(), decoded)));
        }
        remapDac(layout, song, noteMap);
        return List.copyOf(result);
    }

    private static void remapDac(SongLayout layout, byte[] song, Map<Integer, Integer> notes) {
        for (int at : layout.dacNotes()) song[at] = (byte) remap(notes, u8(song, at), at, "DAC sample");
        for (int at : layout.dacCommands()) {
            int operand = u8(song, at);
            if (operand != 0)
                song[at] = (byte) (remap(notes, operand | 0x80, at, "DAC sample") & 0x7F);
        }
    }

    static List<EnvelopeMapping> envelopes(SongInput input, SongLayout layout, byte[] song, byte[] driver, Map<Integer, Byte> ram) throws IOException {
        int oldTable = word(driver, PSG_MASTER);
        if (oldTable != PSG_TABLE) throw new IOException("Unexpected original PSG pointer table");
        Map<Integer, byte[]> stock = new LinkedHashMap<>();
        for (int i = 1; i <= PSG_COUNT; i++) {
            int start = word(driver, oldTable + (i - 1) * 2);
            int end = i == PSG_COUNT ? MUSIC_POINTERS : word(driver, oldTable + i * 2);
            stock.put(i, Arrays.copyOfRange(driver, start, end));
        }
        var result = new ArrayList<EnvelopeMapping>();
        var available = new LinkedHashMap<>(stock);
        int dataSize = 0;
        var indices = new TreeSet<Integer>();
        for (int at : layout.envelopes()) if (u8(song, at) != 0) indices.add(u8(song, at));
        for (int index : indices) {
            var values = input.psg().get(index);
            if (values == null) throw new IllegalArgumentException("Missing PSG envelope " + index);
            EnvelopeProgram.validateNativePlayback(Envelope.PSG_VOLUME, values);
            byte[] envelope = bytes(values);
            int match = exact(available, envelope);
            if (match < 0 && (available.size() + 1) * 2 + dataSize + envelope.length <= PSG_FREE_END - PSG_FREE_START) {
                match = available.size() + 1;
                available.put(match, envelope);
                dataSize += envelope.length;
            }
            if (match >= 0) {
                result.add(new EnvelopeMapping(index, match, true));
            } else {
                int nearest = stock.entrySet().stream()
                        .min(Comparator.comparingLong(entry -> envelopeError(envelope, entry.getValue())))
                        .orElseThrow().getKey();
                result.add(new EnvelopeMapping(index, nearest, false));
            }
        }
        if (dataSize > 0) {
            int data = PSG_FREE_START + 2 * available.size();
            for (var entry : available.entrySet()) {
                int index = entry.getKey();
                int pointer = index <= PSG_COUNT ? word(driver, oldTable + (index - 1) * 2) : data;
                putWord(ram, PSG_FREE_START + (index - 1) * 2, pointer);
                if (index > PSG_COUNT) for (byte value : entry.getValue()) ram.put(data++, value);
            }
            putWord(ram, PSG_MASTER, PSG_FREE_START);
        }
        var mapping = new LinkedHashMap<Integer, Integer>();
        for (var entry : result) mapping.put(entry.index, entry.mappedIndex);
        mapping.put(0, 0);
        for (int at : layout.envelopes()) song[at] = (byte) remap(mapping, u8(song, at), at, "PSG envelope");
        remapModulation(input, layout, song, driver);
        return List.copyOf(result);
    }

    private static void remapModulation(SongInput input, SongLayout layout, byte[] song, byte[] driver) {
        if (layout.modulation().stream().allMatch(at -> u8(song, at) == 0)) return;
        Map<Integer, byte[]> stock = new LinkedHashMap<>();
        for (int slot = 1; slot <= MOD_COUNT; slot++) {
            int pointer = word(driver, MOD_TABLE + (slot - 1) * 2);
            stock.put(slot, modulationPlayback(driver, pointer));
        }
        for (int at : layout.modulation()) {
            int id = u8(song, at);
            if (id == 0) continue;
            var values = input.modulation().get(id);
            int match = values == null ? -1 : exact(stock, bytes(values));
            if (match <= 0) throw invalid(at, "modulation envelope has no exact retail entry");
            song[at] = (byte) match;
        }
    }

    private static byte[] modulationPlayback(byte[] driver, int pointer) {
        // The retail 82/84 commands take their operand from code at cursor+1.
        // Match the playback view used by the pinned loader, without loading
        // or examining any retail music or SFX table.
        byte[] playback = Arrays.copyOfRange(driver, pointer, pointer + 256);
        var visited = new BitSet(256);
        int position = 0, end = 0;
        while (!visited.get(position)) {
            visited.set(position);
            end = Math.max(end, position + 1);
            int command = u8(playback, position);
            if (command == 0x81 || command == 0x83) break;
            if (command == 0x80) break;
            if (command == 0x82 || command == 0x84) {
                playback[position + 1] = driver[position + 1];
                end = Math.max(end, position + 2);
                position = command == 0x82 ? u8(playback, position + 1) : (position + 2) & 255;
            } else {
                position = (position + 1) & 255;
            }
        }
        return Arrays.copyOf(playback, end);
    }

    private static long envelopeError(byte[] first, byte[] second) {
        int[] a = contour(first), b = contour(second);
        long error = 0;
        for (int i = 0; i < a.length; i++) error += (a[i] - b[i]) * (a[i] - b[i]);
        return error;
    }

    private static int[] contour(byte[] envelope) {
        int[] result = new int[256];
        int pc = 0, value = 0;
        for (int tick = 0; tick < result.length; tick++) {
            if (pc < envelope.length && u8(envelope, pc) == 0x80) pc = 0;
            int step = pc < envelope.length ? u8(envelope, pc) : 0x83;
            if (step < 0x80) {
                value = Math.min(step, 15);
                pc++;
            } else if (step == 0x83 && pc < envelope.length) {
                value = 15;
            }
            result[tick] = value;
        }
        return result;
    }

    private static int exact(Map<Integer, byte[]> options, byte[] data) {
        return options.entrySet().stream().filter(entry -> Arrays.equals(entry.getValue(), data))
                .mapToInt(Map.Entry::getKey).min().orElse(-1);
    }

    private static byte[] bytes(List<Integer> values) {
        byte[] data = new byte[values.size()];
        for (int i = 0; i < data.length; i++) data[i] = values.get(i).byteValue();
        return data;
    }

    private static int remap(Map<Integer, Integer> map, int old, int at, String label) {
        var value = map.get(old);
        if (value == null) throw invalid(at, "missing " + label + " 0x%02X".formatted(old));
        return value;
    }

    static void putWord(Map<Integer, Byte> ram, int at, int value) {
        ram.put(at, (byte) value);
        ram.put(at + 1, (byte) (value >>> 8));
    }
}

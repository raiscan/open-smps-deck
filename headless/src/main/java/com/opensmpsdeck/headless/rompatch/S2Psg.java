package com.opensmpsdeck.headless.rompatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.rompatch.S2Revision.*;
import static com.opensmpsdeck.headless.rompatch.SongLayout.word;

final class S2Psg {
    record Binding(int index, int mappedIndex, boolean stock, boolean exact, double rmsAttenuationError) {}
    record Plan(byte[] relocatedTable, List<Binding> bindings) {}

    private S2Psg() {}

    static Plan map(byte[] driver, byte[] bank, Set<Integer> offsets, Map<Integer, byte[]> available) {
        var used = new TreeMap<Integer, byte[]>();
        for (int at : offsets) {
            int index = u8(bank, at);
            if (index == 0) continue;
            byte[] envelope = available.get(index);
            if (envelope == null) throw new IllegalArgumentException("Undefined S2 PSG envelope " + index);
            used.put(index, envelope);
        }
        if (used.isEmpty()) return new Plan(new byte[0], List.of());

        byte[][] stock = stock(driver);
        var custom = new ArrayList<byte[]>();
        var bindings = new TreeMap<Integer, Binding>();
        int tableSize = 2 * PSG_COUNT;
        for (var entry : used.entrySet()) {
            int index = entry.getKey();
            byte[] envelope = entry.getValue();
            if (envelope.length < 2 || envelope.length > 256 || envelopeEnd(envelope, 0) != envelope.length - 1)
                throw new IllegalArgumentException("S2 PSG envelope " + index + " needs attenuation bytes and a final 80 hold");
            int match = find(stock, envelope);
            boolean exact = true;
            double error = 0;
            if (match == -1) {
                int installed = find(custom.toArray(byte[][]::new), envelope);
                if (installed != -1) {
                    match = PSG_COUNT + installed;
                } else if (tableSize + 2 + envelope.length <= BUFFER_START - PSG_RELOCATED) {
                    custom.add(envelope);
                    tableSize += 2 + envelope.length;
                    match = PSG_COUNT + custom.size();
                } else {
                    match = 1;
                    error = Double.POSITIVE_INFINITY;
                    for (int i = 0; i < stock.length; i++) {
                        double candidate = rms(envelope, stock[i]);
                        if (candidate < error) {
                            match = i + 1;
                            error = candidate;
                        }
                    }
                    exact = false;
                }
            }
            bindings.put(index, new Binding(index, match, match <= PSG_COUNT, exact, error));
        }
        for (int at : offsets) {
            int source = u8(bank, at);
            if (source != 0) bank[at] = (byte) bindings.get(source).mappedIndex();
        }
        byte[] table = new byte[custom.isEmpty() ? 0 : tableSize];
        if (!custom.isEmpty()) {
            System.arraycopy(driver, PSG_TABLE, table, 0, PSG_COUNT * 2);
            int next = 2 * (PSG_COUNT + custom.size());
            for (int i = 0; i < custom.size(); i++) {
                byte[] envelope = custom.get(i);
                word(table, 2 * (PSG_COUNT + i), PSG_RELOCATED + next);
                System.arraycopy(envelope, 0, table, next, envelope.length);
                next += envelope.length;
            }
        }
        return new Plan(table, List.copyOf(bindings.values()));
    }

    private static byte[][] stock(byte[] driver) {
        byte[][] stock = new byte[PSG_COUNT][];
        for (int i = 0; i < stock.length; i++) {
            int start = word(driver, PSG_TABLE + i * 2);
            int end = envelopeEnd(driver, start);
            if (start < PSG_TABLE + PSG_COUNT * 2 || end <= start || end >= PLAYLIST || end - start >= 256)
                throw new IllegalArgumentException("Invalid Sonic 2 stock PSG envelope " + (i + 1));
            stock[i] = Arrays.copyOfRange(driver, start, end + 1);
        }
        return stock;
    }

    private static int envelopeEnd(byte[] bytes, int start) {
        int end = start;
        while (end < bytes.length && u8(bytes, end) < 0x80) end++;
        return end < bytes.length && u8(bytes, end) == 0x80 ? end : -1;
    }

    private static int find(byte[][] choices, byte[] envelope) {
        for (int i = 0; i < choices.length; i++) if (Arrays.equals(choices[i], envelope)) return i + 1;
        return -1;
    }

    private static double rms(byte[] first, byte[] second) {
        int squares = 0;
        for (int tick = 0; tick < 256; tick++) {
            int a = Math.min(15, u8(first, Math.min(tick, first.length - 2)));
            int b = Math.min(15, u8(second, Math.min(tick, second.length - 2)));
            squares += (a - b) * (a - b);
        }
        return Math.sqrt(squares / 256.0);
    }
}

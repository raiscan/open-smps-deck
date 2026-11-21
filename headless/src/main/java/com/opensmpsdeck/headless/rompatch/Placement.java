package com.opensmpsdeck.headless.rompatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.rompatch.SongLayout.*;

final class Placement {
    record Piece(Span source, int romOffset) {}
    record Song(int bank, List<Piece> pieces) {
        int address(int offset) {
            for (var piece : pieces)
                if (piece.source.contains(offset)) return piece.romOffset + offset - piece.source.start();
            throw invalid(offset, "relocation points outside placed data");
        }

        int pointer(int offset) { return WINDOW + address(offset) % WINDOW; }

        byte[] relocate(byte[] source, int base, Iterable<Integer> operands) {
            byte[] relocated = source.clone();
            for (int operand : operands)
                word(relocated, operand, pointer(word(source, operand) - base));
            return relocated;
        }
    }

    private final List<Span> free;

    Placement(List<Span> free) {
        this.free = new ArrayList<>(free);
    }

    int capacity() {
        return byBank().values().stream().mapToInt(Placement::capacity).max().orElse(0);
    }

    Song song(List<Span> pieces, int compiledSize) {
        var banks = byBank().entrySet().stream()
                .sorted(Comparator.comparingInt(bank -> capacity(bank.getValue()))).toList();
        for (var bank : banks) {
            var packed = packBank(bank.getKey(), pieces);
            if (packed != null) return packed;
        }
        throw oversized(compiledSize, capacity());
    }

    Song packBank(int bank, List<Span> pieces) {
        var gaps = byBank().getOrDefault(bank, List.of());
        if (capacity(gaps) < capacity(pieces)) return null;
        var sorted = pieces.stream().sorted(Comparator.comparingInt(Span::size).reversed()).toList();
        var placed = new ArrayList<Piece>();
        if (gaps.size() == 1) {
            int destination = gaps.getFirst().start();
            for (var piece : sorted) {
                placed.add(new Piece(piece, destination));
                destination += piece.size();
            }
        } else if (!fit(sorted, gaps.toArray(Span[]::new), placed)) {
            return null;
        }
        for (var piece : placed) reserve(piece.romOffset, piece.source.size());
        return new Song(bank, List.copyOf(placed));
    }

    static IllegalArgumentException oversized(int compiledSize, int capacity) {
        return new IllegalArgumentException("Compiled song size %d bytes; largest placeable bank capacity %d bytes "
                .formatted(compiledSize, capacity)
                + "(32 KB / 32768 bytes per-song limit). Shorten the song or reduce layering.");
    }

    private Map<Integer, List<Span>> byBank() {
        var banks = new TreeMap<Integer, List<Span>>();
        for (var span : free) banks.computeIfAbsent(span.start() / WINDOW, ignored -> new ArrayList<>()).add(span);
        return banks;
    }

    private static int capacity(List<Span> spans) {
        return spans.stream().mapToInt(Span::size).sum();
    }

    private static boolean fit(List<Span> pieces, Span[] gaps, List<Piece> result) {
        int index = result.size();
        if (index == pieces.size()) return true;
        var piece = pieces.get(index);
        // Try best fits first and backtrack if a later piece cannot fit.
        var choices = new ArrayList<Integer>();
        for (int i = 0; i < gaps.length; i++) if (gaps[i].size() >= piece.size()) choices.add(i);
        choices.sort(Comparator.comparingInt(i -> gaps[i].size()));
        int lastSize = -1;
        for (int i : choices) {
            var gap = gaps[i];
            if (gap.size() == lastSize) continue;
            lastSize = gap.size();
            result.add(new Piece(piece, gap.start()));
            gaps[i] = new Span(gap.start() + piece.size(), gap.end());
            if (fit(pieces, gaps, result)) return true;
            result.removeLast();
            gaps[i] = gap;
        }
        return false;
    }

    int findAligned(int size) {
        int best = -1, bestRemaining = Integer.MAX_VALUE;
        for (var gap : free) {
            int start = (gap.start() + 1) & -2;
            int remaining = gap.end() - start - size;
            if (remaining >= 0 && remaining < bestRemaining) {
                best = start;
                bestRemaining = remaining;
            }
        }
        return best;
    }

    void reserve(int start, int size) {
        for (int i = 0; i < free.size(); i++) {
            var gap = free.get(i);
            int end = start + size;
            if (gap.start() <= start && end <= gap.end()) {
                free.remove(i);
                if (end < gap.end()) free.add(i, new Span(end, gap.end()));
                if (start > gap.start()) free.add(i, new Span(gap.start(), start));
                return;
            }
        }
        throw new IllegalArgumentException("Patch allocation overlaps occupied bytes at ROM 0x%06X".formatted(start));
    }

}

package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HierarchyDecompiler {

    private HierarchyDecompiler() {}

    public record DecompileResult(
            List<Phrase> phrases,
            List<ChainEntry> chainEntries,
            boolean hasLoopPoint,
            int loopEntryIndex,
            int sharedPhraseCount) {}

    public static DecompileResult decompileTrack(byte[] track, ChannelType type) {
        return decompileTrack(track, type, SmpsCoordFlags.Dialect.S2);
    }

    /**
     * Decompile a track using the coordination flag dialect of the song's
     * driver. Flag sizes, the return flag (E3 vs S3K's F9), and track-end
     * flags differ per dialect.
     */
    public static DecompileResult decompileTrack(byte[] track, ChannelType type,
            SmpsCoordFlags.Dialect dialect) {
        PhraseLibrary library = new PhraseLibrary();
        List<ChainEntry> chainEntries = new ArrayList<>();

        // Pass 1: Find subroutines (CALL targets → RETURN)
        Map<Integer, Phrase> subroutines = new LinkedHashMap<>();
        findSubroutines(track, type, library, subroutines, dialect);

        // Pre-pass: the channel loop's JUMP target and every F7 loop target
        // must become chain entry boundaries, otherwise loops snap to the
        // nearest phrase start and playback drifts after the first iteration.
        int loopBoundary = findChannelLoopTarget(track, dialect);
        java.util.TreeSet<Integer> splitBoundaries = collectLoopTargets(track, dialect);
        if (loopBoundary >= 0) {
            splitBoundaries.add(loopBoundary);
        }

        // Pass 2: Linear scan of main stream, splitting at structural boundaries
        int pos = 0;
        boolean hasLoopPoint = false;
        int loopEntryIndex = -1;
        int jumpTarget = -1;
        boolean done = false;

        int gotoGuard = 0;
        boolean[] visitedScan = new boolean[track.length];

        // Collect segments between structural commands
        List<int[]> segments = new ArrayList<>(); // [start, end] pairs
        List<Integer> entryStartOffsets = new ArrayList<>(); // byte offset per chain entry
        int segStart = 0;

        while (pos < track.length && !done) {
            visitedScan[pos] = true;
            int b = track[pos] & 0xFF;

            // Force phrase boundaries exactly at loop targets (channel F6 and
            // backward F7). Rips may loop to a position the linear instruction
            // stream never lands on (mid-instruction reparse) — split at the
            // exact byte when the scan crosses it, byte-faithfully.
            for (Integer b2;
                 (b2 = splitBoundaries.higher(segStart)) != null && b2 <= pos; ) {
                segments.add(new int[]{segStart, b2});
                flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
                segments.clear();
                segStart = b2;
                splitBoundaries.remove(b2);
            }

            if (b == SmpsCoordFlags.CALL && pos + 2 < track.length) {
                // Save any preceding data as a segment
                if (pos > segStart) {
                    segments.add(new int[]{segStart, pos});
                }
                int target = (track[pos + 1] & 0xFF) | ((track[pos + 2] & 0xFF) << 8);
                Phrase subPhrase = subroutines.get(target);
                if (subPhrase != null) {
                    // Flush preceding segments as a phrase
                    flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
                    segments.clear();
                    entryStartOffsets.add(pos);
                    chainEntries.add(new ChainEntry(subPhrase.getId()));
                }
                pos += 3;
                segStart = pos;
            } else if (b == SmpsCoordFlags.LOOP && pos + 4 < track.length) {
                // LOOP format: F7 <index> <count> <ptr_lo> <ptr_hi>
                int count = track[pos + 2] & 0xFF; // repeat count (pos+1 is loop counter index)
                int loopTarget = (track[pos + 3] & 0xFF) | ((track[pos + 4] & 0xFF) << 8);

                // The loop wraps the data from loopTarget to pos
                if (loopTarget >= segStart && loopTarget <= pos) {
                    // Loop body is within the current segment range
                    // Flush any preceding non-loop data
                    if (loopTarget > segStart) {
                        segments.add(new int[]{segStart, loopTarget});
                        flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
                        segments.clear();
                    }
                    // The looped body
                    byte[] body = copyRegionFlattened(track, loopTarget, pos, dialect, 0);
                    if (body.length > 0) {
                        Phrase loopPhrase = library.createPhrase("Loop", type);
                        loopPhrase.setData(body);
                        entryStartOffsets.add(loopTarget);
                        ChainEntry entry = new ChainEntry(loopPhrase.getId());
                        entry.setRepeatCount(Math.max(2, count));
                        chainEntries.add(entry);
                    }
                } else if (loopTarget < segStart && count > 1) {
                    // Loop spans previously-flushed chain entries (e.g., wraps CALLs).
                    // Flush any pending data before the loop end.
                    if (pos > segStart) {
                        segments.add(new int[]{segStart, pos});
                        flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
                        segments.clear();
                    }
                    // Find the first chain entry whose start offset >= loopTarget
                    int firstLoopEntry = findEntryForOffset(loopTarget, entryStartOffsets);
                    if (firstLoopEntry >= 0 && firstLoopEntry < chainEntries.size()) {
                        // The entries from firstLoopEntry to end are the loop body (1st iteration).
                        // Duplicate them for the remaining (count-1) iterations.
                        int bodyEnd = chainEntries.size();
                        List<ChainEntry> loopBody = new ArrayList<>(
                            chainEntries.subList(firstLoopEntry, bodyEnd));
                        for (int rep = 1; rep < count; rep++) {
                            for (ChainEntry orig : loopBody) {
                                ChainEntry dup = new ChainEntry(orig.getPhraseId());
                                dup.setTransposeSemitones(orig.getTransposeSemitones());
                                dup.setRepeatCount(orig.getRepeatCount());
                                chainEntries.add(dup);
                                entryStartOffsets.add(entryStartOffsets.get(firstLoopEntry));
                            }
                        }
                    }
                }
                pos += 5;
                segStart = pos;
            } else if (b == SmpsCoordFlags.JUMP && pos + 2 < track.length) {
                // Flush remaining data before jump
                if (pos > segStart) {
                    segments.add(new int[]{segStart, pos});
                }
                flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
                segments.clear();

                int target = (track[pos + 1] & 0xFF) | ((track[pos + 2] & 0xFF) << 8);
                if (target >= 0 && target < track.length && !visitedScan[target]
                        && gotoGuard < MAX_GOTOS) {
                    // Mid-stream goto to an unvisited position (e.g. a channel
                    // jumping into another channel's shared stream): continue
                    // scanning at the target
                    gotoGuard++;
                    pos = target;
                    segStart = pos;
                } else {
                    // Back-edge to already-scanned bytes: the channel loop
                    jumpTarget = target;
                    hasLoopPoint = true;
                    done = true;
                    pos += 3;
                    segStart = pos;
                }
            } else if (SmpsCoordFlags.isTrackEnd(b, dialect)) {
                if (pos > segStart) {
                    segments.add(new int[]{segStart, pos});
                }
                flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
                segments.clear();
                done = true;
                pos++;
                segStart = pos;
            } else if (SmpsCoordFlags.isReturn(b, dialect)) {
                // Entered subroutine area, stop main scan
                if (pos > segStart) {
                    segments.add(new int[]{segStart, pos});
                }
                flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
                segments.clear();
                done = true;
                pos++;
                segStart = pos;
            } else if (b >= 0xE0) {
                pos += 1 + flagBytes(track, pos, b, dialect);
            } else {
                pos++;
            }
        }

        // Flush any remaining data if we ran off the end without hitting STOP/JUMP
        if (!done && !segments.isEmpty()) {
            flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
        }

        // Resolve loop target to chain entry index
        if (hasLoopPoint && jumpTarget >= 0) {
            loopEntryIndex = resolveLoopEntryIndex(jumpTarget, entryStartOffsets);
        }

        // If no chain entries were created, create one from the whole track
        int mainEnd = findMainEnd(track, dialect);
        if (chainEntries.isEmpty() && mainEnd > 0) {
            byte[] data = Arrays.copyOf(track, mainEnd);
            Phrase phrase = library.createPhrase("Track", type);
            phrase.setData(data);
            chainEntries.add(new ChainEntry(phrase.getId()));
        }

        return new DecompileResult(
            library.getAllPhrases(),
            chainEntries,
            hasLoopPoint,
            loopEntryIndex,
            subroutines.size()
        );
    }

    private static void findSubroutines(byte[] track, ChannelType type,
            PhraseLibrary library, Map<Integer, Phrase> subroutines,
            SmpsCoordFlags.Dialect dialect) {
        // Scan for CALL targets and extract subroutine bodies
        List<Integer> callTargets = new ArrayList<>();
        int pos = 0;
        while (pos < track.length) {
            int b = track[pos] & 0xFF;
            if (b == SmpsCoordFlags.CALL && pos + 2 < track.length) {
                int target = (track[pos + 1] & 0xFF) | ((track[pos + 2] & 0xFF) << 8);
                if (!callTargets.contains(target)) {
                    callTargets.add(target);
                }
                pos += 3;
            } else if (b >= 0xE0) {
                pos += 1 + flagBytes(track, pos, b, dialect);
            } else {
                pos++;
            }
        }

        // For each call target, extract bytes until RETURN
        for (int target : callTargets) {
            if (target < 0 || target >= track.length) continue;
            byte[] body = extractSubBody(track, target, dialect, 0);
            if (body.length > 0) {
                Phrase phrase = library.createPhrase("Sub", type);
                phrase.setData(body);
                subroutines.put(target, phrase);
            }
        }
    }

    /**
     * Extract a subroutine body up to its RETURN flag. The body is copied
     * through {@link #copyRegionFlattened}, which inlines nested F8 CALLs and
     * unrolls embedded backward F7 loops — both carry track-local pointers
     * that would go stale once the phrase is recompiled at a new position.
     */
    private static byte[] extractSubBody(byte[] track, int target,
            SmpsCoordFlags.Dialect dialect, int depth) {
        int subEnd = target;
        while (subEnd < track.length) {
            int b = track[subEnd] & 0xFF;
            if (SmpsCoordFlags.isReturn(b, dialect)) {
                break;
            }
            int len = (b >= 0xE0) ? 1 + flagBytes(track, subEnd, b, dialect) : 1;
            subEnd += Math.max(1, Math.min(len, track.length - subEnd));
        }
        return copyRegionFlattened(track, target, subEnd, dialect, depth);
    }

    /** Largest F7 repeat count that gets unrolled when found inside a phrase body. */
    private static final int MAX_UNROLL_COUNT = 0x20;

    /** Safety bound on mid-stream F6 gotos followed during the main scan. */
    private static final int MAX_GOTOS = 64;

    /**
     * Copy a track region into self-contained phrase bytes: nested F8 CALLs
     * are inlined and backward F7 loops whose target lies within the region
     * are unrolled (which also reproduces per-iteration effects such as an
     * additive transpose inside the loop body). Pointers that cannot be made
     * self-contained are copied as-is.
     */
    private static byte[] copyRegionFlattened(byte[] track, int from, int to,
            SmpsCoordFlags.Dialect dialect, int depth) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int pos = from;
        while (pos < to && pos < track.length) {
            int b = track[pos] & 0xFF;
            if (b == SmpsCoordFlags.CALL && pos + 2 < track.length && depth < 4) {
                int nested = (track[pos + 1] & 0xFF) | ((track[pos + 2] & 0xFF) << 8);
                if (nested >= 0 && nested < track.length) {
                    byte[] inner = extractSubBody(track, nested, dialect, depth + 1);
                    out.write(inner, 0, inner.length);
                    pos += 3;
                    continue;
                }
            }
            if (b == SmpsCoordFlags.LOOP && pos + 4 < track.length && depth < 4) {
                int count = track[pos + 2] & 0xFF;
                int target = (track[pos + 3] & 0xFF) | ((track[pos + 4] & 0xFF) << 8);
                if (target >= from && target < pos && count >= 1 && count <= MAX_UNROLL_COUNT) {
                    // The first pass over [target..pos) is already in the output;
                    // emit the remaining (count - 1) iterations.
                    for (int rep = 1; rep < count; rep++) {
                        byte[] again = copyRegionFlattened(track, target, pos, dialect, depth + 1);
                        out.write(again, 0, again.length);
                    }
                    pos += 5;
                    continue;
                }
            }
            int len = (b >= 0xE0) ? 1 + flagBytes(track, pos, b, dialect) : 1;
            len = Math.max(1, Math.min(len, track.length - pos));
            out.write(track, pos, len);
            pos += len;
        }
        return out.toByteArray();
    }

    private static int findMainEnd(byte[] track, SmpsCoordFlags.Dialect dialect) {
        int pos = 0;
        while (pos < track.length) {
            int b = track[pos] & 0xFF;
            if (SmpsCoordFlags.isTrackEnd(b, dialect) || b == SmpsCoordFlags.JUMP) {
                return pos;
            } else if (SmpsCoordFlags.isReturn(b, dialect)) {
                // Entered subroutine area
                return pos;
            } else if (b >= 0xE0) {
                pos += 1 + flagBytes(track, pos, b, dialect);
            } else {
                pos++;
            }
        }
        return track.length;
    }

    /**
     * Walk the main stream and return the channel loop's JUMP target
     * (track-local offset), or -1 when the track does not loop.
     *
     * <p>Mid-stream F6 jumps to unvisited positions are followed as gotos
     * (channels can share another channel's stream); the channel loop is the
     * first back-edge — a jump to an already-visited position.
     */
    private static int findChannelLoopTarget(byte[] track, SmpsCoordFlags.Dialect dialect) {
        boolean[] visited = new boolean[track.length];
        int gotos = 0;
        int pos = 0;
        while (pos >= 0 && pos < track.length) {
            if (visited[pos]) {
                return pos; // ran back into visited bytes without a jump
            }
            visited[pos] = true;
            int b = track[pos] & 0xFF;
            if (b == SmpsCoordFlags.JUMP && pos + 2 < track.length) {
                int target = (track[pos + 1] & 0xFF) | ((track[pos + 2] & 0xFF) << 8);
                if (target < 0 || target >= track.length) return -1;
                if (visited[target] || gotos >= MAX_GOTOS) {
                    return target; // back-edge: the channel loop
                }
                gotos++;
                pos = target;
                continue;
            }
            if (SmpsCoordFlags.isTrackEnd(b, dialect) || SmpsCoordFlags.isReturn(b, dialect)) {
                return -1;
            }
            if (b >= 0xE0) {
                int len = 1 + flagBytes(track, pos, b, dialect);
                for (int i = 1; i < len && pos + i < track.length; i++) {
                    visited[pos + i] = true;
                }
                pos += len;
            } else {
                pos++;
            }
        }
        return -1;
    }

    /**
     * Collect every backward F7 loop target reachable in the main stream
     * (following mid-stream F6 gotos), so each can become an exact phrase
     * boundary before the main scan runs.
     */
    private static java.util.TreeSet<Integer> collectLoopTargets(byte[] track,
            SmpsCoordFlags.Dialect dialect) {
        java.util.TreeSet<Integer> targets = new java.util.TreeSet<>();
        boolean[] visited = new boolean[track.length];
        int gotos = 0;
        int pos = 0;
        while (pos >= 0 && pos < track.length && !visited[pos]) {
            visited[pos] = true;
            int b = track[pos] & 0xFF;
            if (b == SmpsCoordFlags.LOOP && pos + 4 < track.length) {
                int target = (track[pos + 3] & 0xFF) | ((track[pos + 4] & 0xFF) << 8);
                if (target >= 0 && target < pos) {
                    targets.add(target);
                }
                pos += 5;
                continue;
            }
            if (b == SmpsCoordFlags.JUMP && pos + 2 < track.length) {
                int target = (track[pos + 1] & 0xFF) | ((track[pos + 2] & 0xFF) << 8);
                if (target < 0 || target >= track.length || visited[target]
                        || gotos >= MAX_GOTOS) {
                    break; // back-edge: channel loop, handled separately
                }
                gotos++;
                pos = target;
                continue;
            }
            if (SmpsCoordFlags.isTrackEnd(b, dialect) || SmpsCoordFlags.isReturn(b, dialect)) {
                break;
            }
            pos += (b >= 0xE0) ? 1 + flagBytes(track, pos, b, dialect) : 1;
        }
        return targets;
    }

    /**
     * Total parameter bytes for the flag at {@code pos} in the dialect
     * (the S3K FF meta prefix includes its sub-command's parameters).
     */
    private static int flagBytes(byte[] track, int pos, int cmd, SmpsCoordFlags.Dialect dialect) {
        int params = SmpsCoordFlags.getParamCount(cmd, dialect);
        if (dialect == SmpsCoordFlags.Dialect.S3K
                && cmd == SmpsCoordFlags.S3K_META && pos + 1 < track.length) {
            params += SmpsCoordFlags.getMetaParamCount(track[pos + 1] & 0xFF);
        }
        return params;
    }

    private static void flushSegmentsWithOffsets(List<int[]> segments, byte[] track,
            ChannelType type, PhraseLibrary library, List<ChainEntry> chainEntries,
            List<Integer> entryStartOffsets) {
        if (segments.isEmpty()) return;

        // Combine all segments into a single phrase
        int totalLen = 0;
        for (int[] seg : segments) {
            totalLen += seg[1] - seg[0];
        }
        if (totalLen == 0) return;

        byte[] combined = new byte[totalLen];
        int offset = 0;
        for (int[] seg : segments) {
            int len = seg[1] - seg[0];
            System.arraycopy(track, seg[0], combined, offset, len);
            offset += len;
        }

        entryStartOffsets.add(segments.getFirst()[0]);
        Phrase phrase = library.createPhrase("Phrase", type);
        phrase.setData(combined);
        chainEntries.add(new ChainEntry(phrase.getId()));
    }

    /**
     * Find the first chain entry whose start offset is >= the given target.
     */
    private static int findEntryForOffset(int target, List<Integer> entryStartOffsets) {
        // Exact match first
        for (int i = 0; i < entryStartOffsets.size(); i++) {
            if (entryStartOffsets.get(i) == target) {
                return i;
            }
        }
        // Closest entry at or after target
        int bestIndex = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < entryStartOffsets.size(); i++) {
            int offset = entryStartOffsets.get(i);
            if (offset >= target) {
                int dist = offset - target;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIndex = i;
                }
            }
        }
        return bestIndex;
    }

    /**
     * Find the chain entry index whose source byte offset best matches the JUMP target.
     */
    private static int resolveLoopEntryIndex(int jumpTarget, List<Integer> entryStartOffsets) {
        if (entryStartOffsets.isEmpty()) return 0;

        // Exact match
        for (int i = 0; i < entryStartOffsets.size(); i++) {
            if (entryStartOffsets.get(i) == jumpTarget) {
                return i;
            }
        }

        // Closest match (JUMP target may point slightly before or into an entry)
        int bestIndex = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < entryStartOffsets.size(); i++) {
            int dist = Math.abs(entryStartOffsets.get(i) - jumpTarget);
            if (dist < bestDist) {
                bestDist = dist;
                bestIndex = i;
            }
        }
        return bestIndex;
    }
}

package com.opensmps.deck.codec;

import com.opensmps.deck.model.*;
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
        PhraseLibrary library = new PhraseLibrary();
        List<ChainEntry> chainEntries = new ArrayList<>();

        // Pass 1: Find subroutines (CALL targets → RETURN)
        Map<Integer, Phrase> subroutines = new LinkedHashMap<>();
        findSubroutines(track, type, library, subroutines);

        // Pass 2: Linear scan of main stream, splitting at structural boundaries
        int pos = 0;
        boolean hasLoopPoint = false;
        int loopEntryIndex = -1;
        int jumpTarget = -1;
        boolean done = false;

        // Collect segments between structural commands
        List<int[]> segments = new ArrayList<>(); // [start, end] pairs
        List<Integer> entryStartOffsets = new ArrayList<>(); // byte offset per chain entry
        int segStart = 0;

        while (pos < track.length && !done) {
            int b = track[pos] & 0xFF;

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
                    byte[] body = Arrays.copyOfRange(track, loopTarget, pos);
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

                jumpTarget = (track[pos + 1] & 0xFF) | ((track[pos + 2] & 0xFF) << 8);
                hasLoopPoint = true;
                done = true;
                pos += 3;
                segStart = pos;
            } else if (b == SmpsCoordFlags.STOP) {
                if (pos > segStart) {
                    segments.add(new int[]{segStart, pos});
                }
                flushSegmentsWithOffsets(segments, track, type, library, chainEntries, entryStartOffsets);
                segments.clear();
                done = true;
                pos++;
                segStart = pos;
            } else if (b == SmpsCoordFlags.RETURN) {
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
                pos += 1 + SmpsCoordFlags.getParamCount(b);
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
        int mainEnd = findMainEnd(track);
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
            PhraseLibrary library, Map<Integer, Phrase> subroutines) {
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
                pos += 1 + SmpsCoordFlags.getParamCount(b);
            } else {
                pos++;
            }
        }

        // For each call target, extract bytes until RETURN
        for (int target : callTargets) {
            if (target < 0 || target >= track.length) continue;
            int subEnd = target;
            while (subEnd < track.length) {
                int b = track[subEnd] & 0xFF;
                if (b == SmpsCoordFlags.RETURN) {
                    break;
                } else if (b >= 0xE0) {
                    subEnd += 1 + SmpsCoordFlags.getParamCount(b);
                } else {
                    subEnd++;
                }
            }
            if (subEnd > target) {
                byte[] body = Arrays.copyOfRange(track, target, subEnd);
                Phrase phrase = library.createPhrase("Sub", type);
                phrase.setData(body);
                subroutines.put(target, phrase);
            }
        }
    }

    private static int findMainEnd(byte[] track) {
        int pos = 0;
        while (pos < track.length) {
            int b = track[pos] & 0xFF;
            if (b == SmpsCoordFlags.STOP || b == SmpsCoordFlags.JUMP) {
                return pos;
            } else if (b == SmpsCoordFlags.RETURN) {
                // Entered subroutine area
                return pos;
            } else if (b >= 0xE0) {
                pos += 1 + SmpsCoordFlags.getParamCount(b);
            } else {
                pos++;
            }
        }
        return track.length;
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

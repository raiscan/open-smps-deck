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
                    flushSegments(segments, track, type, library, chainEntries);
                    segments.clear();
                    chainEntries.add(new ChainEntry(subPhrase.getId()));
                }
                pos += 3;
                segStart = pos;
            } else if (b == SmpsCoordFlags.LOOP && pos + 4 < track.length) {
                int count = track[pos + 1] & 0xFF;
                int loopTarget = (track[pos + 3] & 0xFF) | ((track[pos + 4] & 0xFF) << 8);

                // The loop wraps the data from loopTarget to pos
                // Flush any preceding non-loop data
                if (loopTarget >= segStart && loopTarget <= pos) {
                    if (loopTarget > segStart) {
                        segments.add(new int[]{segStart, loopTarget});
                        flushSegments(segments, track, type, library, chainEntries);
                        segments.clear();
                    }
                    // The looped body
                    byte[] body = Arrays.copyOfRange(track, loopTarget, pos);
                    if (body.length > 0) {
                        Phrase loopPhrase = library.createPhrase("Loop", type);
                        loopPhrase.setData(body);
                        ChainEntry entry = new ChainEntry(loopPhrase.getId());
                        entry.setRepeatCount(Math.max(2, count));
                        chainEntries.add(entry);
                    }
                }
                pos += 5;
                segStart = pos;
            } else if (b == SmpsCoordFlags.JUMP && pos + 2 < track.length) {
                // Flush remaining data before jump
                if (pos > segStart) {
                    segments.add(new int[]{segStart, pos});
                }
                flushSegments(segments, track, type, library, chainEntries);
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
                flushSegments(segments, track, type, library, chainEntries);
                segments.clear();
                done = true;
                pos++;
                segStart = pos;
            } else if (b == SmpsCoordFlags.RETURN) {
                // Entered subroutine area, stop main scan
                if (pos > segStart) {
                    segments.add(new int[]{segStart, pos});
                }
                flushSegments(segments, track, type, library, chainEntries);
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
            flushSegments(segments, track, type, library, chainEntries);
        }

        // Resolve loop target to chain entry index
        if (hasLoopPoint && jumpTarget >= 0) {
            loopEntryIndex = resolveLoopEntryIndex(jumpTarget, chainEntries, library);
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

    private static void flushSegments(List<int[]> segments, byte[] track,
            ChannelType type, PhraseLibrary library, List<ChainEntry> chainEntries) {
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

        Phrase phrase = library.createPhrase("Phrase", type);
        phrase.setData(combined);
        chainEntries.add(new ChainEntry(phrase.getId()));
    }

    private static int resolveLoopEntryIndex(int jumpTarget,
            List<ChainEntry> entries, PhraseLibrary library) {
        // For simple cases, the jump target is offset 0 → entry 0
        if (jumpTarget == 0 && !entries.isEmpty()) {
            return 0;
        }
        // Default: loop to first entry
        return 0;
    }
}

package com.opensmpsdeck.codec;

import com.opensmpsdeck.model.*;
import com.opensmps.smps.SmpsCoordFlags;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HierarchyCompiler {

    private HierarchyCompiler() {}

    /** Result of compiling a chain, with metadata for timeline construction. */
    public record ChainCompilationResult(byte[] trackData, int[] entryOffsets, int contentEndOffset) {}

    /** Position in the main stream where a CALL was emitted, plus the target phrase ID. */
    private record CallPatch(int callOffset, int phraseId) {}

    public static byte[] compileChain(Chain chain, PhraseLibrary library) {
        return compileChainDetailed(chain, library).trackData();
    }

    public static ChainCompilationResult compileChainDetailed(Chain chain, PhraseLibrary library) {
        return compileChainDetailed(chain, library, SmpsCoordFlags.Dialect.S2);
    }

    /**
     * Compile a chain emitting the coordination flags of the given dialect
     * (S3K returns from subroutines with F9 and transposes with FB).
     */
    public static ChainCompilationResult compileChainDetailed(Chain chain, PhraseLibrary library,
            SmpsCoordFlags.Dialect dialect) {
        if (chain.getEntries().isEmpty()) {
            return new ChainCompilationResult(new byte[]{(byte) SmpsCoordFlags.STOP}, new int[0], 0);
        }

        // Count phrase references to decide inline vs CALL
        Map<Integer, Integer> refCounts = new HashMap<>();
        for (var entry : chain.getEntries()) {
            refCounts.merge(entry.getPhraseId(), 1, Integer::sum);
        }

        var mainStream = new ByteArrayOutputStream();
        var subroutinePool = new ByteArrayOutputStream();
        Map<Integer, Integer> subroutineOffsets = new HashMap<>();
        List<CallPatch> callPatches = new ArrayList<>();

        // Track entry byte offsets for loop point resolution
        int[] entryOffsets = new int[chain.getEntries().size()];
        int currentTranspose = 0;

        for (int i = 0; i < chain.getEntries().size(); i++) {
            var entry = chain.getEntries().get(i);

            Phrase phrase = library.getPhrase(entry.getPhraseId());
            if (phrase == null) {
                entryOffsets[i] = mainStream.size();
                continue;
            }

            byte[] phraseData = phrase.getDataDirect();
            if (phraseData.length == 0) {
                entryOffsets[i] = mainStream.size();
                continue;
            }

            // Emit transpose if needed. The transpose flag ADDS to the track's
            // key displacement, so emit the delta from the current value.
            int targetTranspose = entry.getTransposeSemitones();
            if (targetTranspose != currentTranspose) {
                mainStream.write((byte) SmpsCoordFlags.transposeAddFlag(dialect));
                mainStream.write((byte) ((targetTranspose - currentTranspose) & 0xFF));
                currentTranspose = targetTranspose;
            }

            // Record the entry offset AFTER the delta: the channel loop jumps
            // here, and re-executing an additive delta would accumulate
            // transposition on every loop pass.
            entryOffsets[i] = mainStream.size();

            boolean isShared = refCounts.getOrDefault(entry.getPhraseId(), 0) > 1;
            int repeatCount = entry.getRepeatCount();

            if (repeatCount > 1) {
                // Emit LOOP wrapper
                int loopStart = mainStream.size();
                if (isShared) {
                    emitCall(mainStream, entry.getPhraseId(), subroutinePool,
                        subroutineOffsets, phraseData, callPatches, dialect);
                } else {
                    mainStream.write(phraseData, 0, phraseData.length);
                }
                emitLoop(mainStream, repeatCount, loopStart);
            } else if (isShared) {
                emitCall(mainStream, entry.getPhraseId(), subroutinePool,
                    subroutineOffsets, phraseData, callPatches, dialect);
            } else {
                // Inline directly
                mainStream.write(phraseData, 0, phraseData.length);
            }
        }

        int contentEndOffset = mainStream.size();

        // Reset transpose if it was non-zero at end (additive flag: emit the inverse)
        if (currentTranspose != 0 && !chain.hasLoop()) {
            mainStream.write((byte) SmpsCoordFlags.transposeAddFlag(dialect));
            mainStream.write((byte) ((-currentTranspose) & 0xFF));
        }

        // Emit loop or stop
        if (chain.hasLoop() && chain.getLoopEntryIndex() >= 0
                && chain.getLoopEntryIndex() < entryOffsets.length) {
            // The loop target sits after its entry's transpose delta, so the
            // jump must first restore that entry's transpose state.
            int loopTranspose = chain.getEntries()
                    .get(chain.getLoopEntryIndex()).getTransposeSemitones();
            if (currentTranspose != loopTranspose) {
                mainStream.write((byte) SmpsCoordFlags.transposeAddFlag(dialect));
                mainStream.write((byte) ((loopTranspose - currentTranspose) & 0xFF));
                currentTranspose = loopTranspose;
            }
            int loopTarget = entryOffsets[chain.getLoopEntryIndex()];
            emitJump(mainStream, loopTarget);
        } else {
            mainStream.write((byte) SmpsCoordFlags.STOP);
        }

        // Append subroutine pool after main stream
        int mainSize = mainStream.size();
        byte[] mainBytes = mainStream.toByteArray();
        byte[] subBytes = subroutinePool.toByteArray();

        byte[] combined = new byte[mainBytes.length + subBytes.length];
        System.arraycopy(mainBytes, 0, combined, 0, mainBytes.length);
        System.arraycopy(subBytes, 0, combined, mainBytes.length, subBytes.length);

        // Patch CALL pointers to point to subroutine positions
        for (var patch : callPatches) {
            int subOffset = subroutineOffsets.get(patch.phraseId());
            int target = mainSize + subOffset;
            combined[patch.callOffset() + 1] = (byte) (target & 0xFF);
            combined[patch.callOffset() + 2] = (byte) ((target >> 8) & 0xFF);
        }

        return new ChainCompilationResult(combined, entryOffsets, contentEndOffset);
    }

    private static void emitCall(ByteArrayOutputStream stream, int phraseId,
            ByteArrayOutputStream subPool, Map<Integer, Integer> subOffsets,
            byte[] data, List<CallPatch> patches, SmpsCoordFlags.Dialect dialect) {
        if (!subOffsets.containsKey(phraseId)) {
            subOffsets.put(phraseId, subPool.size());
            subPool.write(data, 0, data.length);
            subPool.write((byte) SmpsCoordFlags.returnFlag(dialect));
        }
        // Record position for patching
        patches.add(new CallPatch(stream.size(), phraseId));
        // CALL placeholder — pointer patched later
        stream.write((byte) SmpsCoordFlags.CALL);
        stream.write(0); // placeholder low
        stream.write(0); // placeholder high
    }

    private static void emitLoop(ByteArrayOutputStream stream, int count, int loopStart) {
        stream.write((byte) SmpsCoordFlags.LOOP);
        stream.write(0);              // loop counter index (0 for first nested loop level)
        stream.write(count & 0xFF);   // repeat count
        stream.write(loopStart & 0xFF);
        stream.write((loopStart >> 8) & 0xFF);
    }

    private static void emitJump(ByteArrayOutputStream stream, int target) {
        stream.write((byte) SmpsCoordFlags.JUMP);
        stream.write(target & 0xFF);
        stream.write((target >> 8) & 0xFF);
    }
}

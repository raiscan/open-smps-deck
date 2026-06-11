package com.opensmpsdeck.io.midi;

import com.opensmps.smps.SmpsCoordFlags;
import com.opensmpsdeck.io.HexUtil;
import com.opensmpsdeck.model.ChainEntry;
import com.opensmpsdeck.model.ChannelType;
import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.PhraseLibrary;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Encodes a quantized monophonic line into bar-aligned, deduplicated phrases.
 * Returns the chain entries referencing them (consecutive duplicates collapsed
 * into repeatCount).
 */
public final class MidiPhraseEncoder {

    /** unitsPerSixteenth from TempoFit; stepsPerBar from time signature (16 * num/den * 4/4). */
    public record EncodeParams(int unitsPerSixteenth, int stepsPerBar, int barsPerPhrase,
                               int octaveShift) {
        public int stepsPerPhrase() { return stepsPerBar * barsPerPhrase; }
    }

    static final int NOTE_BASE = 0x81;
    static final int NOTE_MAX = 0xDF;
    static final int REST = 0x80;
    static final int MAX_DURATION = 0x7F;
    static final int NOISE_NOTE = 0xB0;

    private MidiPhraseEncoder() {}

    /**
     * @param dedupIndex shared map "channelType:hexBytes" → phraseId, passed across
     *                   calls so dedup spans all lines of a channel type
     */
    public static List<ChainEntry> encodeLine(List<NoteQuantizer.QuantizedNote> notes,
                                              ChannelType type, EncodeParams p,
                                              PhraseLibrary library,
                                              Map<String, Integer> dedupIndex,
                                              String namePrefix, List<String> warnings) {
        int totalSteps = notes.stream()
                .mapToInt(n -> n.startStep() + n.lengthSteps()).max().orElse(0);
        int phraseSteps = p.stepsPerPhrase();
        int phraseCount = Math.max(1, (totalSteps + phraseSteps - 1) / phraseSteps);

        // step → pitch sounding at that step (already monophonic), -1 = silent,
        // attackStep marks where each note begins so continuations become ties
        int[] pitchAt = new int[phraseCount * phraseSteps];
        boolean[] attackAt = new boolean[pitchAt.length];
        Arrays.fill(pitchAt, -1);
        for (NoteQuantizer.QuantizedNote n : notes) {
            for (int s = n.startStep(); s < n.startStep() + n.lengthSteps()
                    && s < pitchAt.length; s++) {
                pitchAt[s] = n.pitch();
            }
            if (n.startStep() < attackAt.length) attackAt[n.startStep()] = true;
        }

        List<ChainEntry> entries = new ArrayList<>();
        for (int ph = 0; ph < phraseCount; ph++) {
            byte[] data = encodePhrase(pitchAt, attackAt, ph * phraseSteps,
                    Math.min((ph + 1) * phraseSteps, pitchAt.length), p, type, warnings);
            int phraseId = dedupOrCreate(data, type, library, dedupIndex,
                    namePrefix + "-" + String.format("%02d", ph));
            // collapse consecutive identical entries into repeatCount
            if (!entries.isEmpty()
                    && entries.get(entries.size() - 1).getPhraseId() == phraseId) {
                ChainEntry last = entries.get(entries.size() - 1);
                last.setRepeatCount(last.getRepeatCount() + 1);
            } else {
                entries.add(new ChainEntry(phraseId));
            }
        }
        return entries;
    }

    private static byte[] encodePhrase(int[] pitchAt, boolean[] attackAt, int from, int to,
                                       EncodeParams p, ChannelType type, List<String> warnings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int lastDuration = -1;
        int s = from;
        while (s < to) {
            int pitch = pitchAt[s];
            boolean tied = pitch >= 0 && !attackAt[s]; // continuation from previous bar/chunk
            int run = 1;
            while (s + run < to && pitchAt[s + run] == pitch
                    && (pitch < 0 || !attackAt[s + run])) {
                run++;
            }
            int durUnits = run * p.unitsPerSixteenth();
            int noteByte = pitch < 0 ? REST : toNoteByte(pitch, p.octaveShift(), type, warnings);
            lastDuration = emitChunked(out, noteByte, durUnits, tied, lastDuration);
            s += run;
        }
        return out.toByteArray();
    }

    /**
     * Emits a note/rest with its duration, splitting any run longer than 0x7F into
     * chunks joined by a TIE (E7) so the note continues without re-attacking.
     *
     * <p><b>Verified E7 semantics (decoder + sequencer are authoritative):</b>
     * <ul>
     *   <li>{@code SmpsSequencer} (case 0xE7, ~line 1246) treats E7 as a
     *       <em>zero-parameter</em> flag: it sets {@code tieNext=true} and consumes
     *       no bytes. The <em>following</em> note byte then plays with the attack
     *       suppressed ({@code shouldPreventNoteAttack} skips KEY_OFF/KEY_ON,
     *       SMPSPlay DoNoteOn HOLD bit), and {@code tieNext} is cleared afterwards.
     *       So the correct on-disk shape is {@code E7, noteByte[, dur]} — a normal
     *       note prefixed by a standalone E7 byte. That is exactly what this method
     *       writes, and it preserves the total sustained duration (sum of all
     *       chunks) while preventing the re-attack of continuations.</li>
     *   <li>{@code SmpsDecoder.decode} (~line 155) treats E7 as its own row: it
     *       emits a {@code "==="} tie-marker row carrying the <em>previous</em>
     *       row's {@code currentDuration} as a display carry-over, advances exactly
     *       one byte, and does <em>not</em> consume the continuation's note/dur.
     *       The continuation therefore decodes as a separate note row with its own
     *       duration. Consequently a decoded tie produces an extra "===" row whose
     *       duration duplicates the prior chunk for display only — it is not a fresh
     *       attack and must be ignored when summing sounding duration.</li>
     * </ul>
     * Net: the byte output here is correct for both playback (no re-attack, full
     * sustain) and decode (a "===" continuation marker precedes each tied chunk).
     */
    private static int emitChunked(ByteArrayOutputStream out, int noteByte, int durUnits,
                                   boolean tied, int lastDuration) {
        boolean first = true;
        while (durUnits > 0) {
            int chunk = Math.min(durUnits, MAX_DURATION);
            boolean needTie = noteByte != REST && (tied || !first);
            if (needTie) out.write(SmpsCoordFlags.TIE);
            out.write(noteByte);
            if (chunk != lastDuration) {
                out.write(chunk);
                lastDuration = chunk;
            }
            durUnits -= chunk;
            first = false;
        }
        return lastDuration;
    }

    private static int toNoteByte(int pitch, int octaveShift, ChannelType type,
                                  List<String> warnings) {
        int nb = NOTE_BASE + pitch - 12 + 12 * octaveShift;
        if (nb < NOTE_BASE || nb > NOTE_MAX) {
            int clamped = Math.floorMod(nb - NOTE_BASE, 12) + NOTE_BASE
                    + (nb < NOTE_BASE ? 0 : (NOTE_MAX - NOTE_BASE) / 12 * 12);
            warnings.add(String.format("%s: MIDI pitch %d out of SMPS range, clamped", type, pitch));
            nb = Math.max(NOTE_BASE, Math.min(NOTE_MAX, clamped));
        }
        return nb;
    }

    private static int dedupOrCreate(byte[] data, ChannelType type, PhraseLibrary library,
                                     Map<String, Integer> dedupIndex, String name) {
        String key = type.name() + ":" + HexUtil.bytesToHex(data);
        Integer existing = dedupIndex.get(key);
        if (existing != null) return existing;
        Phrase phrase = library.createPhrase(name, type);
        phrase.setData(data);
        dedupIndex.put(key, phrase.getId());
        return phrase.getId();
    }
}

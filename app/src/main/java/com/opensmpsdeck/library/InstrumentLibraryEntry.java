package com.opensmpsdeck.library;

import com.opensmpsdeck.io.HexUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class InstrumentLibraryEntry {

    private final String id;
    private final InstrumentAssetKind kind;
    private final String displayName;
    private final String dedupeKey;
    private final Instant createdTimestamp;
    private final Instant updatedTimestamp;
    private final List<SourceReference> sourceReferences;
    private final byte[] data;
    private final int playbackRate;
    private final String compressionLabel;
    private final String pan;
    private final String param1;
    private final String param2;
    private final String dacId;

    private InstrumentLibraryEntry(
            InstrumentAssetKind kind,
            String displayName,
            byte[] data,
            int playbackRate,
            String compressionLabel,
            String pan,
            String param1,
            String param2,
            String dacId,
            List<SourceReference> sourceReferences,
            Instant createdTimestamp,
            Instant updatedTimestamp) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.displayName = displayName == null ? "" : displayName;
        this.data = Objects.requireNonNull(data, "data").clone();
        this.playbackRate = playbackRate & 0xFF;
        this.compressionLabel = compressionLabel == null ? "" : compressionLabel;
        this.pan = pan;
        this.param1 = param1;
        this.param2 = param2;
        this.dacId = dacId == null ? "" : dacId;
        this.dedupeKey = dedupeKey(kind, this.data, this.playbackRate);
        this.id = dedupeKey;
        this.sourceReferences = List.copyOf(sourceReferences == null ? List.of() : sourceReferences);
        this.createdTimestamp = Objects.requireNonNull(createdTimestamp, "createdTimestamp");
        this.updatedTimestamp = Objects.requireNonNull(updatedTimestamp, "updatedTimestamp");
    }

    public static InstrumentLibraryEntry fmVoice(String displayName, byte[] voiceData, SourceReference source) {
        return new InstrumentLibraryEntry(
                InstrumentAssetKind.FM_VOICE, displayName, voiceData, 0,
                "", null, null, null, "", sourceList(source), Instant.EPOCH, Instant.EPOCH);
    }

    public static InstrumentLibraryEntry psgEnvelope(String displayName, byte[] data, SourceReference source) {
        return new InstrumentLibraryEntry(
                InstrumentAssetKind.PSG_ENVELOPE, displayName, data, 0,
                "", null, null, null, "", sourceList(source), Instant.EPOCH, Instant.EPOCH);
    }

    public static InstrumentLibraryEntry modEnvelope(String displayName, byte[] data, SourceReference source) {
        return new InstrumentLibraryEntry(
                InstrumentAssetKind.MOD_ENVELOPE, displayName, data, 0,
                "", null, null, null, "", sourceList(source), Instant.EPOCH, Instant.EPOCH);
    }

    public static InstrumentLibraryEntry dacSample(
            String displayName,
            byte[] data,
            int rate,
            String compressionLabel,
            String pan,
            String param1,
            String param2,
            String dacId,
            SourceReference source) {
        return new InstrumentLibraryEntry(
                InstrumentAssetKind.DAC_SAMPLE, displayName, data, rate,
                compressionLabel, pan, param1, param2, dacId, sourceList(source), Instant.EPOCH, Instant.EPOCH);
    }

    public String id() {
        return id;
    }

    public InstrumentAssetKind kind() {
        return kind;
    }

    public String displayName() {
        return displayName;
    }

    public String dedupeKey() {
        return dedupeKey;
    }

    public Instant createdTimestamp() {
        return createdTimestamp;
    }

    public Instant updatedTimestamp() {
        return updatedTimestamp;
    }

    public List<SourceReference> sourceReferences() {
        return sourceReferences;
    }

    public byte[] data() {
        return data.clone();
    }

    public int algorithm() {
        return kind == InstrumentAssetKind.FM_VOICE && data.length > 0 ? data[0] & 0x07 : 0;
    }

    public int feedback() {
        return kind == InstrumentAssetKind.FM_VOICE && data.length > 0 ? (data[0] >> 3) & 0x07 : 0;
    }

    public int stepCount() {
        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 0xFF) == 0x80) {
                return i;
            }
        }
        return data.length;
    }

    public int playbackRate() {
        return playbackRate;
    }

    public int byteLength() {
        return data.length;
    }

    public String compressionLabel() {
        return compressionLabel;
    }

    public String pan() {
        return pan;
    }

    public String param1() {
        return param1;
    }

    public String param2() {
        return param2;
    }

    public String dacId() {
        return dacId;
    }

    public InstrumentLibraryEntry withTimestamps(Instant created, Instant updated) {
        return new InstrumentLibraryEntry(
                kind, displayName, data, playbackRate, compressionLabel, pan, param1, param2, dacId,
                sourceReferences, created, updated);
    }

    public InstrumentLibraryEntry withMergedSources(List<SourceReference> newSources, Instant now) {
        List<SourceReference> merged = new ArrayList<>(sourceReferences);
        boolean changed = false;
        for (SourceReference source : newSources) {
            if (source != null && !merged.contains(source)) {
                merged.add(source);
                changed = true;
            }
        }
        if (!changed) {
            return this;
        }
        return new InstrumentLibraryEntry(
                kind, displayName, data, playbackRate, compressionLabel, pan, param1, param2, dacId,
                merged, createdTimestamp, now);
    }

    private static List<SourceReference> sourceList(SourceReference source) {
        return source == null ? List.of() : List.of(source);
    }

    private static String dedupeKey(InstrumentAssetKind kind, byte[] data, int rate) {
        String hex = HexUtil.bytesToHex(data);
        return switch (kind) {
            case FM_VOICE -> "fm:" + hex;
            case PSG_ENVELOPE -> "psg:" + hex;
            case MOD_ENVELOPE -> "mod:" + hex;
            case DAC_SAMPLE -> "dac:" + (rate & 0xFF) + ":" + hex;
        };
    }
}

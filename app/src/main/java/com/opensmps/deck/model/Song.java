package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level model for an SMPS song project.
 *
 * <p>Contains the voice bank, PSG envelopes, patterns (each holding raw
 * SMPS track data per channel), and the order list that sequences patterns
 * for playback. The internal representation is SMPS-native: track data is
 * stored as raw bytecode matching the Z80 driver convention.
 *
 * <p>Default state: one empty pattern, one order row, S2 mode, tempo 0x80.
 */
public class Song {

    private String name = "Untitled";
    private SmpsMode smpsMode = SmpsMode.S2;
    private ArrangementMode arrangementMode = ArrangementMode.LEGACY_PATTERNS;
    private int tempo = 0x80;
    private int dividingTiming = 1;
    private int loopPoint = 0;

    private final List<FmVoice> voiceBank = new ArrayList<>();
    private final List<PsgEnvelope> psgEnvelopes = new ArrayList<>();
    private final List<DacSample> dacSamples = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<int[]> orderList = new ArrayList<>(); // int[10] per row
    private StructuredArrangement structuredArrangement;
    private HierarchicalArrangement hierarchicalArrangement;

    public Song() {
        patterns.add(new Pattern(0, 64));
        int[] firstOrder = new int[Pattern.CHANNEL_COUNT];
        orderList.add(firstOrder);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SmpsMode getSmpsMode() {
        return smpsMode;
    }

    public void setSmpsMode(SmpsMode smpsMode) {
        this.smpsMode = smpsMode;
    }

    public ArrangementMode getArrangementMode() {
        return arrangementMode;
    }

    public void setArrangementMode(ArrangementMode arrangementMode) {
        this.arrangementMode = arrangementMode != null ? arrangementMode : ArrangementMode.LEGACY_PATTERNS;
    }

    public int getTempo() {
        return tempo;
    }

    public void setTempo(int tempo) {
        this.tempo = tempo;
    }

    public int getDividingTiming() {
        return dividingTiming;
    }

    public void setDividingTiming(int dt) {
        this.dividingTiming = dt;
    }

    public int getLoopPoint() {
        return loopPoint;
    }

    public void setLoopPoint(int lp) {
        this.loopPoint = lp;
    }

    public List<FmVoice> getVoiceBank() {
        return voiceBank;
    }

    public List<PsgEnvelope> getPsgEnvelopes() {
        return psgEnvelopes;
    }

    public List<DacSample> getDacSamples() {
        return dacSamples;
    }

    public List<Pattern> getPatterns() {
        return patterns;
    }

    public List<int[]> getOrderList() {
        return orderList;
    }

    /**
     * Structured block-reference arrangement (optional, phase-1 scaffold).
     *
     * <p>When {@link #getArrangementMode()} is {@link ArrangementMode#STRUCTURED_BLOCKS},
     * editors should prefer this model over legacy pattern/order data.
     */
    public StructuredArrangement getStructuredArrangement() {
        return structuredArrangement;
    }

    public void setStructuredArrangement(StructuredArrangement structuredArrangement) {
        this.structuredArrangement = structuredArrangement;
    }

    public HierarchicalArrangement getHierarchicalArrangement() {
        return hierarchicalArrangement;
    }

    public void setHierarchicalArrangement(HierarchicalArrangement arr) {
        this.hierarchicalArrangement = arr;
    }
}

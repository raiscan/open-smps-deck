package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.List;

public class Song {

    private String name = "Untitled";
    private SmpsMode smpsMode = SmpsMode.S2;
    private int tempo = 0x80;
    private int dividingTiming = 1;
    private int loopPoint = 0;

    private final List<FmVoice> voiceBank = new ArrayList<>();
    private final List<PsgEnvelope> psgEnvelopes = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<int[]> orderList = new ArrayList<>(); // int[10] per row

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

    public List<Pattern> getPatterns() {
        return patterns;
    }

    public List<int[]> getOrderList() {
        return orderList;
    }
}

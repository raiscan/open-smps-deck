package com.opensmpsdeck.model;

public class ChainEntry {

    private int phraseId;
    private int transposeSemitones;
    private int repeatCount = 1;

    public ChainEntry(int phraseId) {
        this.phraseId = phraseId;
    }

    public int getPhraseId() { return phraseId; }
    public void setPhraseId(int phraseId) { this.phraseId = phraseId; }

    public int getTransposeSemitones() { return transposeSemitones; }
    public void setTransposeSemitones(int semitones) { this.transposeSemitones = semitones; }

    public int getRepeatCount() { return repeatCount; }
    public void setRepeatCount(int count) { this.repeatCount = Math.max(1, count); }
}

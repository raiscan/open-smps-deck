package com.opensmpsdeck.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PhraseLibrary {

    private final List<Phrase> phrases = new ArrayList<>();
    private int nextId = 1;

    public Phrase createPhrase(String name, ChannelType channelType) {
        var phrase = new Phrase(nextId++, name, channelType);
        phrases.add(phrase);
        return phrase;
    }

    public Phrase getPhrase(int id) {
        for (var p : phrases) {
            if (p.getId() == id) return p;
        }
        return null;
    }

    public boolean removePhrase(int id) {
        return phrases.removeIf(p -> p.getId() == id);
    }

    public List<Phrase> getAllPhrases() {
        return Collections.unmodifiableList(phrases);
    }

    public int getNextId() { return nextId; }
    public void setNextId(int nextId) { this.nextId = nextId; }
}

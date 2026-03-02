package com.opensmpsdeck.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HierarchicalArrangement {

    public static final int MAX_DEPTH = 4;

    private final PhraseLibrary phraseLibrary = new PhraseLibrary();
    private final List<Chain> chains = new ArrayList<>();

    public HierarchicalArrangement() {
        for (int ch = 0; ch < Pattern.CHANNEL_COUNT; ch++) {
            chains.add(new Chain(ch));
        }
    }

    public PhraseLibrary getPhraseLibrary() { return phraseLibrary; }
    public List<Chain> getChains() { return Collections.unmodifiableList(chains); }
    public Chain getChain(int channelIndex) { return chains.get(channelIndex); }

    public boolean wouldCreateCycle(int fromPhraseId, int targetPhraseId) {
        if (fromPhraseId == targetPhraseId) return true;
        Set<Integer> visited = new HashSet<>();
        return reachesFrom(targetPhraseId, fromPhraseId, visited);
    }

    private boolean reachesFrom(int current, int target, Set<Integer> visited) {
        if (current == target) return true;
        if (!visited.add(current)) return false;
        Phrase phrase = phraseLibrary.getPhrase(current);
        if (phrase == null) return false;
        for (var ref : phrase.getSubPhraseRefs()) {
            if (reachesFrom(ref.phraseId(), target, visited)) return true;
        }
        return false;
    }

    public int getDepth(int phraseId) {
        return computeDepth(phraseId, new HashSet<>());
    }

    private int computeDepth(int phraseId, Set<Integer> visited) {
        if (!visited.add(phraseId)) return 0;
        Phrase phrase = phraseLibrary.getPhrase(phraseId);
        if (phrase == null || phrase.getSubPhraseRefs().isEmpty()) return 1;
        int maxChild = 0;
        for (var ref : phrase.getSubPhraseRefs()) {
            maxChild = Math.max(maxChild, computeDepth(ref.phraseId(), visited));
        }
        return 1 + maxChild;
    }
}

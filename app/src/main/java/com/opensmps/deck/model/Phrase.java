package com.opensmps.deck.model;

import java.util.ArrayList;
import java.util.List;

public class Phrase {

    public record SubPhraseRef(int phraseId, int insertAtRow, int repeatCount) {
        public SubPhraseRef {
            repeatCount = Math.max(1, repeatCount);
        }
    }

    private final int id;
    private String name;
    private final ChannelType channelType;
    private byte[] data;
    private final List<SubPhraseRef> subPhraseRefs = new ArrayList<>();

    public Phrase(int id, String name, ChannelType channelType) {
        this.id = id;
        this.name = name;
        this.channelType = channelType;
        this.data = new byte[0];
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ChannelType getChannelType() { return channelType; }

    public byte[] getData() { return data.clone(); }
    public byte[] getDataDirect() { return data; }
    public void setData(byte[] data) {
        this.data = data != null ? data.clone() : new byte[0];
    }

    public List<SubPhraseRef> getSubPhraseRefs() { return subPhraseRefs; }
}

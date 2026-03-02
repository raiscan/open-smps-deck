package com.opensmpsdeck.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestPhraseModel {

    @Test
    void newPhraseHasDefaults() {
        var phrase = new Phrase(1, "Verse A", ChannelType.FM);
        assertEquals(1, phrase.getId());
        assertEquals("Verse A", phrase.getName());
        assertEquals(ChannelType.FM, phrase.getChannelType());
        assertEquals(0, phrase.getData().length);
        assertTrue(phrase.getSubPhraseRefs().isEmpty());
    }

    @Test
    void dataIsDefensivelyCopied() {
        var phrase = new Phrase(1, "Test", ChannelType.FM);
        byte[] data = {(byte) 0xA1, 0x18};
        phrase.setData(data);
        data[0] = 0; // mutate original
        assertEquals((byte) 0xA1, phrase.getData()[0]); // phrase unchanged
    }

    @Test
    void getDataDirectReturnsReference() {
        var phrase = new Phrase(1, "Test", ChannelType.FM);
        phrase.setData(new byte[]{(byte) 0xA1, 0x18});
        assertSame(phrase.getDataDirect(), phrase.getDataDirect());
    }

    @Test
    void subPhraseRefsTrackNestedCalls() {
        var phrase = new Phrase(1, "Outer", ChannelType.FM);
        phrase.getSubPhraseRefs().add(new Phrase.SubPhraseRef(2, 3, 1));
        assertEquals(1, phrase.getSubPhraseRefs().size());
        assertEquals(2, phrase.getSubPhraseRefs().get(0).phraseId());
        assertEquals(3, phrase.getSubPhraseRefs().get(0).insertAtRow());
        assertEquals(1, phrase.getSubPhraseRefs().get(0).repeatCount());
    }

    @Test
    void setNameUpdates() {
        var phrase = new Phrase(1, "Old", ChannelType.FM);
        phrase.setName("New");
        assertEquals("New", phrase.getName());
    }

    @Test
    void nullDataBecomesEmpty() {
        var phrase = new Phrase(1, "Test", ChannelType.FM);
        phrase.setData(null);
        assertNotNull(phrase.getData());
        assertEquals(0, phrase.getData().length);
    }
}

package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EnvelopeEditorPaneTest {
    @Test void staleOrDeletedEnvelopeDraftCannotOverwriteLiveAssets() {
        Envelope captured=new Envelope("env","Pluck","PSG_VOLUME",List.of(0,3,7,15,0x83));
        StudioProject original=ProjectEdits.addEnvelope(DemoProject.create(),captured);
        assertSame(original,EnvelopeEditorPane.guardEnvelope(original,captured));
        StudioProject changed=ProjectEdits.updateEnvelope(original,new Envelope(captured.id(),"Agent pluck",captured.type(),List.of(0,7,0x83)));
        assertThrows(IllegalArgumentException.class,()->EnvelopeEditorPane.guardEnvelope(changed,captured));
        StudioProject deleted=ProjectEdits.deleteEnvelope(original,captured.id());
        assertThrows(IllegalArgumentException.class,()->EnvelopeEditorPane.guardEnvelope(deleted,captured));
        assertTrue(deleted.envelopes().isEmpty());
        StudioProject unrelated=ProjectEdits.moveClip(original,"clip-a1","FM2",0);
        assertSame(unrelated,EnvelopeEditorPane.guardEnvelope(unrelated,captured));
    }
}

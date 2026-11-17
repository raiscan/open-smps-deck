package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.opensmpsdeck.project.NativeFmVoice.Parameter.*;

class NativeFmVoiceTest {
    @Test void nativeSlotsAndBitMasksPreserveEveryUneditedBit() {
        List<Integer> raw = new ArrayList<>(java.util.Collections.nCopies(25, 0xFF));
        Voice original = new Voice("v", "All bits", raw);
        record Field(NativeFmVoice.Parameter parameter, int group, int mask, int shift) {}
        var fields = List.of(new Field(DETUNE,1,0x70,4),new Field(MULTIPLE,1,0x0F,0),
                new Field(RATE_SCALE,5,0xC0,6),new Field(ATTACK_RATE,5,0x1F,0),
                new Field(AMPLITUDE_MODULATION,9,0x80,7),new Field(DECAY1_RATE,9,0x1F,0),
                new Field(DECAY2_RATE,13,0x1F,0),new Field(SUSTAIN_LEVEL,17,0xF0,4),
                new Field(RELEASE_RATE,17,0x0F,0),new Field(TOTAL_LEVEL,21,0x7F,0),
                new Field(TRACK_VOLUME,21,0x80,7));
        for (int slot=0;slot<4;slot++) for (Field f:fields) {
            assertTrue(f.parameter().operator());
            assertEquals(f.mask()>>f.shift(),f.parameter().max());
            assertEquals(f.parameter().max(),NativeFmVoice.read(original,slot,f.parameter()));
            for(int value:List.of(0, f.parameter().max())) {
                Voice edited=NativeFmVoice.with(original,slot,f.parameter(),value);
                for(int at=0;at<25;at++) assertEquals(at==f.group()+slot ? (0xFF & ~f.mask()) | (value<<f.shift()) : 0xFF,
                        edited.bytes().get(at), f.parameter()+" slot "+slot+" byte "+at);
                assertEquals(value,NativeFmVoice.read(edited,slot,f.parameter()));
            }
        }
        assertEquals(java.util.Collections.nCopies(25,255),original.bytes());
        Voice algorithm=NativeFmVoice.with(original,0,ALGORITHM,0);
        assertEquals(0xF8,algorithm.bytes().getFirst());
        assertEquals(0xC7,NativeFmVoice.with(original,0,FEEDBACK,0).bytes().getFirst());
        assertEquals(7,ALGORITHM.max());assertEquals(7,FEEDBACK.max());assertFalse(ALGORITHM.operator());
        for(var parameter:NativeFmVoice.Parameter.values()) assertFalse(parameter.label().isBlank());
    }

    @Test void initialVoiceUsesSafeNativeBytesAndIndependentTrackVolumeFlags() {
        Voice initial=NativeFmVoice.initial("v","Initial");
        assertEquals(List.of(7,1,1,1,1,31,31,31,31,0,0,0,0,0,0,0,0,15,15,15,15,0x98,0x98,0x98,0x98),initial.bytes());
        Voice tl=NativeFmVoice.with(initial,2,TOTAL_LEVEL,5);
        assertEquals(0x85,tl.bytes().get(23));
        Voice fixed=NativeFmVoice.with(tl,2,TRACK_VOLUME,0);
        assertEquals(5,fixed.bytes().get(23));
        assertEquals(1,NativeFmVoice.read(initial,2,TRACK_VOLUME));
        assertEquals("v",fixed.id());assertEquals("Initial",fixed.name());
    }

    @Test void rejectsInvalidSlotsAndValuesInsteadOfMaskingThem() {
        Voice voice=NativeFmVoice.initial("v","Voice");
        for(int slot:List.of(-1,4)) assertThrows(IllegalArgumentException.class,()->NativeFmVoice.read(voice,slot,TOTAL_LEVEL));
        assertThrows(IllegalArgumentException.class,()->NativeFmVoice.read(voice,1,ALGORITHM));
        for(var parameter:NativeFmVoice.Parameter.values()) {
            assertThrows(IllegalArgumentException.class,()->NativeFmVoice.with(voice,0,parameter,-1));
            assertThrows(IllegalArgumentException.class,()->NativeFmVoice.with(voice,0,parameter,parameter.max()+1));
        }
        assertThrows(IllegalArgumentException.class,()->NativeFmVoice.read(new Voice("v","Bad",List.of()),0,ALGORITHM));
    }
}

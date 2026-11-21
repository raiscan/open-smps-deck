package com.opensmpsdeck.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bit-preserving edits of the 25-byte native S3K FM voice layout. */
public final class NativeFmVoice {
    private NativeFmVoice() {}

    /** Operator slots remain in stored order: YM offsets 00, 08, 04, 0C. */
    public enum Parameter {
        ALGORITHM("Algorithm",0,0x07,0,false), FEEDBACK("Feedback",0,0x38,3,false),
        DETUNE("Detune",1,0x70,4,true), MULTIPLE("Multiple",1,0x0F,0,true),
        RATE_SCALE("Rate scale",5,0xC0,6,true), ATTACK_RATE("Attack rate",5,0x1F,0,true),
        AMPLITUDE_MODULATION("Amplitude modulation",9,0x80,7,true), DECAY1_RATE("Decay 1 rate",9,0x1F,0,true),
        DECAY2_RATE("Decay 2 rate",13,0x1F,0,true), SUSTAIN_LEVEL("Sustain level",17,0xF0,4,true),
        RELEASE_RATE("Release rate",17,0x0F,0,true), TOTAL_LEVEL("Total level",21,0x7F,0,true),
        TRACK_VOLUME("Track volume",21,0x80,7,true);

        private final String label;
        private final int offset, mask, shift;
        private final boolean operator;
        Parameter(String label,int offset,int mask,int shift,boolean operator) {
            this.label=label;this.offset=offset;this.mask=mask;this.shift=shift;this.operator=operator;
        }
        public int max() { return mask>>shift; }
        public boolean operator() { return operator; }
        public String label() { return label; }
    }

    public static int read(Voice voice,int storedSlot,Parameter parameter) {
        int at=position(voice,storedSlot,parameter);
        return (voice.bytes().get(at)&parameter.mask)>>parameter.shift;
    }

    public static Voice with(Voice voice,int storedSlot,Parameter parameter,int value) {
        int at=position(voice,storedSlot,parameter);
        if(value<0 || value>parameter.max()) throw new IllegalArgumentException(parameter.label()+" must be in 0.."+parameter.max());
        List<Integer> bytes=new ArrayList<>(voice.bytes());
        bytes.set(at,(bytes.get(at)&~parameter.mask)|(value<<parameter.shift));
        var origin = voice.origin();
        return new Voice(voice.id(), voice.name(), bytes, origin == null ? null : origin.withPatch(voice.bytes(), bytes));
    }

    public static Voice initial(String id,String name) {
        return new Voice(id,name,List.of(7,1,1,1,1,31,31,31,31,0,0,0,0,0,0,0,0,15,15,15,15,0x98,0x98,0x98,0x98));
    }

    private static int position(Voice voice,int slot,Parameter parameter) {
        Objects.requireNonNull(voice,"voice");Objects.requireNonNull(parameter,"parameter");
        if(voice.bytes().size()!=25) throw new IllegalArgumentException("FM voice requires exactly 25 native bytes");
        for(int b:voice.bytes()) if(b<0 || b>255) throw new IllegalArgumentException("FM voice bytes must be in 0..255");
        if(slot<0 || slot>3 || (!parameter.operator && slot!=0))
            throw new IllegalArgumentException(parameter.operator ? "Stored operator slot must be in 0..3" : "Global voice parameters use slot 0");
        return parameter.offset+(parameter.operator?slot:0);
    }
}

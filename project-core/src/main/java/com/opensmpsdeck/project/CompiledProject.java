package com.opensmpsdeck.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native song bytes together with every declared external envelope dependency. */
public record CompiledProject(byte[] song,List<Envelope> envelopes) {
    public CompiledProject {
        song=song.clone();envelopes=List.copyOf(envelopes);
    }
    @Override public byte[] song() { return song.clone(); }
    public boolean requiresAssets() { return !envelopes.isEmpty(); }
    public Map<Integer,List<Integer>> psgEnvelopes() { return programs(Envelope.PSG_VOLUME); }
    public Map<Integer,List<Integer>> modulationEnvelopes() { return programs(Envelope.MODULATION); }
    private Map<Integer,List<Integer>> programs(String type) {
        Map<Integer,List<Integer>> result=new LinkedHashMap<>();
        for(Envelope envelope:envelopes) if(type.equals(envelope.type())) result.put(result.size()+1,envelope.bytes());
        return Collections.unmodifiableMap(result);
    }
}

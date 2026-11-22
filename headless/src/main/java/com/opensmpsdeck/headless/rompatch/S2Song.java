package com.opensmpsdeck.headless.rompatch;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Standalone S2 bank with its package PSG and DAC bindings. */
public record S2Song(byte[] bank, int base, int localVoiceCount, Map<Integer, byte[]> envelopes,
                     Map<Integer, DacSample> drums, List<Integer> relocations) {
    public S2Song {
        bank = bank.clone();
        envelopes = copy(envelopes);
        drums = Map.copyOf(drums);
        relocations = List.copyOf(relocations);
    }

    @Override public byte[] bank() { return bank.clone(); }

    @Override public Map<Integer, byte[]> envelopes() { return copy(envelopes); }

    private static Map<Integer, byte[]> copy(Map<Integer, byte[]> source) {
        var result = new TreeMap<Integer, byte[]>();
        source.forEach((index, data) -> result.put(index, data.clone()));
        return Map.copyOf(result);
    }

    public static S2Song from(CompiledProject compiled, int voiceCount, List<Integer> relocations) {
        var envelopes = new TreeMap<Integer, byte[]>();
        compiled.psgEnvelopes().forEach((index, envelope) -> {
            byte[] values = new byte[envelope.size()];
            for (int i = 0; i < values.length; i++) values[i] = envelope.get(i).byteValue();
            envelopes.put(index, values);
        });
        var drums = new TreeMap<Integer, DacSample>();
        var nativeProgram = compiled.nativeProgram();
        if (nativeProgram == null) {
            for (var sample : compiled.samples()) drums.put(DacSample.FIRST_NOTE + drums.size(), sample);
        } else {
            var samples = new TreeMap<String, DacSample>();
            compiled.samples().forEach(sample -> samples.put(sample.id(), sample));
            nativeProgram.dacSlots().forEach(slot -> {
                var sample = samples.get(slot.sampleId());
                drums.put(slot.note(), new DacSample(sample.id(), sample.name(), slot.rate(), sample.pcmBase64(), sample.origin()));
            });
        }
        return new S2Song(compiled.song(), nativeProgram == null ? 0 : nativeProgram.bankBase(),
                voiceCount, envelopes, drums, relocations);
    }
}

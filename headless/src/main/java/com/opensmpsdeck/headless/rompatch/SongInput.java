package com.opensmpsdeck.headless.rompatch;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record SongInput(int base, int header, int voiceCount,
                 Map<Integer, List<Integer>> psg, Map<Integer, List<Integer>> modulation,
                 Map<Integer, Sample> dac, List<Integer> relocations, CompiledProject project) {
    record Sample(String id, int rate, byte[] pcm) {}

    static SongInput of(CompiledProject compiled, byte[] bank, List<Integer> relocations) {
        var nativeProgram = compiled.nativeProgram();
        Map<Integer, Sample> dac = new LinkedHashMap<>();
        if (nativeProgram == null) {
            for (var sample : compiled.samples())
                dac.put(DacSample.FIRST_NOTE + dac.size(), new Sample(sample.id(), sample.rate(), sample.pcm()));
        } else {
            var byId = new LinkedHashMap<String, DacSample>();
            compiled.samples().forEach(sample -> byId.put(sample.id(), sample));
            for (var slot : nativeProgram.dacSlots()) {
                var sample = byId.get(slot.sampleId());
                dac.put(slot.note(), new Sample(sample.id(), slot.rate(), sample.pcm()));
            }
        }
        return new SongInput(nativeProgram == null ? 0 : nativeProgram.bankBase(),
                nativeProgram == null ? 0 : nativeProgram.headerOffset(),
                nativeProgram == null ? voices(bank) : nativeProgram.localVoiceCount(),
                compiled.psgEnvelopes(), compiled.modulationEnvelopes(), dac, relocations, compiled);
    }

    private static int voices(byte[] bank) {
        int pointer = SongLayout.word(bank, 0);
        if (pointer == 0) return 0;
        if (pointer >= bank.length || (bank.length - pointer) % 25 != 0)
            throw SongLayout.invalid(pointer, "compiled bank does not end with complete FM voices");
        return (bank.length - pointer) / 25;
    }
}

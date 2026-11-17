package com.opensmpsdeck.project;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeProgramTest {
    private final byte[] bank = {42, 0, 0, 1, 0, 1, (byte) 192, 0, 0, 0, 0};
    private final NativeProgram program = NativeProgram.of(0x8000,1,0,180,bank,List.of(),List.of());
    private final StudioProject project = new StudioProject(5,"Exact bank","S3K",24,192,List.of(),
            List.of(),List.of(),List.of(),List.of(),program);

    @Test void exactBankAndHeaderOffsetSurviveRoundTrip() {
        String json = ProjectFormat.write(project);
        assertFalse(json.contains("\"tempo\""));
        assertFalse(json.contains("\"sequences\""));
        assertFalse(json.contains("\"clips\""));
        assertEquals(project,ProjectFormat.read(json));
        assertThrows(IllegalArgumentException.class,() -> ProjectFormat.read(json.replace("\"voices\"","\"tempo\":192,\"voices\"")));
        assertArrayEquals(bank,ProjectCompiler.compile(project));
        byte[] exposed = program.bank();
        exposed[0] = 0;
        assertArrayEquals(bank,program.bank());
        assertTrue(ProjectCompiler.compileWithAssets(project).requiresAssets());
        bank[6] = 26;
        StudioProject edited = ProjectEdits.setNativeProgram(project,program.withBank(bank));
        assertEquals(26,edited.tempo());
        assertArrayEquals(bank,ProjectCompiler.compile(edited));
    }

    @Test void preservesRawNativeEnvelopesOutsideTheAuthoredProfile() {
        var raw = new Envelope("mod","Raw modulation",Envelope.MODULATION,List.of(0xFF,0x82,0x84));
        NativeProgram bound = NativeProgram.of(0,1,0,1,bank,
                List.of(new NativeProgram.EnvelopeSlot("mod",37)),List.of());
        StudioProject nativeProject = ProjectEdits.setNativeProgram(ProjectEdits.addEnvelope(project,raw),bound);
        assertEquals(nativeProject,ProjectFormat.read(ProjectFormat.write(nativeProject)));
        assertEquals(Map.of(37,raw.bytes()),ProjectCompiler.compileWithAssets(nativeProject).modulationEnvelopes());

        StudioProject authored = new StudioProject(4,"Authored","S3K",24,192,List.of(),
                List.of(),List.of(),List.of(raw),List.of());
        assertThrows(IllegalArgumentException.class,() -> ProjectFormat.write(authored));
        var outOfRange = new Envelope("mod","Bad modulation",Envelope.MODULATION,List.of(256));
        assertThrows(IllegalArgumentException.class,() -> ProjectEdits.updateEnvelope(nativeProject,outOfRange));
    }

    @Test void validatesHeaderAndExplicitResourceBindings() {
        assertThrows(IllegalArgumentException.class,() -> NativeProgram.of(1,1,0,1,bank,List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,() -> NativeProgram.of(0,2,0,1,bank,List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,() -> new NativeProgram(0,1,0,1,
                program.bankBase64().replace("=",""),List.of(),List.of()));
        var dacSlot = new NativeProgram.DacSlot(0xDF,"sample",0);
        assertThrows(IllegalArgumentException.class,() -> NativeProgram.of(0,1,0,1,bank,List.of(),List.of(dacSlot,dacSlot)));

        var psg = new Envelope("psg","PSG",Envelope.PSG_VOLUME,List.of(0x81));
        var mod = new Envelope("mod","MOD",Envelope.MODULATION,List.of(0x83));
        var psgSlot = new NativeProgram.EnvelopeSlot("psg",127);
        var modSlot = new NativeProgram.EnvelopeSlot("mod",127);
        NativeProgram bindings = NativeProgram.of(0,1,0,1,bank,
                List.of(psgSlot,modSlot,new NativeProgram.EnvelopeSlot("psg",255)),List.of(dacSlot));
        assertThrows(IllegalArgumentException.class,() -> ProjectEdits.setNativeProgram(project,bindings));
        DacSample sample = DacSample.of("sample","Sample",17,new byte[]{37});
        StudioProject withAssets = ProjectEdits.addSample(ProjectEdits.addEnvelope(ProjectEdits.addEnvelope(project,psg),mod),sample);
        StudioProject bound = ProjectEdits.setNativeProgram(withAssets,bindings);
        CompiledProject compiled = ProjectCompiler.compileWithAssets(bound);
        assertEquals(Map.of(127,List.of(0x81),255,List.of(0x81)),compiled.psgEnvelopes());
        assertEquals(Map.of(127,List.of(0x83)),compiled.modulationEnvelopes());
        assertEquals(List.of(dacSlot),compiled.nativeProgram().dacSlots());
        assertEquals(bound,ProjectFormat.read(ProjectFormat.write(bound)));
        assertThrows(IllegalArgumentException.class,() -> ProjectEdits.deleteSample(bound,"sample"));
        assertThrows(IllegalArgumentException.class,() -> ProjectEdits.deleteEnvelope(bound,"psg"));
        for (var invalidSlots : List.of(List.of(psgSlot,psgSlot),
                List.of(new NativeProgram.EnvelopeSlot("mod",128)))) {
            var invalid = NativeProgram.of(0,1,0,1,bank,invalidSlots,List.of());
            assertThrows(IllegalArgumentException.class,() -> ProjectEdits.setNativeProgram(withAssets,invalid));
        }
    }
}

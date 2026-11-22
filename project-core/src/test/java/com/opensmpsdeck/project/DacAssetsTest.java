package com.opensmpsdeck.project;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DacAssetsTest {
    private static DacSample sample(String id,int size,int rate) {
        byte[] pcm=new byte[size]; Arrays.fill(pcm,(byte)0x80); return DacSample.of(id,id,rate,pcm);
    }
    private static Sequence dac(List<SampleTrigger> triggers,int length) {
        return new Sequence("drums","Drums","DAC",length,null,127,List.of(),List.of(),"RESET",null,null,triggers);
    }
    private static StudioProject project(Sequence sequence,List<Clip> clips,List<DacSample> samples) {
        return new StudioProject(4,"DAC fixture","S3K",12,0,List.of(),List.of(sequence),clips,List.of(),samples);
    }
    private static StudioProject fixture() {
        return project(dac(List.of(new SampleTrigger("hit",0,"kick")),12),List.of(new Clip("c","drums","DAC",0,12)),List.of(sample("kick",1,1)));
    }
    private static int word(byte[] data,int at) { return (data[at]&255)|((data[at+1]&255)<<8); }
    private static byte[] bytes(int... values) { byte[] result=new byte[values.length];for(int i=0;i<values.length;i++)result[i]=(byte)values[i];return result; }

    @Test void samplesUseCanonicalPaddedBase64AndDefensivePcmArrays() {
        byte[] raw={(byte)0x80,0,(byte)0xFF}; DacSample sample=DacSample.of("s","Sample",1,raw);raw[0]=0;
        assertEquals("gAD/",sample.pcmBase64());assertArrayEquals(bytes(128,0,255),sample.pcm());
        byte[] decoded=sample.pcm();decoded[0]=0;assertEquals(128,sample.pcm()[0]&255);
        for(String base64:List.of("","gA","gA=","gA==\n","gB==","gA__","!!!!")) {
            assertThrows(IllegalArgumentException.class,()->DacSample.validateAll(List.of(new DacSample("s","Sample",1,base64))),base64);
        }
        assertThrows(IllegalArgumentException.class,()->DacSample.validateAll(List.of(new DacSample(" ","Sample",1,"gA=="))));
        assertThrows(IllegalArgumentException.class,()->DacSample.validateAll(List.of(new DacSample("s"," ",1,"gA=="))));
        for(int rate:List.of(-1,256)) assertThrows(IllegalArgumentException.class,()->DacSample.sampleRateHz(rate));
    }
    @Test void sampleLimitsCoverCountIndividualSizeAndAggregateDecodedBytes() {
        DacSample.validateAll(List.of(sample("max",65535,255)));
        assertThrows(IllegalArgumentException.class,()->DacSample.of("s","s",1,new byte[65536]));
        assertThrows(IllegalArgumentException.class,()->DacSample.of("s","s",1,new byte[0]));
        List<DacSample> samples=new ArrayList<>();for(int i=0;i<95;i++)samples.add(sample("s"+i,1,1));DacSample.validateAll(samples);
        samples.add(sample("overflow",1,1));assertThrows(IllegalArgumentException.class,()->DacSample.validateAll(samples));
        assertThrows(IllegalArgumentException.class,()->DacSample.validateAll(List.of(sample("same",1,1),sample("same",1,1))));
        samples.clear();for(int i=0;i<8;i++)samples.add(sample("s"+i,65535,1));
        samples.add(sample("last",8,1));DacSample.validateAll(samples);
        samples.set(8,sample("last",9,1));assertThrows(IllegalArgumentException.class,()->DacSample.validateAll(samples));
    }
    @Test void nativeRatesUseDjnzZeroAs256And297BaseCycles() {
        assertEquals(2*53693175.0/15/297,DacSample.sampleRateHz(1),1e-10);
        assertEquals(2*53693175.0/15/(297+26*255),DacSample.sampleRateHz(0),1e-10);
        assertEquals(DacSample.sampleRateHz(255),sample("s",1,255).sampleRateHz());
        assertEquals(297,DacSample.BASE_CYCLES);assertEquals(0x81,DacSample.FIRST_NOTE);
        assertEquals(95,DacSample.MAX_SAMPLES);assertEquals(65535,DacSample.MAX_SAMPLE_BYTES);
    }
    @Test void strictVersionFourRoundTripRequiresAssetsAndTriggersAtEverySource() {
        var project=fixture();String json=ProjectFormat.write(project);assertEquals(project,ProjectFormat.read(json));
        JsonObject tree=JsonParser.parseString(json).getAsJsonObject();
        JsonObject missingSamples=tree.deepCopy();missingSamples.remove("samples");assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(missingSamples.toString()));
        JsonObject missing=tree.deepCopy();missing.getAsJsonArray("sequences").get(0).getAsJsonObject().remove("triggers");
        String noTriggers=missing.toString();assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(noTriggers));
        for(String field:List.of("sampleRate","loop","pcm")) {
            JsonObject extra=tree.deepCopy();extra.getAsJsonArray("samples").get(0).getAsJsonObject().addProperty(field,1);
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(extra.toString()));
        }
        String duplicate=json.replace("\"sampleId\": \"kick\"","\"sampleId\": \"kick\", \"sampleId\": \"kick\"");
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.read(duplicate));
        var old=new StudioProject(3,"Old","S3K",12,0,List.of(),List.of(),List.of(),List.of());
        String oldJson=ProjectFormat.write(old);assertFalse(oldJson.contains("samples"));assertFalse(oldJson.contains("triggers"));
        assertEquals(old,ProjectFormat.read(oldJson));assertArrayEquals(bytes(0,0,1,0,1,0,10,0,0,0,0xF2),ProjectCompiler.compile(old));
    }
    @Test void onlyDacSourcesAcceptUniqueInBoundsTriggersAndDacSettings() {
        var p=fixture();
        for(List<SampleTrigger> triggers:List.of(List.of(new SampleTrigger("x",12,"kick")),List.of(new SampleTrigger("x",-1,"kick")),
                List.of(new SampleTrigger("x",0,"unknown")),List.of(new SampleTrigger("x",0,"kick"),new SampleTrigger("x",2,"kick")),
                List.of(new SampleTrigger("x",0,"kick"),new SampleTrigger("y",0,"kick")))) {
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(dac(triggers,12),p.clips(),p.samples())));
        }
        for(Sequence source:List.of(
                new Sequence("drums","Drums","DAC",12,null,126,List.of(),List.of(),"RESET",null,null,p.sequences().getFirst().triggers()),
                new Sequence("drums","Drums","DAC",12,null,127,List.of(),List.of(),"CARRY",null,null,p.sequences().getFirst().triggers()),
                new Sequence("drums","Drums","DAC",12,null,127,List.of(new NoteEvent("n",0,1,60)),List.of(),"RESET",null,null,p.sequences().getFirst().triggers()),
                new Sequence("drums","Drums","PSG",12,null,127,List.of(),List.of(),"RESET",null,null,p.sequences().getFirst().triggers()))) {
            assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(project(source,p.clips(),p.samples())));
        }
        assertThrows(IllegalArgumentException.class,()->ProjectEdits.moveClip(p,"c","FM6",0));
        var old=new StudioProject(3,p.name(),p.mode(),p.ticksPerBeat(),p.tempo(),p.voices(),p.sequences(),p.clips(),p.envelopes(),p.samples());
        assertThrows(IllegalArgumentException.class,()->ProjectFormat.validate(old));
    }
    @Test void sampleAndTriggerEditsUpgradeVersionAndPreserveAllFields() {
        var p=new StudioProject(3,"New","S3K",12,0,List.of(),List.of(),List.of(),List.of());
        p=ProjectEdits.addSample(p,sample("kick",1,1));assertEquals(4,p.formatVersion());
        p=ProjectEdits.addSequence(p,dac(List.of(),12));p=ProjectEdits.addTrigger(p,"drums",new SampleTrigger("hit",0,"kick"));
        p=ProjectEdits.addClip(p,new Clip("c","drums","DAC",0,12));
        p=ProjectEdits.updateTrigger(p,"drums",new SampleTrigger("hit",4,"kick"));
        p=ProjectEdits.updateSample(p,DacSample.of("kick","Renamed",2,bytes(1,2)));
        p=ProjectEdits.moveClip(p,"c","DAC",12);p=ProjectEdits.resizeClip(p,"c",24);
        assertEquals(4,p.sequences().getFirst().triggers().getFirst().tick());assertEquals("Renamed",p.samples().getFirst().name());
        var referenced=p;assertThrows(IllegalArgumentException.class,()->ProjectEdits.deleteSample(referenced,"kick"));
        p=ProjectEdits.deleteTrigger(p,"drums","hit");p=ProjectEdits.deleteSample(p,"kick");assertTrue(p.samples().isEmpty());
        var withDac=ProjectEdits.addSequence(new StudioProject(1,"Old","S3K",12,0,List.of(),List.of(),List.of()),dac(List.of(),12));
        assertEquals(4,withDac.formatVersion());
    }
    @Test void dacSlotUsesSampleOrdinalsAndExplicitRestContinuationWithoutHoldOrBareDuration() {
        var source=dac(List.of(new SampleTrigger("one",5,"second"),new SampleTrigger("two",270,"first")),300);
        var p=project(source,List.of(new Clip("c","drums","DAC",0,300)),List.of(sample("first",1,1),sample("second",1,1)));
        byte[] b=ProjectCompiler.compile(p);assertEquals(1,b[2]);assertEquals(0,b[3]);
        assertArrayEquals(bytes(0x80,5,0x82,127,0x80,127,0x80,11,0x81,30,0xF2),Arrays.copyOfRange(b,word(b,6),b.length));
        assertEquals(300,ProjectCompiler.dacEndTick(p));
    }
    @Test void repeatsTrimTriggersExactlyAndCanUseTheLastCustomMapNote() {
        List<DacSample> samples=new ArrayList<>();for(int i=0;i<95;i++)samples.add(sample("s"+i,1,1));
        var source=dac(List.of(new SampleTrigger("one",2,"s94"),new SampleTrigger("trimmed",8,"s0")),10);
        var p=project(source,List.of(new Clip("c","drums","DAC",3,15)),samples);
        byte[] b=ProjectCompiler.compile(p);
        assertArrayEquals(bytes(0x80,3,0x80,2,0xDF,6,0x81,2,0x80,2,0xDF,3,0x80,2,0xF2),Arrays.copyOfRange(b,word(b,6),b.length));
        assertEquals(20,ProjectCompiler.dacEndTick(p));
    }
    @Test void nativeTailUsesOnlyTheLastSurvivingTriggerAndIncludesTempoAndBusGuard() {
        var slow=sample("slow",65535,0);
        var fast=sample("fast",1,1);
        var source=dac(List.of(new SampleTrigger("long",0,"slow"),new SampleTrigger("short",7,"fast")),10);
        var p=project(source,List.of(new Clip("c","drums","DAC",0,7)),List.of(slow,fast));
        assertEquals(3918,ProjectCompiler.dacEndTick(p));
        byte[] song=ProjectCompiler.compile(p, ProjectCompiler.Encoding.LINEAR);
        int at=word(song,6);
        assertEquals(0x81,song[at++]&255);
        int ticks=song[at++]&255;
        while((song[at]&255)==0x80) {
            at++;
            ticks+=song[at++]&255;
        }
        assertEquals(3918,ticks);
        assertEquals(0xF2,song[at]&255);

        var delayed=new StudioProject(4,p.name(),p.mode(),p.ticksPerBeat(),128,p.voices(),p.sequences(),p.clips(),p.envelopes(),p.samples());
        assertEquals(1960,ProjectCompiler.dacEndTick(delayed));
        p=ProjectEdits.resizeClip(p,"c",8);
        assertEquals(12,ProjectCompiler.dacEndTick(p));
    }
    @Test void tailCalculationDoesNotExpandLargeRepeatsOrOverflowTimeline() {
        var p=project(dac(List.of(new SampleTrigger("hit",0,"slow")),1),
                List.of(new Clip("c","drums","DAC",0,Integer.MAX_VALUE)),List.of(sample("slow",65535,0)));
        assertEquals(Integer.MAX_VALUE-1L+3918,ProjectCompiler.dacEndTick(p));
        var error=assertThrows(IllegalArgumentException.class,()->ProjectCompiler.compile(p));
        assertTrue(error.getMessage().contains("32 KiB"));
    }
    @Test void aRepeatedEmptyDacSourceUsesCompactRests() {
        var p=project(dac(List.of(),1),List.of(new Clip("c","drums","DAC",0,20_000)),List.of());
        byte[] song=ProjectCompiler.compile(p, ProjectCompiler.Encoding.LINEAR);
        assertEquals(10+2*158+1,song.length);
        int elapsed=0;
        for(int at=word(song,6);at<song.length-1;at+=2) {
            assertEquals(0x80,song[at]&255);
            elapsed+=song[at+1]&255;
        }
        assertEquals(20_000,elapsed);
        assertEquals(0xF2,song[song.length-1]&255);
    }
    @Test void upgradingWithSamplesRetainsVersionThreeEnvelopeBytesAndVersionFourAssets() {
        var modulation=new Envelope("m","Modulation",Envelope.MODULATION,List.of(1,0x83));
        var volume=new Envelope("p","PSG volume",Envelope.PSG_VOLUME,List.of(2,0x83));
        var source=new Sequence("tone","Tone","PSG",4,null,100,List.of(new NoteEvent("n",0,4,60)),
                List.of(),"RESET","p","m");
        var old=new StudioProject(3,"Tone","S3K",12,0,List.of(),List.of(source),
                List.of(new Clip("c","tone","PSG1",0,8)),List.of(modulation,volume));
        var kick=sample("kick",1,1);
        var upgraded=ProjectEdits.addSample(old,kick);
        assertArrayEquals(ProjectCompiler.compile(old),ProjectCompiler.compile(upgraded));
        var extra=new Envelope("unused","Unused",Envelope.MODULATION,List.of(0x83));
        upgraded=ProjectEdits.addEnvelope(upgraded,extra);
        upgraded=ProjectEdits.updateEnvelope(upgraded,new Envelope("unused","Updated",Envelope.MODULATION,List.of(1,0x83)));
        upgraded=ProjectEdits.deleteEnvelope(upgraded,"unused");
        assertEquals(4,upgraded.formatVersion());
        assertEquals(List.of(kick),upgraded.samples());
        assertArrayEquals(ProjectCompiler.compile(old),ProjectCompiler.compile(upgraded));
    }

    @Test void compiledAssetsRetainDeclarationsEvenWhenNoDacClipUsesThem() {
        var p=project(dac(List.of(),12),List.of(),List.of(sample("unused",1,1)));
        var compiled=ProjectCompiler.compileWithAssets(p);assertTrue(compiled.requiresAssets());assertEquals(p.samples(),compiled.samples());
        assertEquals(0,ProjectCompiler.dacEndTick(p));
        assertEquals(List.of(),new CompiledProject(compiled.song(),List.of()).samples());
    }
}

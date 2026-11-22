package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StudioPlaybackTest {
    private StudioProject song(int tempo){
        return new StudioProject(1,"Timing","S3K",24,tempo,List.of(),
                List.of(new Sequence("s","Tone","PSG",96,null,100,List.of(new NoteEvent("n",84,12,60)))),
                List.of(new Clip("c","s","PSG1",0,96)), TargetGame.S3K, null);
    }
    @Test void slowPreviewActuallyIncludesItsLatePsgNote() throws Exception {
        StudioProject project=song(192);int[] frame={0};boolean[] soundedLate={false};
        com.opensmpsdeck.headless.S3kRenderer.render(ProjectCompiler.compile(project),StudioPlayback.presentationCount(project),pcm->{
            if(frame[0]>340&&frame[0]<380){int min=32767,max=-32768;for(short sample:pcm){min=Math.min(min,sample);max=Math.max(max,sample);}if(max-min>100)soundedLate[0]=true;}
            frame[0]++;
        });
        assertEquals(452,frame[0]);assertTrue(soundedLate[0],"late note must sound before preview finishes");
    }
    @Test void monitorBufferRendersAnEditedSonic1ProjectThroughTheClassicDriver() throws Exception {
        var voice = DemoProject.create().voices().getFirst();
        var vibrato = new CommandEvent("vibrato", 0, NativeCommand.MODULATION, List.of(1, 2, 7, 8));
        var source = new StudioProject(4, "FM vibrato", "S3K", 24, 0, List.of(voice),
                List.of(new Sequence("s", "Tone", "FM", 24, voice.id(), 120,
                        List.of(new NoteEvent("n", 0, 24, 77)), List.of(vibrato), "RESET")),
                List.of(new Clip("c", "s", "FM1", 0, 24)), TargetGame.S1, 7);
        var edited = ProjectEdits.moveClip(source, "c", "FM2", 2);
        assertEquals(TargetGame.S1, edited.target());
        assertEquals(7, edited.nativeTempo());
        var mask = com.opensmpsdeck.headless.S3kRenderer.ChannelMask.NONE;
        assertEquals(6.0, StudioPlayback.tickAtPresentation(edited, 7));
        var buffer = new StudioPlayback.RenderBuffer(32);
        buffer.render(edited, mask);
        var classic = new java.util.ArrayList<short[]>();
        com.opensmpsdeck.headless.TargetPlayback.prepare(edited).render(32, pcm -> classic.add(pcm.clone()));
        var s3k = new java.util.ArrayList<short[]>();
        com.opensmpsdeck.headless.TargetPlayback.prepare(edited.withTarget(TargetGame.S3K))
                .render(32, pcm -> s3k.add(pcm.clone()));
        boolean differs = false;
        for (int frame = 0; frame < classic.size(); frame++) {
            assertArrayEquals(classic.get(frame), buffer.get(frame));
            differs |= !java.util.Arrays.equals(s3k.get(frame), buffer.get(frame));
        }
        assertTrue(differs, "Fixture must distinguish S1 and S3K playback");
    }

    @Test void previewLengthUsesS3kCarryAsDelayRatherThanSonic2CarryAsTick(){
        assertEquals(452,StudioPlayback.presentationCount(song(192)));
        assertEquals(256,StudioPlayback.presentationCount(song(128)));
        assertEquals(158,StudioPlayback.presentationCount(song(0)));
        assertEquals(24.0,StudioPlayback.tickAtPresentation(song(192),96),0.00001);
        assertEquals(96.0,StudioPlayback.tickAtPresentation(song(0),96),0.00001);
    }
}

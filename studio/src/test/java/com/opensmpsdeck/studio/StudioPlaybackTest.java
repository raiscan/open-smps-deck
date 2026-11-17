package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StudioPlaybackTest {
    private StudioProject song(int tempo){
        return new StudioProject(1,"Timing","S3K",24,tempo,List.of(),
                List.of(new Sequence("s","Tone","PSG",96,null,100,List.of(new NoteEvent("n",84,12,60)))),
                List.of(new Clip("c","s","PSG1",0,96)));
    }
    @Test void slowPreviewActuallyIncludesItsLatePsgNote() throws Exception {
        StudioProject project=song(192);int[] frame={0};boolean[] soundedLate={false};
        com.opensmpsdeck.headless.S3kRenderer.render(ProjectCompiler.compile(project),StudioPlayback.presentationCount(project),pcm->{
            if(frame[0]>340&&frame[0]<380){int min=32767,max=-32768;for(short sample:pcm){min=Math.min(min,sample);max=Math.max(max,sample);}if(max-min>100)soundedLate[0]=true;}
            frame[0]++;
        });
        assertEquals(452,frame[0]);assertTrue(soundedLate[0],"late note must sound before preview finishes");
    }
    @Test void previewLengthUsesS3kCarryAsDelayRatherThanSonic2CarryAsTick(){
        assertEquals(452,StudioPlayback.presentationCount(song(192)));
        assertEquals(256,StudioPlayback.presentationCount(song(128)));
        assertEquals(158,StudioPlayback.presentationCount(song(0)));
        assertEquals(24.0,StudioPlayback.tickAtPresentation(song(192),96),0.00001);
        assertEquals(96.0,StudioPlayback.tickAtPresentation(song(0),96),0.00001);
    }
}

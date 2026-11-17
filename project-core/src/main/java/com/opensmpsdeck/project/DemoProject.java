package com.opensmpsdeck.project;

import java.util.ArrayList;
import java.util.List;

/** An original eight-bar sketch: two linked lead phrases, a bass ostinato and PSG answers. */
public final class DemoProject {
    private DemoProject() {}
    public static StudioProject create() {
        // Native parameter groups: feedback/algorithm, DT/MUL, RS/AR, AM/D1R,
        // D2R, D1L/RR, TL. No operator reordering or ROM assets.
        Voice lead=new Voice("voice-lead","Glass keys",List.of(0x04,1,2,1,2,31,31,31,31,8,7,8,7,3,3,3,3,0x2A,0x2A,0x2A,0x2A,34,8,40,8));
        Voice bass=new Voice("voice-bass","Round bass",List.of(0x04,1,1,1,1,31,31,31,31,10,8,10,8,3,3,3,3,0x1A,0x1A,0x1A,0x1A,32,12,40,12));
        int bar=48; // Twelve ticks per beat, four beats per bar: a short, renderable sketch.
        Sequence a=new Sequence("seq-lead-a","Lantern / A","FM",bar*2,lead.id(),112,notes("a",new int[][]{{0,9,72},{12,9,76},{24,18,79},{48,9,76},{60,9,74},{72,18,72}}));
        Sequence b=new Sequence("seq-lead-b","Lantern / B","FM",bar*2,lead.id(),112,notes("b",new int[][]{{0,9,69},{12,9,72},{24,18,76},{48,9,74},{60,9,71},{72,18,67}}));
        Sequence low=new Sequence("seq-bass","Stepping stones","FM",bar*2,bass.id(),115,notes("bass",new int[][]{{0,18,48},{24,18,55},{48,18,45},{72,18,52}}));
        Sequence answer=new Sequence("seq-answer","Fireflies","PSG",bar,null,76,notes("p",new int[][]{{12,6,84},{24,6,88},{36,6,91}}));
        StudioProject project=new StudioProject(1,"Lantern walk","S3K",12,192,List.of(lead,bass),List.of(a,b,low,answer),List.of(
                new Clip("clip-a1",a.id(),"FM1",0,bar*2), new Clip("clip-b1",b.id(),"FM1",bar*2,bar*2),
                new Clip("clip-a2",a.id(),"FM1",bar*4,bar*2),new Clip("clip-b2",b.id(),"FM1",bar*6,bar*2),
                new Clip("clip-bass",low.id(),"FM3",0,bar*8),
                new Clip("clip-answer1",answer.id(),"PSG1",bar,bar), new Clip("clip-answer2",answer.id(),"PSG1",bar*3,bar),
                new Clip("clip-answer3",answer.id(),"PSG1",bar*5,bar*3)));
        ProjectFormat.validate(project); return project;
    }
    private static List<NoteEvent> notes(String prefix,int[][] data) {
        List<NoteEvent> result=new ArrayList<>(); for(int i=0;i<data.length;i++) result.add(new NoteEvent(prefix+"-"+(i+1),data[i][0],data[i][1],data[i][2])); return result;
    }
}

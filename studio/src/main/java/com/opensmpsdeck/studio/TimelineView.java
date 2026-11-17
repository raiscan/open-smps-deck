package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import com.opensmpsdeck.project.Sequence;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.TransferMode;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import java.util.*;
import java.util.function.*;

/** Clip arrangement on positional hardware lanes; right-edge drag repeats source. */
final class TimelineView extends ViewportCanvas {
    static final int GUTTER=104, RULER=40, ROW=52;
    static final List<String> LANES=List.of("FM1","FM2","FM3","FM4","FM5","PSG1","PSG2","PSG3");
    StudioProject project;
    String selected;
    String selectedLane="FM1";
    double scale=5;
    double playhead=-1;
    Runnable openNotes=()->{};
    Consumer<String> selection=ignored -> {};
    Consumer<Clip> edit=ignored -> {};
    Consumer<Clip> placement=ignored -> {};
    private Clip dragging, ghost;
    private double pressX;
    private boolean resizing;

    TimelineView() {
        dimensions(1400,RULER+LANES.size()*ROW+20);
        canvas.setOnMousePressed(e -> {
            canvas.requestFocus(); selectedLane=laneAt(e.getY());
            Clip clip=hit(e.getX(),e.getY());
            selected=clip==null?null:clip.id(); selection.accept(selected);
            dragging=clip; ghost=clip; pressX=e.getX();
            resizing=clip!=null && e.getX()>=GUTTER+(clip.startTick()+clip.lengthTicks())*scale-offset-10;
            draw();
        });
        canvas.setOnMouseDragged(e -> {
            if(dragging==null) return;
            int delta=snap((e.getX()-pressX)/scale);
            if(resizing) ghost=new Clip(dragging.id(),dragging.sequenceId(),dragging.lane(),dragging.startTick(),(int)Math.clamp((long)dragging.lengthTicks()+delta,snapUnit(),Integer.MAX_VALUE));
            else ghost=new Clip(dragging.id(),dragging.sequenceId(),laneAt(e.getY()),(int)Math.clamp((long)dragging.startTick()+delta,0,Integer.MAX_VALUE),dragging.lengthTicks());
            draw();
        });
        canvas.setOnMouseReleased(e -> {
            Clip changed=ghost; dragging=null; ghost=null;
            if(changed!=null) edit.accept(changed);
            draw();
        });
        canvas.setOnMouseClicked(e->{if(e.getClickCount()==2&&e.isStillSincePress()&&selected!=null)openNotes.run();});
        canvas.setOnDragOver(e -> {
            if(e.getDragboard().hasString() && e.getDragboard().getString().startsWith("smps-sequence:")) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        canvas.setOnDragDropped(e -> {
            String value=e.getDragboard().getString();
            if(value!=null && value.startsWith("smps-sequence:")) {
                String id=value.substring("smps-sequence:".length());
                Sequence source=sequence(id);
                if(source!=null) placement.accept(new Clip("clip-"+UUID.randomUUID(),id,laneAt(e.getY()),Math.max(0,snap((e.getX()-GUTTER+offset)/scale)),source.lengthTicks()));
                e.setDropCompleted(source!=null);
            } else e.setDropCompleted(false);
            e.consume();
        });
    }
    void setProject(StudioProject value,String selectedId) {
        if(project!=value){dragging=null;ghost=null;}
        double previousOffset=offset;
        project=value; selected=selectedId;
        int end=project.clips().stream().mapToInt(c->c.startTick()+c.lengthTicks()).max().orElse(0);
        double minimum=project.ticksPerBeat()*32.0;
        dimensions(GUTTER+(Math.max(minimum,end)+project.ticksPerBeat()*4.0)*scale,RULER+LANES.size()*ROW+20);
        double scrollable=Math.max(0,contentWidth-scroll.getViewportBounds().getWidth());
        scroll.setHvalue(scrollable==0?0:Math.clamp(previousOffset/scrollable,0,1));
        updateViewport();
    }
    private int snapUnit() { return project==null?6:Math.max(1,project.ticksPerBeat()/4); }
    private int snap(double ticks) { return (int)Math.clamp(Math.round(ticks/snapUnit())*snapUnit(),Integer.MIN_VALUE,Integer.MAX_VALUE); }
    private String laneAt(double y) { return LANES.get(Math.clamp((int)((y-RULER)/ROW),0,LANES.size()-1)); }
    private Sequence sequence(String id) { return project==null?null:project.sequences().stream().filter(s->s.id().equals(id)).findFirst().orElse(null); }
    private Clip hit(double x,double y) {
        if(project==null || y<RULER || x<GUTTER) return null;
        String lane=laneAt(y); double tick=(x-GUTTER+offset)/scale;
        return project.clips().stream().filter(c->c.lane().equals(lane)&&tick>=c.startTick()&&tick<c.startTick()+c.lengthTicks()).findFirst().orElse(null);
    }
    @Override void draw() {
        GraphicsContext g=canvas.getGraphicsContext2D(); double w=canvas.getWidth();
        g.setFill(Color.web("#101722")); g.fillRect(0,0,w,canvas.getHeight());
        if(project==null) return;
        g.setFont(Font.font("System",13));
        for(int row=0;row<LANES.size();row++) {
            g.setFill(Color.web(row%2==0?"#141d2a":"#111a26")); g.fillRect(0,RULER+row*ROW,w,ROW);
            g.setStroke(Color.web("#243043")); g.strokeLine(0,RULER+(row+1)*ROW,w,RULER+(row+1)*ROW);
        }
        int beat=project.ticksPerBeat(); long first=Math.max(0,(long)Math.floor(offset/(beat*scale)));
        long last=(long)Math.ceil((offset+w)/(beat*scale));
        for(long b=first;b<=last;b++) {
            double x=GUTTER+(double)b*beat*scale-offset;
            if(x<GUTTER) continue;
            boolean bar=b%4==0;
            g.setStroke(Color.web(bar?"#344257":"#233043")); g.strokeLine(x,RULER,x,canvas.getHeight());
            g.setFill(Color.web(bar?"#d1dbe8":"#6f8198"));
            g.fillText(bar?String.valueOf(b/4+1):"·",x+6,24);
        }
        for(Clip clip:project.clips()) drawClip(g,ghost!=null&&clip.id().equals(ghost.id())?ghost:clip);
        if(playhead>=0) {
            double x=GUTTER+playhead*scale-offset;
            if(x>=GUTTER) { g.setStroke(Color.web("#ffcb77")); g.strokeLine(x,RULER,x,canvas.getHeight()); }
        }
        g.setFill(Color.web("#172130")); g.fillRect(0,0,GUTTER,canvas.getHeight());
        g.setFill(Color.web("#74869d")); g.fillText("CHANNEL",14,24);
        for(int row=0;row<LANES.size();row++) {
            g.setFill(Color.web(LANES.get(row).equals(selectedLane)?"#d8f7ee":"#94a6bc"));
            g.fillText(LANES.get(row),16,RULER+row*ROW+29);
            g.setFill(Color.web(row<5?"#5cddbc":"#7daefb")); g.fillOval(78,RULER+row*ROW+21,5,5);
        }
        g.setFill(Color.web("#77889d")); g.fillText("DAC / sample support follows",GUTTER+12,canvas.getHeight()-5);
    }
    private void drawClip(GraphicsContext g,Clip c) {
        Sequence source=sequence(c.sequenceId()); if(source==null) return;
        double x=GUTTER+c.startTick()*scale-offset, width=c.lengthTicks()*scale;
        double y=RULER+LANES.indexOf(c.lane())*ROW+6;
        if(x+width<GUTTER || x>canvas.getWidth()) return;
        Color fill=Color.web(source.type().equals("FM")?"#245f58":"#284d79");
        g.setFill(fill); g.fillRoundRect(x,y,width,ROW-12,7,7);
        g.setStroke(Color.web(c.id().equals(selected)?"#d9fff0":source.type().equals("FM")?"#48bca1":"#6399db"));
        g.setLineWidth(c.id().equals(selected)?2:1); g.strokeRoundRect(x+1,y+1,width-2,ROW-14,6,6); g.setLineWidth(1);
        g.save(); g.beginPath(); g.rect(Math.max(GUTTER,x)+3,y+2,Math.max(0,Math.min(x+width,canvas.getWidth())-Math.max(GUTTER,x)-6),ROW-16); g.clip();
        int firstRepeat=Math.max(0,(int)((offset-GUTTER-c.startTick()*scale)/(source.lengthTicks()*scale)));
        for(int repeat=firstRepeat;repeat*source.lengthTicks()<c.lengthTicks();repeat++) {
            double origin=x+repeat*source.lengthTicks()*scale; if(origin>canvas.getWidth()) break;
            if(repeat>0) { g.setStroke(Color.web("#89d7cf",0.4)); g.strokeLine(origin,y+4,origin,y+ROW-16); }
            for(NoteEvent note:source.notes()) {
                double noteX=origin+note.tick()*scale;
                if(noteX>=x+width) continue;
                g.setStroke(Color.web(source.type().equals("FM")?"#9ef0d7":"#b5d4ff",0.65));
                double noteY=y+31-(note.pitch()%24)*0.6;
                g.strokeLine(noteX,noteY,Math.min(x+width-3,noteX+note.duration()*scale-2),noteY);
            }
        }
        g.setFill(Color.web("#e5f8f4")); g.setFont(Font.font("System",13)); g.fillText(source.name(),Math.max(x+8,GUTTER+8),y+15);
        g.restore();
        g.setFill(Color.web("#d4f3ef",0.7)); g.fillRoundRect(x+width-6,y+12,2,16,2,2);
    }
}

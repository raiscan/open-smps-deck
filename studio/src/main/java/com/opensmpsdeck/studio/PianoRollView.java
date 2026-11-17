package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Sequence;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import java.util.UUID;
import java.util.function.*;

/** Notes are linked source data, shown with pitch vertically and time horizontally. */
final class PianoRollView extends ViewportCanvas {
    static final int KEYBOARD=52, HEADER=26, NOTE_ROW=12;
    Sequence sequence;
    String selected;
    int ticksPerBeat=24, topPitch=76, bottomPitch=48;
    double scale=9;
    Consumer<String> commandSelection=ignored -> {};
    Consumer<String> selection=ignored -> {};
    Consumer<NoteEvent> edit=ignored -> {};
    Consumer<NoteEvent> addition=ignored -> {};
    private NoteEvent dragging, ghost;
    private double pressX,pressY;
    private boolean resizing;
    PianoRollView() {
        dimensions(1000,400);
        canvas.setOnMousePressed(e -> {
            canvas.requestFocus();
            if(sequence!=null&&e.getY()>=HEADER-4&&e.getY()<=HEADER+9){
                var marker=sequence.commands().stream().filter(c->Math.abs(KEYBOARD+c.tick()*scale-offset-e.getX())<=5).findFirst();
                if(marker.isPresent()){commandSelection.accept(marker.get().id());return;}
            }
            NoteEvent note=hit(e.getX(),e.getY()); selected=note==null?null:note.id(); selection.accept(selected);
            dragging=note; ghost=note; pressX=e.getX(); pressY=e.getY();
            resizing=note!=null&&e.getX()>=KEYBOARD+(note.tick()+note.duration())*scale-offset-8;
            if(note==null&&e.getClickCount()==2&&sequence!=null&&e.getX()>KEYBOARD&&e.getY()>HEADER) {
                int tick=Math.max(0,snap((e.getX()-KEYBOARD+offset)/scale));
                if(tick<sequence.lengthTicks()) addition.accept(new NoteEvent("note-"+UUID.randomUUID(),tick,Math.min(unit(),sequence.lengthTicks()-tick),pitchAt(e.getY())));
            }
            draw();
        });
        canvas.setOnMouseDragged(e -> {
            if(dragging==null) return;
            int delta=snap((e.getX()-pressX)/scale);
            ghost=resizing?new NoteEvent(dragging.id(),dragging.tick(),(int)Math.clamp((long)dragging.duration()+delta,1,Integer.MAX_VALUE),dragging.pitch())
                    :new NoteEvent(dragging.id(),(int)Math.clamp((long)dragging.tick()+delta,0,Integer.MAX_VALUE),dragging.duration(),Math.clamp(dragging.pitch()-(int)Math.round((e.getY()-pressY)/NOTE_ROW),minPitch(),maxPitch()));
            draw();
        });
        canvas.setOnMouseReleased(e -> { var change=ghost; dragging=null; ghost=null; if(change!=null) edit.accept(change); draw(); });
    }
    void setSequence(Sequence source,String noteId,int beat) {
        if(sequence!=source){dragging=null;ghost=null;}
        double previousOffset=offset;
        sequence=source; selected=noteId; ticksPerBeat=beat;
        topPitch=source==null?76:Math.min(117,Math.max(72,source.notes().stream().mapToInt(NoteEvent::pitch).max().orElse(72)+4));
        bottomPitch=source==null?48:Math.max(12,Math.min(48,source.notes().stream().mapToInt(NoteEvent::pitch).min().orElse(48)-4));
        dimensions(KEYBOARD+(source==null?beat*8.0:source.lengthTicks())*scale+24,HEADER+(topPitch-bottomPitch+1)*NOTE_ROW);
        double scrollable=Math.max(0,contentWidth-scroll.getViewportBounds().getWidth());
        scroll.setHvalue(scrollable==0?0:Math.clamp(previousOffset/scrollable,0,1));
        updateViewport();
    }
    private int unit(){return Math.max(1,ticksPerBeat/4);}
    private int snap(double tick){return (int)Math.clamp(Math.round(tick/unit())*unit(),Integer.MIN_VALUE,Integer.MAX_VALUE);}
    private int pitchAt(double y){return Math.clamp(topPitch-(int)((y-HEADER)/NOTE_ROW),minPitch(),maxPitch());}
    private int minPitch(){return sequence!=null&&sequence.type().equals("PSG")?45:12;}
    private int maxPitch(){return sequence!=null&&sequence.type().equals("PSG")?117:106;}
    private NoteEvent hit(double x,double y) {
        if(sequence==null||x<KEYBOARD||y<HEADER) return null;
        double tick=(x-KEYBOARD+offset)/scale; int pitch=pitchAt(y);
        return sequence.notes().stream().filter(n->n.pitch()==pitch&&tick>=n.tick()&&tick<n.tick()+n.duration()).findFirst().orElse(null);
    }
    @Override void draw() {
        GraphicsContext g=canvas.getGraphicsContext2D(); double w=canvas.getWidth();
        g.setFill(Color.web("#101722")); g.fillRect(0,0,w,canvas.getHeight());
        g.setFont(Font.font("System",12));
        for(int pitch=topPitch;pitch>=bottomPitch;pitch--) {
            double y=HEADER+(topPitch-pitch)*NOTE_ROW; boolean black=switch(pitch%12){case 1,3,6,8,10->true;default->false;};
            g.setFill(Color.web(black?"#121b28":"#1a2534")); g.fillRect(0,y,w,NOTE_ROW);
            g.setStroke(Color.web(pitch%12==0?"#344356":"#202c3b")); g.strokeLine(0,y,w,y);
        }
        if(sequence!=null) {
            long first=Math.max(0,(long)(offset/(ticksPerBeat*scale)));
            long last=Math.min((long)sequence.lengthTicks()/ticksPerBeat,(long)Math.ceil((offset+w)/(ticksPerBeat*scale)));
            for(long b=first;b<=last;b++) {
                double x=KEYBOARD+(double)b*ticksPerBeat*scale-offset; if(x>w)break; if(x<KEYBOARD)continue;
                g.setStroke(Color.web(b%4==0?"#415169":"#2a374a")); g.strokeLine(x,HEADER,x,canvas.getHeight());
                g.setFill(Color.web("#8fa2bb")); g.fillText(String.valueOf(b+1),x+5,17);
            }
            for(NoteEvent n:sequence.notes()) {
                NoteEvent note=ghost!=null&&ghost.id().equals(n.id())?ghost:n;
                double x=KEYBOARD+note.tick()*scale-offset, y=HEADER+(topPitch-note.pitch())*NOTE_ROW+1;
                g.setFill(Color.web(note.id().equals(selected)?"#b7f7df":"#57bfa6"));
                g.fillRoundRect(x,y,Math.max(3,note.duration()*scale-2),NOTE_ROW-2,3,3);
            }
        }
        if(sequence!=null)for(var command:sequence.commands()){
            double x=KEYBOARD+command.tick()*scale-offset;if(x<KEYBOARD||x>w)continue;
            g.setStroke(Color.web("#ffcb77",.25));g.strokeLine(x,HEADER,x,canvas.getHeight());
            g.setFill(Color.web("#ffcb77"));g.fillOval(x-3,HEADER+1,6,6);
        }
        g.setFill(Color.web("#172130")); g.fillRect(0,0,KEYBOARD,canvas.getHeight());
        for(int pitch=topPitch;pitch>=bottomPitch;pitch--) {
            if(pitch%12==0) {g.setFill(Color.web("#aabbd0"));g.fillText(noteName(pitch),10,HEADER+(topPitch-pitch)*NOTE_ROW+10);}
        }
        g.setFill(Color.web("#8396ad"));g.fillText("PITCH",8,17);
    }
    static String noteName(int midi) {return new String[]{"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"}[midi%12]+(midi/12-1);}
}

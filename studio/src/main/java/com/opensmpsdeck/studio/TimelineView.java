package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import com.opensmpsdeck.project.Sequence;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import java.util.*;
import java.util.function.*;

/** Clip arrangement on positional hardware lanes; right-edge drag repeats source. */
final class TimelineView extends ViewportCanvas {
    static final int GUTTER=104, RULER=40, ROW=52;
    static final List<String> LANES=List.of("FM1","FM2","FM3","FM4","FM5","PSG1","PSG2","PSG3","DAC");
    StudioProject project;
    String selected;
    String selectedLane="FM1";
    boolean laneSelected;
    double scale=5;
    double playhead=-1;
    Runnable openNotes=()->{};
    Consumer<String> selection=ignored -> {};
    Consumer<Clip> edit=ignored -> {};
    Consumer<Clip> placement=ignored -> {};
    final ReferenceLane referenceLane = new ReferenceLane();
    ReviewTransport.Lanes monitor = new ReviewTransport.Lanes();
    Runnable maskChanged = () -> {};
    DoubleConsumer seek = ignored -> {};
    Consumer<ReviewTransport.Range> chooseLoop = ignored -> {};
    Consumer<ReviewNote> openReviewNote = ignored -> {};
    private List<ReviewNote> reviewNotes = List.of();
    private ReviewTransport.Range loop, loopDrag;
    private Double rangeStart;
    private AudioReference reference;
    private final boolean readOnly;
    private Clip dragging, ghost;
    private double pressX;
    private boolean resizing;

    TimelineView() { this(false); }

    TimelineView(boolean readOnly) {
        this.readOnly=readOnly;
        dimensions(1400,RULER+LANES.size()*ROW+20);
        referenceLane.loaded = () -> setProject(project, selected);
        canvas.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            canvas.requestFocus();
            if (e.getX() >= GUTTER) {
                if (e.getY() < RULER && e.isShiftDown()) {
                    rangeStart = secondsAt(e.getX());
                    return;
                }
                var note = markerAt(e.getX(), e.getY());
                if (note != null) {
                    openReviewNote.accept(note);
                    return;
                }
                if (e.getY() < RULER) {
                    laneSelected = false;
                    seek.accept(secondsAt(e.getX()));
                    return;
                }
            }
            laneSelected = e.getY() >= RULER && e.getY() < RULER + LANES.size() * ROW;
            selectedLane=laneAt(e.getY());
            if (e.getY() >= RULER && e.getY() < RULER + LANES.size() * ROW
                    && e.getX() >= 53 && e.getX() < GUTTER) {
                monitor.toggle(selectedLane, e.getX() < 77);
                maskChanged.run();
                draw();
                return;
            }
            Clip clip=hit(e.getX(),e.getY());
            selected=clip==null?null:clip.id(); selection.accept(selected);
            if(readOnly){draw();return;}
            dragging=clip; ghost=clip; pressX=e.getX();
            resizing=clip!=null && e.getX()>=GUTTER+(clip.startTick()+clip.lengthTicks())*scale-offset-10;
            draw();
        });
        canvas.setOnMouseDragged(e -> {
            if (rangeStart != null) {
                double current = secondsAt(e.getX());
                loopDrag = range(current);
                draw();
                return;
            }
            if(dragging==null) return;
            int delta=snap((e.getX()-pressX)/scale);
            if(resizing) ghost=new Clip(dragging.id(),dragging.sequenceId(),dragging.lane(),dragging.startTick(),(int)Math.clamp((long)dragging.lengthTicks()+delta,snapUnit(),Integer.MAX_VALUE));
            else ghost=new Clip(dragging.id(),dragging.sequenceId(),laneAt(e.getY()),(int)Math.clamp((long)dragging.startTick()+delta,0,Integer.MAX_VALUE),dragging.lengthTicks());
            draw();
        });
        canvas.setOnMouseReleased(e -> {
            if (rangeStart != null) {
                var range = range(secondsAt(e.getX()));
                rangeStart = null;
                loopDrag = null;
                if (range != null) chooseLoop.accept(range);
                draw();
                return;
            }
            Clip changed=ghost; dragging=null; ghost=null;
            if(changed!=null) edit.accept(changed);
            draw();
        });
        canvas.setOnMouseClicked(e->{if(e.getClickCount()==2&&e.isStillSincePress()&&selected!=null)openNotes.run();});
        if(readOnly)return;
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
        if(project!=value){dragging=null;ghost=null;rangeStart=null;loopDrag=null;}
        double previousOffset=offset;
        project=value; selected=selectedId;
        if(project==null){updateViewport();return;}
        int end=project.clips().stream().mapToInt(c->c.startTick()+c.lengthTicks()).max().orElse(0);
        double minimum=project.ticksPerBeat()*(readOnly?8.0:32.0);
        double reviewEnd = reviewNotes.stream().mapToDouble(n -> n.endSeconds() == null ? n.startSeconds() : n.endSeconds())
                .max().orElse(0);
        if (reference != null && referenceLane.wave() != null) {
            reviewEnd = Math.max(reviewEnd, reference.offsetSeconds() + referenceLane.wave().duration());
        }
        double reviewTicks = Math.min(reviewEnd * ticksPerSecond(), 10_000_000 / scale);
        dimensions(GUTTER+(Math.max(Math.max(minimum,end), reviewTicks)+project.ticksPerBeat()*4.0)*scale,
                RULER+LANES.size()*ROW+20);
        double scrollable=Math.max(0,contentWidth-scroll.getViewportBounds().getWidth());
        scroll.setHvalue(scrollable==0?0:Math.clamp(previousOffset/scrollable,0,1));
        updateViewport();
    }
    void review(java.nio.file.Path file, AudioReference ref, List<ReviewNote> notes) {
        reference = ref;
        reviewNotes = notes.stream().filter(ReviewNote::isOpen).toList();
        referenceLane.source(file, ref);
        setProject(project, selected);
    }

    void setLoop(ReviewTransport.Range range) {
        loop = range;
        draw();
    }

    void reveal(double seconds) {
        double x = seconds * ticksPerSecond() * scale;
        double width = canvas.getWidth() - GUTTER;
        double scrollable = Math.max(0, contentWidth - canvas.getWidth());
        if ((x < offset || x > offset + width) && scrollable > 0) {
            scroll.setHvalue(Math.clamp((x - width / 4) / scrollable, 0, 1));
        }
    }

    private double ticksPerSecond() {
        return readOnly ? 60 : ReviewTransport.ticksPerSecond(project);
    }

    private double secondsAt(double x) {
        return Math.max(0, (x - GUTTER + offset) / (scale * ticksPerSecond()));
    }

    private double reviewX(double seconds) {
        return GUTTER + seconds * scale * ticksPerSecond() - offset;
    }

    private ReviewTransport.Range range(double other) {
        return Math.abs(other - rangeStart) < .01 ? null
                : new ReviewTransport.Range(Math.min(other, rangeStart), Math.max(other, rangeStart));
    }

    private ReviewNote markerAt(double x, double y) {
        for (var note : reviewNotes) {
            int row = LANES.indexOf(note.laneId() == null ? "" : note.laneId());
            if ((y < RULER || row >= 0 && y >= RULER + row * ROW && y < RULER + (row + 1) * ROW)
                    && Math.abs(reviewX(note.startSeconds()) - x) <= 6) return note;
        }
        return null;
    }

    private int snapUnit() { return project==null?6:Math.max(1,project.ticksPerBeat()/4); }
    private int snap(double ticks) { return (int)Math.clamp(Math.round(ticks/snapUnit())*snapUnit(),Integer.MIN_VALUE,Integer.MAX_VALUE); }
    private String laneAt(double y) { return LANES.get(Math.clamp((int)((y-RULER)/ROW),0,LANES.size()-1)); }
    private Sequence sequence(String id) { return project==null?null:project.sequences().stream().filter(s->s.id().equals(id)).findFirst().orElse(null); }
    private Clip hit(double x,double y) {
        if(project==null || y<RULER || y>=RULER+LANES.size()*ROW || x<GUTTER) return null;
        String lane=laneAt(y); double tick=(x-GUTTER+offset)/scale;
        return project.clips().stream().filter(c->c.lane().equals(lane)&&tick>=c.startTick()&&tick<c.startTick()+c.lengthTicks()).findFirst().orElse(null);
    }
    @Override void draw() {
        GraphicsContext g=canvas.getGraphicsContext2D(); double w=canvas.getWidth();
        g.setFill(Color.web("#101722")); g.fillRect(0,0,w,canvas.getHeight());
        var shownLoop = loopDrag == null ? loop : loopDrag;
        referenceLane.viewport(w, offset, scale * ticksPerSecond(), playhead / ticksPerSecond(), shownLoop);
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
            boolean bar=readOnly||b%4==0;
            g.setStroke(Color.web(bar?"#344257":"#233043")); g.strokeLine(x,RULER,x,canvas.getHeight());
            g.setFill(Color.web(bar?"#d1dbe8":"#6f8198"));
            g.fillText(readOnly?b+" s":bar?String.valueOf(b/4+1):"·",x+6,24);
        }
        for(Clip clip:project.clips()) drawClip(g,ghost!=null&&clip.id().equals(ghost.id())?ghost:clip);
        if (shownLoop != null) {
            g.setFill(Color.web("#ffcb77", .12));
            g.fillRect(reviewX(shownLoop.start()), 0, reviewX(shownLoop.end()) - reviewX(shownLoop.start()), canvas.getHeight());
        }
        for (var note : reviewNotes) drawReviewMarker(g, note);
        if(playhead>=0) {
            double x=GUTTER+playhead*scale-offset;
            if(x>=GUTTER) { g.setStroke(Color.web("#ffcb77")); g.strokeLine(x,RULER,x,canvas.getHeight()); }
        }
        g.setFill(Color.web("#172130")); g.fillRect(0,0,GUTTER,canvas.getHeight());
        g.setFill(Color.web("#74869d")); g.fillText("CHANNEL",14,24);
        for(int row=0;row<LANES.size();row++) {
            g.setFill(Color.web(LANES.get(row).equals(selectedLane)?"#d8f7ee":"#94a6bc"));
            g.fillText(LANES.get(row),16,RULER+row*ROW+29);
            String lane = LANES.get(row);
            g.setFill(Color.web(monitor.soloed(lane) ? "#5cddbc" : "#74869d"));
            g.fillText("S", 59, RULER + row * ROW + 29);
            g.setFill(Color.web(monitor.muted(lane) ? "#ffcb77" : "#74869d"));
            g.fillText("M", 82, RULER + row * ROW + 29);
        }
    }
    private void drawReviewMarker(GraphicsContext g, ReviewNote note) {
        double x = reviewX(note.startSeconds());
        if (x < GUTTER || x > canvas.getWidth()) return;
        Color color = Color.web("agent".equalsIgnoreCase(note.author()) ? "#b4a1ff" : "#ffcb77");
        g.setFill(color);
        g.fillPolygon(new double[]{x - 5, x + 5, x}, new double[]{29, 29, 39}, 3);
        int row = LANES.indexOf(note.laneId() == null ? "" : note.laneId());
        double top = row < 0 ? 29 : RULER + row * ROW + 2;
        if (row >= 0) {
            g.setStroke(color);
            g.strokeLine(x, top, x, top + ROW - 4);
        }
        if (note.endSeconds() != null) {
            g.fillRect(x, top, reviewX(note.endSeconds()) - x, 3);
        }
    }

    private void drawClip(GraphicsContext g,Clip c) {
        Sequence source=sequence(c.sequenceId()); if(source==null) return;
        double x=GUTTER+c.startTick()*scale-offset, width=c.lengthTicks()*scale;
        double y=RULER+LANES.indexOf(c.lane())*ROW+6;
        if(x+width<GUTTER || x>canvas.getWidth()) return;
        boolean dac=source.type().equals("DAC");
        boolean fm=source.type().equals("FM");
        Color fill=Color.web(dac?"#634731":fm?"#245f58":"#284d79");
        g.setFill(fill); g.fillRoundRect(x,y,width,ROW-12,7,7);
        g.setStroke(Color.web(c.id().equals(selected)?"#d9fff0":dac?"#e7ab69":fm?"#48bca1":"#6399db"));
        g.setLineWidth(c.id().equals(selected)?2:1); g.strokeRoundRect(x+1,y+1,width-2,ROW-14,6,6); g.setLineWidth(1);
        g.save(); g.beginPath(); g.rect(Math.max(GUTTER,x)+3,y+2,Math.max(0,Math.min(x+width,canvas.getWidth())-Math.max(GUTTER,x)-6),ROW-16); g.clip();
        int firstRepeat=Math.max(0,(int)((offset-GUTTER-c.startTick()*scale)/(source.lengthTicks()*scale)));
        for(int repeat=firstRepeat;repeat*source.lengthTicks()<c.lengthTicks();repeat++) {
            double origin=x+repeat*source.lengthTicks()*scale; if(origin>canvas.getWidth()) break;
            if(repeat>0) { g.setStroke(Color.web("#89d7cf",0.4)); g.strokeLine(origin,y+4,origin,y+ROW-16); }
            if(dac){
                g.setStroke(Color.web("#ffcf95"));
                for(SampleTrigger trigger:source.triggers()){
                    double hitX=origin+trigger.tick()*scale;
                    if(hitX>=GUTTER&&hitX<x+width&&hitX<=canvas.getWidth()) g.strokeLine(hitX,y+23,hitX,y+33);
                }
            }
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
        if(!readOnly){g.setFill(Color.web("#d4f3ef",0.7)); g.fillRoundRect(x+width-6,y+12,2,16,2,2);}
    }
}

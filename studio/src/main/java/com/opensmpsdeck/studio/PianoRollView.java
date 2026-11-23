package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.TargetProfile;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.*;

/** Notes are linked source data, shown with pitch vertically and time horizontally. */
final class PianoRollView extends ViewportCanvas {
    static final int KEYBOARD=52, HEADER=26, NOTE_ROW=12;
    Sequence sequence;
    String selected;
    private final LinkedHashSet<String> additionalNotes = new LinkedHashSet<>();
    int ticksPerBeat=24, topPitch=76, bottomPitch=48;
    double scale=9;
    Consumer<String> commandSelection=ignored -> {};
    Consumer<String> selection=ignored -> {};
    Consumer<NoteEvent> edit=ignored -> {};
    Consumer<NoteEvent> addition=ignored -> {};
    BiConsumer<ContextMenuEvent, NoteEvent> onContext = (event, note) -> {};
    private final boolean readOnly;
    private NoteEvent dragging, ghost;
    private double pressX,pressY;
    private boolean resizing;
    PianoRollView() { this(false); }

    PianoRollView(boolean readOnly) {
        this.readOnly=readOnly;
        dimensions(1000,400);
        canvas.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            canvas.requestFocus();
            if(sequence!=null&&e.getY()>=HEADER-4&&e.getY()<=HEADER+9){
                var marker=sequence.commands().stream().filter(c->Math.abs(KEYBOARD+c.tick()*scale-offset-e.getX())<=5).findFirst();
                if(marker.isPresent()){commandSelection.accept(marker.get().id());return;}
            }
            NoteEvent note=hit(e.getX(),e.getY());
            boolean extend = !readOnly && note != null && (e.isShortcutDown() || e.isShiftDown());
            select(note, extend);
            if (readOnly || extend) return;
            dragging=note; ghost=note; pressX=e.getX(); pressY=e.getY();
            resizing=note!=null&&e.getX()>=KEYBOARD+(note.tick()+note.duration())*scale-offset-8;
            if(note==null&&e.getClickCount()==2&&sequence!=null&&e.getX()>KEYBOARD&&e.getY()>HEADER) {
                var noteHere = noteAt(e.getX(), e.getY());
                if (noteHere != null) addition.accept(noteHere);
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
        canvas.setOnMouseReleased(e -> { if (e.getButton() != MouseButton.PRIMARY) return; canvas.requestFocus(); var change=ghost; dragging=null; ghost=null; if(change!=null) edit.accept(change); draw(); });
        if (!readOnly) canvas.setOnContextMenuRequested(event -> {
            if (sequence == null) return;
            if (event.isKeyboardTrigger()) {
                if (selected != null) onContext.accept(event, null);
                return;
            }
            var point = canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
            if (point.getX() < KEYBOARD || point.getY() < HEADER) return;
            canvas.requestFocus();
            var note = hit(point.getX(), point.getY());
            if (note == null || !selected(note.id())) {
                select(note, false);
            } else if (!note.id().equals(selected)) {
                additionalNotes.remove(note.id());
                additionalNotes.add(selected);
                selected = note.id();
                selection.accept(selected);
                draw();
            }
            onContext.accept(event, note == null ? noteAt(point.getX(), point.getY()) : null);
        });
    }
    private void select(NoteEvent note, boolean extend) {
        String id = note == null ? null : note.id();
        if (!extend) {
            additionalNotes.clear();
            selected = id;
        } else if (id.equals(selected)) {
            selected = additionalNotes.isEmpty() ? null : additionalNotes.removeLast();
        } else if (!additionalNotes.remove(id)) {
            if (selected != null) additionalNotes.add(selected);
            selected = id;
        }
        selection.accept(selected);
        draw();
    }

    private boolean selected(String id) {
        return id.equals(selected) || additionalNotes.contains(id);
    }

    List<NoteEvent> selectedNotes() {
        return sequence == null ? List.of() : sequence.notes().stream().filter(note -> selected(note.id())).toList();
    }

    List<NoteEvent> lassoNotes(javafx.geometry.Rectangle2D box) {
        if (sequence == null || box.getMaxX() <= KEYBOARD) return List.of();
        return sequence.notes().stream().filter(note -> {
            var shape = noteBounds(note);
            double left = Math.max(KEYBOARD, shape.getMinX());
            return shape.getMaxX() > left && box.intersects(left, shape.getMinY(), shape.getMaxX() - left, shape.getHeight());
        }).toList();
    }

    com.opensmpsdeck.headless.NoteHandoff.ViewRange lassoRange(javafx.geometry.Rectangle2D box) {
        Integer low = null, high = null;
        if (box.getMaxY() > HEADER) {
            low = pitchAt(Math.nextDown(box.getMaxY()));
            high = pitchAt(Math.max(HEADER, box.getMinY()));
        }
        return new com.opensmpsdeck.headless.NoteHandoff.ViewRange(tickAt(box.getMinX()), tickAt(box.getMaxX()),
                low, high, java.util.Map.of("ruler", "sequence", "pixelsPerTick", scale, "topPitch", topPitch, "bottomPitch", bottomPitch));
    }

    private double tickAt(double x) {
        return Math.clamp((Math.max(KEYBOARD, x) - KEYBOARD + offset) / scale, 0, sequence.lengthTicks());
    }

    private javafx.geometry.Rectangle2D noteBounds(NoteEvent note) {
        return new javafx.geometry.Rectangle2D(KEYBOARD + note.tick() * scale - offset,
                HEADER + (topPitch - note.pitch()) * NOTE_ROW + 1, Math.max(3, note.duration() * scale - 2), NOTE_ROW - 2);
    }

    void transpose(int semitones, TargetProfile profile, Consumer<String> status) {
        if (readOnly || sequence == null || selected == null) return;
        var note = sequence.notes().stream().filter(candidate -> candidate.id().equals(selected)).findFirst().orElse(null);
        if (note == null) return;
        boolean psg = sequence.type().equals("PSG");
        if (psg && usesNoise(sequence, note)) {
            status.accept("Transpose skipped · PSG noise note");
            return;
        }
        var range = psg ? profile.psg() : profile.fm();
        int pitch = note.pitch() + semitones;
        if (!range.contains(pitch)) {
            status.accept("Transpose refused · " + sequence.type() + " MIDI pitch must stay in "
                    + range.low() + "–" + range.high());
            return;
        }
        edit.accept(new NoteEvent(note.id(), note.tick(), note.duration(), pitch));
        var current = sequence.notes().stream()
                .anyMatch(candidate -> candidate.id().equals(note.id()) && candidate.pitch() == pitch);
        if (!current) return;
        double viewport = scroll.getViewportBounds().getHeight();
        double scrollable = contentHeight - viewport;
        if (scrollable > 0) {
            double y = HEADER + (topPitch - pitch) * NOTE_ROW;
            double top = scroll.getVvalue() * scrollable;
            if (y < top || y + NOTE_ROW > top + viewport)
                scroll.setVvalue(Math.clamp((y - viewport / 2) / scrollable, 0, 1));
        }
        status.accept("Transposed to " + noteName(pitch));
    }

    static boolean usesNoise(Sequence source, NoteEvent note) {
        var changes = source.commands().stream().filter(event -> event.command() == NativeCommand.PSG_NOISE)
                .sorted(java.util.Comparator.comparingInt(event -> event.tick())).toList();
        boolean noise = source.repeatState().equals("CARRY") && !changes.isEmpty()
                && changes.getLast().arguments().getFirst() != 0;
        for (var change : changes) {
            if (change.tick() >= note.tick() + note.duration()) break;
            if (change.tick() > note.tick() && noise) return true;
            noise = change.arguments().getFirst() != 0;
        }
        return noise;
    }

    void setSequence(Sequence source,String noteId,int beat) {
        if(sequence!=source){dragging=null;ghost=null;}
        if (source == null || sequence == null || !source.id().equals(sequence.id()) || !Objects.equals(noteId, selected))
            additionalNotes.clear();
        double previousOffset=offset;
        sequence=source; selected=noteId; ticksPerBeat=beat;
        topPitch=source==null?76:Math.min(117,Math.max(72,source.notes().stream().mapToInt(NoteEvent::pitch).max().orElse(72)+4));
        bottomPitch=source==null?48:Math.max(12,Math.min(48,source.notes().stream().mapToInt(NoteEvent::pitch).min().orElse(48)-4));
        dimensions(KEYBOARD+(source==null?beat*8.0:source.lengthTicks())*scale+24,HEADER+(topPitch-bottomPitch+1)*NOTE_ROW);
        double scrollable=Math.max(0,contentWidth-scroll.getViewportBounds().getWidth());
        scroll.setHvalue(scrollable==0?0:Math.clamp(previousOffset/scrollable,0,1));
        updateViewport();
    }
    private NoteEvent noteAt(double x, double y) {
        int tick = Math.max(0, snap((x - KEYBOARD + offset) / scale));
        return tick >= sequence.lengthTicks() ? null : new NoteEvent("note-" + UUID.randomUUID(), tick,
                Math.min(unit(), sequence.lengthTicks() - tick), pitchAt(y));
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
                g.setFill(Color.web("#8fa2bb")); g.fillText(readOnly?b+" s":String.valueOf(b+1),x+5,17);
            }
            for(NoteEvent n:sequence.notes()) {
                NoteEvent note=ghost!=null&&ghost.id().equals(n.id())?ghost:n;
                var shape = noteBounds(note);
                g.setFill(Color.web(selected(note.id())?"#b7f7df":"#57bfa6"));
                g.fillRoundRect(shape.getMinX(), shape.getMinY(), shape.getWidth(), shape.getHeight(), 3, 3);
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

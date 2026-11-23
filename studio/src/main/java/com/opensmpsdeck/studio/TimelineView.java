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
    private static final Font FONT=Font.font("System",13);
    private static final Color BACKGROUND=Color.web("#101722"), EVEN_ROW=Color.web("#141d2a"), ODD_ROW=Color.web("#111a26"),
            ROW_LINE=Color.web("#243043"), BAR_LINE=Color.web("#344257"), BEAT_LINE=Color.web("#233043"),
            BAR_LABEL=Color.web("#d1dbe8"), BEAT_LABEL=Color.web("#6f8198"), GROUP_SHADE=Color.web("#171426",.94),
            GROUP_EDGE=Color.web("#9476ca"), LOOP=Color.web("#ffcb77", .12), PLAYHEAD=Color.web("#ffcb77"),
            GUTTER_FILL=Color.web("#172130"), GUTTER_LABEL=Color.web("#74869d"), LANE_ACTIVE=Color.web("#d8f7ee"),
            LANE_LABEL=Color.web("#94a6bc"), SOLO=Color.web("#5cddbc"), MUTE=Color.web("#ffcb77"),
            GROUP_LINE_ACTIVE=Color.web("#e1d6ff"), GROUP_LINE=Color.web("#9c80dc"), GROUP_CHILD=Color.web("#8060b9", .38),
            GROUP_CHILD_LINE=Color.web("#bda1f5", .78), GROUP_HEADER_ACTIVE=Color.web("#654695"), GROUP_HEADER=Color.web("#493769"),
            GROUP_REPEAT=Color.web("#bda1f5",.60), GROUP_TITLE=Color.web("#f1eaff"), AGENT_NOTE=Color.web("#b4a1ff"),
            HUMAN_NOTE=Color.web("#ffcb77"), DAC_FILL=Color.web("#634731"), FM_FILL=Color.web("#245f58"), PSG_FILL=Color.web("#284d79"),
            ACTIVE_EDGE=Color.web("#d9fff0"), DAC_EDGE=Color.web("#e7ab69"), FM_EDGE=Color.web("#48bca1"), PSG_EDGE=Color.web("#6399db"),
            REPEAT_LINE=Color.web("#89d7cf",0.4), TRIGGER=Color.web("#ffcf95"), FM_NOTE=Color.web("#9ef0d7",0.65),
            PSG_NOTE=Color.web("#b5d4ff",0.65), CLIP_TITLE=Color.web("#e5f8f4"), HANDLE=Color.web("#d4f3ef",0.7);
    StudioProject project;
    String selected;
    String selectedLane="FM1";
    boolean laneSelected;
    double scale=5;
    double playhead=-1;
    Runnable openNotes=()->{};
    Runnable openGroup=()->{};
    final Set<String> selectedIds = new LinkedHashSet<>();
    private String parentId;
    long origin;
    private List<Clip> clips = List.of(), backdrop = List.of();
    private Map<String, GroupBlock> blocks = Map.of();
    private List<GroupBlock> topmostFirst = List.of();
    private GroupArrangement resolver;
    Consumer<String> selection=ignored -> {};
    Consumer<Placement> edit=ignored -> {};
    Predicate<Placement> placement=ignored -> false;
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
    java.util.function.BiConsumer<javafx.scene.input.ContextMenuEvent, Double> onContext = (event, time) -> {};
    private Placement dragging, ghost;
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
            Placement item=hit(e.getX(),e.getY());
            String id=item==null?null:item.id();
            boolean toggle = !readOnly && (e.isShortcutDown() || e.isShiftDown());
            if (toggle && id != null) {
                if (!selectedIds.add(id)) selectedIds.remove(id);
                selected = selectedIds.contains(id) ? id : selectedIds.stream().reduce((a,b)->b).orElse(null);
            } else {
                selectOnly(id);
            }
            selection.accept(selected);
            if (readOnly || toggle) {draw();return;}
            resizing=item!=null && e.getX()>=tickX(item.endTick())-10;
            boolean canDrag=item instanceof Clip || item instanceof GroupPlacement group
                    && (resizing || blocks.get(group.id()).headerAt(e.getY()));
            dragging=canDrag ? item : null; ghost=dragging; pressX=e.getX();
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
            int start = resizing ? dragging.startTick() : (int)Math.clamp((long)dragging.startTick()+delta,0,Integer.MAX_VALUE);
            int length = resizing ? (int)Math.clamp((long)dragging.lengthTicks()+delta,1,Integer.MAX_VALUE) : dragging.lengthTicks();
            ghost = switch (dragging) {
                case Clip clip -> new Clip(clip.id(), clip.sequenceId(), resizing ? clip.lane() : laneAt(e.getY()), start, length);
                case GroupPlacement group -> new GroupPlacement(group.id(), group.groupId(), start, length);
            };
            draw();
        });
        canvas.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            canvas.requestFocus();
            if (rangeStart != null) {
                var range = range(secondsAt(e.getX()));
                rangeStart = null;
                loopDrag = null;
                if (range != null) chooseLoop.accept(range);
                draw();
                return;
            }
            Placement changed=ghost;
            Placement initial=dragging;
            dragging=null; ghost=null;
            if(changed!=null && !changed.equals(initial)) edit.accept(changed);
            draw();
        });
        canvas.setOnMouseClicked(e->{
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount()==2 && e.isStillSincePress() && selected!=null) {
                if (blocks.containsKey(selected)) openGroup.run(); else openNotes.run();
            }
        });
        if(readOnly)return;
        canvas.setOnContextMenuRequested(event -> {
            if (event.isKeyboardTrigger()) return;
            var point = canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
            if (point.getX() < GUTTER) return;
            canvas.requestFocus();
            var item = hit(point.getX(), point.getY());
            String id = item == null ? null : item.id();
            if (!java.util.Objects.equals(selected, id)) {
                if (selectedIds.contains(id)) selected = id;
                else selectOnly(id);
                selection.accept(selected);
            }
            onContext.accept(event, secondsAt(point.getX()));
        });
        canvas.setOnDragOver(e -> {
            if(e.getDragboard().hasString() && validDropPosition(e.getX(), e.getY())) {
                String value=e.getDragboard().getString();
                if (value.startsWith("smps-group:") || value.startsWith("smps-sequence:"))
                    e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        canvas.setOnDragDropped(e -> {
            Placement item=decodeDrop(e.getDragboard().getString(), e.getX(), e.getY());
            e.setDropCompleted(item != null && placement.test(item));
            e.consume();
        });
    }

    private boolean validDropPosition(double x, double y) {
        if (project == null || x < GUTTER || y < RULER || y >= RULER + LANES.size()*ROW) return false;
        return parentId == null || x >= tickX(0) && x < tickX(GroupArrangement.source(project,parentId).lengthTicks());
    }

    private Placement decodeDrop(String value, double x, double y) {
        if (value == null || !validDropPosition(x,y)) return null;
        int tick = Math.max(0, snap((x-GUTTER+offset)/scale-origin));
        if (value.startsWith("smps-group:")) {
            String id = value.substring("smps-group:".length());
            return project.groupSequences().stream().filter(group -> group.id().equals(id)).findFirst()
                    .map(group -> new GroupPlacement("group-" + UUID.randomUUID(), id, tick, group.lengthTicks())).orElse(null);
        }
        if (!value.startsWith("smps-sequence:")) return null;
        String id = value.substring("smps-sequence:".length());
        Sequence source = sequence(id);
        return source == null ? null : new Clip("clip-" + UUID.randomUUID(), id, laneAt(y), tick, source.lengthTicks());
    }

    void selectOnly(String id) {
        selected = id;
        selectedIds.clear();
        if (id != null) selectedIds.add(id);
    }

    void setProject(StudioProject value,String selectedId) {
        setArrangement(value, selectedId, parentId, origin);
    }

    void setArrangement(StudioProject value, String selectedId, String parent, long start) {
        if (project!=value || !Objects.equals(parentId, parent) || origin != start) {
            dragging=null; ghost=null; rangeStart=null; loopDrag=null;
            resolver = value == null ? null : new GroupArrangement(value);
            clips = value == null ? List.of() : GroupArrangement.clips(value, parent);
            var next = new LinkedHashMap<String, GroupBlock>();
            if (value != null)
                for (var item : GroupArrangement.placements(value, parent)) next.put(item.id(), block(value, item));
            blocks = next;
            topmostFirst = new ArrayList<>(next.values()).reversed();
            backdrop = parent == null ? List.of() : GroupArrangement.flatten(value);
        }
        double previousOffset=offset;
        project=value; parentId=parent; origin=start;
        if (!Objects.equals(selected, selectedId)) selectOnly(selectedId);
        selectedIds.removeIf(id -> !blocks.containsKey(id) && clips.stream().noneMatch(clip -> clip.id().equals(id)));
        if(project==null){updateViewport();return;}
        long end=GroupArrangement.endTick(project);
        if (parentId != null) end = Math.max(end, origin+GroupArrangement.source(project,parentId).lengthTicks());
        double minimum=project.ticksPerBeat()*(readOnly?8.0:32.0);
        double reviewEnd = reviewNotes.stream().mapToDouble(n -> n.endSeconds() == null ? n.startSeconds() : n.endSeconds())
                .max().orElse(0);
        if (reference != null && referenceLane.wave() != null) {
            reviewEnd = Math.max(reviewEnd, reference.offsetSeconds() + referenceLane.wave().duration());
        }
        double reviewTicks = Math.min(ReviewTransport.tickAt(readOnly ? null : project, reviewEnd), 10_000_000 / scale);
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
        double x = ReviewTransport.tickAt(readOnly ? null : project, seconds) * scale;
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
        return ReviewTransport.secondsAt(readOnly ? null : project, Math.max(0, (x - GUTTER + offset) / scale));
    }

    private double reviewX(double seconds) {
        return GUTTER + ReviewTransport.tickAt(readOnly ? null : project, seconds) * scale - offset;
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
    private double tickX(long tick) { return GUTTER+(origin+tick)*scale-offset; }

    private Placement hit(double x,double y) {
        if(project==null || y<RULER || y>=RULER+LANES.size()*ROW || x<GUTTER) return null;
        double tick=(x-GUTTER+offset)/scale-origin;
        int row = LANES.indexOf(laneAt(y));
        for (var block : topmostFirst) {
            if (tick < block.item.startTick() || tick >= block.item.endTick()) continue;
            boolean edge = row >= block.firstRow && row <= block.lastRow && x >= tickX(block.item.endTick())-10;
            if (block.headerAt(y) || edge) return block.item;
        }
        String lane = LANES.get(row);
        for (Clip clip : clips) if (contains(clip,lane,tick)) return clip;
        for (var block : topmostFirst) {
            if (tick < block.item.startTick() || tick >= block.item.endTick()) continue;
            for (Clip clip : block.content) if (contains(clip,lane,tick)) return block.item;
        }
        return null;
    }

    private static boolean contains(Clip clip, String lane, double tick) {
        return clip.lane().equals(lane) && tick>=clip.startTick() && tick<clip.endTick();
    }

    private record GroupBlock(GroupPlacement item, GroupSequence source, List<Clip> content, int firstRow, int lastRow) {
        double top() { return RULER+firstRow*ROW+5; }
        boolean headerAt(double y) { return y>=top() && y<top()+22; }
    }

    private GroupBlock block(StudioProject project, GroupPlacement item) {
        GroupSequence source = GroupArrangement.source(project, item.groupId());
        List<Clip> content = resolver.expand(item);
        int first = content.stream().mapToInt(c -> LANES.indexOf(c.lane())).min().orElse(0);
        int last = content.stream().mapToInt(c -> LANES.indexOf(c.lane())).max().orElse(first);
        return new GroupBlock(item, source, content, first, last);
    }

    private void drawGroup(GraphicsContext g, GroupBlock block) {
        GroupPlacement item = ghost instanceof GroupPlacement changed && changed.id().equals(block.item.id())
                ? changed : block.item;
        double x = tickX(item.startTick()), end = tickX((long)item.startTick()+item.lengthTicks());
        double left = Math.max(GUTTER,x), right = Math.min(canvas.getWidth(),end);
        if (right <= left) return;
        double top = block.top(), bottom = RULER+(block.lastRow+1)*ROW-5;
        double header = 22;
        boolean active = selectedIds.contains(item.id());
        Color line = active ? GROUP_LINE_ACTIVE : GROUP_LINE;
        g.setFill(GROUP_CHILD);
        g.setStroke(GROUP_CHILD_LINE);
        for (Clip child : block.content) {
            double a = Math.max(left, tickX((long)child.startTick()+item.startTick()-block.item.startTick()));
            double b = Math.min(Math.min(end, right), tickX((long)child.startTick()+item.startTick()-block.item.startTick())+child.lengthTicks()*scale);
            if (b <= a) continue;
            double y = RULER+LANES.indexOf(child.lane())*ROW;
            g.fillRect(a,y+8,b-a,ROW-16);
            g.strokeLine(a,y+ROW-11,b,y+ROW-11);
        }
        g.setFill(active ? GROUP_HEADER_ACTIVE : GROUP_HEADER);
        g.fillRect(left,top,right-left,header);
        g.setStroke(line);
        g.strokeRect(x+.5,top+.5,end-x-1,bottom-top-1);
        double period = block.source.lengthTicks()*scale;
        double first = Math.max(1,Math.ceil((left-x)/period));
        g.setStroke(GROUP_REPEAT);
        for (double boundary=x+first*period; boundary<right; boundary+=period)
            g.strokeLine(boundary,top,boundary,bottom);
        g.setFill(GROUP_TITLE);
        g.fillText(CanvasText.fit(block.source.name(),FONT,right-left-17),left+8,top+15);
        if (end-6 < right) {
            g.setFill(line);
            g.fillRoundRect(end-6,top+5,2,12,2,2);
        }
    }
    @Override void paint() {
        GraphicsContext g=canvas.getGraphicsContext2D(); double w=canvas.getWidth();
        g.setFill(BACKGROUND); g.fillRect(0,0,w,canvas.getHeight());
        var shownLoop = loopDrag == null ? loop : loopDrag;
        referenceLane.viewport(w, offset, scale * ticksPerSecond(), ReviewTransport.secondsAt(readOnly ? null : project, playhead), shownLoop);
        if(project==null) return;
        g.setFont(FONT);
        for(int row=0;row<LANES.size();row++) {
            g.setFill(row%2==0?EVEN_ROW:ODD_ROW); g.fillRect(0,RULER+row*ROW,w,ROW);
            g.setStroke(ROW_LINE); g.strokeLine(0,RULER+(row+1)*ROW,w,RULER+(row+1)*ROW);
        }
        int beat=project.ticksPerBeat(); long first=Math.max(0,(long)Math.floor(offset/(beat*scale)));
        long last=(long)Math.ceil((offset+w)/(beat*scale));
        for(long b=first;b<=last;b++) {
            double x=GUTTER+(double)b*beat*scale-offset;
            if(x<GUTTER) continue;
            boolean bar=readOnly||b%4==0;
            g.setStroke(bar?BAR_LINE:BEAT_LINE); g.strokeLine(x,RULER,x,canvas.getHeight());
            g.setFill(bar?BAR_LABEL:BEAT_LABEL);
            g.fillText(readOnly?b+" s":bar?String.valueOf(b/4+1):"·",x+6,24);
        }
        if (parentId != null) {
            g.save(); g.setGlobalAlpha(.26);
            for (Clip clip : backdrop) drawClip(g,clip,0,false);
            g.restore();
            double x = Math.max(GUTTER,tickX(0));
            double end = Math.min(w,tickX(GroupArrangement.source(project,parentId).lengthTicks()));
            if (end > x) {
                g.setFill(GROUP_SHADE); g.fillRect(x,RULER,end-x,canvas.getHeight()-RULER);
                g.setStroke(GROUP_EDGE); g.strokeLine(x,RULER,x,canvas.getHeight());
                g.strokeLine(end,RULER,end,canvas.getHeight());
            }
        }
        for (Clip clip : clips) drawClip(g,ghost instanceof Clip moved && clip.id().equals(moved.id()) ? moved : clip,origin,true);
        for (var block : blocks.values()) drawGroup(g,block);
        if (shownLoop != null) {
            g.setFill(LOOP);
            g.fillRect(reviewX(shownLoop.start()), 0, reviewX(shownLoop.end()) - reviewX(shownLoop.start()), canvas.getHeight());
        }
        for (var note : reviewNotes) drawReviewMarker(g, note);
        if(playhead>=0) {
            double x=GUTTER+playhead*scale-offset;
            if(x>=GUTTER) { g.setStroke(PLAYHEAD); g.strokeLine(x,RULER,x,canvas.getHeight()); }
        }
        g.setFill(GUTTER_FILL); g.fillRect(0,0,GUTTER,canvas.getHeight());
        g.setFill(GUTTER_LABEL); g.fillText("CHANNEL",14,24);
        for(int row=0;row<LANES.size();row++) {
            g.setFill(LANES.get(row).equals(selectedLane)?LANE_ACTIVE:LANE_LABEL);
            g.fillText(LANES.get(row),16,RULER+row*ROW+29);
            String lane = LANES.get(row);
            g.setFill(monitor.soloed(lane) ? SOLO : GUTTER_LABEL);
            g.fillText("S", 59, RULER + row * ROW + 29);
            g.setFill(monitor.muted(lane) ? MUTE : GUTTER_LABEL);
            g.fillText("M", 82, RULER + row * ROW + 29);
        }
    }
    private void drawReviewMarker(GraphicsContext g, ReviewNote note) {
        double x = reviewX(note.startSeconds());
        if (x < GUTTER || x > canvas.getWidth()) return;
        Color color = "agent".equalsIgnoreCase(note.author()) ? AGENT_NOTE : HUMAN_NOTE;
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

    private void drawClip(GraphicsContext g,Clip c,long base,boolean interactive) {
        Sequence source=sequence(c.sequenceId()); if(source==null) return;
        double w=canvas.getWidth();
        double x=GUTTER+(base+c.startTick())*scale-offset, width=c.lengthTicks()*scale;
        double y=RULER+LANES.indexOf(c.lane())*ROW+6;
        if(x+width<GUTTER || x>w) return;
        boolean dac=source.type().equals("DAC");
        boolean fm=source.type().equals("FM");
        g.setFill(dac?DAC_FILL:fm?FM_FILL:PSG_FILL); g.fillRoundRect(x,y,width,ROW-12,7,7);
        boolean active=interactive && (selectedIds.contains(c.id()) || c.id().equals(selected));
        g.setStroke(active?ACTIVE_EDGE:dac?DAC_EDGE:fm?FM_EDGE:PSG_EDGE);
        g.setLineWidth(active?2:1); g.strokeRoundRect(x+1,y+1,width-2,ROW-14,6,6); g.setLineWidth(1);
        // The clip's inner area. Contents are clamped to it rather than clipped.
        double innerLeft=Math.max(GUTTER,x)+3, innerRight=Math.min(x+width,w)-3;
        if (innerRight > innerLeft) {
            long period=Math.max(1,source.lengthTicks());
            int firstRepeat=Math.max(0,(int)((offset-GUTTER-(base+c.startTick())*scale)/(period*scale)));
            g.setStroke(fm?FM_NOTE:PSG_NOTE);
            for(int repeat=firstRepeat;(long)repeat*period<c.lengthTicks();repeat++) {
                double origin=x+(double)repeat*period*scale; if(origin>innerRight) break;
                if(repeat>0 && origin>=innerLeft) { g.setStroke(REPEAT_LINE); g.strokeLine(origin,y+4,origin,y+ROW-16); }
                if(dac){
                    g.setStroke(TRIGGER);
                    for(SampleTrigger trigger:source.triggers()){
                        double hitX=origin+trigger.tick()*scale;
                        if(hitX>=innerLeft&&hitX<=innerRight) g.strokeLine(hitX,y+23,hitX,y+33);
                    }
                }
                g.setStroke(fm?FM_NOTE:PSG_NOTE);
                double repeatEnd=Math.min(innerRight,x+width-3);
                for(NoteEvent note:source.notes()) {
                    double noteX=origin+note.tick()*scale;
                    if(noteX>=x+width || noteX>innerRight) continue;
                    double start=Math.max(noteX,innerLeft), end=Math.min(repeatEnd,noteX+note.duration()*scale-2);
                    if(end<start) continue;
                    double noteY=y+31-(note.pitch()%24)*0.6;
                    g.strokeLine(start,noteY,end,noteY);
                }
            }
            double titleX=Math.max(x+8,GUTTER+8);
            g.setFill(CLIP_TITLE); g.setFont(FONT);
            g.fillText(CanvasText.fit(source.name(),FONT,innerRight-titleX),titleX,y+15);
        }
        if(interactive && !readOnly && x+width-6<w){g.setFill(HANDLE); g.fillRoundRect(x+width-6,y+12,2,16,2,2);}
    }
}

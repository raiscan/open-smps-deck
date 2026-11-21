package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioSource;
import com.opensmpsdeck.headless.ReconstructionInspection.BendPoint;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionEditing;
import java.util.function.BiConsumer;

import com.opensmpsdeck.headless.ReconstructionSections.Level;
import com.opensmpsdeck.headless.ReconstructionSections.Section;
import com.opensmpsdeck.studio.ReconstructionTools.Mode;
import com.opensmpsdeck.studio.ReconstructionTools.Placement;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import java.util.function.DoubleConsumer;

/** Source-time viewport and hit testing. The underlying trace and score remain immutable. */
final class ReconstructionCanvas extends ViewportCanvas implements AutoCloseable {
    static final String UNASSIGNED = "Unallocated";
    private static final int AXIS = 76;
    private static final int SECTION_HEIGHT = 18;
    private static final int LANE_HEIGHT = 17;
    private static final int LANE_HEADER = 20;
    private static final Color BACKGROUND = Color.web("#0c1220");
    private static final Color PANEL = Color.web("#151f31");
    private static final Color TEXT = Color.web("#dce7f8");
    private static final Color SUBTLE = Color.web("#98aeca");
    private static final Color CURSOR = Color.web("#faf0a9");
    private static final Color GRID = Color.web("#a5badd").deriveColor(0, 1, 1, .16);
    private static final String[] PITCHES = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};

    private record Hit(Overlay note, double left, double top, double width, double height) {
        boolean contains(double x, double y) {
            return x >= left && x <= left + width && y >= top && y <= top + height;
        }
    }

    private ReconstructionInspection model;
    private ReconstructionTiles tiles;
    private final Consumer<String> error;
    private List<Section> sections;
    private Mode mode = Mode.SELECT;
    private ReconstructionInspection.Grid grid = ReconstructionInspection.Grid.BEAT;
    private final ReconstructionTools selection = new ReconstructionTools();
    private List<Overlay> pending = List.of();
    private Map<String, Overlay> pendingByNote = Map.of();
    private boolean outline;
    private double opacity = .34;
    private boolean editing = true;
    private boolean insertAvailable;
    private NoteDrag noteDrag;
    private Box box;
    private EffectDrag effect;
    private List<BendPoint> bendPreview = List.of();
    private ReconstructionEditing.Vibrato vibratoPreview;
    private String effectReadout;
    private List<Overlay> selectionNotes = List.of();
    private SectionDrag sectionDrag;
    private Placement preview;
    private Double boundaryPreview;
    private boolean dragged;

    private enum Handle { MOVE, START, END }
    private record NoteDrag(Overlay note, Handle handle, double at, int pitch) {}
    private record Box(double x, double y, double endX, double endY, boolean add, boolean toggle) {
        double left() { return Math.min(x, endX); }
        double top() { return Math.min(y, endY); }
        double width() { return Math.abs(endX - x); }
        double height() { return Math.abs(endY - y); }
        boolean intersects(Hit hit) {
            return left() <= hit.left() + hit.width() && left() + width() >= hit.left()
                    && top() <= hit.top() + hit.height() && top() + height() >= hit.top();
        }
    }
    private record EffectDrag(Overlay note, ReconstructionEditing.Attack attack, double x, double y,
                              double at, boolean retune, List<BendPoint> curve, int point,
                              ReconstructionEditing.Vibrato vibrato) {}
    private record SectionDrag(Section section, boolean end) {}
    record SectionPoint(Section section, double time, double screenX, double screenY) {}
    private final Tooltip hover = new Tooltip();
    private final List<Hit> hits = new ArrayList<>();
    private List<String> lanes;
    private int low;
    private int high;
    private static final List<String> SECTION_LABELS = List.of("Section", "Period", "Phrase");
    private double[] endByIndex;
    private final Set<Role> roles = EnumSet.allOf(Role.class);
    private final Set<String> visibleLanes = new HashSet<>();
    private double pixelsPerSecond = 1;
    private double zoom;
    private double playhead;
    private Double anchor;
    private ReviewTransport.Range range;
    private String laneSelection;
    private AudioSource source;
    private Map<String, List<BendPoint>> assembledBends = Map.of();
    private int dbRange = 55;
    private boolean ridges;
    private boolean projectRuler;
    private boolean ready;

    Consumer<Overlay> onNote = ignored -> {};
    Consumer<String> onLane = ignored -> {};
    Consumer<ReviewTransport.Range> onRange = ignored -> {};
    DoubleConsumer onSeek = ignored -> {};

    Consumer<JsonObject> onEffect = ignored -> {};
    Runnable onSelection = () -> {};
    Function<Overlay, ReconstructionEditing.Attack> attack = ignored -> null;
    ToIntFunction<Overlay> detune = ignored -> 0;
    Function<Overlay, ReconstructionEditing.Vibrato> vibrato = ignored -> null;

    Consumer<Placement> onInsert = ignored -> {};
    BiConsumer<Overlay, Placement> onPlacement = (note, place) -> {};
    Consumer<SectionEdits.Mutation> onSectionEdit = ignored -> {};
    Consumer<SectionPoint> onSectionMenu = ignored -> {};

    ReconstructionCanvas(ReconstructionInspection model, Consumer<String> error) {
        this.error = error;
        refresh(model);
        canvas.setId("reconstruction-canvas");
        scroll.setFitToHeight(false);
        scroll.viewportBoundsProperty().addListener((ignored, old, next) -> resizePlot());
        canvas.setOnMouseMoved(event -> {
            var hit = hit(event.getX(), event.getY());
            var section = sectionAt(event.getX(), event.getY());
            String text = hit != null ? description(hit.note()) : section != null
                    ? section.label() + " · " + section.author() : "";
            if (text.isEmpty()) hover.hide();
            hover.setText(text);
            boolean horizontal = sectionEdge(section, event.getX()) != null
                    || (mode == Mode.SELECT || mode == Mode.INSERT) && hit != null
                    && inPitch(event) && handle(hit, event.getX()) != Handle.MOVE;
            boolean crosshair = inPitch(event) && ((mode == Mode.INSERT && insertAvailable)
                    || mode == Mode.CUT || mode == Mode.BEND || mode == Mode.VIBRATO);
            canvas.setCursor(editing && horizontal ? Cursor.H_RESIZE
                    : editing && crosshair ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        });
        hover.setShowDelay(javafx.util.Duration.millis(250));
        hover.setShowDuration(javafx.util.Duration.seconds(20));
        Tooltip.install(canvas, hover);
        canvas.setOnMousePressed(this::press);
        canvas.setOnMouseDragged(this::drag);
        canvas.setOnMouseReleased(this::release);
        ready = true;
    }

    void refresh(ReconstructionInspection snapshot) {
        cancelGesture();
        model = snapshot;
        sections = model.sections().sections();
        if (tiles != null) tiles.close();
        tiles = new ReconstructionTiles(model, this::draw, error);
        assembledBends = Map.of();
        var notes = model.overlays();
        double lowestBend = notes.stream().flatMap(note -> note.bend().stream())
                .mapToDouble(BendPoint::pitch).min().orElse(127);
        double highestBend = notes.stream().flatMap(note -> note.bend().stream())
                .mapToDouble(BendPoint::pitch).max().orElse(0);
        low = Math.max(0, Math.min((int) Math.floor(lowestBend),
                notes.stream().mapToInt(Overlay::pitch).min().orElse(42)) - 3);
        high = Math.min(127, Math.max((int) Math.ceil(highestBend),
                notes.stream().mapToInt(Overlay::pitch).max().orElse(78)) + 4);
        endByIndex = new double[notes.size()];
        double priorEnd = 0;
        for (int i = 0; i < notes.size(); i++) {
            priorEnd = Math.max(priorEnd, notes.get(i).interval().sourceEnd());
            endByIndex[i] = priorEnd;
        }
        visibleLanes.add(UNASSIGNED);
        updateLanes();
        resizePlot();
        draw();
    }

    void sections(List<Section> next) {
        sections = next;
        cancelGesture();
        draw();
    }

    void pending(List<Overlay> overlays) {
        pending = overlays;
        pendingByNote = new java.util.HashMap<>();
        for (var overlay : overlays) pendingByNote.put(overlay.id(), overlay);
        updateLanes();
        resizePlot();
        var primary = overlays.stream().filter(selection::primary).findFirst().orElseGet(() -> model.overlays().stream()
                .filter(selection::primary).findFirst().orElse(null));
        if (primary != null && pendingByNote.containsKey(primary.id())) primary = pendingByNote.get(primary.id());
        publishSelection(primary);
        draw();
    }

    void mode(Mode mode) { this.mode = mode; cancelGesture(); draw(); }
    void grid(ReconstructionInspection.Grid grid) { this.grid = grid; }
    void editing(boolean enabled) { editing = enabled; if (!enabled) cancelGesture(); }
    void insertAvailable(boolean available) { insertAvailable = available; }
    void opacity(double value) { opacity = value; draw(); }
    void outline(boolean value) { outline = value; draw(); }

    private void cancelGesture() {
        noteDrag = null;
        box = null;
        effect = null;
        bendPreview = List.of();
        vibratoPreview = null;
        effectReadout = null;
        sectionDrag = null;
        preview = null;
        boundaryPreview = null;
        anchor = null;
        dragged = false;
    }

    private void press(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) return;
        cancelGesture();
        canvas.requestFocus();
        if (event.isShiftDown() && event.getX() >= AXIS && !inPitch(event)) {
            anchor = seconds(event.getX());
            return;
        }
        if (!editing) return;
        var section = sectionAt(event.getX(), event.getY());
        var edge = sectionEdge(section, event.getX());
        if (edge != null) sectionDrag = new SectionDrag(section, edge);
        var hit = hit(event.getX(), event.getY());
        if (!inPitch(event)) return;
        if (mode == Mode.SELECT && (hit == null || event.isShiftDown() || event.isControlDown())) {
            box = new Box(event.getX(), event.getY(), event.getX(), event.getY(), event.isShiftDown(), event.isControlDown());
        } else if (mode == Mode.SELECT || mode == Mode.INSERT) {
            if (hit != null) noteDrag = new NoteDrag(hit.note(), handle(hit, event.getX()),
                    seconds(event.getX()), pitch(event.getY()));
        } else beginEffect(event, hit);
    }

    private void drag(MouseEvent event) {
        if (anchor != null) {
            selectRange(seconds(event.getX()));
            return;
        }
        if (box != null) {
            box = new Box(box.x(), box.y(), Math.clamp(event.getX(), AXIS, canvas.getWidth()),
                    Math.clamp(event.getY(), sectionBottom(), pitchBottom()), box.add(), box.toggle());
        } else if (effect != null) updateEffect(event);
        else if (sectionDrag != null) {
            boundaryPreview = placementTime(seconds(event.getX()), event.isAltDown());
        } else if (noteDrag != null) {
            preview = placement(noteDrag, event);
        }
        dragged = true;
        draw();
    }

    private void release(MouseEvent event) {
        if (event.getButton() == MouseButton.SECONDARY) {
            var section = sectionAt(event.getX(), event.getY());
            if (section != null && editing) onSectionMenu.accept(new SectionPoint(section,
                    placementTime(seconds(event.getX()), event.isAltDown()), event.getScreenX(), event.getScreenY()));
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY) return;
        if (anchor != null) {
            selectRange(seconds(event.getX()));
            cancelGesture();
            return;
        }
        if (effect != null) {
            finishEffect(event);
            return;
        }
        if (box != null && (dragged || box.add() || box.toggle())) {
            var area = new Box(box.x(), box.y(), Math.clamp(event.getX(), AXIS, canvas.getWidth()),
                    Math.clamp(event.getY(), sectionBottom(), pitchBottom()), box.add(), box.toggle());
            var found = hits.stream().filter(hit -> area.intersects(hit) && hit.top() < pitchBottom())
                    .map(hit -> hit.note().eventId()).distinct().toList();
            cancelGesture();
            selection.modify(found, area.add(), area.toggle());
            publishSelection(null);
            draw();
            return;
        }
        var moving = noteDrag;
        var boundary = sectionDrag;
        boolean moved = dragged;
        cancelGesture();
        if (moved && boundary != null) {
            double at = placementTime(seconds(event.getX()), event.isAltDown());
            double previous = boundary.end() ? boundary.section().sourceEnd() : boundary.section().sourceStart();
            if (Math.abs(at - previous) > 1e-6) {
                try {
                    onSectionEdit.accept(SectionEdits.resize(sections, boundary.section(), boundary.end(), at));
                } catch (IllegalArgumentException failure) {
                    error.accept(failure.getMessage());
                }
            }
            draw();
            return;
        }
        if (moved && moving != null) {
            var next = placement(moving, event);
            select(moving.note(), moving.note().lane());
            if (!next.equals(Placement.of(moving.note()))) onPlacement.accept(moving.note(), next);
            return;
        }
        var section = sectionAt(event.getX(), event.getY());
        var hit = hit(event.getX(), event.getY());
        if (section != null) {
            range = new ReviewTransport.Range(section.sourceStart(), section.sourceEnd());
            onRange.accept(range);
            onSeek.accept(range.start());
        } else if (hit != null) {
            select(hit.note(), hit.note().lane());
            onSeek.accept(hit.note().interval().sourceStart());
        } else if (event.getY() >= laneTop() && event.getY() < laneTop() + lanes.size() * LANE_HEIGHT) {
            select(null, lanes.get((int) (event.getY() - laneTop()) / LANE_HEIGHT));
            onSeek.accept(event.getX() >= AXIS ? seconds(event.getX()) : playhead);
        } else if (event.getX() >= AXIS) {
            if (inPitch(event)) {
                if (editing && mode == Mode.INSERT && insertAvailable && !moved) {
                    double limit = model.duration();
                    double start = Math.min(placementTime(seconds(event.getX()), event.isAltDown()), Math.max(0, limit - .017));
                    double end = Math.max(start + Math.min(.017, limit - start), model.nextBeat(start));
                    onInsert.accept(new Placement(start, end, pitch(event.getY())));
                    return;
                }
                select(null, null);
            }
            onSeek.accept(seconds(event.getX()));
        }
        draw();
    }

    private void beginEffect(MouseEvent event, Hit hit) {
        double x = event.getX(), y = event.getY();
        var note = hit == null ? null : hit.note();
        int point = -1;
        if (mode == Mode.BEND) {
            // A curve can extend above or below its note rectangle.
            for (var candidate : selectedNotes()) {
                var data = attack.apply(candidate);
                if (data == null) continue;
                for (int i = 0; i < data.bend().size(); i++) {
                    var p = data.bend().get(i);
                    if (Math.hypot(x(p.sourceSeconds()) - x, y(p.pitch()) - y) <= 6) {
                        note = candidate;
                        point = i;
                        break;
                    }
                }
                if (point >= 0) break;
            }
        }
        if (mode == Mode.CUT && event.isAltDown() && selectedNotes().size() == 1) {
            var selected = selectedNotes().getFirst();
            var data = attack.apply(selected);
            if (data != null && data.start() < seconds(x) && seconds(x) < data.end()) note = selected;
        }
        if (note == null) return;
        var data = attack.apply(note);
        if (mode == Mode.VIBRATO) {
            if (!note.sounds() || note.lane() == null || !note.lane().startsWith("FM")) {
                error.accept("Select an allocated FM note to edit vibrato.");
                return;
            }
        } else if (data == null || !data.single() || mode == Mode.BEND && data.drums()) {
            error.accept(mode == Mode.BEND ? "A slide needs a single tonal score attack."
                    : "A cut needs a single score attack.");
            return;
        }
        var base = data == null || mode != Mode.BEND ? List.<BendPoint>of() : data.bend();
        effect = new EffectDrag(note, data, x, y, seconds(x), event.isAltDown(), base, point,
                mode == Mode.VIBRATO ? vibrato.apply(note) : null);
        if (!selection.contains(note)) select(note, note.lane());
        updateEffect(event);
        draw();
    }

    private void updateEffect(MouseEvent event) {
        var gesture = effect;
        var data = gesture.attack();
        if (mode == Mode.CUT) {
            effectReadout = gesture.retune() ? "Cut · right half " + pitchName(pitch(event.getY())) : "Cut";
        } else if (mode == Mode.BEND) {
            var curve = new java.util.TreeMap<Double, Double>();
            for (var p : gesture.curve()) curve.put(p.sourceSeconds(), p.pitch());
            curve.putIfAbsent(data.start(), (double) data.pitch());
            curve.putIfAbsent(data.end(), curve.lastEntry().getValue());
            double at = Math.clamp(gesture.at(), data.start(), data.end());
            double base = (double) data.pitch();
            if (gesture.point() >= 0) {
                var point = gesture.curve().get(gesture.point());
                at = point.sourceSeconds();
                base = point.pitch();
            }
            double target = base + (gesture.y() - event.getY()) / rowHeight();
            if (event.isShiftDown()) target = Math.rint(target);
            target = Math.round(Math.clamp(target, 12, 106) * 100) / 100.0;
            if (gesture.point() >= 0 || at == data.end()) {
                curve.put(at, target);
            } else {
                // Hold the pitch up to the grabbed time, then slew over the horizontal drag
                // (one presentation at minimum) and hold until the next authored point.
                var floor = curve.floorEntry(at);
                var ceiling = curve.ceilingEntry(at);
                double current = floor.getKey().equals(ceiling.getKey()) ? floor.getValue()
                        : floor.getValue() + (ceiling.getValue() - floor.getValue())
                        * (at - floor.getKey()) / (ceiling.getKey() - floor.getKey());
                double reach = model.toSource(model.toProject(at) + 1.0 / 60);
                double end = Math.min(data.end(), Math.max(reach, seconds(event.getX())));
                curve.put(at, current);
                curve.put(end, target);
                if (gesture.curve().isEmpty()) curve.put(data.end(), target);
            }
            bendPreview = curve.entrySet().stream().map(p -> new BendPoint(p.getKey(), p.getValue())).toList();
            effectReadout = String.format(Locale.ROOT, "Slide  %+.2f semitones", target - data.pitch());
        } else if (mode == Mode.VIBRATO) {
            var origin = gesture.vibrato();
            double hz = (origin == null ? 5 : origin.hz()) + (event.getX() - gesture.x()) * .08;
            double cents = (origin == null ? 0 : origin.cents()) + (gesture.y() - event.getY()) * 2;
            vibratoPreview = ReconstructionEditing.vibrato(gesture.note().pitch(), detune.applyAsInt(gesture.note()), hz, cents);
            effectReadout = vibratoLabel(vibratoPreview);
        }
    }

    static String vibratoLabel(ReconstructionEditing.Vibrato value) {
        return String.format(Locale.ROOT, "%.2f Hz · %.1f cents   (range %.1f–%.1f cents at this rate)",
                value.hz(), value.cents(), value.minCents(), value.maxCents());
    }

    private void finishEffect(MouseEvent event) {
        updateEffect(event);
        var gesture = effect;
        JsonObject intent = null;
        if (mode == Mode.CUT) {
            var data = gesture.attack();
            long tick = Math.round(model.toProject(gesture.at()) * 60);
            if (tick <= Math.round(model.toProject(data.start()) * 60)
                    || tick >= Math.round(model.toProject(data.end()) * 60)) {
                error.accept("Cut at least one driver tick from either end of the score note.");
            } else {
                int target = pitch(event.getY());
                intent = ReconstructionTools.cut(gesture.note().id(), gesture.at(), gesture.retune()
                        ? Math.clamp(target, data.drums() ? 0 : 12, data.drums() ? 127 : 106) : null);
            }
        } else if (dragged && mode == Mode.BEND) {
            intent = ReconstructionTools.bend(gesture.note().id(), gesture.attack().pitch(), bendPreview);
        } else if (dragged && mode == Mode.VIBRATO) {
            intent = ReconstructionTools.vibrato(gesture.note().id(), vibratoPreview);
        }
        cancelGesture();
        if (intent != null) onEffect.accept(intent);
        draw();
    }

    private Placement placement(NoteDrag gesture, MouseEvent event) {
        var original = Placement.of(gesture.note());
        double start = Math.clamp(original.start(), 0, model.duration());
        double end = Math.clamp(original.end(), start, model.duration());
        int pitch = original.pitch();
        double at = seconds(event.getX());
        double delta = at - gesture.at();
        double gate = Math.min(.017, end - start);
        if (gate <= 0) return original;
        switch (gesture.handle()) {
            case START -> start = Math.clamp(placementTime(start + delta, event.isAltDown()), 0, end - gate);
            case END -> end = Math.clamp(placementTime(end + delta, event.isAltDown()), start + gate, model.duration());
            case MOVE -> {
                if (Math.abs(delta * pixelsPerSecond) >= 1) {
                    double next = Math.clamp(placementTime(start + delta, event.isAltDown()), 0,
                            Math.max(0, model.duration() - (end - start)));
                    end = Math.min(model.duration(), end + next - start);
                    start = next;
                }
                pitch = Math.clamp(pitch + pitch(event.getY()) - gesture.pitch(), low, high);
            }
        }
        return new Placement(start, end, pitch);
    }

    private boolean inPitch(MouseEvent event) {
        return event.getX() >= AXIS && event.getY() >= sectionBottom() && event.getY() < pitchBottom();
    }

    private int pitch(double y) {
        return Math.clamp((int) Math.round(high + .5 - (y - sectionBottom()) / rowHeight()), low, high);
    }

    private double placementTime(double value, boolean free) {
        return free ? Math.clamp(value, 0, model.duration()) : model.snap(value, grid);
    }

    private Handle handle(Hit hit, double x) {
        // Very short notes need a usable body when zoomed out.
        double edge = Math.min(6, hit.width() / 4);
        if (hit.width() < 8) return Handle.MOVE;
        if (x - hit.left() <= edge) return Handle.START;
        if (hit.left() + hit.width() - x <= edge) return Handle.END;
        return Handle.MOVE;
    }

    private Section sectionAt(double x, double y) {
        if (x < AXIS || y < rulerHeight() || y >= sectionBottom()) return null;
        var level = Level.values()[(int) (y - rulerHeight()) / SECTION_HEIGHT];
        double time = seconds(x);
        return sections.stream().filter(section -> section.level() == level
                && section.sourceStart() <= time && time <= section.sourceEnd()).findFirst().orElse(null);
    }

    private Boolean sectionEdge(Section section, double x) {
        if (section == null) return null;
        double left = Math.abs(x(section.sourceStart()) - x);
        double right = Math.abs(x(section.sourceEnd()) - x);
        return Math.min(left, right) > 5 ? null : right <= left;
    }

    private void updateLanes() {
        var used = new HashSet<String>();
        for (var note : model.overlays()) if (note.lane() != null) used.add(note.lane());
        for (var note : pending) if (note.lane() != null) used.add(note.lane());
        var next = TimelineView.LANES.stream().filter(used::contains).toList();
        for (var lane : next) if (lanes == null || !lanes.contains(lane)) visibleLanes.add(lane);
        lanes = next;
    }

    List<String> lanes() { return lanes; }
    void source(AudioSource next) { source = next; draw(); }
    void assembledBends(Map<String, List<BendPoint>> curves) {
        assembledBends = curves;
        draw();
    }
    void dbRange(int range) { dbRange = range; draw(); }
    void ridges(boolean show) { ridges = show; draw(); }
    void projectRuler(boolean show) { projectRuler = show; draw(); }

    void roleVisible(Role role, boolean show) {
        if (show) roles.add(role); else roles.remove(role);
        draw();
    }

    boolean laneVisible(String lane) { return visibleLanes.contains(lane); }

    void laneVisible(String lane, boolean show) {
        if (show) visibleLanes.add(lane); else visibleLanes.remove(lane);
        draw();
    }

    void zoom(double value) {
        double playheadX = x(playhead);
        double anchor = playheadX >= AXIS && playheadX <= canvas.getWidth() ? playhead : seconds(AXIS + plotWidth() / 2);
        zoom = value;
        resizePlot();
        center(anchor);
    }

    private void resizePlot() {
        if (!ready) return;
        pixelsPerSecond = plotWidth() / Math.max(.001, model.duration()) * Math.pow(2, zoom);
        double height = Math.max(sectionBottom() + 145 + stripHeight(), scroll.getViewportBounds().getHeight());
        double width = AXIS + pixelsPerSecond * model.duration();
        if (Math.abs(height - contentHeight) >= .5 || Math.abs(width - contentWidth) >= .5) dimensions(width, height);
    }

    void center(double sourceSeconds) {
        double available = contentWidth - canvas.getWidth();
        scroll.setHvalue(available <= 0 ? 0 : Math.clamp((sourceSeconds * pixelsPerSecond - plotWidth() / 2) / available, 0, 1));
    }

    void movePlayhead(double seconds, boolean follow) {
        playhead = seconds;
        if (follow && (x(seconds) < AXIS || x(seconds) > canvas.getWidth())) center(seconds);
        draw();
    }

    void clearRange() {
        range = null;
        onRange.accept(null);
        draw();
    }

    private void selectRange(double end) {
        range = Math.abs(end - anchor) < .01 ? null
                : new ReviewTransport.Range(Math.min(anchor, end), Math.max(anchor, end));
        onRange.accept(range);
        draw();
    }

    void select(Overlay note, String lane) {
        selection.select(note == null ? List.of() : List.of(note.eventId()), note == null ? null : note.eventId());
        laneSelection = lane;
        publishSelection(note);
        draw();
    }

    List<Overlay> selectedNotes() {
        var byId = new java.util.LinkedHashMap<String, Overlay>();
        for (var note : selectionNotes) if (selection.contains(note)) byId.putIfAbsent(note.id(), note);
        return List.copyOf(byId.values());
    }

    void filterSelection(java.util.function.Predicate<Overlay> filter) {
        var kept = selectionNotes.stream().filter(selection::contains).filter(filter)
                .map(Overlay::eventId).toList();
        selection.select(kept, null);
        publishSelection(null);
        draw();
    }

    private void publishSelection(Overlay note) {
        var selected = new ArrayList<Overlay>();
        for (var row : model.overlays()) if (selection.contains(row)) selected.add(pendingByNote.getOrDefault(row.id(), row));
        for (var row : pending) if (selection.contains(row) && !selected.contains(row)) selected.add(row);
        selectionNotes = List.copyOf(selected);
        if (note == null) note = selectionNotes.stream().filter(selection::primary).findFirst()
                .orElse(selectionNotes.isEmpty() ? null : selectionNotes.getFirst());
        if (note != null) laneSelection = note.lane();
        onNote.accept(note);
        onLane.accept(laneSelection);
        onSelection.run();
    }

    private Hit hit(double x, double y) {
        if (x < AXIS) return null;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) return hits.get(i);
        return null;
    }

    private double x(double seconds) { return AXIS + seconds * pixelsPerSecond - offset; }
    private double seconds(double x) { return Math.clamp((x - AXIS + offset) / pixelsPerSecond, 0, model.duration()); }
    private double plotWidth() { return Math.max(100, scroll.getViewportBounds().getWidth() - AXIS); }
    private int rulerHeight() { return 31; }
    private int sectionBottom() { return rulerHeight() + SECTION_LABELS.size() * SECTION_HEIGHT; }
    private int stripHeight() { return LANE_HEADER + lanes.size() * LANE_HEIGHT; }
    private double pitchBottom() { return contentHeight - stripHeight(); }
    private double laneTop() { return pitchBottom() + LANE_HEADER; }
    private double rowHeight() { return (pitchBottom() - sectionBottom()) / (high - low + 1); }
    private double y(double pitch) { return sectionBottom() + (high + .5 - pitch) * rowHeight(); }

    @Override void draw() {
        if (!ready) return;
        var g = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        hits.clear();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, contentHeight);
        g.setFont(Font.font("Monospaced", 11));
        g.setTextBaseline(VPos.CENTER);
        drawSpectrum(g, width);
        drawPitchGrid(g, width);
        drawBands(g, width);
        drawLanes(g, width);
        drawRuler(g, width);
        g.save();
        clip(g, AXIS, 0, width - AXIS, contentHeight);
        if (range != null) {
            g.setFill(Color.web("#83abff", .17));
            g.fillRect(x(range.start()), 0, x(range.end()) - x(range.start()), contentHeight);
        }
        drawNotes(g, width);
        if (boundaryPreview != null) {
            g.setStroke(Color.GOLD);
            g.strokeLine(x(boundaryPreview), rulerHeight(), x(boundaryPreview), sectionBottom());
        }
        if (preview != null) {
            g.setStroke(Color.GOLD);
            double left = x(preview.start());
            g.strokeRect(left, y(preview.pitch()) - 3, Math.max(2, x(preview.end()) - left), 6);
        }
        g.setStroke(CURSOR);
        g.strokeLine(x(playhead), 0, x(playhead), contentHeight);
        g.restore();
        g.setStroke(GRID);
        g.strokeLine(AXIS - .5, 0, AXIS - .5, contentHeight);
    }

    private void drawSpectrum(GraphicsContext g, double width) {
        if (source == null) {
            g.setFill(SUBTLE);
            g.fillText("No source audio available", AXIS + 16, sectionBottom() + 22);
            return;
        }
        int level = (int) Math.clamp(Math.floor(Math.log(ReconstructionTiles.WIDTH / (1.5 * pixelsPerSecond))
                / Math.log(2)), 0, 7);
        double tileSpan = Math.scalb(1.5, level);
        double end = seconds(width);
        var needed = new LinkedHashSet<ReconstructionTiles.ImageKey>();
        g.save();
        clip(g, AXIS, sectionBottom(), width - AXIS, pitchBottom() - sectionBottom());
        for (int index = (int) (seconds(AXIS) / tileSpan); index * tileSpan < end; index++) {
            var key = new ReconstructionTiles.Key(source, level, index, low, high);
            var imageKey = new ReconstructionTiles.ImageKey(key, dbRange);
            needed.add(imageKey);
            var tile = tiles.get(imageKey);
            if (tile == null) {
                drawCoarserTile(g, key);
                continue;
            }
            g.drawImage(tile.image(), x(key.start()), sectionBottom(), tileSpan * pixelsPerSecond,
                    pitchBottom() - sectionBottom());
            if (ridges) {
                g.setFill(Color.web("#9cf4fb", .75));
                for (var ridge : tile.ridges()) if (ridge.dbfs() >= ReconstructionTiles.CEILING_DB - dbRange)
                    g.fillOval(x(ridge.sourceSeconds()) - 1, y(ridge.pitch()) - 1, 2, 2);
            }
        }
        g.restore();
        tiles.request(needed);
    }

    private void drawCoarserTile(GraphicsContext g, ReconstructionTiles.Key key) {
        for (int level = key.level() + 1; level <= 7; level++) {
            var parent = new ReconstructionTiles.Key(key.source(), level,
                    key.index() >> (level - key.level()), key.low(), key.high());
            var cached = tiles.get(new ReconstructionTiles.ImageKey(parent, dbRange));
            if (cached == null) continue;
            g.save();
            clip(g, x(key.start()), sectionBottom(), key.span() * pixelsPerSecond, pitchBottom() - sectionBottom());
            g.drawImage(cached.image(), x(parent.start()), sectionBottom(), parent.span() * pixelsPerSecond,
                    pitchBottom() - sectionBottom());
            g.restore();
            return;
        }
    }

    private void drawPitchGrid(GraphicsContext g, double width) {
        g.setFill(PANEL);
        g.fillRect(0, sectionBottom(), AXIS, pitchBottom() - sectionBottom());
        int minimumSpacing = Math.max(1, (int) Math.ceil(12 / rowHeight()));
        for (int pitch = low; pitch <= high; pitch++) {
            g.setStroke(pitch % 12 == 0 ? GRID : Color.web("#a5badd", .07));
            g.strokeLine(AXIS, y(pitch - .5), width, y(pitch - .5));
            if (minimumSpacing > 1 && pitch % 12 % minimumSpacing != 0) continue;
            g.setFill(pitch % 12 == 0 ? TEXT : SUBTLE);
            g.fillText(pitchName(pitch), 12, y(pitch));
        }
    }

    private void drawBands(GraphicsContext g, double width) {
        g.setFill(PANEL);
        g.fillRect(0, rulerHeight(), width, sectionBottom() - rulerHeight());
        g.setFill(SUBTLE);
        for (int i = 0; i < SECTION_LABELS.size(); i++)
            g.fillText(SECTION_LABELS.get(i), 8, rulerHeight() + (i + .5) * SECTION_HEIGHT);
        g.save();
        clip(g, AXIS, rulerHeight(), width - AXIS, sectionBottom() - rulerHeight());
        for (var band : sections) {
            double left = x(band.sourceStart()), right = x(band.sourceEnd());
            if (right < AXIS || left > width) continue;
            double top = rulerHeight() + band.level().ordinal() * SECTION_HEIGHT;
            g.setFill(switch (band.level()) {
                case section -> Color.web("#2b4767");
                case period -> Color.web("#304058");
                case phrase -> Color.web("#243147");
            });
            g.fillRect(left, top, Math.max(1, right - left - 1), SECTION_HEIGHT - 1);
            g.setFill(TEXT);
            double labelStart = Math.max(AXIS, left) + 5;
            String label = band.label();
            double space = right - labelStart - 3;
            drawLabel(g, label, labelStart, top + SECTION_HEIGHT / 2.0, space);
        }
        g.restore();
    }

    private void drawLanes(GraphicsContext g, double width) {
        g.setFill(PANEL);
        g.fillRect(0, pitchBottom(), width, stripHeight());
        g.setFill(TEXT);
        g.fillText("Chip lanes", 4, pitchBottom() + LANE_HEADER / 2.0);
        if (lanes.isEmpty()) g.fillText("No placed notes", AXIS + 16, pitchBottom() + LANE_HEADER / 2.0);
        for (int i = 0; i < lanes.size(); i++) {
            String lane = lanes.get(i);
            double top = laneTop() + i * LANE_HEIGHT;
            g.setFill(lane.equals(laneSelection) ? Color.web("#304666") : i % 2 == 0 ? BACKGROUND : PANEL);
            g.fillRect(0, top, width, LANE_HEIGHT);
            g.setFill(visibleLanes.contains(lane) ? TEXT : SUBTLE);
            g.fillText(lane, 12, top + LANE_HEIGHT / 2.0);
        }
    }

    private void drawRuler(GraphicsContext g, double width) {
        g.setFill(PANEL);
        g.fillRect(0, 0, width, rulerHeight());
        g.setFill(TEXT);
        g.fillText(projectRuler ? "Project s" : "Source s", 8, rulerHeight() / 2.0);
        g.save();
        clip(g, AXIS, 0, width - AXIS, contentHeight);
        double metric = 82 / pixelsPerSecond;
        double magnitude = Math.pow(10, Math.floor(Math.log10(metric)));
        double step = (metric / magnitude < 2 ? 2 : metric / magnitude < 5 ? 5 : 10) * magnitude;
        double start = seconds(AXIS), end = seconds(width);
        if (projectRuler) {
            start = model.toProject(start);
            end = model.toProject(end);
        }
        for (double tick = Math.ceil(start / step) * step; tick <= end; tick += step) {
            double left = x(projectRuler ? model.toSource(tick) : tick);
            g.setStroke(GRID);
            g.strokeLine(left + .5, rulerHeight() - 8, left + .5, contentHeight);
            g.setFill(SUBTLE);
            g.fillText(String.format(Locale.ROOT, step >= 1 ? "%.0f" : step >= .1 ? "%.1f" : "%.2f", tick),
                    left + 5, rulerHeight() / 2.0);
        }
        g.restore();
    }

    private void drawNotes(GraphicsContext g, double width) {
        g.save();
        clip(g, AXIS, sectionBottom(), width - AXIS, pitchBottom() - sectionBottom());
        var visible = visibleNotes(width);
        var labels = new ArrayList<LabelBounds>();
        for (var note : visible) {
            if (note.part() < 0 || note.pitch() < low || note.pitch() > high) continue;
            double left = x(note.interval().sourceStart());
            double barWidth = Math.max(2, x(note.interval().sourceEnd()) - left);
            double height = Math.max(3, Math.min(13, rowHeight() - 1));
            double top = y(note.pitch()) - height / 2;
            var color = color(note.role());
            boolean draft = pendingByNote.get(note.id()) == note;
            boolean deleted = draft && note.fate().equals("Pending deletion");
            boolean dashed = note.style() == ReconstructionInspection.Style.DASHED;
            if (!outline && !deleted) {
                g.setFill(color.deriveColor(0, 1, 1, dashed ? opacity * .4 : opacity));
                g.fillRect(left, top, barWidth, height);
            }
            g.setStroke(draft ? Color.GOLD : selection.contains(note) ? Color.WHITE : color);
            g.setLineWidth(draft || selection.contains(note) ? 1.6 : 1);
            if (dashed && !draft) g.setLineDashes(3, 2);
            g.strokeRect(left + .5, top + .5, barWidth - 1, height - 1);
            if (deleted) g.strokeLine(left, top + height / 2, left + barWidth, top + height / 2);
            g.setLineWidth(1);
            g.setLineDashes();
            if (!draft || !note.bend().isEmpty()) drawBend(g, note, left, barWidth, draft ? Color.GOLD : color);
            if (mode == Mode.BEND && selection.contains(note) && effect == null) {
                var data = attack.apply(note);
                if (data != null) drawCurve(g, data.bend(), Color.GOLD, true);
            }
            if (pixelsPerSecond >= 24) {
                double labelLeft = Math.max(left, AXIS) + 3;
                double space = Math.min(left + barWidth, width) - labelLeft;
                String text = fitLabel(shortLabel(note), space);
                double baseline = top - 7;
                if (!text.isEmpty() && baseline >= sectionBottom() + 7) {
                    var bounds = new LabelBounds(labelLeft, baseline - 7, text.length() * 6.7, 14);
                    if (labels.stream().noneMatch(bounds::intersects)) {
                        labels.add(bounds);
                        g.setFill(BACKGROUND.deriveColor(0, 1, 1, .83));
                        g.fillRoundRect(bounds.x - 2, bounds.y, bounds.width + 4, bounds.height, 3, 3);
                        g.setFill(TEXT);
                        g.fillText(text, labelLeft, baseline);
                    }
                }
            }
            hits.add(new Hit(note, left, top, barWidth, height));
        }
        if (box != null && dragged) {
            g.setFill(Color.LIGHTSKYBLUE.deriveColor(0, 1, 1, .2));
            g.fillRect(box.left(), box.top(), box.width(), box.height());
            g.setStroke(Color.LIGHTSKYBLUE);
            g.strokeRect(box.left(), box.top(), box.width(), box.height());
        }
        if (effect != null) {
            drawCurve(g, bendPreview, Color.GOLD, true);
            if (mode == Mode.CUT) {
                var data = effect.attack();
                g.setStroke(Color.GOLD);
                g.strokeLine(effect.x(), y(data.pitch() + .5), effect.x(), y(data.pitch() - .5));
            }
            if (effectReadout != null) {
                double left = Math.clamp(effect.x() + 10, AXIS, Math.max(AXIS, width - 360));
                double baseline = Math.max(sectionBottom() + 19, effect.y() - 16);
                g.setFill(BACKGROUND);
                g.fillRoundRect(left, baseline - 15, effectReadout.length() * 7 + 10, 21, 5, 5);
                g.setFill(Color.GOLD);
                g.fillText(effectReadout, left + 5, baseline);
            }
        }
        g.restore();

        for (var note : visible) {
            if (!note.sounds()) continue;
            int lane = lanes.indexOf(note.lane());
            if (lane < 0) continue;
            double left = Math.max(AXIS, x(note.interval().sourceStart()));
            double barWidth = Math.max(2, x(note.interval().sourceEnd()) - left);
            double top = laneTop() + lane * LANE_HEIGHT + 2;
            var color = color(note.role());
            g.setFill(color.deriveColor(0, 1, 1, .45));
            g.fillRect(left, top, barWidth, LANE_HEIGHT - 4);
            if (selection.contains(note) || pendingByNote.get(note.id()) == note) {
                g.setStroke(pendingByNote.get(note.id()) == note ? Color.GOLD : Color.WHITE);
                g.strokeRect(left, top, barWidth, LANE_HEIGHT - 4);
            }
            if (barWidth > 30) {
                g.setFill(TEXT);
                drawLabel(g, note.patch() == null ? pitchName(note.pitch()) : note.patch(),
                        left + 3, top + (LANE_HEIGHT - 4) / 2.0, Math.min(width - left, barWidth) - 6);
            }
            hits.add(new Hit(note, left, top, barWidth, LANE_HEIGHT - 4));
        }
    }

    private void drawBend(GraphicsContext g, Overlay note, double left, double width, Color color) {
        var bend = pendingByNote.containsKey(note.id()) ? note.bend()
                : assembledBends.getOrDefault(note.eventId(), note.bend());
        if (bend.isEmpty()) return;
        g.save();
        clip(g, left, sectionBottom(), width, pitchBottom() - sectionBottom());
        drawCurve(g, bend, color.brighter(), false);
        g.restore();
    }

    private void drawCurve(GraphicsContext g, List<BendPoint> bend, Color color, boolean handles) {
        if (bend.isEmpty()) return;
        g.setStroke(color);
        g.setLineWidth(1.6);
        g.beginPath();
        var first = bend.getFirst();
        g.moveTo(x(first.sourceSeconds()), y(first.pitch()));
        for (var point : bend) g.lineTo(x(point.sourceSeconds()), y(point.pitch()));
        g.stroke();
        g.setLineWidth(1);
        if (handles) {
            g.setFill(color);
            for (var point : bend) g.fillOval(x(point.sourceSeconds()) - 3, y(point.pitch()) - 3, 6, 6);
        }
    }

    private List<Overlay> visibleNotes(double width) {
        double from = seconds(AXIS), to = seconds(width);
        int first = 0, last = endByIndex.length;
        while (first < last) {
            int middle = (first + last) >>> 1;
            if (endByIndex[middle] < from) first = middle + 1;
            else last = middle;
        }
        var notes = model.overlays();
        var visible = new ArrayList<Overlay>();
        for (int i = first; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.interval().sourceStart() > to) break;
            if (!pendingByNote.containsKey(note.id()) && note.interval().sourceEnd() >= from && roles.contains(note.role())
                    && visibleLanes.contains(note.lane() == null ? UNASSIGNED : note.lane()))
                visible.add(note);
        }
        for (var note : pending) {
            if (!note.fate().equals("Superseded draft")
                    && note.interval().sourceEnd() >= from && note.interval().sourceStart() <= to) visible.add(note);
        }
        return visible;
    }

    private record LabelBounds(double x, double y, double width, double height) {
        boolean intersects(LabelBounds other) {
            return x < other.x + other.width && other.x < x + width
                    && y < other.y + other.height && other.y < y + height;
        }
    }

    private static void drawLabel(GraphicsContext g, String text, double x, double y, double width) {
        g.fillText(fitLabel(text, width), x, y);
    }

    private static String fitLabel(String text, double width) {
        int characters = (int) (width / 6.7);
        if (characters >= text.length()) return text;
        if (characters < 3) return "";
        return text.substring(0, characters - 1).stripTrailing() + "…";
    }

    static String pitchName(int pitch) { return PITCHES[pitch % 12] + (pitch / 12 - 1); }
    static String roleName(Role role) {
        String name = role.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
    static Color color(Role role) { return Color.rgb(role.rgb() >> 16, role.rgb() >> 8 & 255, role.rgb() & 255); }

    static String shortLabel(Overlay note) {
        return pitchName(note.pitch()) + " · " + (note.lane() == null ? UNASSIGNED : note.lane())
                + (note.patch() == null ? "" : " · " + note.patch());
    }

    static String description(Overlay note) {
        return shortLabel(note) + " · " + roleName(note.role()) + "\n" + note.fate()
                + (note.reason() == null ? "" : " · " + note.reason())
                + (note.competitors().isEmpty() ? "" : "\nCompeting note: " + String.join(", ", note.competitors()));
    }

    private static void clip(GraphicsContext g, double x, double y, double width, double height) {
        g.beginPath();
        g.rect(x, y, Math.max(0, width), Math.max(0, height));
        g.clip();
    }

    @Override public void close() {
        Tooltip.uninstall(canvas, hover);
        tiles.close();
    }
}

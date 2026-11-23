package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.AudioSource;
import com.opensmpsdeck.headless.ReconstructionInspection.BendPoint;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.project.TargetGame;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.paint.Color;
import com.opensmpsdeck.headless.ReconstructionLayers;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionEditing;
import java.util.function.BiConsumer;

import com.opensmpsdeck.headless.ReconstructionSections.Level;
import com.opensmpsdeck.headless.ReconstructionSections.Section;
import com.opensmpsdeck.studio.ReconstructionTools.Mode;
import com.opensmpsdeck.studio.ReconstructionTools.Placement;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import java.util.function.DoubleConsumer;

/** Source-time viewport and hit testing. The underlying trace and score remain immutable. */
final class ReconstructionCanvas extends ViewportCanvas implements AutoCloseable {
    static final String UNASSIGNED = "Unallocated";
    private static final int AXIS = 76;
    private static final int SECTION_HEIGHT = 18;
    private static final int LANE_HEIGHT = 17;
    private static final int LANE_HEADER = 20;
    static final int MAX_TIME_ZOOM = 9;
    private static final double WHEEL_STEP = 40;
    private static final double MIDI_TOP = 127.5;
    private static final double MIDI_BOTTOM = -.5;
    private static final Color BACKGROUND = Color.web("#0c1220");
    private static final Color PANEL = Color.web("#151f31");
    private static final Color TEXT = Color.web("#dce7f8");
    private static final Color SUBTLE = Color.web("#98aeca");
    private static final Color CURSOR = Color.web("#faf0a9");
    private static final Color GRID = Color.web("#a5badd").deriveColor(0, 1, 1, .16);
    private static final Color FAINT_GRID = Color.web("#a5badd", .07);
    private static final Color RANGE = Color.web("#83abff", .17);
    private static final Color RIDGE = Color.web("#9cf4fb", .75);
    private static final Color SECTION_FILL = Color.web("#2b4767");
    private static final Color PERIOD_FILL = Color.web("#304058");
    private static final Color PHRASE_FILL = Color.web("#243147");
    private static final Color LISTENING = Color.web("#6de1cc");
    private static final Color SELECTED_LANE = Color.web("#304666");
    private static final Color TAG_BACKGROUND = BACKGROUND.deriveColor(0, 1, 1, .91);
    private static final Color LABEL_BACKGROUND = BACKGROUND.deriveColor(0, 1, 1, .83);
    private static final Color BOX_FILL = Color.web("#83abff", .20);
    private static final Color BOX_LINE = Color.web("#83abff");
    private static final Color PROPOSAL = Color.web("#ef8aff");
    private static final Font FONT = Font.font("Monospaced", 11);
    private static final String[] PITCHES = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};


    private record Hit(Overlay note, double left, double top, double width, double height) {
        boolean contains(double x, double y) {
            return x >= left && x <= left + width && y >= top && y <= top + height;
        }
    }

    private boolean rolesView;
    private boolean showDrums;
    private boolean reviewing;
    private Map<String, Integer> drumRows = Map.of();
    final ScrollBar pitchScroll = new ScrollBar();
    private boolean updatingPitchScroll;
    private boolean autoFitPitch = true;
    private boolean pitchFitDirty = true;
    private double fittedFrom = Double.NaN;
    private double fittedTo = Double.NaN;
    private ReconstructionVoices voices = new ReconstructionVoices(List.of(), Map.of());
    private final Set<String> hiddenVoices = new HashSet<>();
    private String soloVoice;
    Runnable onVoiceMonitor = () -> {};

    boolean voiceMonitorActive() { return soloVoice != null || !hiddenVoices.isEmpty(); }

    boolean voiceAudible(Overlay note) {
        var voice = voices.byNote().get(note.id());
        return (soloVoice == null || voice != null && soloVoice.equals(voice.id()))
                && (voice == null || voiceVisible(voice.id()));
    }
    private Set<String> detailedNotes = Set.of();
    private Overlay roleDrag;

    Consumer<List<Overlay>> onDetailJump = notes -> {};

    private ReconstructionInspection model;
    private ReconstructionTiles tiles;
    private boolean spectrumReady;

    boolean spectrumReady() { return spectrumReady; }
    private final Consumer<String> error;
    private List<Section> sections;
    private Mode mode = Mode.SELECT;
    private ReconstructionInspection.Grid grid = ReconstructionInspection.Grid.BEAT;
    private final ReconstructionTools selection = new ReconstructionTools();
    record ProposalMark(com.opensmpsdeck.headless.ReconstructionProposals.Marker note, String reason, String id) {}
    private record ProposalHit(ProposalMark proposal, double left, double top, double width, double height) {
        boolean contains(double x, double y) {
            return x >= left && x <= left + width && y >= top && y <= top + height;
        }
    }
    record ContextTarget(Overlay note, ProposalMark proposal, double time, Placement insertion) {}
    BiConsumer<javafx.scene.input.ContextMenuEvent, ContextTarget> onContext = (event, target) -> {};

    private List<ProposalMark> proposals = List.of();
    private record SkipHit(double x, double y, String reason) {
        boolean contains(double px, double py) {
            return px >= x - 2 && px <= x + 7 && py >= y - 2 && py <= y + 7;
        }
    }

    private final List<SkipHit> layerSkips = new ArrayList<>();
    private final List<ProposalHit> proposalHits = new ArrayList<>();
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
    private List<ReconstructionEditing.VibratoSegment> vibratoPreview;
    private String effectReadout;
    private List<Overlay> selectionNotes = List.of();
    private SectionDrag sectionDrag;
    private Placement preview;
    private Double boundaryPreview;
    private boolean dragged;
    private Pan pan;
    private boolean spaceHeld;
    private boolean spaceDragged;

    private enum Handle { MOVE, START, END }
    private record Pan(MouseButton button, double sceneX, double sceneY) {}
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
                              List<ReconstructionEditing.VibratoSegment> vibrato, int vibratoTick) {}
    private record SectionDrag(Section section, boolean end) {}
    record SectionPoint(Section section, double time, double screenX, double screenY) {}
    private final Tooltip hover = new Tooltip();
    private final List<Hit> hits = new ArrayList<>();
    private List<String> lanes;
    private int builtLow, builtHigh;
    private double pitchTop, pitchSpan;
    private static final List<String> SECTION_LABELS = List.of("Section", "Period", "Phrase");
    private double[] endByIndex;
    private final Set<Role> roles = EnumSet.allOf(Role.class);
    private final Set<String> visibleLanes = new HashSet<>();
    private double pixelsPerSecond = 1;
    private double zoom;
    private double playhead;

    double playhead() { return playhead; }
    private Double anchor;
    private ReviewTransport.Range range;
    private Role soloRole;
    private Section listeningSection;
    private String laneSelection;
    private AudioSource source;
    private Map<String, List<BendPoint>> assembledBends = Map.of();
    private ReconstructionTiles.Contrast contrast = new ReconstructionTiles.Contrast(ReconstructionTiles.CEILING_DB, 55);
    private boolean autoContrast = true;
    private Object autoSignature;
    private java.util.function.Consumer<ReconstructionTiles.Contrast> contrastChanged = ignored -> {};
    private boolean reassigned;
    private boolean ridges;
    private boolean projectRuler;
    private boolean ready;

    Consumer<Overlay> onNote = ignored -> {};
    Consumer<String> onLane = ignored -> {};
    Consumer<ReviewTransport.Range> onRange = ignored -> {};
    Consumer<Section> onSection = ignored -> {};
    Consumer<List<Section>> onSectionsChanged = ignored -> {};
    DoubleConsumer onSeek = ignored -> {};
    DoubleConsumer onTimeZoom = ignored -> {};

    Consumer<JsonObject> onEffect = ignored -> {};
    Runnable onSelection = () -> {};
    Function<Overlay, ReconstructionEditing.Attack> attack = ignored -> null;
    Function<Overlay, List<ReconstructionEditing.VibratoSegment>> vibrato = ignored -> List.of();

    Consumer<Placement> onInsert = ignored -> {};
    BiConsumer<Overlay, Placement> onPlacement = (note, place) -> {};
    Consumer<SectionEdits.Mutation> onSectionEdit = ignored -> {};
    Consumer<SectionPoint> onSectionMenu = ignored -> {};

    ReconstructionCanvas(ReconstructionInspection model, Consumer<String> error) {
        super(AXIS + 100);
        this.error = error;
        refresh(model);
        canvas.setId("reconstruction-canvas");
        scroll.setFitToHeight(true);
        pitchScroll.setId("reconstruction-pitch-scroll");
        pitchScroll.setAccessibleText("Scroll pitch");
        pitchScroll.setOrientation(Orientation.VERTICAL);
        pitchScroll.valueProperty().addListener((ignored, old, value) -> {
            if (updatingPitchScroll) return;
            autoFitPitch = false;
            cancelGesture();
            pitchTop = MIDI_TOP - value.doubleValue();
            draw();
        });
        scroll.viewportBoundsProperty().addListener((ignored, old, next) -> resizePlot());
        canvas.setOnMouseMoved(event -> {
            if (spaceHeld || pan != null) return;
            var hit = hit(event.getX(), event.getY());
            var section = sectionAt(event.getX(), event.getY());
            var proposalsAtPointer = proposalHits.stream().filter(mark -> mark.contains(event.getX(), event.getY()))
                    .map(mark -> mark.proposal().reason()).distinct().limit(4)
                    .collect(java.util.stream.Collectors.joining("\n"));
            String blockers = layerSkips.stream().filter(mark -> mark.contains(event.getX(), event.getY()))
                    .map(SkipHit::reason).distinct().limit(4).collect(java.util.stream.Collectors.joining("\n"));
            String text = !blockers.isEmpty() ? blockers : !proposalsAtPointer.isEmpty() ? proposalsAtPointer
                    : hit != null ? hoverDescription(hit.note()) : section != null
                    ? section.label() + " · " + section.author() : "";
            if (text.isEmpty()) hover.hide();
            hover.setText(text);
            boolean horizontal = sectionEdge(section, event.getX()) != null
                    || (mode == Mode.SELECT || mode == Mode.INSERT) && hit != null
                    && inPitch(event) && handle(hit, event.getX()) != Handle.MOVE;
            if (rolesView) horizontal = false;
            boolean crosshair = !rolesView && inPitch(event) && ((mode == Mode.INSERT && insertAvailable)
                    || mode == Mode.CUT || mode == Mode.BEND || mode == Mode.VIBRATO);
            canvas.setCursor(editing && !reviewing && horizontal ? Cursor.H_RESIZE
                    : editing && !reviewing && crosshair ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        });
        hover.setShowDelay(javafx.util.Duration.millis(250));
        hover.setShowDuration(javafx.util.Duration.seconds(20));
        Tooltip.install(canvas, hover);
        canvas.setOnMousePressed(this::press);
        canvas.setOnMouseDragged(this::drag);
        canvas.setOnDragDetected(event -> {
            if (roleDrag == null || !editing) return;
            if (!selection.contains(roleDrag)) select(roleDrag, roleDrag.lane());
            var content = new ClipboardContent();
            content.putString("Reconstruction notes");
            canvas.startDragAndDrop(TransferMode.MOVE).setContent(content);
            cancelGesture();
            event.consume();
        });
        canvas.setOnMouseReleased(this::release);
        canvas.setOnContextMenuRequested(event -> {
            var point = event.isKeyboardTrigger()
                    ? selectedNotes().stream().findFirst().flatMap(note -> noteContextPoint(note.id())).orElse(null)
                    : canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
            if (point == null) return;
            double x = point.getX(), y = point.getY();
            if (x < axis() || (!reviewing && sectionAt(x, y) != null)) return;
            var hit = hit(x, y);
            var mark = proposalHits.stream().filter(proposal -> proposal.contains(x, y))
                    .map(ProposalHit::proposal).findFirst().orElse(null);
            canvas.requestFocus();
            if (!reviewing && hit != null && selectedNotes().stream().noneMatch(note -> note.id().equals(hit.note().id())))
                select(hit.note(), hit.note().lane());
            onContext.accept(event, new ContextTarget(hit == null ? null : hit.note(), mark, seconds(x),
                    !rolesView && pitchArea(y) ? insertionAt(seconds(x), pitch(y), false) : null));
        });
        canvas.setOnScroll(this::wheelZoom);
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
        builtLow = 127;
        builtHigh = 0;
        for (var note : notes) if (!isDrum(note, null)) includePitch(note);
        if (builtLow > builtHigh) {
            builtLow = 42;
            builtHigh = 78;
        }
        if (!ready) {
            pitchTop = builtHigh + .5;
            pitchSpan = builtHigh - builtLow + 1;
        }
        pitchFitDirty = true;
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
        onSectionsChanged.accept(next);
        draw();
    }

    void proposals(List<ProposalMark> marks) {
        proposals = List.copyOf(marks);
        draw();
    }

    private void includePitch(Overlay note) {
        builtLow = Math.min(builtLow, noteLow(note));
        builtHigh = Math.max(builtHigh, noteHigh(note));
    }

    private static int noteLow(Overlay note) {
        double min = note.bend().stream().mapToDouble(BendPoint::pitch).min().orElse(note.pitch());
        return Math.max(0, (int) Math.floor(Math.min(min, note.pitch())) - 3);
    }

    private static int noteHigh(Overlay note) {
        double max = note.bend().stream().mapToDouble(BendPoint::pitch).max().orElse(note.pitch());
        return Math.min(127, (int) Math.ceil(Math.max(max, note.pitch())) + 4);
    }

    void fitPitch() {
        autoFitPitch = true;
        pitchFitDirty = true;
        cancelGesture();
        draw();
    }

    void zoomPitch(double factor) {
        zoomPitch(factor, .5);
    }

    private void zoomPitch(double factor, double fraction) {
        double span = Math.clamp(pitchSpan * factor, 6, 128);
        double top = Math.clamp(pitchTop + fraction * (span - pitchSpan), MIDI_BOTTOM + span, MIDI_TOP);
        autoFitPitch = false;
        cancelGesture();
        pitchRange(top, span);
        draw();
    }

    // The pitch fit is view state that callers read back at once, so it is not deferred with the paint.
    @Override void settle() {
        if (ready) fitVisiblePitch(canvas.getWidth());
    }

    private void fitVisiblePitch(double width) {
        double from = seconds(axis()), to = seconds(width);
        if (!autoFitPitch || !pitchFitDirty && fittedFrom == from && fittedTo == to) return;
        fittedFrom = from;
        fittedTo = to;
        pitchFitDirty = false;
        int bottom = 127, top = 0;
        for (var note : visibleNotes(width)) {
            if (!roles.contains(note.role()) || isDrum(note, voices.byNote().get(note.id()))
                    || !selectableDraft(note)) continue;
            bottom = Math.min(bottom, noteLow(note));
            top = Math.max(top, noteHigh(note));
        }
        if (bottom > top) {
            bottom = builtLow;
            top = builtHigh;
        }
        pitchRange(top + .5, top - bottom + 1);
    }

    private void pitchRange(double top, double span) {
        pitchTop = top;
        pitchSpan = span;
        updatingPitchScroll = true;
        try {
            pitchScroll.setMax(128 - span);
            // JavaFX sizes the thumb relative to the scrollable range.
            pitchScroll.setVisibleAmount(span * (128 - span) / 128.0);
            pitchScroll.setBlockIncrement(Math.max(1, span / 2));
            pitchScroll.setUnitIncrement(1);
            pitchScroll.setValue(MIDI_TOP - pitchTop);
            pitchScroll.setDisable(span == 128);
        } finally {
            updatingPitchScroll = false;
        }
    }

    void pending(List<Overlay> overlays) {
        pending = overlays;
        pitchFitDirty = true;
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

    boolean rolesView() { return rolesView; }

    void rolesView(boolean enabled) {
        if (rolesView == enabled) return;
        rolesView = enabled;
        cancelGesture();
        resizePlot();
        draw();
    }

    void reviewing(boolean enabled) {
        if (reviewing == enabled) return;
        reviewing = enabled;
        cancelGesture();
        draw();
    }

    boolean reviewing() { return reviewing; }

    void voices(ReconstructionVoices projection, Set<String> edited) {
        voices = projection;
        detailedNotes = edited;
        var rows = new java.util.LinkedHashMap<String, Integer>();
        for (var entry : voices.entries()) {
            if (entry.notes().stream().anyMatch(note -> isDrum(note, entry)))
                rows.put(entry.id(), rows.size());
        }
        drumRows = rows;
        pitchFitDirty = true;
        resizePlot();
        draw();
    }

    ReconstructionVoices voices() { return voices; }

    void voiceTags(ReconstructionVoices projection) {
        voices = projection;
        draw();
    }

    void showDrums(boolean show) {
        showDrums = show;
        resizePlot();
        draw();
    }

    boolean voiceVisible(String id) { return !hiddenVoices.contains(id); }

    void voiceVisible(String id, boolean visible) {
        boolean changed = visible ? hiddenVoices.remove(id) : hiddenVoices.add(id);
        if (!changed) return;
        pitchFitDirty = true;
        draw();
        onVoiceMonitor.run();
    }

    void soloVoice(String id) {
        if (java.util.Objects.equals(soloVoice, id)) return;
        soloVoice = id;
        draw();
        onVoiceMonitor.run();
    }

    private void pressRole(MouseEvent event) {
        var hit = hit(event.getX(), event.getY());
        if (!inPitch(event)) {
            if (hit != null) roleDrag = hit.note();
            return;
        }
        if (hit == null || event.isShiftDown() || event.isControlDown()) {
            box = new Box(event.getX(), event.getY(), event.getX(), event.getY(), event.isShiftDown(), event.isControlDown());
        } else roleDrag = hit.note();
    }

    private void releaseRole(MouseEvent event) {
        var hit = hit(event.getX(), event.getY());
        boolean moved = dragged;
        cancelGesture();
        if (moved) return;
        if (hit != null) {
            if (!selection.contains(hit.note())) select(hit.note(), hit.note().lane());
            if (event.getClickCount() == 2) onDetailJump.accept(selectedNotes());
            else onSeek.accept(hit.note().interval().sourceStart());
        } else if (event.getX() >= axis()) {
            var section = sectionAt(event.getX(), event.getY());
            if (section != null) onSection.accept(section);
            else {
                if (inPitch(event)) select(null, null);
                onSeek.accept(seconds(event.getX()));
            }
        }
        draw();
    }

    void selectNotes(List<Overlay> notes) {
        var ids = notes.stream().map(Overlay::id).collect(java.util.stream.Collectors.toSet());
        var events = java.util.stream.Stream.concat(
                model.overlays().stream().filter(note -> !pendingByNote.containsKey(note.id())), pending.stream())
                .filter(note -> ids.contains(note.id())).map(Overlay::eventId).toList();
        selection.select(events, null);
        publishSelection(null);
        draw();
    }

    double detailZoom(List<Overlay> notes) {
        double start = notes.stream().mapToDouble(note -> note.interval().sourceStart()).min().orElse(0);
        double end = notes.stream().mapToDouble(note -> note.interval().sourceEnd()).max().orElse(start);
        return Math.clamp(Math.log(model.duration() / Math.max(.05, (end - start) * 1.2)) / Math.log(2),
                0, MAX_TIME_ZOOM);
    }

    void mode(Mode mode) { this.mode = mode; cancelGesture(); draw(); }
    void grid(ReconstructionInspection.Grid grid) { this.grid = grid; }
    void editing(boolean enabled) { editing = enabled; if (!enabled) cancelGesture(); }
    void insertAvailable(boolean available) { insertAvailable = available; }
    void opacity(double value) { opacity = value; draw(); }
    void outline(boolean value) { outline = value; draw(); }

    private void cancelGesture() {
        noteDrag = null;
        roleDrag = null;
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

    boolean panning() {
        return spaceHeld || pan != null;
    }

    void holdSpace() {
        if (spaceHeld) return;
        spaceHeld = true;
        spaceDragged = false;
        updatePanCursor();
    }

    boolean releaseSpace() {
        boolean tap = spaceHeld && !spaceDragged;
        spaceHeld = false;
        updatePanCursor();
        return tap;
    }

    void cancelNavigation() {
        pan = null;
        releaseSpace();
    }

    private void updatePanCursor() {
        canvas.setCursor(pan != null ? Cursor.CLOSED_HAND : spaceHeld ? Cursor.OPEN_HAND : Cursor.DEFAULT);
        if (pan != null || spaceHeld) hover.hide();
    }

    private void beginPan(MouseButton button, MouseEvent event) {
        cancelGesture();
        pan = new Pan(button, event.getSceneX(), event.getSceneY());
        updatePanCursor();
        draw();
    }

    private void press(MouseEvent event) {
        if (pan != null) {
            event.consume();
            return;
        }
        if (event.getButton() == MouseButton.MIDDLE || spaceHeld && event.getButton() == MouseButton.PRIMARY) {
            canvas.requestFocus();
            beginPan(event.getButton(), event);
            event.setDragDetect(false);
            event.consume();
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY) return;
        cancelGesture();
        canvas.requestFocus();
        if (event.isShiftDown() && event.getX() >= axis() && !inPitch(event)) {
            anchor = seconds(event.getX());
            return;
        }
        if (!editing || reviewing) return;
        if (rolesView) {
            pressRole(event);
            return;
        }
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
        if (pan == null && spaceHeld && event.isPrimaryButtonDown()) beginPan(MouseButton.PRIMARY, event);
        if (pan != null) {
            double dx = event.getSceneX() - pan.sceneX();
            double dy = event.getSceneY() - pan.sceneY();
            if (spaceHeld) spaceDragged = true;
            if (dy != 0) pitchScroll.setValue(pitchScroll.getValue() - dy / rowHeight());
            if (dx != 0) scrollTo(offset - dx);
            pan = new Pan(pan.button(), event.getSceneX(), event.getSceneY());
            event.setDragDetect(false);
            event.consume();
            return;
        }
        if (anchor != null) {
            selectRange(seconds(event.getX()));
            return;
        }
        if (box != null) {
            box = new Box(box.x(), box.y(), Math.clamp(event.getX(), axis(), canvas.getWidth()),
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
        if (pan != null) {
            if (event.getButton() == pan.button()) {
                pan = null;
                updatePanCursor();
            }
            event.consume();
            return;
        }
        if (event.getButton() == MouseButton.SECONDARY) {
            var section = sectionAt(event.getX(), event.getY());
            if (section != null && editing && !reviewing) onSectionMenu.accept(new SectionPoint(section,
                    placementTime(seconds(event.getX()), event.isAltDown()), event.getScreenX(), event.getScreenY()));
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY) return;
        // ScrollPane can take focus during the press as part of its mouse handling.
        canvas.requestFocus();
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
            flush();
            var area = new Box(box.x(), box.y(), Math.clamp(event.getX(), axis(), canvas.getWidth()),
                    Math.clamp(event.getY(), sectionBottom(), pitchBottom()), box.add(), box.toggle());
            var found = hits.stream().filter(hit -> area.intersects(hit) && hit.top() < pitchBottom())
                    .map(hit -> hit.note().eventId()).distinct().toList();
            cancelGesture();
            selection.modify(found, area.add(), area.toggle());
            publishSelection(null);
            draw();
            return;
        }
        if (rolesView) {
            releaseRole(event);
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
            onSection.accept(section);
        } else if (hit != null) {
            select(hit.note(), hit.note().lane());
            onSeek.accept(hit.note().interval().sourceStart());
        } else if (event.getY() >= laneTop() && event.getY() < laneTop() + lanes.size() * LANE_HEIGHT) {
            select(null, lanes.get((int) (event.getY() - laneTop()) / LANE_HEIGHT));
            onSeek.accept(event.getX() >= axis() ? seconds(event.getX()) : playhead);
        } else if (event.getX() >= axis()) {
            if (inPitch(event)) {
                if (editing && !reviewing && mode == Mode.INSERT && insertAvailable && !moved) {
                    onInsert.accept(insertionAt(seconds(event.getX()), pitch(event.getY()), event.isAltDown()));
                    return;
                }
                select(null, null);
            }
            onSeek.accept(seconds(event.getX()));
        }
        draw();
    }

    private boolean pitchArea(double y) {
        return y >= sectionBottom() && y < pitchBottom();
    }

    private Placement insertionAt(double time, int pitch, boolean free) {
        double limit = model.duration();
        double start = Math.min(placementTime(time, free), Math.max(0, limit - .017));
        double end = Math.max(start + Math.min(.017, limit - start), model.nextBeat(start));
        return new Placement(start, end, pitch);
    }

    List<Overlay> selectableOverlays() {
        return java.util.stream.Stream.concat(
                model.overlays().stream().filter(note -> !pendingByNote.containsKey(note.id())), pending.stream())
                .filter(note -> note.part() >= 0 && selectableDraft(note)).toList();
    }

    void zoomToFit() {
        onTimeZoom.accept(0);
        fitPitch();
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
        var stages = List.<ReconstructionEditing.VibratoSegment>of();
        int vibratoTick = 0;
        if (mode == Mode.VIBRATO) {
            if (model.target() != TargetGame.S3K) {
                error.accept("Vibrato envelopes require an S3K target.");
                return;
            }
            if (!note.sounds() || note.lane() == null || !note.lane().startsWith("FM")) {
                error.accept("Select an allocated FM note to edit vibrato.");
                return;
            }
            stages = vibrato.apply(note);
            var nearby = stages.stream().filter(stage -> Math.abs(x(stageTime(hit.note(), stage.startOffset())) - x) <= 5)
                    .min(java.util.Comparator.comparingDouble(stage -> Math.abs(x(stageTime(hit.note(), stage.startOffset())) - x)))
                    .orElse(null);
            if (event.isAltDown()) {
                if (nearby != null) onEffect.accept(ReconstructionTools.vibrato(note.id(), stages.stream()
                        .filter(stage -> stage != nearby).toList()));
                return;
            }
            var interval = note.interval();
            int length = Math.max(1, (int) (Math.round(interval.projectEnd() * 60) - Math.round(interval.projectStart() * 60)));
            vibratoTick = nearby == null ? Math.clamp((int) Math.round((model.toProject(seconds(x)) - interval.projectStart()) * 60),
                    0, length - 1) : nearby.startOffset();
        } else if (data == null || !data.single() || mode == Mode.BEND && data.drums()) {
            error.accept(mode == Mode.BEND ? "A slide needs a single tonal score attack."
                    : "A cut needs a single score attack.");
            return;
        }
        var base = data == null || mode != Mode.BEND ? List.<BendPoint>of() : data.bend();
        effect = new EffectDrag(note, data, x, y, seconds(x), event.isAltDown(), base, point,
                stages, vibratoTick);
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
            int tick = gesture.vibratoTick();
            var stages = new java.util.TreeMap<Integer, ReconstructionEditing.VibratoSegment>();
            for (var stage : gesture.vibrato()) stages.put(stage.startOffset(), stage);
            var previous = stages.floorEntry(tick);
            var origin = previous == null ? null : previous.getValue();
            double hz = Math.clamp((origin == null || origin.rateHz() == 0 ? 5 : origin.rateHz())
                    + (event.getX() - gesture.x()) * .08, .12, 15);
            double cents = Math.clamp((origin == null ? 0 : origin.depthCents()) + (gesture.y() - event.getY()) * 2, 0, 200);
            var segment = new ReconstructionEditing.VibratoSegment(tick, hz, cents);
            stages.put(tick, segment);
            vibratoPreview = List.copyOf(stages.values());
            effectReadout = vibratoLabel(segment);
        }
    }

    static String vibratoLabel(ReconstructionEditing.VibratoSegment value) {
        return value.depthCents() == 0 ? String.format(Locale.ROOT, "+%d ticks · flat", value.startOffset())
                : String.format(Locale.ROOT, "+%d ticks · %.2f Hz · %.1f cents",
                        value.startOffset(), value.rateHz(), value.depthCents());
    }

    private double stageTime(Overlay note, int offset) {
        return model.toSource(note.interval().projectStart() + offset / 60.0);
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
                pitch = Math.clamp(pitch + pitch(event.getY()) - gesture.pitch(), lowestPitch(), highestPitch());
            }
        }
        return new Placement(start, end, pitch);
    }

    private boolean inPitch(MouseEvent event) {
        return event.getX() >= axis() && pitchArea(event.getY());
    }

    private int pitch(double y) {
        return Math.clamp((int) Math.round(pitchTop - (y - sectionBottom()) / rowHeight()),
                lowestPitch(), highestPitch());
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
        if (x < axis() || y < rulerHeight() || y >= sectionBottom()) return null;
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
        for (var layer : model.layers()) if (layer.layer().lane() != null) used.add(layer.layer().lane());
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
    /** Sets a manual contrast, which turns automatic contrast off. */
    void contrast(int ceiling, int range) {
        autoContrast = false;
        contrast = new ReconstructionTiles.Contrast(ceiling, range);
        draw();
    }

    void autoContrast(boolean auto) {
        autoContrast = auto;
        autoSignature = null;
        draw();
    }

    ReconstructionTiles.Contrast contrast() { return contrast; }
    void onContrastChanged(java.util.function.Consumer<ReconstructionTiles.Contrast> listener) { contrastChanged = listener; }
    /** Draws a frequency-reassigned spectrogram, where steady partials become thin lines. */
    void reassigned(boolean sharp) { reassigned = sharp; draw(); }
    void ridges(boolean show) { ridges = show; draw(); }
    void projectRuler(boolean show) { projectRuler = show; draw(); }

    void roleVisible(Role role, boolean show) {
        if (show) roles.add(role); else roles.remove(role);
        pitchFitDirty = true;
        draw();
    }

    boolean laneVisible(String lane) { return visibleLanes.contains(lane); }

    void laneVisible(String lane, boolean show) {
        if (show) visibleLanes.add(lane); else visibleLanes.remove(lane);
        pitchFitDirty = true;
        draw();
    }

    void zoom(double value) {
        if (value == zoom) return;
        double playheadX = x(playhead);
        double anchor = playheadX >= axis() && playheadX <= canvas.getWidth() ? playhead : seconds(axis() + plotWidth() / 2);
        zoom = value;
        resizePlot();
        center(anchor);
    }

    private void wheelZoom(ScrollEvent event) {
        // Some devices report a Shift-modified wheel as horizontal scroll.
        double delta = event.getDeltaY();
        if (event.isShiftDown() && delta == 0) delta = event.getDeltaX();
        if (delta == 0) return;
        if (event.isShiftDown()) {
            double fraction = Math.clamp((event.getY() - sectionBottom()) / (pitchBottom() - sectionBottom()), 0, 1);
            zoomPitch(Math.pow(1.4, -delta / WHEEL_STEP), fraction);
        } else {
            double value = Math.clamp(zoom + delta / WHEEL_STEP, 0, MAX_TIME_ZOOM);
            if (value != zoom) {
                double anchorX = Math.clamp(event.getX(), axis(), canvas.getWidth());
                double seconds = seconds(anchorX);
                zoom = value;
                resizePlot();
                scroll.layout();
                scrollTo(seconds * pixelsPerSecond - (anchorX - axis()));
                onTimeZoom.accept(zoom);
            }
        }
        event.consume();
    }

    private void resizePlot() {
        if (!ready) return;
        pixelsPerSecond = plotWidth() / Math.max(.001, model.duration()) * Math.pow(2, zoom);
        // Let the ScrollPane fill spare height. Using the old viewport height as the
        // minimum makes a new horizontal scrollbar create vertical overflow.
        double minHeight = sectionBottom() + 145 + stripHeight();
        // Match the skin's layout width so it doesn't renormalize hvalue after zoom.
        double width = snapSizeX(axis() + pixelsPerSecond * model.duration());
        if (minHeight != getMinHeight() || width != contentWidth) dimensions(width, minHeight);
    }

    void center(double sourceSeconds) {
        scrollTo(sourceSeconds * pixelsPerSecond - plotWidth() / 2);
    }

    private void scrollTo(double pixels) {
        double available = contentWidth - canvas.getWidth();
        scroll.setHvalue(available <= 0 ? 0 : Math.clamp(pixels / available, 0, 1));
    }

    void movePlayhead(double seconds, boolean follow) {
        playhead = seconds;
        if (follow && pan == null && (x(seconds) < axis() || x(seconds) > canvas.getWidth())) center(seconds);
        draw();
    }

    void loopRange(ReviewTransport.Range next) {
        if (java.util.Objects.equals(range, next)) return;
        range = next;
        draw();
    }

    void listeningSection(Section section) {
        if (java.util.Objects.equals(section, listeningSection)) return;
        listeningSection = section;
        draw();
    }

    void soloRole(Role role) {
        soloRole = role;
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

    List<Overlay> lassoNotes(javafx.geometry.Rectangle2D box) {
        flush();
        var notes = new java.util.LinkedHashMap<String, Overlay>();
        for (var hit : hits) if (box.intersects(hit.left(), hit.top(), hit.width(), hit.height()))
            notes.putIfAbsent(hit.note().eventId(), hit.note());
        return List.copyOf(notes.values());
    }

    com.opensmpsdeck.headless.NoteHandoff.ViewRange lassoRange(javafx.geometry.Rectangle2D box, List<Overlay> notes) {
        Integer low = null, high = null;
        if (box.getMinY() < pitchBottom() && box.getMaxY() > sectionBottom()) {
            low = Math.clamp((int) Math.floor(pitchTop - (Math.min(pitchBottom(), box.getMaxY()) - sectionBottom()) / rowHeight()), 0, 127);
            high = Math.clamp((int) Math.ceil(pitchTop - (Math.max(sectionBottom(), box.getMinY()) - sectionBottom()) / rowHeight()), 0, 127);
        }
        for (var note : notes) {
            low = low == null ? note.pitch() : Math.min(low, note.pitch());
            high = high == null ? note.pitch() : Math.max(high, note.pitch());
        }
        return new com.opensmpsdeck.headless.NoteHandoff.ViewRange(seconds(box.getMinX()), seconds(box.getMaxX()), low, high,
                Map.ofEntries(Map.entry("audio", source == null ? "none" : source.label()),
                        Map.entry("ruler", projectRuler ? "project" : "source"),
                        Map.entry("pixelsPerSecond", pixelsPerSecond), Map.entry("visibleRoles", List.copyOf(roles)),
                        Map.entry("visibleLanes", visibleLanes.stream().sorted().toList()),
                        Map.entry("showDrums", showDrums), Map.entry("ridges", ridges),
                        Map.entry("dbRange", contrast.range()), Map.entry("ceilingDb", contrast.ceiling()),
                        Map.entry("autoContrast", autoContrast), Map.entry("reassigned", reassigned),
                        Map.entry("pitchTop", pitchTop), Map.entry("pitchSpan", pitchSpan)));
    }

    List<Overlay> selectedNotes() {
        var byId = new java.util.LinkedHashMap<String, Overlay>();
        for (var note : selectionNotes) if (selection.contains(note)) byId.putIfAbsent(note.id(), note);
        return List.copyOf(byId.values());
    }

    void selectRole(Role role, boolean add) {
        var matches = new ArrayList<String>();
        for (var built : model.overlays()) {
            var current = pendingByNote.getOrDefault(built.id(), built);
            if (current.role() == role && current.part() >= 0 && selectableDraft(current))
                matches.add(built.eventId());
        }
        for (var note : pending) {
            if (note.role() == role && note.part() >= 0 && selectableDraft(note)) matches.add(note.eventId());
        }
        selection.modify(matches, add, false);
        if (selection.eventIds().isEmpty()) laneSelection = null;
        canvas.requestFocus();
        publishSelection(null);
        draw();
    }

    private static boolean selectableDraft(Overlay note) {
        return !note.fate().equals("Superseded draft") && !note.fate().equals("Pending deletion");
    }

    void filterSelection(java.util.function.Predicate<Overlay> filter) {
        var kept = selectionNotes.stream().filter(selection::contains).filter(filter)
                .map(Overlay::eventId).toList();
        selection.select(kept, null);
        publishSelection(null);
        draw();
    }

    private void publishSelection(Overlay note) {
        var selected = new LinkedHashSet<Overlay>();
        for (var row : model.overlays()) if (selection.contains(row)) selected.add(pendingByNote.getOrDefault(row.id(), row));
        for (var row : pending) if (selection.contains(row)) selected.add(row);
        selectionNotes = List.copyOf(selected);
        if (note == null) note = selectionNotes.stream().filter(selection::primary).findFirst()
                .orElse(selectionNotes.isEmpty() ? null : selectionNotes.getFirst());
        if (note != null) laneSelection = note.lane();
        onNote.accept(note);
        onLane.accept(laneSelection);
        onSelection.run();
    }

    java.util.Optional<javafx.geometry.Point2D> noteContextPoint(String id) {
        flush();
        double right = canvas.getWidth();
        return hits.stream().filter(hit -> hit.note().id().equals(id) && hit.left() < right
                        && hit.left() + hit.width() > axis() && hit.top() < pitchBottom())
                .findFirst().map(hit -> new javafx.geometry.Point2D(
                        (Math.max(axis(), hit.left()) + Math.min(right, hit.left() + hit.width())) / 2,
                        hit.top() + hit.height() / 2));
    }

    private Hit hit(double x, double y) {
        flush();
        if (x < axis()) return null;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) return hits.get(i);
        return null;
    }

    private int axis() { return AXIS; }
    private double x(double seconds) { return axis() + seconds * pixelsPerSecond - offset; }
    private double seconds(double x) { return Math.clamp((x - axis() + offset) / pixelsPerSecond, 0, model.duration()); }
    private double plotWidth() { return Math.max(100, scroll.getViewportBounds().getWidth() - axis()); }
    private int rulerHeight() { return 31; }
    private int sectionBottom() { return rulerHeight() + SECTION_LABELS.size() * SECTION_HEIGHT; }
    private int stripHeight() {
        if (rolesView) return showDrums && !drumRows.isEmpty() ? LANE_HEADER + drumRows.size() * 9 : 0;
        return LANE_HEADER + lanes.size() * LANE_HEIGHT;
    }
    private double pitchBottom() { return contentHeight - stripHeight(); }
    private double laneTop() { return pitchBottom() + LANE_HEADER; }
    private double rowHeight() { return (pitchBottom() - sectionBottom()) / pitchSpan; }
    private double y(double pitch) { return sectionBottom() + (pitchTop - pitch) * rowHeight(); }
    private int lowestPitch() { return (int) Math.ceil(pitchTop - pitchSpan); }
    private int highestPitch() { return (int) Math.floor(pitchTop); }

    @Override void paint() {
        if (!ready) return;
        var g = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        fitVisiblePitch(width);
        hits.clear();
        proposalHits.clear();
        layerSkips.clear();
        // JavaFX canvas clips are rendered through masks, which makes every clipped
        // operation expensive. Paint the plot first, then paint the gutter, header
        // and lane strip over anything that spilled out of it.
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, width, contentHeight);
        g.setFont(FONT);
        g.setTextBaseline(VPos.CENTER);
        g.setGlobalAlpha(1);
        g.setLineWidth(1);
        g.setLineDashes();
        double plotTop = sectionBottom(), plotBottom = pitchBottom();
        drawSpectrum(g, width);
        drawPitchLines(g, width);
        var ticks = rulerTicks(width);
        drawTickLines(g, ticks, plotTop, plotBottom);
        drawRange(g, plotTop, plotBottom);
        var visible = visibleNotes(width);
        List<ReconstructionInspection.LayerOverlay> layers = List.of();
        if (rolesView) {
            drawVoiceNotes(g, width, visible);
            drawBox(g);
        } else {
            layers = visibleLayers(width);
            drawNotes(g, width, visible, layers);
        }
        if (preview != null) {
            g.setStroke(Color.GOLD);
            double left = x(preview.start());
            g.strokeRect(left, y(preview.pitch()) - 3, Math.max(2, x(preview.end()) - left), 6);
        }
        drawPitchLabels(g);
        // The lane strip below the plot.
        if (rolesView) {
            if (showDrums && !drumRows.isEmpty()) {
                g.setFill(PANEL);
                g.fillRect(0, plotBottom, width, stripHeight());
                g.setFill(SUBTLE);
                g.fillText("Drums", 12, plotBottom + LANE_HEADER / 2.0);
            }
        } else drawLanes(g, width);
        drawTickLines(g, ticks, plotBottom, contentHeight);
        drawRange(g, plotBottom, contentHeight);
        if (rolesView) {
            if (showDrums) drawDrums(g, visible);
        } else drawLaneNotes(g, width, visible, layers);
        // The header above the plot.
        drawBands(g, width);
        drawRuler(g, width, ticks);
        drawRange(g, 0, plotTop);
        if (boundaryPreview != null) plotLine(g, Color.GOLD, x(boundaryPreview), rulerHeight(), sectionBottom());
        plotLine(g, CURSOR, x(playhead), 0, contentHeight);
        g.setStroke(GRID);
        g.strokeLine(axis() - .5, 0, axis() - .5, contentHeight);
    }

    /** A one-pixel vertical line, cut at the gutter edge as the old plot clip did. */
    private void plotLine(GraphicsContext g, Color color, double x, double top, double bottom) {
        double left = Math.max(axis(), x - .5), right = Math.min(canvas.getWidth(), x + .5);
        if (right <= left) return;
        g.setFill(color);
        g.fillRect(left, top, right - left, bottom - top);
    }

    private void drawRange(GraphicsContext g, double top, double bottom) {
        if (range == null || bottom <= top) return;
        double left = Math.max(axis(), x(range.start())), right = Math.min(canvas.getWidth(), x(range.end()));
        if (right <= left) return;
        g.setFill(RANGE);
        g.fillRect(left, top, right - left, bottom - top);
    }

    private void drawSpectrum(GraphicsContext g, double width) {
        spectrumReady = false;
        if (source == null) {
            g.setFill(SUBTLE);
            g.fillText("No source audio available", axis() + 16, sectionBottom() + 22);
            return;
        }
        // Measure against device pixels, so HiDPI displays get a sharper spectrogram.
        var window = getScene() == null ? null : getScene().getWindow();
        double scaleX = window == null ? 1 : window.getRenderScaleX(), scaleY = window == null ? 1 : window.getRenderScaleY();
        int level = ReconstructionTiles.level(pixelsPerSecond * scaleX);
        int rows = ReconstructionTiles.rows(rowHeight() * scaleY);
        double tileSpan = ReconstructionTiles.span(level);
        double from = seconds(axis()), end = seconds(width);
        int firstBlock = ReconstructionTiles.block(lowestPitch() - 1);
        int lastBlock = ReconstructionTiles.block(highestPitch() + 1);
        int firstIndex = (int) (from / tileSpan), lastIndex = (int) Math.ceil(end / tileSpan) - 1;
        var keys = new ArrayList<ReconstructionTiles.Key>();
        for (int index = firstIndex; index <= Math.max(firstIndex, lastIndex); index++)
            for (int block = firstBlock; block <= lastBlock; block++)
                keys.add(ReconstructionTiles.key(source, level, index, block, rows, reassigned));
        if (autoContrast) updateAutoContrast(keys);
        var needed = new LinkedHashSet<ReconstructionTiles.ImageKey>();
        int drawn = 0, visible = 0;
        // Tiles hold about one row per device pixel, so nearest-neighbour scaling stays crisp.
        g.setImageSmoothing(false);
        for (var key : keys) {
            var imageKey = new ReconstructionTiles.ImageKey(key, contrast);
            needed.add(imageKey);
            visible++;
            var tile = tiles.get(imageKey);
            if (tile == null) {
                drawFallbackTile(g, key);
                continue;
            }
            drawTile(g, key, tile.image(), key.start(), key.end());
            drawn++;
            if (ridges) {
                g.setFill(RIDGE);
                for (var ridge : tile.ridges()) if (ridge.dbfs() >= contrast.floor())
                    g.fillRect(x(ridge.sourceSeconds()) - 1, y(ridge.pitch()) - 1, 2, 2);
            }
        }
        g.setImageSmoothing(true);
        // Prefetch the neighbouring columns, so scrolling reveals finished tiles.
        double limit = model.duration();
        for (int index : new int[]{lastIndex + 1, firstIndex - 1}) {
            if (index < 0 || index * tileSpan >= limit) continue;
            for (int block = firstBlock; block <= lastBlock; block++)
                needed.add(new ReconstructionTiles.ImageKey(
                        ReconstructionTiles.key(source, level, index, block, rows, reassigned), contrast));
        }
        spectrumReady = drawn > 0 && drawn == visible;
        tiles.request(needed);
    }

    /**
     * Fits the contrast to the measured visible tiles. It recomputes only when the measured set
     * changes, and ignores shifts under 3 dB, so scrolling does not make the plot flicker.
     */
    private void updateAutoContrast(List<ReconstructionTiles.Key> keys) {
        var levels = new ArrayList<float[]>();
        var measuredKeys = new ArrayList<ReconstructionTiles.Key>();
        for (var key : keys) {
            var data = tiles.levels(key);
            if (data == null) continue;
            levels.add(data);
            measuredKeys.add(key);
        }
        if (measuredKeys.equals(autoSignature)) return;
        autoSignature = measuredKeys;
        var next = ReconstructionTiles.autoContrast(levels);
        if (next == null || Math.abs(next.ceiling() - contrast.ceiling()) < 3
                && Math.abs(next.floor() - contrast.floor()) < 3) return;
        contrast = next;
        contrastChanged.accept(next);
    }

    /** Stands in a cached tile of another resolution or row density while the right one is measured. */
    private void drawFallbackTile(GraphicsContext g, ReconstructionTiles.Key key) {
        var densities = new LinkedHashSet<Integer>();
        densities.add(key.rows());
        for (int rows = ReconstructionTiles.MAX_ROWS; rows >= ReconstructionTiles.MIN_ROWS; rows /= 2) densities.add(rows);
        for (int rows : densities) {
            var dense = ReconstructionTiles.key(key.source(), key.level(), key.index(), key.block(), rows, key.reassigned());
            if (rows != key.rows()) {
                var same = tiles.get(new ReconstructionTiles.ImageKey(dense, contrast));
                if (same != null) {
                    drawTile(g, dense, same.image(), key.start(), key.end());
                    return;
                }
            }
            if (drawFallbackLevels(g, dense, key)) return;
        }
    }

    private boolean drawFallbackLevels(GraphicsContext g, ReconstructionTiles.Key dense, ReconstructionTiles.Key key) {
        for (int level = key.level() + 1; level <= ReconstructionTiles.MAX_LEVEL; level++) {
            var parent = ReconstructionTiles.key(key.source(), level, key.index() >> (level - key.level()), key.block(),
                    dense.rows(), key.reassigned());
            var cached = tiles.get(new ReconstructionTiles.ImageKey(parent, contrast));
            if (cached == null) continue;
            drawTile(g, parent, cached.image(), key.start(), key.end());
            return true;
        }
        if (key.level() <= ReconstructionTiles.MIN_LEVEL) return false;
        boolean any = false;
        for (int child = 0; child < 2; child++) {
            var finer = ReconstructionTiles.key(key.source(), key.level() - 1, key.index() * 2 + child, key.block(),
                    dense.rows(), key.reassigned());
            var cached = tiles.get(new ReconstructionTiles.ImageKey(finer, contrast));
            if (cached != null) {
                drawTile(g, finer, cached.image(), finer.start(), finer.end());
                any = true;
            }
        }
        return any;
    }

    /** Draws the part of a tile between two source times, without a canvas clip. */
    private void drawTile(GraphicsContext g, ReconstructionTiles.Key key, javafx.scene.image.Image image,
                          double from, double to) {
        double start = Math.max(from, key.start()), end = Math.min(to, key.end());
        if (end <= start) return;
        double columns = image.getWidth();
        double sx = (start - key.start()) / key.span() * columns;
        double sw = (end - start) / key.span() * columns;
        double top = y(key.high() + .5);
        g.drawImage(image, sx, 0, sw, image.getHeight(), x(start), top,
                (end - start) * pixelsPerSecond, (key.high() - key.low() + 1) * rowHeight());
    }

    private void drawPitchLines(GraphicsContext g, double width) {
        for (int pitch = lowestPitch(); pitch <= highestPitch(); pitch++) {
            g.setStroke(pitch % 12 == 0 ? GRID : FAINT_GRID);
            g.strokeLine(axis(), y(pitch - .5), width, y(pitch - .5));
        }
    }

    private void drawPitchLabels(GraphicsContext g) {
        double top = sectionBottom(), bottom = pitchBottom();
        g.setFill(PANEL);
        g.fillRect(0, top, axis(), bottom - top);
        int minimumSpacing = Math.max(1, (int) Math.ceil(12 / rowHeight()));
        for (int pitch = lowestPitch(); pitch <= highestPitch(); pitch++) {
            if (minimumSpacing > 1 && pitch % 12 % minimumSpacing != 0) continue;
            double y = y(pitch);
            // A label at the edge of the plot would spill into the header or lane strip.
            if (y < top + 5 || y > bottom - 5) continue;
            g.setFill(pitch % 12 == 0 ? TEXT : SUBTLE);
            g.fillText(pitchName(pitch), 12, y);
        }
    }

    private void drawBands(GraphicsContext g, double width) {
        g.setFill(PANEL);
        g.fillRect(0, rulerHeight(), width, sectionBottom() - rulerHeight());
        for (var band : sections) {
            double left = x(band.sourceStart()), right = x(band.sourceEnd());
            if (right < axis() || left > width) continue;
            double top = rulerHeight() + band.level().ordinal() * SECTION_HEIGHT;
            g.setFill(switch (band.level()) {
                case section -> SECTION_FILL;
                case period -> PERIOD_FILL;
                case phrase -> PHRASE_FILL;
            });
            g.fillRect(left, top, Math.max(1, right - left - 1), SECTION_HEIGHT - 1);
            if (band.equals(listeningSection)) {
                g.setStroke(LISTENING);
                g.strokeRect(left + .5, top + .5, Math.max(1, right - left - 2), SECTION_HEIGHT - 2);
            }
            g.setFill(TEXT);
            double labelStart = Math.max(axis(), left) + 5;
            drawLabel(g, band.label(), labelStart, top + SECTION_HEIGHT / 2.0, right - labelStart - 3);
        }
        g.setFill(PANEL);
        g.fillRect(0, rulerHeight(), axis(), sectionBottom() - rulerHeight());
        g.setFill(SUBTLE);
        for (int i = 0; i < SECTION_LABELS.size(); i++)
            g.fillText(SECTION_LABELS.get(i), 8, rulerHeight() + (i + .5) * SECTION_HEIGHT);
    }

    private void drawLanes(GraphicsContext g, double width) {
        g.setFill(PANEL);
        g.fillRect(0, pitchBottom(), width, stripHeight());
        g.setFill(TEXT);
        g.fillText("Chip lanes", 4, pitchBottom() + LANE_HEADER / 2.0);
        if (lanes.isEmpty()) g.fillText("No placed notes", axis() + 16, pitchBottom() + LANE_HEADER / 2.0);
        for (int i = 0; i < lanes.size(); i++) {
            String lane = lanes.get(i);
            double top = laneTop() + i * LANE_HEIGHT;
            g.setFill(lane.equals(laneSelection) ? SELECTED_LANE : i % 2 == 0 ? BACKGROUND : PANEL);
            g.fillRect(0, top, width, LANE_HEIGHT);
            g.setFill(visibleLanes.contains(lane) ? TEXT : SUBTLE);
            g.fillText(lane, 12, top + LANE_HEIGHT / 2.0);
        }
    }

    /** Ruler ticks as source-time x positions and labels. */
    private record Ticks(double[] x, String[] labels) {}

    private Ticks rulerTicks(double width) {
        double metric = 82 / pixelsPerSecond;
        double magnitude = Math.pow(10, Math.floor(Math.log10(metric)));
        double step = (metric / magnitude < 2 ? 2 : metric / magnitude < 5 ? 5 : 10) * magnitude;
        double start = seconds(axis()), end = seconds(width);
        if (projectRuler) {
            start = model.toProject(start);
            end = model.toProject(end);
        }
        String format = step >= 1 ? "%.0f" : step >= .1 ? "%.1f" : "%.2f";
        double first = Math.ceil(start / step) * step;
        int count = Math.max(0, (int) Math.floor((end - first) / step + 1e-9) + 1);
        var xs = new double[count];
        var labels = new String[count];
        for (int i = 0; i < count; i++) {
            double tick = first + i * step;
            xs[i] = x(projectRuler ? model.toSource(tick) : tick);
            labels[i] = String.format(Locale.ROOT, format, tick);
        }
        return new Ticks(xs, labels);
    }

    private void drawTickLines(GraphicsContext g, Ticks ticks, double top, double bottom) {
        if (bottom <= top) return;
        g.setStroke(GRID);
        for (double left : ticks.x()) if (left >= axis()) g.strokeLine(left + .5, top, left + .5, bottom);
    }

    private void drawRuler(GraphicsContext g, double width, Ticks ticks) {
        g.setFill(PANEL);
        g.fillRect(0, 0, width, rulerHeight());
        drawTickLines(g, ticks, rulerHeight() - 8, sectionBottom());
        g.setFill(SUBTLE);
        for (int i = 0; i < ticks.x().length; i++)
            if (ticks.x()[i] >= axis()) g.fillText(ticks.labels()[i], ticks.x()[i] + 5, rulerHeight() / 2.0);
        g.setFill(PANEL);
        g.fillRect(0, 0, axis(), rulerHeight());
        g.setFill(TEXT);
        g.fillText(projectRuler ? "Project s" : "Source s", 8, rulerHeight() / 2.0);
    }

    private static boolean isDrum(Overlay note, ReconstructionVoices.Entry voice) {
        return note.role() == Role.DRUMS || voice != null && voice.drum();
    }

    private void drawDrums(GraphicsContext g, List<Overlay> visible) {
        for (var note : visible) {
            var voice = voices.byNote().get(note.id());
            if (voice == null || !isDrum(note, voice) || !roles.contains(note.role()) || !selectableDraft(note)) continue;
            Integer row = drumRows.get(voice.id());
            if (row == null) continue;
            double x = x(note.interval().sourceStart()), y = laneTop() + row * 9 + 4.5;
            if (x < axis() || x > canvas.getWidth()) continue;
            boolean selected = selection.contains(note);
            g.setGlobalAlpha(voiceDimmed(note) ? .24 : selected ? 1 : .75);
            var mark = voice.drum() ? voice.drumMark() : ReconstructionVoices.DrumMark.of(note.partName(), note.pitch());
            mark.draw(g, voice.style().color(), x, y);
            if (selected) {
                g.setStroke(Color.WHITE);
                g.strokeRect(x - 4, y - 4, 8, 8);
            }
            hits.add(new Hit(note, x - 4, y - 4, 8, 8));
        }
        g.setGlobalAlpha(1);
    }

    private void drawVoiceNotes(GraphicsContext g, double width, List<Overlay> visible) {
        var tags = new ArrayList<VoiceTag>();
        for (var note : visible) {
            var voice = voices.byNote().get(note.id());
            if (voice == null || isDrum(note, voice) || !roles.contains(note.role()) || !inPitchRange(note)) continue;
            var bounds = noteBounds(note);
            double left = bounds.left(), top = bounds.top(), length = bounds.width(), height = bounds.height();
            boolean selected = selection.contains(note);
            boolean dimmed = voiceDimmed(note);
            boolean deleted = note.fate().equals("Pending deletion");
            boolean draft = pendingByNote.get(note.id()) == note;
            g.setGlobalAlpha(dimmed ? .24 : 1);
            voice.style().draw(g, left, top, length, height, deleted ? 0 : note.sounds() ? .78 : .42);
            if (deleted) {
                g.setStroke(voice.style().color());
                g.strokeLine(left, top + height / 2, left + length, top + height / 2);
            }
            if (selected || draft) {
                g.setStroke(selected ? Color.WHITE : Color.GOLD);
                g.strokeRect(left - .5, top - .5, length + 1, height + 1);
            }
            if (detailedNotes.contains(note.id())) {
                g.setFill(Color.GOLD);
                g.fillOval(left + 1, top - 4, 4, 4);
            }
            if (!dimmed && pixelsPerSecond >= 24) {
                // Leave the attack and its pattern visible beside the tag.
                double labelLeft = Math.max(left + 7, axis() + 2);
                String label = voice.tag();
                double labelWidth = label.codePointCount(0, label.length()) * 6.7;
                double baseline = height >= 12 ? top + height / 2 : top - 7;
                if (labelLeft + labelWidth + 2 <= Math.min(left + length, width) && baseline >= sectionBottom() + 7) {
                    var tag = new LabelBounds(labelLeft, baseline - 6, labelWidth + 4, 12);
                    if (!overlapsAny(tags, tag)) tags.add(new VoiceTag(tag, label));
                }
            }
            hits.add(bounds);
        }
        g.setGlobalAlpha(1);
        for (var tag : tags) {
            var bounds = tag.bounds();
            g.setFill(TAG_BACKGROUND);
            g.fillRoundRect(bounds.x - 2, bounds.y, bounds.width, bounds.height, 3, 3);
            g.setFill(TEXT);
            g.fillText(tag.text(), bounds.x, bounds.y + bounds.height / 2);
        }
    }

    private record VoiceTag(LabelBounds bounds, String text) {}

    private boolean inPitchRange(Overlay note) {
        return note.part() >= 0 && note.pitch() >= lowestPitch() && note.pitch() <= highestPitch();
    }

    private Hit noteBounds(Overlay note) {
        double left = x(note.interval().sourceStart());
        double height = Math.max(3, Math.min(13, rowHeight() - 1));
        return new Hit(note, left, y(note.pitch()) - height / 2,
                Math.max(rolesView ? 4 : 2, x(note.interval().sourceEnd()) - left), height);
    }

    private void drawBox(GraphicsContext g) {
        if (box == null || !dragged) return;
        g.setFill(BOX_FILL);
        g.fillRect(box.left(), box.top(), box.width(), box.height());
        g.setStroke(BOX_LINE);
        g.strokeRect(box.left(), box.top(), box.width(), box.height());
    }

    private List<ReconstructionInspection.LayerOverlay> visibleLayers(double width) {
        double from = seconds(axis()), to = seconds(width);
        var visible = new ArrayList<ReconstructionInspection.LayerOverlay>();
        for (var layer : model.layers()) {
            if (layer.interval().sourceEnd() >= from && layer.interval().sourceStart() <= to
                    && roles.contains(layer.role())
                    && (layer.layer().lane() == null || visibleLanes.contains(layer.layer().lane()))) visible.add(layer);
        }
        return visible;
    }

    private void drawNotes(GraphicsContext g, double width, List<Overlay> visible,
                           List<ReconstructionInspection.LayerOverlay> layers) {
        var labels = new ArrayList<LabelBounds>();
        for (var note : visible) {
            if (!inPitchRange(note)) continue;
            var bounds = noteBounds(note);
            double left = bounds.left(), barWidth = bounds.width(), height = bounds.height(), top = bounds.top();
            g.setGlobalAlpha(voiceDimmed(note) ? .24 : 1);
            var color = noteColor(note);
            boolean draft = pendingByNote.get(note.id()) == note;
            boolean deleted = draft && note.fate().equals("Pending deletion");
            boolean dashed = note.style() == ReconstructionInspection.Style.DASHED;
            if (!outline && !deleted) {
                g.setFill(alpha(color, dashed ? opacity * .4 : opacity));
                g.fillRect(left, top, barWidth, height);
            }
            var edge = draft ? Color.GOLD : selection.contains(note) ? Color.WHITE : color;
            double lineWidth = draft || selection.contains(note) ? 1.6 : 1;
            g.setStroke(edge);
            g.setLineWidth(lineWidth);
            if (dashed && !draft) dashedRect(g, edge, left + .5, top + .5, barWidth - 1, height - 1, lineWidth, 3, 2);
            else g.strokeRect(left + .5, top + .5, barWidth - 1, height - 1);
            if (deleted) g.strokeLine(left, top + height / 2, left + barWidth, top + height / 2);
            g.setLineWidth(1);
            drawVibrato(g, note, top, height, left + barWidth);
            if (!draft || !note.bend().isEmpty()) drawBend(g, note, left, barWidth, draft ? Color.GOLD : color);
            if (!reviewing && mode == Mode.BEND && selection.contains(note) && effect == null) {
                var data = attack.apply(note);
                if (data != null) drawCurve(g, data.bend(), Color.GOLD, true);
            }
            if (pixelsPerSecond >= 24) {
                double labelLeft = Math.max(left, axis()) + 3;
                double space = Math.min(left + barWidth, width) - labelLeft;
                String text = displayNoteLabel(note, space);
                double baseline = top - 7;
                if (!text.isEmpty() && baseline >= sectionBottom() + 7) {
                    var label = new LabelBounds(labelLeft, baseline - 7, text.length() * 6.7, 14);
                    if (!overlaps(labels, label)) {
                        labels.add(label);
                        g.setFill(LABEL_BACKGROUND);
                        g.fillRoundRect(label.x - 2, label.y, label.width + 4, label.height, 3, 3);
                        g.setFill(TEXT);
                        g.fillText(text, labelLeft, baseline);
                    }
                }
            }
            hits.add(bounds);
        }
        g.setGlobalAlpha(1);
        for (var ghost : layers) {
            double left = x(ghost.interval().sourceStart());
            double barWidth = Math.max(3, x(ghost.interval().sourceEnd()) - left);
            double top = y(ghost.pitch()) + Math.min(7, rowHeight() / 2 + 2);
            if (ghost.layer().status() == ReconstructionLayers.Status.skipped) {
                layerSkips.add(new SkipHit(left, top, ghost.layer().mode() + ": " + ghost.layer().reason()));
                g.setStroke(Color.SALMON);
                g.strokeLine(left, top, left + 5, top + 5);
                g.strokeLine(left, top + 5, left + 5, top);
            } else {
                g.setFill(layerColor(ghost.layer().lane(), .36));
                g.fillRect(left, top, barWidth, 4);
                g.setStroke(layerColor(ghost.layer().lane(), .78));
                g.strokeRect(left, top, barWidth, 4);
            }
        }
        if (reviewing) drawProposalMarks(g, width);
        drawBox(g);
        if (effect != null) {
            drawCurve(g, bendPreview, Color.GOLD, true);
            if (mode == Mode.CUT) {
                var data = effect.attack();
                g.setStroke(Color.GOLD);
                g.strokeLine(effect.x(), y(data.pitch() + .5), effect.x(), y(data.pitch() - .5));
            }
            if (effectReadout != null) {
                double left = Math.clamp(effect.x() + 10, axis(), Math.max(axis(), width - 360));
                double baseline = Math.max(sectionBottom() + 19, effect.y() - 16);
                g.setFill(BACKGROUND);
                g.fillRoundRect(left, baseline - 15, effectReadout.length() * 7 + 10, 21, 5, 5);
                g.setFill(Color.GOLD);
                g.fillText(effectReadout, left + 5, baseline);
            }
        }
    }

    private void drawLaneNotes(GraphicsContext g, double width, List<Overlay> visible,
                               List<ReconstructionInspection.LayerOverlay> layers) {
        for (var note : visible) {
            if (!note.sounds()) continue;
            int lane = lanes.indexOf(note.lane());
            if (lane < 0) continue;
            double left = Math.max(axis(), x(note.interval().sourceStart()));
            double barWidth = Math.max(2, x(note.interval().sourceEnd()) - left);
            double top = laneTop() + lane * LANE_HEIGHT + 2;
            g.setGlobalAlpha(voiceDimmed(note) ? .24 : 1);
            var color = noteColor(note);
            g.setFill(alpha(color, .45));
            g.fillRect(left, top, barWidth, LANE_HEIGHT - 4);
            if (selection.contains(note) || pendingByNote.get(note.id()) == note) {
                g.setStroke(pendingByNote.get(note.id()) == note ? Color.GOLD : Color.WHITE);
                g.strokeRect(left, top, barWidth, LANE_HEIGHT - 4);
            }
            if (barWidth > 30) {
                g.setFill(TEXT);
                String label = displayNoteLabel(note, Math.min(width - left, barWidth) - 6);
                g.fillText(label, left + 3, top + (LANE_HEIGHT - 4) / 2.0);
            }
            hits.add(new Hit(note, left, top, barWidth, LANE_HEIGHT - 4));
        }
        g.setGlobalAlpha(1);
        for (var ghost : layers) {
            String lane = ghost.layer().lane();
            if (lane == null) continue;
            int row = lanes.indexOf(lane);
            if (row < 0) continue;
            double left = Math.max(axis(), x(ghost.interval().sourceStart()));
            double right = Math.min(width, x(ghost.interval().sourceEnd()));
            g.setFill(layerColor(lane, .52));
            g.fillRect(left, laneTop() + row * LANE_HEIGHT + 4, Math.max(2, right - left), LANE_HEIGHT - 8);
        }
    }

    private static final Map<String, Color> LANE_COLORS = new HashMap<>();
    private static Color layerColor(String lane, double alpha) {
        var base = LANE_COLORS.computeIfAbsent(lane == null ? "" : lane, value -> {
            int rgb = ReconstructionInspection.laneRgb(lane);
            return Color.rgb(rgb >> 16, rgb >> 8 & 255, rgb & 255);
        });
        return alpha(base, alpha);
    }

    private void drawVibrato(GraphicsContext g, Overlay note, double top, double height, double right) {
        var stages = effect != null && effect.note() == note && vibratoPreview != null ? vibratoPreview : vibrato.apply(note);
        if (stages.isEmpty()) return;
        g.setStroke(Color.GOLD);
        for (int i = 0; i < stages.size(); i++) {
            var stage = stages.get(i);
            double left = x(stageTime(note, stage.startOffset()));
            if (left >= right) break;
            double end = i + 1 == stages.size() ? right : Math.min(right, x(stageTime(note, stages.get(i + 1).startOffset())));
            g.setLineWidth(1);
            g.strokeLine(left, top - 2, left, top + height);
            if (stage.depthCents() > 0) {
                g.setLineWidth(Math.clamp(stage.depthCents() / 26, 1.25, 4));
                g.strokeLine(left, top + height - 1, end, top + height - 1);
            }
        }
        g.setLineWidth(1);
    }

    private void drawBend(GraphicsContext g, Overlay note, double left, double width, Color color) {
        var bend = pendingByNote.containsKey(note.id()) ? note.bend()
                : assembledBends.getOrDefault(note.eventId(), note.bend());
        if (bend.isEmpty()) return;
        g.setStroke(brighter(color));
        g.setLineWidth(1.6);
        strokeCurveBetween(g, bend, left, left + width);
        g.setLineWidth(1);
    }

    /**
     * Strokes the part of a curve between two x positions. It uses one line per
     * segment: a canvas path would be rasterized into a mask on the CPU.
     */
    private void strokeCurveBetween(GraphicsContext g, List<BendPoint> bend, double from, double to) {
        from = Math.max(from, 0);
        to = Math.min(to, canvas.getWidth());
        double px = x(bend.getFirst().sourceSeconds()), py = y(bend.getFirst().pitch());
        for (int i = 1; i < bend.size(); i++) {
            double nx = x(bend.get(i).sourceSeconds()), ny = y(bend.get(i).pitch());
            double dx = nx - px, t0 = 0, t1 = 1;
            if (dx == 0) {
                if (px < from || px > to) t0 = 2;
            } else {
                double ta = (from - px) / dx, tb = (to - px) / dx;
                t0 = Math.max(0, Math.min(ta, tb));
                t1 = Math.min(1, Math.max(ta, tb));
            }
            if (t0 <= t1) g.strokeLine(px + dx * t0, py + (ny - py) * t0, px + dx * t1, py + (ny - py) * t1);
            px = nx;
            py = ny;
        }
    }

    private void drawCurve(GraphicsContext g, List<BendPoint> bend, Color color, boolean handles) {
        if (bend.isEmpty()) return;
        g.setStroke(color);
        g.setLineWidth(1.6);
        strokeCurveBetween(g, bend, 0, canvas.getWidth());
        g.setLineWidth(1);
        if (handles) {
            g.setFill(color);
            for (var point : bend) g.fillOval(x(point.sourceSeconds()) - 3, y(point.pitch()) - 3, 6, 6);
        }
    }

    /**
     * A dashed rectangle outline drawn as filled dashes. Canvas line dashes are
     * rasterized into a mask on the CPU, while rectangles are drawn directly.
     */
    private void dashedRect(GraphicsContext g, Color color, double x, double y, double width, double height,
                            double lineWidth, double dash, double gap) {
        g.setFill(color);
        double half = lineWidth / 2, right = x + width, bottom = y + height;
        double from = Math.max(x, -dash), to = Math.min(right, canvas.getWidth() + dash);
        double first = x + Math.max(0, Math.floor((from - x) / (dash + gap))) * (dash + gap);
        for (double at = first; at < to; at += dash + gap) {
            double length = Math.min(dash, right - at);
            g.fillRect(at, y - half, length, lineWidth);
            g.fillRect(at, bottom - half, length, lineWidth);
        }
        for (double at = y; at < bottom; at += dash + gap) {
            double length = Math.min(dash, bottom - at);
            if (x >= -half && x <= canvas.getWidth() + half) g.fillRect(x - half, at, lineWidth, length);
            if (right >= -half && right <= canvas.getWidth() + half) g.fillRect(right - half, at, lineWidth, length);
        }
    }

    private List<Overlay> visibleNotes(double width) {
        double from = seconds(axis()), to = seconds(width);
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
                    && (rolesView || visibleLanes.contains(note.lane() == null ? UNASSIGNED : note.lane()))
                    && voiceShown(note))
                visible.add(note);
        }
        for (var note : pending) {
            if (!note.fate().equals("Superseded draft")
                    && roles.contains(note.role()) && note.interval().sourceEnd() >= from && note.interval().sourceStart() <= to
                    && voiceShown(note)) visible.add(note);
        }
        return visible;
    }

    private void drawProposalMarks(GraphicsContext g, double width) {
        var proposalColor = PROPOSAL;
        for (var proposal : proposals) {
            var mark = proposal.note();
            if (mark.pitch() < lowestPitch() || mark.pitch() > highestPitch()
                    || mark.end() < seconds(axis()) || mark.start() > seconds(width))
                continue;
            double left = x(mark.start());
            double barWidth = Math.max(5, x(mark.end()) - left);
            double height = Math.max(6, Math.min(17, rowHeight() + 2));
            double top = y(mark.pitch()) - height / 2;
            dashedRect(g, proposalColor, left, top, barWidth, height, 1.6, 4, 2);
            if (!mark.bend().isEmpty()) drawCurve(g, mark.bend(), proposalColor, false);
            proposalHits.add(new ProposalHit(proposal, left, top, barWidth, height));
        }
    }

    private boolean voiceShown(Overlay note) {
        var voice = voices.byNote().get(note.id());
        return voice == null || voiceVisible(voice.id());
    }

    private boolean voiceDimmed(Overlay note) {
        var voice = voices.byNote().get(note.id());
        return !selection.contains(note) && (soloRole != null && soloRole != note.role()
                || soloVoice != null && (voice == null || !soloVoice.equals(voice.id())));
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
    private static final Map<Role, Color> ROLE_COLORS = new java.util.EnumMap<>(Role.class);
    static Color color(Role role) {
        return ROLE_COLORS.computeIfAbsent(role, value -> Color.rgb(value.rgb() >> 16, value.rgb() >> 8 & 255, value.rgb() & 255));
    }

    static String shortLabel(Overlay note) {
        return pitchName(note.pitch()) + " · " + (note.lane() == null ? UNASSIGNED : note.lane())
                + (note.patch() == null ? "" : " · " + note.patch());
    }

    private Color noteColor(Overlay note) {
        var voice = voices.byNote().get(note.id());
        return voice == null ? color(note.role()) : voice.style().color();
    }

    private String displayNoteLabel(Overlay note, double space) {
        String name = noteLabel(note);
        return name.length() * 6.7 <= space ? name : fitLabel(pitchName(note.pitch()), space);
    }

    String noteLabel(Overlay note) {
        var voice = voices.byNote().get(note.id());
        String name = voice == null ? note.partName() : voice.shortName();
        return pitchName(note.pitch()) + (name.isBlank() ? "" : " · " + name)
                + (note.lane() == null ? "" : " · " + note.lane());
    }

    private String hoverDescription(Overlay note) {
        return noteLabel(note) + (note.patch() == null ? "" : " · " + note.patch())
                + "\n" + note.fate() + (note.reason() == null ? "" : " · " + note.reason())
                + (note.competitors().isEmpty() ? "" : "\nCompeting note: " + String.join(", ", note.competitors()));
    }

    static String description(Overlay note) {
        return shortLabel(note) + " · " + roleName(note.role()) + "\n" + note.fate()
                + (note.reason() == null ? "" : " · " + note.reason())
                + (note.competitors().isEmpty() ? "" : "\nCompeting note: " + String.join(", ", note.competitors()));
    }

    private static boolean overlaps(List<LabelBounds> placed, LabelBounds label) {
        for (var other : placed) if (label.intersects(other)) return true;
        return false;
    }

    private static boolean overlapsAny(List<VoiceTag> placed, LabelBounds label) {
        for (var other : placed) if (label.intersects(other.bounds())) return true;
        return false;
    }

    private static final Map<Color, Map<Double, Color>> ALPHAS = new HashMap<>();
    private static final Map<Color, Color> BRIGHTER = new HashMap<>();

    /** Cached translucent copies, since each paint would otherwise allocate one per note. */
    private static Color alpha(Color color, double opacity) {
        return ALPHAS.computeIfAbsent(color, ignored -> new HashMap<>())
                .computeIfAbsent(opacity, value -> color.deriveColor(0, 1, 1, value));
    }

    private static Color brighter(Color color) {
        return BRIGHTER.computeIfAbsent(color, Color::brighter);
    }

    @Override public void close() {
        Tooltip.uninstall(canvas, hover);
        tiles.close();
    }
}

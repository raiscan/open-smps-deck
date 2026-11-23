package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.ReconstructionTimeMap.TimeBase;

/** Read-only evidence shared by the Studio viewport and the reconstruction CLI. */
public final class ReconstructionInspection {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public enum Role {
        MELODY(0x52dec4), COUNTER(0x83abff), HARMONY(0xc8a2ff), BASS(0xf6be68),
        DRUMS(0xff897c), UNASSIGNED(0xb5bfce);
        private final int rgb;
        Role(int rgb) { this.rgb = rgb; }
        public int rgb() { return rgb; }
    }

    public enum Style { SOLID, DASHED }
    public record Interval(double sourceStart, double sourceEnd, double projectStart, double projectEnd) {}
    public record BendPoint(double sourceSeconds, double pitch) {}
    public record Overlay(String id, String eventId, int part, String partName, Role role, String lane, int pitch,
                          String patch, Interval interval, List<BendPoint> bend, boolean sounds,
                          Style style, String fate, String reason, List<String> competitors) {}

    public record LayerOverlay(ReconstructionLayers.Result layer, int part, Role role, int pitch, Interval interval) {}

    public enum AudioKind { MIX, STEM, CHIP }

    public record AudioSource(AudioKind kind, String label, Path path, boolean projectClock) {
        @Override public String toString() { return label; }
    }

    public record Ridge(double sourceSeconds, double pitch, double dbfs) {}
    public record SpectrumTile(int columns, int rows, float[] dbfs, List<Ridge> ridges) {}

    public record Part(int id, String name, boolean drums) {
        @Override public String toString() { return id + (name.isBlank() ? "" : " · " + name); }
    }

    public record NoteDetails(boolean scored, boolean generated, Integer velocity, List<String> edits) {}

    public enum Grid { BEAT, BAR }

    private final ReconstructionTimeMap clock;
    private final ReconstructionNoteContext noteContext;
    private final Map<String, ReconstructionTrace.Row> trace;
    private final StudioProject project;
    private final TargetGame target;
    private final List<AudioSource> sources;
    private final List<Overlay> overlays;
    private final List<LayerOverlay> layers;
    private final ReconstructionSections.Result sections;
    private final List<Part> parts;
    private final Map<String, String> stockReasons;
    private final List<ReconstructionStructure.Bar> bars;
    private final ReconstructionBeatMap beats;

    private ReconstructionInspection(Path work, ReconstructionStages.Score score, StudioProject project, String noteRevision) throws IOException {
        this.clock = ReconstructionTimeMap.of(score);
        this.trace = ReconstructionTrace.read(work, score);
        this.noteContext = new ReconstructionNoteContext(work, score, noteRevision, trace);
        this.project = project;
        this.target = score.profile().game();
        this.stockReasons = ReconstructionVoicing.stockReasons(work);
        this.layers = trace.values().stream().flatMap(row -> layerOverlays(row, clock).stream()).toList();
        this.sources = sources(work, score);
        var span = new ReconstructionView.Span(0, duration(), TimeBase.source, clock);
        var patches = new HashMap<String, NavigableMap<Double, String>>();
        for (var change : EvidenceNotes.patchChanges(project, span, ReconstructionView.LANES))
            patches.computeIfAbsent(change.lane(), ignored -> new TreeMap<>()).put(change.time(), change.name());
        var allocation = ReconstructionTrace.optional(work, "allocation.json", ReconstructionAllocation.Result.class);
        var allocationClock = allocation == null || allocation.timeBase() == null ? TimeBase.source : allocation.timeBase();
        var drums = score.source().parts().stream().filter(MidiScore.Part::drums).map(MidiScore.Part::id).toList();
        var notes = new ArrayList<Overlay>();
        for (var row : trace.values()) {
            var bend = EvidenceBends.authoredCurve(span, row, allocationClock);
            var points = bend == null ? List.<BendPoint>of() : bend.points().stream()
                    .map(point -> new BendPoint(point.sourceTime(), point.pitch())).toList();
            notes.addAll(overlay(row, patches, points, drums.contains(row.part())));
        }
        addAssemblyOnlyNotes(project, span, patches, notes);
        notes.sort(Comparator.comparingDouble(note -> note.interval().sourceStart()));
        this.overlays = List.copyOf(notes);
        this.sections = ReconstructionSections.load(work, score);
        this.parts = score.source().parts().stream().map(part -> new Part(part.id(), part.name(), part.drums())).toList();
        this.beats = score.beatMap();
        this.bars = score.usesBeatGrid() ? beats.bars(duration()) : score.bars();
    }

    public static ReconstructionInspection open(Path work) throws IOException {
        var revision = ReconstructionNoteContext.builtRevision(work);
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var arrangement = work.resolve("arrangement.smps.json");
        var project = Files.isRegularFile(arrangement) ? ProjectFiles.load(arrangement).project() : null;
        var inspection = new ReconstructionInspection(work, score, project, revision);
        if (!revision.equals(ReconstructionNoteContext.builtRevision(work))) throw new ReconstructionReview.Changed();
        return inspection;
    }

    public String copyNotes(List<Overlay> notes, boolean roles, boolean draft, ReconstructionReview.Revision revision,
                            Map<String, String> draftVoices, Map<String, Integer> draftVelocities,
                            com.google.gson.JsonArray entries, Map<String, List<ReconstructionReview.EditResult>> outcomes,
                            List<ReconstructionProposals.Result> proposals) {
        return noteBlock(notes, roles, draft, revision, draftVoices, draftVelocities, entries, outcomes, proposals, null).text();
    }

    public NoteHandoff.Capture captureNotes(List<Overlay> notes, boolean roles, boolean draft, ReconstructionReview.Revision revision,
                            Map<String, String> draftVoices, Map<String, Integer> draftVelocities,
                            com.google.gson.JsonArray entries, Map<String, List<ReconstructionReview.EditResult>> outcomes,
                            List<ReconstructionProposals.Result> proposals, NoteHandoff.ViewRange range) {
        return noteBlock(notes, roles, draft, revision, draftVoices, draftVelocities, entries, outcomes, proposals, range)
                .capture(noteContext.area(range));
    }

    private NoteHandoff.Block noteBlock(List<Overlay> notes, boolean roles, boolean draft, ReconstructionReview.Revision revision,
                            Map<String, String> draftVoices, Map<String, Integer> draftVelocities,
                            com.google.gson.JsonArray entries, Map<String, List<ReconstructionReview.EditResult>> outcomes,
                            List<ReconstructionProposals.Result> proposals, NoteHandoff.ViewRange range) {
        return noteContext.block(notes, roles, draft, revision, draftVoices, draftVelocities,
                ReconstructionNoteContext.touching(entries, outcomes, proposals), range);
    }

    public double duration() { return clock.sourceDuration(); }
    public double projectDuration() { return clock.projectDuration(); }
    public double toSource(double project) { return clock.source(project); }
    public double toProject(double source) { return clock.project(source); }
    public TargetGame target() { return target; }
    public StudioProject project() { return project; }
    public List<AudioSource> sources() { return sources; }
    public List<Overlay> overlays() { return overlays; }
    public ReconstructionSections.Result sections() { return sections; }
    public List<Part> parts() { return parts; }

    public String stockVoiceReason(Overlay note) {
        if (note.lane() == null || !note.lane().startsWith("FM")) return null;
        String source = ReconstructionVoicePlan.SourceInstrument.id(note.part());
        return stockReasons.getOrDefault(source, stockReasons.get(note.lane()));
    }

    public double snap(double seconds, Grid grid) {
        return Math.clamp(gridTime(seconds, grid, false), 0, duration());
    }

    /** Source-time start of the bar containing this position, if a grid is available. */
    public java.util.OptionalDouble barStart(double seconds) {
        return bars.stream().filter(bar -> bar.start() <= seconds && seconds < bar.end())
                .mapToDouble(ReconstructionStructure.Bar::start).findFirst();
    }

    public double nextBeat(double seconds) {
        return Math.clamp(gridTime(seconds, Grid.BEAT, true), 0, duration());
    }

    private double gridTime(double seconds, Grid grid, boolean next) {
        if (beats != null && grid == Grid.BEAT) {
            double index = beats.index(seconds);
            return beats.seconds(next ? Math.floor(index + 1e-7) + 1 : Math.round(index));
        }
        if (bars.isEmpty()) return next ? seconds + .25 : Math.round(seconds * 4) / 4.0;
        double best = next ? duration() : 0;
        for (var bar : bars) {
            // Old scores may have bar anchors without a recorded beat map.
            int divisions = grid == Grid.BAR ? 1 : 4;
            for (int i = 0; i <= divisions; i++) {
                double point = bar.at(i / (double) divisions);
                if (next ? point > seconds + 1e-7 && point < best
                        : Math.abs(point - seconds) < Math.abs(best - seconds)) best = point;
            }
        }
        return best;
    }

    public ReconstructionLaneType laneType(Overlay overlay) {
        String lane = overlay.lane();
        if (lane == null) return null;
        if (lane.startsWith("FM")) return ReconstructionLaneType.FM;
        if (lane.equals("DAC")) return ReconstructionLaneType.DAC;
        var row = trace.get(overlay.id());
        var placement = row == null ? null : row.allocation();
        return overlay.role() == Role.DRUMS || placement != null && lane.equals(placement.lane())
                && placement.laneType() == ReconstructionLaneType.NOISE
                ? ReconstructionLaneType.NOISE : ReconstructionLaneType.PSG;
    }

    public List<LayerOverlay> layers() { return layers; }

    public List<ReconstructionLayers.Result> noteLayers(String noteId) {
        var row = trace.get(noteId);
        return row == null ? List.of() : displayLayers(row);
    }

    public static int laneRgb(String lane) { return EvidencePlot.laneColor(lane).getRGB() & 0xffffff; }

    private static List<ReconstructionLayers.Result> displayLayers(ReconstructionTrace.Row row) {
        var layers = new ArrayList<ReconstructionLayers.Result>();
        if (row.layers() != null) layers.addAll(row.layers());
        if (row.policyDecisions() != null) {
            for (var decision : row.policyDecisions()) {
                if (decision.status() != ReconstructionLanePolicies.Status.skipped
                        || decision.blockers() == null || decision.blockers().isEmpty()) continue;
                layers.add(new ReconstructionLayers.Result(row.id(), null, decision.policy().name(), null, decision.delay(),
                        0, ReconstructionLayers.Status.skipped, decision.detail(), null, decision.pitch(),
                        decision.attackTick(), decision.attackTick() + decision.gate(), null));
            }
        }
        return layers;
    }

    private static List<LayerOverlay> layerOverlays(ReconstructionTrace.Row row, ReconstructionTimeMap clock) {
        var role = row.allocation() != null ? row.allocation().role() : row.roles() == null ? null : row.roles().role();
        var color = role == null || role == ReconstructionRoles.Role.drop ? Role.UNASSIGNED
                : Role.valueOf(role.name().toUpperCase(Locale.ROOT));
        return displayLayers(row).stream().filter(layer -> layer.status() != ReconstructionLayers.Status.off)
                .map(layer -> new LayerOverlay(layer, row.part(), color, layer.pitch() == null ? row.pitch() : layer.pitch(),
                        interval(layer.startTick() != null && layer.endTick() != null
                                ? ReconstructionTrace.ticks(layer.startTick(), layer.endTick(), clock)
                                : java.util.Objects.requireNonNullElse(row.effectiveTiming(), row.timing())))).toList();
    }

    public NoteDetails noteDetails(String id) {
        var row = trace.get(id);
        if (row == null) return new NoteDetails(false, false, null, List.of());
        var role = row.roles();
        boolean generated = row.score() == null && role != null && role.effective() != null && row.source() == null;
        Integer velocity = row.score() != null ? row.score().note().velocity()
                : generated ? role.effective().note().velocity() : null;
        return new NoteDetails(row.score() != null, generated, velocity, row.editReasons());
    }
    public String trace(String id) { return trace.containsKey(id) ? JSON.toJson(trace.get(id)) : ""; }

    /** Replay chip frequency writes for assembled gates. Call from a worker after opening the snapshot. */
    public Map<String, List<BendPoint>> assembledBends() throws IOException {
        if (project == null || project.nativeProgram() != null || project.tempo() != 0) return Map.of();
        var gates = trace.values().stream().flatMap(row -> row.voiced().stream())
                .filter(gate -> gate.bend() != null && gate.evidence().equals("assembled")
                        && ReconstructionAllocation.FM.contains(gate.lane())).toList();
        if (gates.isEmpty()) return Map.of();
        double end = gates.stream().mapToDouble(gate -> gate.gate().sourceEnd()).max().orElseThrow();
        var span = new ReconstructionView.Span(0, end, TimeBase.source, clock);
        var observer = new EvidenceBends(ProjectRenderTiming.presentationCount(project));
        ReconstructionView.render(project, span, List.of(), observer, false);
        var curves = new HashMap<String, List<BendPoint>>();
        for (var curve : observer.curves(span, gates))
            curves.put(curve.noteId(), curve.points().stream()
                    .map(point -> new BendPoint(point.sourceTime(), point.pitch())).toList());
        return Map.copyOf(curves);
    }

    private void addAssemblyOnlyNotes(StudioProject project, ReconstructionView.Span span,
                                      Map<String, NavigableMap<Double, String>> patches, List<Overlay> notes) {
        var voiced = trace.values().stream().flatMap(row -> row.voiced().stream()).toList();
        if (voiced.stream().noneMatch(gate -> gate.evidence().equals("assembled")
                || ReconstructionChords.isLabel(gate.evidence()))) return;
        var covered = new ArrayList<ReconstructionView.DrawnNote>();
        for (var gate : voiced) covered.add(new ReconstructionView.DrawnNote(gate.id(), null,
                ReconstructionView.Layer.lane, null, gate.lane(), gate.pitch(), null, gate.gate(), null, gate.evidence()));
        for (var layer : layers) if (layer.layer().status() == ReconstructionLayers.Status.applied)
            covered.add(new ReconstructionView.DrawnNote(layer.layer().noteId(), null,
                    ReconstructionView.Layer.noteLayer, layer.part(), layer.layer().lane(), layer.pitch(), null,
                    ReconstructionTrace.ticks(layer.layer().startTick(), layer.layer().endTick(), clock), null, layer.layer().mode()));
        int count = covered.size();
        EvidenceNotes.addAssemblyOnlyNotes(project, span, List.of(), covered);
        for (var note : covered.subList(count, covered.size())) {
            var time = note.timing();
            notes.add(new Overlay(note.id(), note.id(), -1, "", Role.UNASSIGNED, note.lane(), note.pitch(),
                    patchAt(patches, note.lane(), time.sourceStart()), interval(time), List.of(), true,
                    Style.SOLID, "assembly-only", "Generated by the arrangement", List.of()));
        }
    }

    private static List<AudioSource> sources(Path work, ReconstructionStages.Score score) {
        var sources = new ArrayList<AudioSource>();
        var inputs = score.inputs();
        var mix = inputs == null || inputs.wav() == null ? null : work.resolve(inputs.wav());
        if (mix != null && Files.isRegularFile(mix))
            sources.add(new AudioSource(AudioKind.MIX, "Source mix", mix, false));
        var steady = work.resolve(ReconstructionStages.STEADY_REFERENCE);
        if (sources.isEmpty() && Files.isRegularFile(steady))
            sources.add(new AudioSource(AudioKind.MIX, "Steady reference mix", steady, true));
        if (inputs != null && inputs.stems() != null) {
            var stems = new TreeMap<>(inputs.stems());
            stems.forEach((part, filename) -> {
                var path = work.resolve(filename);
                if (!Files.isRegularFile(path)) return;
                String name = score.source().parts().stream().filter(p -> p.id() == part)
                        .map(MidiScore.Part::name).findFirst().orElse("");
                sources.add(new AudioSource(AudioKind.STEM, "Stem " + part + (name.isBlank() ? "" : " · " + name),
                        path, false));
            });
        }
        Path chip = work.resolve("render.wav");
        if (Files.isRegularFile(chip)) sources.add(new AudioSource(AudioKind.CHIP, "Chip render", chip, true));
        return List.copyOf(sources);
    }

    public SpectrumTile spectrum(AudioSource source, double start, double end, int columns, int low, int high)
            throws IOException {
        return spectrum(source, start, end, columns, low, high, 2, false);
    }

    /**
     * Measures a display tile with {@code rowsPerSemitone} rows per pitch.
     * With {@code reassigned}, energy moves to each bin's instantaneous frequency.
     */
    public SpectrumTile spectrum(AudioSource source, double start, double end, int columns, int low, int high,
                                 int rowsPerSemitone, boolean reassigned) throws IOException {
        var span = new ReconstructionView.Span(start, end, TimeBase.source, clock);
        var base = source.projectClock ? TimeBase.project : TimeBase.source;
        var audio = ReferenceAudio.evidenceRange(source.path, span.seconds(start, base) - EvidenceSpectrum.CONTEXT,
                span.seconds(end, base) + EvidenceSpectrum.CONTEXT);
        double[] times = new double[columns];
        for (int x = 0; x < columns; x++) times[x] = span.seconds(start + (end - start) * (x + .5) / columns, base);
        int rows = Math.max(1, rowsPerSemitone) * (high - low + 1);
        var spectrum = EvidenceSpectrum.measure(audio, times, low, high, rows, end - start, false, reassigned);
        var ridges = new ArrayList<Ridge>();
        for (int column = 0; column < columns; column++) {
            double sourceTime = start + (end - start) * (column + .5) / columns;
            for (var peak : spectrum.peaks(column, 3))
                ridges.add(new Ridge(sourceTime, peak.pitch(), peak.strength()));
        }
        float[] dbfs = spectrum.powers();
        for (int i = 0; i < dbfs.length; i++) dbfs[i] = (float) EvidenceSpectrum.Spectrum.db(dbfs[i]);
        return new SpectrumTile(columns, rows, dbfs, List.copyOf(ridges));
    }

    static List<Overlay> overlay(ReconstructionTrace.Row row, Map<String, NavigableMap<Double, String>> patches,
                                 List<BendPoint> bend, boolean drums) {
        var allocation = row.allocation();
        var roles = row.roles();
        var role = allocation == null || allocation.role() == null
                ? roles == null ? null : roles.role() : allocation.role();
        Role color = role == null || role == ReconstructionRoles.Role.drop
                ? drums ? Role.DRUMS : Role.UNASSIGNED : Role.valueOf(role.name().toUpperCase(Locale.ROOT));
        boolean lost = row.status() == ReconstructionTrace.Status.dropped || row.status() == ReconstructionTrace.Status.merged;
        var competitors = new LinkedHashSet<String>();
        if (allocation != null) {
            if (allocation.mergedInto() != null) competitors.add(allocation.mergedInto());
            if (allocation.competitorNoteId() != null) competitors.add(allocation.competitorNoteId());
            if (allocation.blockingNoteIds() != null) competitors.addAll(allocation.blockingNoteIds());
        }
        if (roles != null && roles.mergedInto() != null) competitors.add(roles.mergedInto());
        if (row.scoreDecision() != null && row.scoreDecision().successorId() != null)
            competitors.add(row.scoreDecision().successorId());
        var audible = row.voiced().stream().filter(gate -> !lost || ReconstructionChords.isLabel(gate.evidence())).toList();
        if (!audible.isEmpty()) return audible.stream()
                .map(gate -> drawable(row, gate.id(), gate.lane(), gate.pitch(), gate.gate(), color, patches, bend,
                        true, false, competitors)).toList();
        var effective = allocation != null && allocation.effective() != null ? allocation.effective()
                : roles != null && roles.effective() != null ? roles.effective()
                : row.structure() != null && row.structure().effective() != null ? row.structure().effective() : row.score();
        int pitch = effective == null ? row.pitch() : effective.note().pitch();
        String lane = allocation == null ? null : allocation.lane();
        boolean sounds = !lost && lane != null && allocation.placement() != null;
        var timing = java.util.Objects.requireNonNullElse(row.effectiveTiming(), row.timing());
        return List.of(drawable(row, row.id(), lane, pitch, timing, color, patches, bend, sounds, lost, competitors));
    }

    private static Overlay drawable(ReconstructionTrace.Row row, String eventId, String lane, int pitch,
                                    ReconstructionTrace.Timing time, Role role,
                                    Map<String, NavigableMap<Double, String>> patches, List<BendPoint> bend,
                                    boolean sounds, boolean lost, LinkedHashSet<String> competitors) {
        return new Overlay(row.id(), eventId, row.part(), row.partName(), role, lane, pitch,
                patchAt(patches, lane, time.sourceStart()), interval(time), bend, sounds,
                lost ? Style.DASHED : Style.SOLID, row.status().name(),
                row.fateDetail() == null ? row.reason() : row.fateDetail(), List.copyOf(competitors));
    }

    private static Interval interval(ReconstructionTrace.Timing time) {
        return new Interval(time.sourceStart(), time.sourceEnd(), time.projectStart(), time.projectEnd());
    }

    private static String patchAt(Map<String, NavigableMap<Double, String>> patches, String lane, double start) {
        var track = lane == null ? null : patches.get(lane);
        var patch = track == null ? null : track.floorEntry(start + 1e-8);
        return patch == null ? null : patch.getValue();
    }

}

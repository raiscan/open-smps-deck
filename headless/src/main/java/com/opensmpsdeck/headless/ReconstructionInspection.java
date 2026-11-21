package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;

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

    public enum AudioKind { MIX, STEM, CHIP }

    public record AudioSource(AudioKind kind, String label, Path path, boolean projectClock) {
        @Override public String toString() { return label; }
    }

    public record Ridge(double sourceSeconds, double pitch, double dbfs) {}
    public record SpectrumTile(int columns, int rows, float[] dbfs, List<Ridge> ridges) {}

    public record Band(String label, int level, double start, double end) {}
    /** Band positions are in source seconds. Levels are ordered from broad to narrow. */
    public interface Sections {
        List<String> levelLabels();
        List<Band> bands();
    }
    private record StructureBands(List<Band> bands) implements Sections {
        @Override public List<String> levelLabels() { return List.of("Sections", "Bars"); }
    }

    private final ReconstructionTimeMap clock;
    private final Map<String, ReconstructionTrace.Row> trace;
    private final StudioProject project;
    private final List<AudioSource> sources;
    private final List<Overlay> overlays;
    private final Sections sections;

    private ReconstructionInspection(Path work, ReconstructionStages.Score score, StudioProject project) throws IOException {
        this.clock = ReconstructionTimeMap.of(score);
        this.trace = ReconstructionTrace.read(work, score);
        this.project = project;
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
        this.sections = structureBands(ReconstructionTrace.optional(work, "structure.json",
                ReconstructionStructure.Result.class), score.bars());
    }

    public static ReconstructionInspection open(Path work) throws IOException {
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var arrangement = work.resolve("arrangement.smps.json");
        var project = Files.isRegularFile(arrangement) ? ProjectFiles.load(arrangement).project() : null;
        return new ReconstructionInspection(work, score, project);
    }

    public double duration() { return clock.sourceDuration(); }
    public double projectDuration() { return clock.projectDuration(); }
    public double toSource(double project) { return clock.source(project); }
    public double toProject(double source) { return clock.project(source); }
    public StudioProject project() { return project; }
    public List<AudioSource> sources() { return sources; }
    public List<Overlay> overlays() { return overlays; }
    public Sections sections() { return sections; }
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
        if (voiced.stream().noneMatch(gate -> gate.evidence().equals("assembled"))) return;
        var covered = new ArrayList<ReconstructionView.DrawnNote>();
        for (var gate : voiced) covered.add(new ReconstructionView.DrawnNote(gate.id(), null,
                ReconstructionView.Layer.lane, null, gate.lane(), gate.pitch(), null, gate.gate(), null, gate.evidence()));
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
        var span = new ReconstructionView.Span(start, end, TimeBase.source, clock);
        var base = source.projectClock ? TimeBase.project : TimeBase.source;
        var audio = ReferenceAudio.evidenceRange(source.path, span.seconds(start, base) - EvidenceSpectrum.CONTEXT,
                span.seconds(end, base) + EvidenceSpectrum.CONTEXT);
        double[] times = new double[columns];
        for (int x = 0; x < columns; x++) times[x] = span.seconds(start + (end - start) * (x + .5) / columns, base);
        int rows = 2 * (high - low + 1);
        var spectrum = EvidenceSpectrum.measure(audio, times, low, high, rows, end - start, false);
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
        if (!lost && !row.voiced().isEmpty()) return row.voiced().stream()
                .map(gate -> drawable(row, gate.id(), gate.lane(), gate.pitch(), gate.gate(), color, patches, bend,
                        true, false, competitors)).toList();
        var effective = allocation != null && allocation.effective() != null ? allocation.effective()
                : roles != null && roles.effective() != null ? roles.effective()
                : row.structure() != null && row.structure().effective() != null ? row.structure().effective() : row.score();
        int pitch = effective == null ? row.pitch() : effective.note().pitch();
        String lane = allocation == null ? null : allocation.lane();
        boolean sounds = !lost && lane != null && allocation.placement() != null;
        return List.of(drawable(row, row.id(), lane, pitch, row.effectiveTiming(), color, patches, bend,
                sounds, lost, competitors));
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

    private Sections structureBands(ReconstructionStructure.Result structure, List<ReconstructionStructure.Bar> scoreBars) {
        var bars = structure == null ? scoreBars : structure.bars();
        boolean project = structure != null && structure.timeBase() == TimeBase.project;
        var byId = new HashMap<String, Band>();
        var bands = new ArrayList<Band>();
        for (var bar : bars) {
            var band = new Band(bar.id(), 1, project ? toSource(bar.start()) : bar.start(),
                    project ? toSource(bar.end()) : bar.end());
            bands.add(band);
            byId.put(bar.id(), band);
        }
        if (structure != null) for (var section : structure.sections()) {
            var members = section.bars().stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
            if (!members.isEmpty()) bands.add(new Band(section.id(), 0,
                    members.stream().mapToDouble(Band::start).min().orElseThrow(),
                    members.stream().mapToDouble(Band::end).max().orElseThrow()));
        }
        return new StructureBands(List.copyOf(bands));
    }
}

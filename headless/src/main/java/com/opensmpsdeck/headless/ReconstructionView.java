package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionTimeMap.TimeBase;
import static com.opensmpsdeck.headless.ReconstructionTrace.Timing;
import static com.opensmpsdeck.headless.S3kRenderer.*;

public final class ReconstructionView {
    static final List<String> LANES = List.of("FM1", "FM2", "FM3", "FM4", "FM5", "PSG1", "PSG2", "PSG3", "DAC");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final double MAX_SPAN = 60;

    public record Options(double start, double end, String time, Integer stem, Path wav, boolean render,
                          Integer pitchMin, Integer pitchMax, List<String> lanes, List<Integer> parts,
                          String harmonics, boolean harmonicSum, int width) {
        public Options {
            if (!(start >= 0 && end - start >= .001 - 1e-12 && end - start <= MAX_SPAN))
                throw new IllegalArgumentException("Expected a nonnegative span of 0.001–60 seconds");
            TimeBase.valueOf(time);
            if (stem != null && (wav != null || stem < 0))
                throw new IllegalArgumentException("Choose a nonnegative --stem part or --wav");
            if (width < 640 || width > 2400)
                throw new IllegalArgumentException("Width must be 640–2400 pixels");
            if (pitchMin != null && (pitchMin < 0 || pitchMin > 127)
                    || pitchMax != null && (pitchMax < 0 || pitchMax > 127)
                    || pitchMin != null && pitchMax != null && pitchMin > pitchMax)
                throw new IllegalArgumentException("Expected an ordered MIDI pitch range in 0–127");
            lanes = List.copyOf(lanes);
            parts = List.copyOf(parts);
            if (!LANES.containsAll(lanes) || parts.stream().anyMatch(part -> part < 0))
                throw new IllegalArgumentException("Expected FM1–FM5, PSG1–PSG3, DAC and nonnegative part IDs");
        }
    }

    enum Layer { suno, external, edit, trace, lane }
    record DrawnNote(String id, String label, Layer layer, Integer part, String lane, int pitch,
                     String pitchName, Timing timing, ReconstructionTrace.Status status, String evidence) {
        boolean warning() {
            return status == ReconstructionTrace.Status.dropped || status == ReconstructionTrace.Status.merged;
        }
    }
    record Harmonic(int multiple, double pitch, double hz) {}
    record Span(double start, double end, TimeBase base, ReconstructionTimeMap timeline) {
        double start(Timing timing) { return base == TimeBase.source ? timing.sourceStart() : timing.projectStart(); }
        double end(Timing timing) { return base == TimeBase.source ? timing.sourceEnd() : timing.projectEnd(); }
        boolean overlaps(Timing timing) { return end(timing) > start && start(timing) < end; }
        double seconds(double time, TimeBase target) {
            if (base == target) return time;
            return target == TimeBase.source ? timeline.source(time) : timeline.project(time);
        }
        double length() { return end - start; }
    }
    record Source(Path path, String label, TimeBase base) {}
    record Plot(int left, int width, int sourceTop, Integer renderTop, int pitchHeight, int laneTop) {}

    public record Legend(String path, String source, String time, double start, double end, int pitchMin, int pitchMax,
                         boolean warped, boolean harmonicSum, Plot plot, List<DrawnNote> notes,
                         List<Harmonic> harmonics, List<EvidenceNotes.PatchChange> patchChanges) {}
    public record Result(Legend legend, byte[] png) {}

    private ReconstructionView() {}

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("Expected view WORK_DIR --start S --end S [options] OUT.png");
        var keys = Set.of("--start", "--end", "--time", "--stem", "--wav", "--pitch-min", "--pitch-max",
                "--lanes", "--parts", "--harmonics", "--width", "--render", "--harmonic-sum");
        var flags = new HashMap<String, String>();
        for (int i = 1; i < args.length - 1; i++) {
            String key = args[i];
            if (!keys.contains(key) || flags.containsKey(key))
                throw new IllegalArgumentException("Unknown or repeated view option " + key);
            if (key.equals("--render") || key.equals("--harmonic-sum")) flags.put(key, "true");
            else if (i + 1 < args.length - 1) flags.put(key, args[++i]);
            else throw new IllegalArgumentException("Missing value for " + key);
        }
        if (!flags.containsKey("--start") || !flags.containsKey("--end"))
            throw new IllegalArgumentException("Expected --start and --end");
        var options = new Options(Double.parseDouble(flags.get("--start")), Double.parseDouble(flags.get("--end")),
                flags.getOrDefault("--time", "source"), integer(flags, "--stem"),
                flags.containsKey("--wav") ? Path.of(flags.get("--wav")) : null, flags.containsKey("--render"),
                integer(flags, "--pitch-min"), integer(flags, "--pitch-max"), csv(flags.get("--lanes")),
                csv(flags.get("--parts")).stream().map(Integer::valueOf).toList(), flags.get("--harmonics"),
                flags.containsKey("--harmonic-sum"), Integer.parseInt(flags.getOrDefault("--width", "1440")));
        out.println(JSON.toJson(run(Path.of(args[0]), options, Path.of(args[args.length - 1])).legend()));
    }

    private static Integer integer(HashMap<String, String> flags, String key) {
        return flags.containsKey(key) ? Integer.valueOf(flags.get(key)) : null;
    }

    private static List<String> csv(String value) {
        return value == null ? List.of() : Arrays.stream(value.split(",", -1)).map(String::strip).toList();
    }

    public static Result run(Path work, Options options, Path output) throws IOException {
        if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
            throw new IllegalArgumentException("Evidence view output must end in .png");
        if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.nio.file.FileAlreadyExistsException(output.toString());
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var timeline = ReconstructionTimeMap.of(score);
        var span = new Span(options.start, options.end, TimeBase.valueOf(options.time), timeline);
        for (var base : TimeBase.values()) {
            double start = span.seconds(span.start, base), end = span.seconds(span.end, base);
            double duration = base == TimeBase.source ? timeline.sourceDuration() : timeline.frames() / 60.0;
            // Permit blank padding at the boundaries, while bounding audio reads in both time bases.
            if (!(end > start && start < duration && end - start <= MAX_SPAN + 1e-8))
                throw new IllegalArgumentException("View must overlap the run and span at most 60 seconds in both time bases");
        }

        var rows = ReconstructionTrace.read(work, score);
        var lanes = options.lanes.isEmpty() ? LANES : LANES.stream().filter(options.lanes::contains).toList();
        Path projectFile = work.resolve("arrangement.smps.json");
        var project = options.render || Files.isRegularFile(projectFile) ? ProjectFiles.load(projectFile).project() : null;
        boolean hasVoicing = Files.isRegularFile(work.resolve("voicing.json"));
        var notes = EvidenceNotes.collect(work, span, options, rows, hasVoicing, project);
        var target = EvidenceNotes.harmonicTarget(notes, options.harmonics, span);
        var source = source(work, options, score, span, rows);
        double audioStart = span.seconds(span.start, source.base), audioEnd = span.seconds(span.end, source.base);
        var audio = ReferenceAudio.evidenceRange(source.path, audioStart - EvidenceSpectrum.CONTEXT,
                audioEnd + EvidenceSpectrum.CONTEXT);
        var rendered = options.render ? render(project, span) : null;

        int minPitch = notes.stream().mapToInt(DrawnNote::pitch).min().orElse(51) - 3;
        int maxPitch = notes.stream().mapToInt(DrawnNote::pitch).max().orElse(81) + 3;
        if (target != null) {
            double nyquist = audio.rate() / 2.0;
            for (int multiple = 1; multiple <= 6; multiple++) {
                double pitch = EvidenceSpectrum.harmonicPitch(target.pitch, multiple);
                if (EvidenceSpectrum.hz(pitch) < nyquist) maxPitch = Math.max(maxPitch, (int) Math.ceil(pitch) + 1);
            }
        }
        minPitch = options.pitchMin == null ? Math.min(options.pitchMax == null ? 127 : options.pitchMax,
                Math.max(0, minPitch)) : options.pitchMin;
        maxPitch = options.pitchMax == null ? Math.max(minPitch, Math.min(127, maxPitch)) : options.pitchMax;

        int low = minPitch, high = maxPitch;
        var visible = notes.stream().filter(note -> note.pitch >= low && note.pitch <= high || note.layer == Layer.lane)
                .toList();
        var harmonics = harmonics(target, low, high, audio.rate() / 2.0);
        boolean projected = visible.stream().anyMatch(note -> note.layer == Layer.lane
                && ("voicing-projection".equals(note.evidence) || "allocated".equals(note.evidence)
                || "legacy-report-and-voicing".equals(note.evidence)));
        var picture = new EvidencePlot(options.width, span, low, high, rendered != null, lanes);
        var sounding = lanes.stream().filter(lane -> visible.stream()
                .anyMatch(note -> note.layer == Layer.lane && lane.equals(note.lane))).toList();
        var patchChanges = EvidenceNotes.patchChanges(project, span, sounding);
        picture.draw(source.label, source.base, audio, rendered, visible, target, harmonics, options.harmonicSum,
                patchChanges, projected);
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(picture.image(), "png", stream)) throw new IOException("PNG image writer is unavailable");
        }
        byte[] png = bytes.toByteArray();
        Files.write(output, png, StandardOpenOption.CREATE_NEW);
        var legend = new Legend(output.toAbsolutePath().normalize().toString(), source.label, options.time,
                span.start, span.end, low, high, timeline.warped(), options.harmonicSum,
                picture.geometry(), visible, harmonics, patchChanges);
        return new Result(legend, png);
    }

    private static Source source(Path work, Options options, ReconstructionStages.Score score, Span span,
                                 Map<String, ReconstructionTrace.Row> rows) {
        if (options.wav != null) return new Source(options.wav, "WAV · " + options.wav.getFileName(), TimeBase.source);
        var inputs = score.inputs();
        var stems = inputs == null || inputs.stems() == null ? Map.<Integer, String>of() : inputs.stems();
        Integer part = options.stem;
        if (part == null && inputs != null) {
            // The actual FM1 lead in the review window may differ from the song-wide hint.
            var leadTime = new LinkedHashMap<Integer, Double>();
            for (var row : rows.values()) {
                for (var gate : row.voiced()) if (gate.lane().equals("FM1") && span.overlaps(gate.gate()))
                    leadTime.merge(row.part(), Math.min(span.end, span.end(gate.gate()))
                            - Math.max(span.start, span.start(gate.gate())), Double::sum);
                if (row.voiced().isEmpty() && row.allocation() != null && "FM1".equals(row.allocation().lane())
                        && row.allocation().placement() != null && span.overlaps(row.allocation().placement())) {
                    var placement = row.allocation().placement();
                    leadTime.merge(row.part(), Math.min(span.end, span.end(placement))
                            - Math.max(span.start, span.start(placement)), Double::sum);
                }
            }
            part = leadTime.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(inputs.leadPart());
        }
        String stem = part == null ? null : stems.get(part);
        if (options.stem != null && stem == null) throw new IllegalArgumentException("No stem mapped to part " + options.stem);
        if (stem != null && (options.stem != null || Files.isRegularFile(Path.of(stem))))
            return new Source(Path.of(stem), "Stem " + part + " · " + Path.of(stem).getFileName(), TimeBase.source);
        Path steady = work.resolve(ReconstructionStages.STEADY_REFERENCE);
        if (span.timeline.warped() && Files.isRegularFile(steady))
            return new Source(steady, "Steady reference mix", TimeBase.project);
        if (inputs != null && inputs.wav() != null) return new Source(Path.of(inputs.wav()), "Source mix", TimeBase.source);
        throw new IllegalArgumentException("No recorded source WAV. Supply --wav FILE");
    }

    private static EvidenceSpectrum.Audio render(StudioProject project, Span span) throws IOException {
        if (project.nativeProgram() != null || project.tempo() != 0)
            throw new IllegalArgumentException("Render view requires an editable arrangement at 60 project ticks per second");
        int first = Math.max(0, (int) Math.floor((span.seconds(span.start, TimeBase.project) - EvidenceSpectrum.CONTEXT) * 60));
        int limit = ProjectRenderTiming.presentationCount(project);
        int end = Math.min(limit, (int) Math.ceil((span.seconds(span.end, TimeBase.project) + EvidenceSpectrum.CONTEXT) * 60));
        if (end <= first) return new EvidenceSpectrum.Audio(new float[0], SAMPLE_RATE, 0, limit / 60.0);
        var mono = new float[(end - first) * STEREO_FRAMES_PER_PRESENTATION];
        int[] tick = {0};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), end, samples -> {
            int frame = tick[0]++ - first;
            if (frame < 0) return;
            int offset = frame * STEREO_FRAMES_PER_PRESENTATION;
            for (int i = 0; i < STEREO_FRAMES_PER_PRESENTATION; i++)
                mono[offset + i] = (samples[2 * i] + samples[2 * i + 1]) / 65536f;
        });
        return new EvidenceSpectrum.Audio(mono, SAMPLE_RATE, first / 60.0, limit / 60.0);
    }

    private static List<Harmonic> harmonics(DrawnNote note, int min, int max, double nyquist) {
        if (note == null) return List.of();
        var result = new ArrayList<Harmonic>();
        double upper = Math.min(nyquist, EvidenceSpectrum.hz(max + .5));
        for (int k = 1; EvidenceSpectrum.hz(note.pitch) * k <= upper; k++) {
            double pitch = EvidenceSpectrum.harmonicPitch(note.pitch, k);
            if (pitch >= min - .5) result.add(new Harmonic(k, pitch, EvidenceSpectrum.hz(note.pitch) * k));
        }
        return List.copyOf(result);
    }
}

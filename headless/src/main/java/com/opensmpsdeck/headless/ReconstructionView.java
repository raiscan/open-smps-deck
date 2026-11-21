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
                          String harmonics, boolean harmonicSum, int width, Path notes, List<String> harmonicsAt,
                          boolean ridges, boolean sharedDb, boolean db, String pitchPreset, int ridgeCount,
                          boolean colorByVoice) {
        public Options(double start, double end, String time, Integer stem, Path wav, boolean render,
                       Integer pitchMin, Integer pitchMax, List<String> lanes, List<Integer> parts,
                       String harmonics, boolean harmonicSum, int width, Path notes, List<String> harmonicsAt,
                       boolean ridges, boolean sharedDb, boolean db, String pitchPreset, int ridgeCount) {
            this(start, end, time, stem, wav, render, pitchMin, pitchMax, lanes, parts, harmonics, harmonicSum,
                    width, notes, harmonicsAt, ridges, sharedDb, db, pitchPreset, ridgeCount, false);
        }

        public Options {
            if (!(start >= 0 && end - start >= .001 - 1e-12 && end - start <= MAX_SPAN))
                throw new IllegalArgumentException("Expected a nonnegative span of 0.001–60 seconds");
            TimeBase.valueOf(time);
            if (ridgeCount < 1 || ridgeCount > 32)
                throw new IllegalArgumentException("Ridge count must be 1–32");
            if (stem != null && (wav != null || stem < 0))
                throw new IllegalArgumentException("Choose a nonnegative --stem part or --wav");
            if (width < 640 || width > 2400)
                throw new IllegalArgumentException("Width must be 640–2400 pixels");
            if (pitchMin != null && (pitchMin < 0 || pitchMin > 127)
                    || pitchMax != null && (pitchMax < 0 || pitchMax > 127)
                    || pitchMin != null && pitchMax != null && pitchMin > pitchMax)
                throw new IllegalArgumentException("Expected an ordered MIDI pitch range in 0–127");
            if (pitchPreset != null && !pitchPreset.equals("drums"))
                throw new IllegalArgumentException("Expected the drums pitch preset");
            harmonicsAt = List.copyOf(harmonicsAt);
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
    record HarmonicGuide(int pitch, double start, double end, Double at, List<Harmonic> partials) {}
    record Levels(Double sourcePeakDbfs, Double renderPeakDbfs, boolean shared) {}
    record RidgeExport(String time, String source, String strengthUnit, int peaksPerColumn,
                       List<EvidenceTrajectories.Point> ridges, List<EvidenceTrajectories.Point> renderRidges,
                       List<EvidenceTrajectories.Track> sourceTrajectories, List<EvidenceTrajectories.Track> renderTrajectories) {}
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

    public record VoiceColor(String name, String color) {}

    public record Legend(String path, String source, String time, double start, double end,
                         double sourceStart, double sourceEnd, double projectStart, double projectEnd,
                         int pitchMin, int pitchMax,
                         boolean warped, boolean harmonicSum, Plot plot, List<DrawnNote> notes,
                         List<Harmonic> harmonics, List<EvidenceNotes.PatchChange> patchChanges,
                         List<HarmonicGuide> harmonicsAt, Levels levels, String ridgesPath,
                         List<EvidenceBends.Curve> bends, List<EvidenceTrajectories.Track> sourceLevels,
                         List<EvidenceTrajectories.Track> renderLevels, DrumEvidence.Review drums,
                         Map<String, VoiceColor> voiceColors, Map<String, String> noteVoices) {}
    public record Result(Legend legend, byte[] png) {}

    private ReconstructionView() {}

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("Expected view WORK_DIR --start S --end S [options] OUT.png");
        var switches = Set.of("--render", "--harmonic-sum", "--ridges", "--shared-db", "--db", "--force", "--sections");
        var keys = Set.of("--start", "--end", "--time", "--stem", "--wav", "--pitch-min", "--pitch-max",
                "--lanes", "--parts", "--harmonics", "--width", "--notes", "--harmonics-at", "--pitch-preset", "--ridge-count", "--section", "--color-by");
        var flags = new HashMap<String, String>();
        var harmonicsAt = new ArrayList<String>();
        for (int i = 1; i < args.length - 1; i++) {
            String key = args[i];
            if (key.equals("--harmonics-at") && i + 1 < args.length - 1) {
                harmonicsAt.add(args[++i]);
                continue;
            }
            if ((!keys.contains(key) && !switches.contains(key)) || flags.containsKey(key))
                throw new IllegalArgumentException("Unknown or repeated view option " + key);
            if (switches.contains(key)) flags.put(key, "true");
            else if (i + 1 < args.length - 1) flags.put(key, args[++i]);
            else throw new IllegalArgumentException("Missing value for " + key);
        }
        Path work = Path.of(args[0]);
        String time = flags.getOrDefault("--time", "source");
        double[] bounds = ReconstructionSections.bounds(work, flags.get("--section"),
                decimal(flags, "--start"), decimal(flags, "--end"), time);
        String colorBy = flags.getOrDefault("--color-by", "lane");
        if (!Set.of("lane", "voice").contains(colorBy))
            throw new IllegalArgumentException("--color-by expects lane or voice");
        var options = new Options(bounds[0], bounds[1], time, integer(flags, "--stem"),
                flags.containsKey("--wav") ? Path.of(flags.get("--wav")) : null, flags.containsKey("--render"),
                integer(flags, "--pitch-min"), integer(flags, "--pitch-max"), csv(flags.get("--lanes")),
                csv(flags.get("--parts")).stream().map(Integer::valueOf).toList(), flags.get("--harmonics"),
                flags.containsKey("--harmonic-sum"), Integer.parseInt(flags.getOrDefault("--width", "1440")),
                flags.containsKey("--notes") ? Path.of(flags.get("--notes")) : null, harmonicsAt,
                flags.containsKey("--ridges") || flags.containsKey("--ridge-count"),
                flags.containsKey("--shared-db"), flags.containsKey("--db"),
                flags.get("--pitch-preset"), Integer.parseInt(flags.getOrDefault("--ridge-count", "3")), colorBy.equals("voice"));
        var result = run(work, options, Path.of(args[args.length - 1]),
                flags.containsKey("--force"), flags.containsKey("--section") || flags.containsKey("--sections"));
        out.println(JSON.toJson(legendJson(result.legend())));
    }

    public static com.google.gson.JsonObject legendJson(Legend legend) {
        var json = JSON.toJsonTree(legend).getAsJsonObject();
        for (String key : List.of("harmonicsAt", "levels", "ridgesPath", "bends", "sourceLevels", "renderLevels", "drums", "voiceColors", "noteVoices"))
            if (json.get(key).isJsonNull()) json.remove(key);
        return json;
    }

    private static Double decimal(HashMap<String, String> flags, String key) {
        return flags.containsKey(key) ? Double.valueOf(flags.get(key)) : null;
    }

    private static Integer integer(HashMap<String, String> flags, String key) {
        return flags.containsKey(key) ? Integer.valueOf(flags.get(key)) : null;
    }

    static List<String> csv(String value) {
        return value == null ? List.of() : Arrays.stream(value.split(",", -1)).map(String::strip).toList();
    }

    public static Path ridgesPath(Path output) {
        String filename = output.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".png"))
            throw new IllegalArgumentException("Evidence view output must end in .png");
        return output.resolveSibling(filename.substring(0, filename.length() - 4) + ".ridges.json");
    }

    public static Result run(Path work, Options options, Path output) throws IOException {
        return run(work, options, output, false, false);
    }

    public static Result run(Path work, Options options, Path output, boolean showSections) throws IOException {
        return run(work, options, output, false, showSections);
    }

    private static Result run(Path work, Options options, Path output, boolean force, boolean showSections) throws IOException {
        Path sidecar = ridgesPath(output);
        Path ridgeFile = options.ridges ? sidecar : null;
        try (var png = new ExportTarget(output, force);
             var ridge = ridgeFile == null ? null : new ExportTarget(ridgeFile, force)) {
            return renderView(work, options, output, ridgeFile, png, ridge, force, showSections);
        }
    }

    private static Result renderView(Path work, Options options, Path output, Path ridgeFile,
                                     ExportTarget pngTarget, ExportTarget ridgeTarget, boolean force, boolean showSections) throws IOException {
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

        var targets = options.harmonicsAt.stream().map(value -> harmonicGuide(value, span)).toList();
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
        var assembledGates = project == null || project.nativeProgram() != null || project.tempo() != 0
                ? List.<ReconstructionTrace.Voiced>of() : EvidenceBends.assembled(rows, notes);
        var bendTrace = assembledGates.isEmpty() ? null : new EvidenceBends(ProjectRenderTiming.presentationCount(project));
        var rendered = options.render || bendTrace != null ? render(project, span, options.lanes, bendTrace, options.render) : null;
        var allocation = ReconstructionTrace.optional(work, "allocation.json", ReconstructionAllocation.Result.class);
        var clock = allocation == null || allocation.timeBase() == null ? TimeBase.source : allocation.timeBase();
        var bends = new ArrayList<>(EvidenceBends.authored(span, rows, notes, clock));
        if (bendTrace != null) bends.addAll(bendTrace.curves(span, assembledGates));
        var drums = DrumEvidence.review(rows.values().stream().toList(), rows.values(),
                span.seconds(span.start, TimeBase.source), span.seconds(span.end, TimeBase.source), timeline,
                options.lanes.isEmpty() || options.lanes.contains("DAC"), options.parts());

        int minPitch = notes.stream().mapToInt(DrawnNote::pitch).min().orElse(51) - 3;
        int maxPitch = notes.stream().mapToInt(DrawnNote::pitch).max().orElse(81) + 3;
        for (var curve : bends) for (var point : curve.points()) {
            minPitch = Math.min(minPitch, (int) Math.floor(point.pitch()) - 1);
            maxPitch = Math.max(maxPitch, (int) Math.ceil(point.pitch()) + 1);
        }
        var guidePitches = new ArrayList<>(targets.stream().map(HarmonicGuide::pitch).toList());
        if (target != null) guidePitches.add(target.pitch());
        for (int pitch : guidePitches) {
            minPitch = Math.min(minPitch, pitch - 3);
            double nyquist = audio.rate() / 2.0;
            for (int multiple = 1; multiple <= 6; multiple++) {
                double partial = EvidenceSpectrum.harmonicPitch(pitch, multiple);
                if (EvidenceSpectrum.hz(partial) < nyquist) maxPitch = Math.max(maxPitch, (int) Math.ceil(partial) + 1);
            }
        }
        if ("drums".equals(options.pitchPreset)) {
            minPitch = 35;
            maxPitch = 81;
        }
        minPitch = options.pitchMin == null ? Math.min(options.pitchMax == null ? 127 : options.pitchMax,
                Math.max(0, minPitch)) : options.pitchMin;
        maxPitch = options.pitchMax == null ? Math.max(minPitch, Math.min(127, maxPitch)) : options.pitchMax;

        int low = minPitch, high = maxPitch;
        var visible = notes.stream().filter(note -> note.pitch >= low && note.pitch <= high || note.layer == Layer.lane)
                .toList();
        var harmonics = target == null ? List.<Harmonic>of() : harmonics(target.pitch, low, high, audio.rate() / 2.0);
        var guides = targets.stream().map(guide -> new HarmonicGuide(guide.pitch, guide.start, guide.end, guide.at,
                harmonics(guide.pitch, low, high, audio.rate() / 2.0))).toList();
        boolean projected = visible.stream().anyMatch(note -> note.layer == Layer.lane
                && ("voicing-projection".equals(note.evidence) || "allocated".equals(note.evidence)
                || "legacy-report-and-voicing".equals(note.evidence)));
        boolean trajectories = (target != null || !targets.isEmpty()) && (options.ridges || options.db);
        var bands = showSections || Files.exists(work.resolve(ReconstructionSections.FILE))
                ? ReconstructionSections.load(work, score).sections() : List.<ReconstructionSections.Section>of();
        var voiceColors = new java.util.TreeMap<String, VoiceColor>();
        var noteVoices = new java.util.TreeMap<String, String>();
        var noteColors = new HashMap<String, java.awt.Color>();
        if (options.colorByVoice) {
            var identities = new HashMap<String, ReconstructionVoicePlan.Identity>();
            for (var row : rows.values()) {
                if (row.voice() == null) continue;
                identities.put(row.id(), row.voice());
                for (var gate : row.voiced()) identities.put(gate.id(), row.voice());
            }
            for (var note : visible) {
                var identity = identities.get(note.id());
                if (identity == null) continue;
                int hash = identity.id().hashCode() * 0x9E3779B9;
                var color = java.awt.Color.getHSBColor((hash >>> 8) / 16777216f, .52f, .98f);
                noteColors.put(note.id(), color);
                noteVoices.put(note.id(), identity.id());
                voiceColors.put(identity.id(), new VoiceColor(identity.name(),
                        String.format(Locale.ROOT, "#%06x", color.getRGB() & 0xffffff)));
            }
        }
        var picture = new EvidencePlot(options.width, span, low, high, rendered != null, lanes,
                trajectories && options.db, drums != null, bands, noteColors);
        if (!options.lanes.isEmpty()) picture.renderLabel = "Solo " + String.join(", ", options.lanes);
        var sounding = lanes.stream().filter(lane -> visible.stream()
                .anyMatch(note -> note.layer == Layer.lane && lane.equals(note.lane))).toList();
        var patchChanges = EvidenceNotes.patchChanges(project, span, sounding);
        var sourceSpectrum = picture.measure(audio, source.base, options.harmonicSum);
        boolean renderSum = options.sharedDb && options.harmonicSum;
        var renderSpectrum = rendered == null ? null : picture.measure(rendered, TimeBase.project, renderSum);
        var levels = options.db || options.sharedDb ? new Levels(sourceSpectrum.peakDb(),
                renderSpectrum == null ? null : renderSpectrum.peakDb(), options.sharedDb) : null;
        var sourceRaw = sourceSpectrum;
        var renderRaw = renderSpectrum;
        if (ridgeFile != null || trajectories) {
            if (options.harmonicSum) sourceRaw = picture.measure(audio, source.base, false);
            if (renderSum && rendered != null) renderRaw = picture.measure(rendered, TimeBase.project, false);
        }
        List<EvidenceTrajectories.Track> sourceTracks = null, renderTracks = null;
        if (trajectories) {
            var sourceTargets = trajectoryTargets(target, targets, notes, bends, span, false);
            sourceTracks = EvidenceTrajectories.tracks(trackSpectrum(picture, sourceRaw, audio, source.base, sourceTargets, bends),
                    span, sourceTargets);
            if (rendered != null) {
                var chipTargets = trajectoryTargets(target, targets, notes, bends, span, true);
                renderTracks = EvidenceTrajectories.tracks(trackSpectrum(picture, renderRaw, rendered, TimeBase.project,
                        chipTargets, bends), span, chipTargets);
            }
        }
        picture.draw(source.label, sourceSpectrum, renderSpectrum, visible, target, harmonics, guides,
                options.harmonicSum, levels, patchChanges, projected, bends,
                options.db ? sourceTracks : null, options.db ? renderTracks : null, drums);
        String ridgeJson = null;
        if (ridgeFile != null) {
            ridgeJson = JSON.toJson(new RidgeExport(options.time, source.label, "spectral dBFS", options.ridgeCount,
                    EvidenceTrajectories.ridges(sourceRaw, span, options.ridgeCount),
                    renderRaw == null ? null : EvidenceTrajectories.ridges(renderRaw, span, options.ridgeCount),
                    sourceTracks, renderTracks)) + "\n";
        }
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(picture.image(), "png", stream)) throw new IOException("PNG image writer is unavailable");
        }
        byte[] png = bytes.toByteArray();
        Files.write(pngTarget.path(), png, StandardOpenOption.CREATE_NEW);
        if (ridgeTarget != null) {
            try {
                Files.writeString(ridgeTarget.path(), ridgeJson, StandardOpenOption.CREATE_NEW);
                ridgeTarget.publish();
            } catch (IOException failure) {
                if (!force) Files.deleteIfExists(output);
                throw failure;
            }
        }
        pngTarget.publish();
        var legend = new Legend(output.toAbsolutePath().normalize().toString(), source.label, options.time,
                span.start, span.end, span.seconds(span.start, TimeBase.source), span.seconds(span.end, TimeBase.source),
                span.seconds(span.start, TimeBase.project), span.seconds(span.end, TimeBase.project), low, high, timeline.warped(), options.harmonicSum,
                picture.geometry(), visible, harmonics, patchChanges, guides.isEmpty() ? null : guides, levels,
                ridgeFile == null ? null : ridgeFile.toAbsolutePath().normalize().toString(),
                bends.isEmpty() ? null : bends, options.db ? sourceTracks : null, options.db ? renderTracks : null, drums,
                options.colorByVoice ? voiceColors : null, options.colorByVoice ? noteVoices : null);
        return new Result(legend, png);
    }

    private static List<EvidenceTrajectories.Target> trajectoryTargets(DrawnNote selected, List<HarmonicGuide> pitches,
            List<DrawnNote> notes, List<EvidenceBends.Curve> bends, Span span, boolean chip) {
        var result = new ArrayList<EvidenceTrajectories.Target>();
        if (selected != null) {
            DrawnNote gate = chip ? notes.stream().filter(note -> note.id.equals(selected.id) && note.layer == Layer.lane)
                    .findFirst().orElse(selected) : selected;
            var curve = bends.stream().filter(bend -> bend.noteId().equals(gate.id)
                    && (chip ? bend.kind().equals("assembled") && java.util.Objects.equals(gate.lane, bend.lane())
                            : !bend.kind().equals("assembled"))).findFirst().orElse(null);
            result.add(new EvidenceTrajectories.Target(gate.id, gate.pitch, span.start(gate.timing), span.end(gate.timing),
                    time -> curve == null ? gate.pitch : EvidenceBends.pitchAt(curve, time)));
        }
        for (var pitch : pitches)
            result.add(new EvidenceTrajectories.Target(null, pitch.pitch, pitch.start, pitch.end, time -> pitch.pitch));
        return result;
    }

    private static EvidenceSpectrum.Spectrum trackSpectrum(EvidencePlot picture, EvidenceSpectrum.Spectrum raw,
            EvidenceSpectrum.Audio audio, TimeBase base, List<EvidenceTrajectories.Target> targets,
            List<EvidenceBends.Curve> bends) {
        int low = raw.minPitch(), high = raw.maxPitch();
        for (var target : targets) {
            low = Math.min(low, target.pitch() - 2);
            high = Math.max(high, (int) Math.ceil(EvidenceSpectrum.harmonicPitch(target.pitch(), 3)) + 2);
            if (target.noteId() == null) continue;
            for (var bend : bends) if (target.noteId().equals(bend.noteId())) for (var point : bend.points()) {
                low = Math.min(low, (int) Math.floor(point.pitch()) - 2);
                high = Math.max(high, (int) Math.ceil(point.pitch() + EvidenceSpectrum.harmonicPitch(0, 3)) + 2);
            }
        }
        return low == raw.minPitch() && high == raw.maxPitch() ? raw : picture.measure(audio, base, false, low, high);
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

    static ChannelMask soloMask(List<String> solo) {
        return laneMask(solo, List.of());
    }

    public static ChannelMask laneMask(List<String> solo, List<String> mute) {
        if (!LANES.containsAll(solo) || !LANES.containsAll(mute))
            throw new IllegalArgumentException("Expected FM1–FM5, PSG1–PSG3 or DAC");
        int fm = 0, psg = 0;
        for (int i = 0; i < LANES.size(); i++) {
            String lane = LANES.get(i);
            if ((!solo.isEmpty() && !solo.contains(lane)) || mute.contains(lane)) {
                if (i < 5) fm |= 1 << i;
                else if (lane.equals("DAC")) fm |= 1 << 5;
                else psg |= 1 << (i - 5);
            }
        }
        // PSG3 owns both tone 3 and the noise output.
        if ((psg & 0b100) != 0) psg |= 0b1000;
        return new ChannelMask(fm, psg);
    }

    static EvidenceSpectrum.Audio render(StudioProject project, Span span, List<String> solo,
                                                EvidenceBends bends, boolean captureAudio) throws IOException {
        if (project.nativeProgram() != null || project.tempo() != 0)
            throw new IllegalArgumentException("Render view requires an editable arrangement at 60 project ticks per second");
        int first = Math.max(0, (int) Math.floor((span.seconds(span.start, TimeBase.project) - EvidenceSpectrum.CONTEXT) * 60));
        int limit = ProjectRenderTiming.presentationCount(project);
        int end = Math.min(limit, (int) Math.ceil((span.seconds(span.end, TimeBase.project) + EvidenceSpectrum.CONTEXT) * 60));
        if (end <= first) return !captureAudio ? null : new EvidenceSpectrum.Audio(new float[0], SAMPLE_RATE, 0, limit / 60.0);
        var mono = captureAudio ? new float[(end - first) * STEREO_FRAMES_PER_PRESENTATION] : null;
        int[] tick = {0};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(project), end, soloMask(solo), samples -> {
            int current = tick[0]++;
            if (bends != null) bends.frame(current);
            int frame = current - first;
            if (frame < 0 || mono == null) return;
            int offset = frame * STEREO_FRAMES_PER_PRESENTATION;
            for (int i = 0; i < STEREO_FRAMES_PER_PRESENTATION; i++)
                mono[offset + i] = (samples[2 * i] + samples[2 * i + 1]) / 65536f;
        }, bends == null ? com.openggf.audio.synth.ChipWriteObserver.NONE : bends);
        return mono == null ? null : new EvidenceSpectrum.Audio(mono, SAMPLE_RATE, first / 60.0, limit / 60.0);
    }

    private static HarmonicGuide harmonicGuide(String value, Span span) {
        String[] pieces = value.split("@", -1);
        if (pieces.length > 2) throw new IllegalArgumentException("Expected PITCH[@TIME]");
        String name = pieces[0].replace('♯', '#').replace('♭', 'b');
        int pitch;
        if (name.matches("[0-9]+")) pitch = Integer.parseInt(name);
        else {
            var note = java.util.regex.Pattern.compile("(?i)([A-G])([#b]?)(-?[0-9]+)").matcher(name);
            if (!note.matches()) throw new IllegalArgumentException("Expected a MIDI pitch or note name in " + value);
            int natural = switch (note.group(1).toUpperCase(Locale.ROOT)) {
                case "C" -> 0;
                case "D" -> 2;
                case "E" -> 4;
                case "F" -> 5;
                case "G" -> 7;
                case "A" -> 9;
                default -> 11;
            };
            int octave = Integer.parseInt(note.group(3));
            if (octave < -2 || octave > 10) throw new IllegalArgumentException("Pitch must be in MIDI range 0–127");
            int accidental = note.group(2).equals("#") ? 1 : note.group(2).isEmpty() ? 0 : -1;
            pitch = (octave + 1) * 12 + natural + accidental;
        }
        if (pitch < 0 || pitch > 127) throw new IllegalArgumentException("Pitch must be in MIDI range 0–127");
        if (pieces.length == 1) return new HarmonicGuide(pitch, span.start, span.end, null, List.of());
        double at = Double.parseDouble(pieces[1]);
        if (!(at >= span.start && at < span.end))
            throw new IllegalArgumentException("Harmonic time must be inside the selected span");
        double halfWidth = Math.min(.12, span.length() / 8);
        return new HarmonicGuide(pitch, Math.max(span.start, at - halfWidth), Math.min(span.end, at + halfWidth),
                at, List.of());
    }

    private static List<Harmonic> harmonics(int notePitch, int min, int max, double nyquist) {
        var result = new ArrayList<Harmonic>();
        double upper = Math.min(nyquist, EvidenceSpectrum.hz(max + .5));
        for (int k = 1; EvidenceSpectrum.hz(notePitch) * k <= upper; k++) {
            double pitch = EvidenceSpectrum.harmonicPitch(notePitch, k);
            if (pitch >= min - .5) result.add(new Harmonic(k, pitch, EvidenceSpectrum.hz(notePitch) * k));
        }
        return List.copyOf(result);
    }
}

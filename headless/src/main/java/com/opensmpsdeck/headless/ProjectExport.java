package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import java.nio.file.FileAlreadyExistsException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class ProjectExport {
    public enum Format {
        WAV("wav"), MP3("mp3"), VGM("vgm"), SMPS("bin"), PACKAGE("smps.zip"), PORTABLE("smps.zip"), MOD("jar"), DISASM("zip");

        private final String extension;

        Format(String extension) { this.extension = extension; }
        public String extension() { return extension; }
        public boolean audio() { return this == WAV || this == MP3; }
    }

    public record Options(Format format, Double start, Double end, Double fade, Integer loops,
                          boolean stems, boolean zip, PortableSettings portable, DisasmSettings disasm) {
        public Options(Format format, Double start, Double end, Double fade, Integer loops, boolean stems, boolean zip,
                       PortableSettings portable) {
            this(format, start, end, fade, loops, stems, zip, portable, null);
        }

        public Options(Format format, Double start, Double end, Double fade, Integer loops, boolean stems, boolean zip) {
            this(format, start, end, fade, loops, stems, zip, null);
        }

        public Options {
            if (!format.audio() && (start != null || end != null || fade != null || stems || zip))
                throw new IllegalArgumentException("Span, fade and stems require WAV or MP3");
            if (zip && !stems) throw new IllegalArgumentException("ZIP requires stems");
            if (fade != null && !(fade > 0 && fade <= 600))
                throw new IllegalArgumentException("Fade must be greater than zero and at most 600 seconds");
            if (loops != null && (!format.audio() || loops < 1 || loops > 99))
                throw new IllegalArgumentException("Loop count requires WAV or MP3 and must be in 1..99");
        }
    }

    public record DisasmSettings(List<Path> roms, boolean dpcmClosed, List<Integer> replaceDac) {
        public DisasmSettings {
            roms = List.copyOf(roms);
            replaceDac = List.copyOf(replaceDac);
        }
    }

    public record PortableSettings(String driver, boolean includeSource, String license, String id,
                                   String version, List<String> authors, List<Integer> replacements, Integer speedUpTempo,
                                   boolean strictTempo, boolean retailPatch, List<Path> roms) {
        public PortableSettings {
            roms = List.copyOf(roms);
        }

        public PortableSettings(String driver, boolean includeSource, String license, String id,
                                String version, List<String> authors, List<Integer> replacements, Integer speedUpTempo,
                                boolean strictTempo) {
            this(driver, includeSource, license, id, version, authors, replacements, speedUpTempo, strictTempo, false, List.of());
        }
        public PortableSettings(String driver, boolean includeSource, String license, String id,
                                String version, List<String> authors, List<Integer> replacements, Integer speedUpTempo) {
            this(driver, includeSource, license, id, version, authors, replacements, speedUpTempo, false);
        }
        public PortableSettings(String driver, boolean includeSource, String license, String id,
                                String version, List<String> authors, List<Integer> replacements) {
            this(driver, includeSource, license, id, version, authors, replacements, null);
        }
        PortableOptions options(TargetGame fallback) {
            return new PortableOptions(driver == null ? fallback.id() : driver, includeSource, license, id, version, authors, replacements, speedUpTempo, strictTempo,
                    retailPatch, roms);
        }
    }

    public record Source(StudioProject project, Path work, Integer frames) {
        public Source(StudioProject project, Path work) { this(project, work, null); }
    }

    private record Stem(String name, S3kRenderer.ChannelMask mask) {}

    private static final List<Stem> STEMS = List.of(
            fm("FM1", 0), fm("FM2", 1), fm("FM3", 2), fm("FM4", 3), fm("FM5", 4), fm("DAC", 5),
            psg("PSG1", 0), psg("PSG2", 1), psg("PSG3", 2), psg("Noise", 3));

    private ProjectExport() {}

    public static boolean canExportRetailPatch(StudioProject project) {
        return project.nativeProgram() != null && ProjectProvenance.retailOrigin(project) != null;
    }

    public static Source open(Path input) throws IOException {
        Path work = Files.isDirectory(input) ? input : null;
        return new Source(ProjectFiles.load(work == null ? input : work.resolve("arrangement.smps.json")).project(), work);
    }

    public static List<String> write(StudioProject project, Path work, Path output, Options options,
                             ReconstructionStages.Progress progress) throws IOException {
        return write(new Source(project, work), output, options, progress);
    }

    public static List<String> write(Source source, Path output, Options options,
                                     ReconstructionStages.Progress progress) throws IOException {
        var project = source.project();
        output = output.toAbsolutePath().normalize();
        if (Files.exists(output, NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());
        if (options.format == Format.PACKAGE || options.format == Format.PORTABLE
                || options.format == Format.MOD || options.format == Format.DISASM) {
            return preparePackage(project, options).write(output, progress);
        }
        if (options.format == Format.SMPS) {
            progress.report(0, 1, "Writing raw S3K track");
            Main.writeNewBinary(output, rawBytes(ProjectCompiler.compileWithAssets(project)));
            progress.report(1, 1, "Export complete");
            return List.of();
        }

        var playback = TargetPlayback.prepare(project);
        var compiled = playback.compiled();
        int length = source.frames() == null ? length(project, source.work()) : source.frames();
        NativeExportLength.Loop loop = null;
        if (compiled.nativeProgram() != null) {
            var nativeLength = NativeExportLength.measure(compiled, options.loops == null ? 1 : options.loops);
            if (!nativeLength.finished() && options.loops != null)
                throw new IllegalArgumentException("Native export could not complete the requested loop passes or reach the song end within ten minutes");
            loop = nativeLength.loop();
            length = nativeLength.end();
        } else if (options.loops != null) {
            throw new IllegalArgumentException("Loop count is available for native S3K banks");
        }
        S3kRenderer.validatePresentations(length);
        var range = options.start == null && options.end == null ? new WaveFile.Range(0, length)
                : WaveFile.Range.seconds(options.start == null ? 0 : options.start,
                                         options.end == null ? length / 60.0 : options.end);
        if (range.end() > length) throw new IllegalArgumentException("Span exceeds the export duration");
        int fadeSamples = options.fade == null ? 0 : (int) Math.round(options.fade * S3kRenderer.SAMPLE_RATE);
        if (fadeSamples > range.count() * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION)
            throw new IllegalArgumentException("Fade exceeds the export span");

        if (options.format.audio()) FfmpegAudio.checkAvailable();

        // Publish only completed files or a complete set of stems.
        var stage = Files.createTempDirectory(output.getParent(), ".project-export-");
        try {
            if (options.format == Format.VGM) {
                var vgm = stage.resolve("track.vgm");
                VgmFile.write(playback, project.name(), range.end(), loop, vgm, progress);
                publish(vgm, output);
            } else if (!options.stems) {
                var file = stage.resolve("track." + options.format.extension());
                audio(playback, file, range, fadeSamples, S3kRenderer.ChannelMask.NONE, options.format,
                        progress, 0, 1, options.format.name());
                publish(file, output);
            } else {
                var folder = Files.createDirectory(stage.resolve("stems"));
                var names = new ArrayList<String>();
                for (var stem : STEMS) {
                    String name = stem.name() + "." + options.format.extension();
                    audio(playback, folder.resolve(name), range, fadeSamples, stem.mask(), options.format,
                            progress, names.size(), STEMS.size(), stem.name());
                    names.add(name);
                }
                if (options.zip) {
                    var archive = stage.resolve("stems.zip");
                    zip(folder, names, archive);
                    publish(archive, output);
                } else {
                    publish(folder, output);
                }
            }
            int total = options.format == Format.VGM ? range.end() + 1
                    : audioProgressTotal(range, options.stems ? STEMS.size() : 1);
            progress.report(total, total, "Export complete");
        } finally {
            try (var paths = Files.walk(stage)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return List.of();
    }

    @FunctionalInterface
    private interface PackageOutput {
        void write(Path output) throws IOException;
    }

    /** A prepared export keeps the bytes checked and reviewed before publishing. */
    public static final class PreparedPackage {
        private final List<String> warnings;
        private final List<String> matchedStockAssets;
        private final PackageOutput output;

        private PreparedPackage(List<String> warnings, List<String> matchedStockAssets, PackageOutput output) {
            this.warnings = List.copyOf(warnings);
            this.matchedStockAssets = List.copyOf(matchedStockAssets);
            this.output = output;
        }

        public List<String> warnings() { return warnings; }
        public List<String> matchedStockAssets() { return matchedStockAssets; }

        public List<String> write(Path path, ReconstructionStages.Progress progress) throws IOException {
            progress.report(0, 1, "Writing export");
            output.write(path.toAbsolutePath().normalize());
            progress.report(1, 1, "Export complete");
            return warnings;
        }
    }

    public static PreparedPackage preparePackage(StudioProject project, Options options) throws IOException {
        if (options.format == Format.DISASM) {
            var settings = options.disasm == null ? new DisasmSettings(List.of(), false, List.of()) : options.disasm;
            var kit = DisassemblyKit.prepare(project, project.target().id(), settings.roms(),
                    settings.dpcmClosed(), settings.replaceDac());
            return new PreparedPackage(List.copyOf(kit.warnings), kit.matchedStockAssets, kit::publish);
        }
        var settings = options.portable == null ? PortableOptions.defaults(project.target()) : options.portable.options(project.target());
        if (options.format == Format.PACKAGE) {
            var warnings = new java.util.TreeSet<String>();
            var verification = new ExportStockCheck(PortablePackages.openExportRoms(settings.roms(), warnings::add));
            var resolved = verification.resolveProject(project);
            warnings.addAll(ProjectProvenance.detect(resolved).warnings());
            return new PreparedPackage(List.copyOf(warnings), verification.matches(), output -> ProjectPackage.write(resolved, output));
        }
        return preparePortable(options.format, settings, PortablePackageWriter.build(project, settings));
    }

    private static PreparedPackage preparePortable(Format format, PortableOptions options, PortablePackageWriter.Payload payload) {
        PackageOutput publisher;
        if (format == Format.MOD) {
            var entries = ModPackageWriter.build(payload, options.replacements());
            publisher = output -> PortableArchive.write(output, entries, null);
        } else if (format == Format.PORTABLE) {
            if (!options.replacements().isEmpty()) throw new IllegalArgumentException("--replace requires --format mod");
            publisher = output -> PortablePackageWriter.publish(payload, output);
        } else {
            throw new IllegalArgumentException("Expected a package export format");
        }
        return new PreparedPackage(payload.exportWarnings(), payload.matchedStockAssets(), publisher);
    }

    /** Provenance warnings for a default portable export, computed without writing a file. */
    public static List<String> portableWarnings(StudioProject project) throws IOException {
        return portableWarnings(project, null);
    }

    public static List<String> portableWarnings(StudioProject project, PortableSettings settings) throws IOException {
        return PortablePackageWriter.build(project, settings == null ? PortableOptions.defaults(project.target()) : settings.options(project.target())).exportWarnings();
    }

    public static String warningMessage(Format format, String warning) {
        return switch (warning) {
            case "DERIVED_FROM_RETAIL_SONG" -> ProjectProvenance.warningMessage(warning)
                    + " Do not redistribute without the rights to the composition.";
            case "SOURCE_AUDIO" -> "This export contains samples derived from an input recording. "
                    + "Do not redistribute without the rights to that recording.";
            case "UNDECLARED_STOCK_CONVERTED" -> DisassemblyKit.warningMessage(warning);
            default -> format == Format.DISASM ? DisassemblyKit.warningMessage(warning)
                    : ProjectProvenance.warningMessage(warning);
        };
    }

    public static com.google.gson.JsonObject exportPortable(StudioProject project, Path output, Options options,
                                                            ReconstructionStages.Progress progress) throws IOException {
        var settings = options.portable == null ? PortableOptions.defaults(project.target()) : options.portable.options(project.target());
        var payload = PortablePackageWriter.build(project, settings);
        preparePortable(options.format, settings, payload).write(output, progress);
        var result = new com.google.gson.JsonObject();
        var json = new com.google.gson.Gson();
        result.add("warnings", json.toJsonTree(payload.exportWarnings()));
        if (!payload.converted().isEmpty()) result.add("undeclaredStockConverted", json.toJsonTree(payload.converted()));
        return result;
    }

    private static void publish(Path staged, Path output) throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("Export cancelled");
        if (Files.isDirectory(staged, NOFOLLOW_LINKS)) {
            publishStems(staged, output);
            return;
        }
        var target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW);
        boolean complete = false;
        try {
            try (target) {
                Files.copy(staged, target);
            }
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(output);
        }
    }

    private static void publishStems(Path staged, Path output) throws IOException {
        List<Path> files;
        try (var contents = Files.list(staged)) {
            files = contents.toList();
        }
        Files.createDirectory(output);
        var published = new ArrayList<Path>();
        try {
            for (var file : files) {
                var target = output.resolve(file.getFileName());
                Files.move(file, target);
                published.add(target);
            }
        } catch (IOException failure) {
            try {
                for (var file : published) Files.deleteIfExists(file);
                Files.delete(output);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static int length(StudioProject project, Path work) throws IOException {
        if (work != null && !project.detachedFromReconstruction() && Files.isRegularFile(work.resolve("score.json"), NOFOLLOW_LINKS))
            return ReconstructionTimeMap.of(ReconstructionStages.readScore(work.resolve("score.json"))).frames();
        return ProjectRenderTiming.presentationCount(project);
    }

    static byte[] rawBytes(CompiledProject compiled) {
        if (compiled.nativeProgram() != null)
            throw new IllegalArgumentException("Use compile-package or export --format package to preserve native bank addresses and resources");
        if (compiled.requiresAssets())
            throw new IllegalArgumentException("Project declares envelope or DAC sample assets; use compile-package or export --format package");
        return S3kBinary.validateAndCopy(compiled.song());
    }

    private static Stem fm(String name, int channel) {
        return new Stem(name, new S3kRenderer.ChannelMask(0b111111 ^ (1 << channel), 0b1111));
    }

    private static Stem psg(String name, int channel) {
        return new Stem(name, new S3kRenderer.ChannelMask(0b111111, 0b1111 ^ (1 << channel)));
    }

    private static void audio(TargetPlayback playback, Path output, WaveFile.Range range, int fade,
                              S3kRenderer.ChannelMask mask, Format format, ReconstructionStages.Progress progress,
                              int index, int count, String name) throws IOException {
        var wav = output.resolveSibling(output.getFileName() + ".pcm.wav");
        render(playback, wav, range, fade, mask, progress, index, count, name);
        FfmpegAudio.encode(wav, output, format == Format.MP3);
        Files.delete(wav);
        progress.report((index + 1) * (range.end() + 1), audioProgressTotal(range, count), "Wrote " + name);
    }

    private static void render(TargetPlayback playback, Path output, WaveFile.Range range, int fade,
                               S3kRenderer.ChannelMask mask, ReconstructionStages.Progress progress,
                               int index, int count, String label) throws IOException {
        int total = audioProgressTotal(range, count);
        int offset = index * (range.end() + 1);
        int samples = range.count() * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        String message = "Rendering " + label;
        if (index == 0) progress.report(0, total, message);
        WaveFile.write(output, range.count(), sink -> {
            int[] frame = {0};
            playback.render(range.end(), mask, pcm -> {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("Export cancelled");
                int current = frame[0]++;
                if (current >= range.start()) {
                    if (fade != 0) fade(pcm, (current - range.start()) * pcm.length / 2, samples, fade);
                    sink.accept(pcm);
                }
                if (frame[0] % 30 == 0 || frame[0] == range.end())
                    progress.report(offset + frame[0], total, message);
            });
        });
    }

    private static int audioProgressTotal(WaveFile.Range range, int count) {
        return count * (range.end() + 1) + 1;
    }

    private static void fade(short[] pcm, int offset, int total, int fade) {
        for (int index = 0; index < pcm.length / 2; index++) {
            int remaining = total - 1 - offset - index;
            if (remaining >= fade) continue;
            double gain = remaining / (double) Math.max(1, fade - 1);
            pcm[index * 2] = (short) Math.round(pcm[index * 2] * gain);
            pcm[index * 2 + 1] = (short) Math.round(pcm[index * 2 + 1] * gain);
        }
    }

    private static void zip(Path folder, List<String> names, Path archive) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW))) {
            for (String name : names) {
                var entry = new ZipEntry(name);
                entry.setTime(0);
                zip.putNextEntry(entry);
                Files.copy(folder.resolve(name), zip);
                zip.closeEntry();
            }
        }
    }

    public static List<String> exportDisasm(Path input, Path output, List<Path> roms, boolean dpcmClosed,
                                            List<Integer> replaceDac, ReconstructionStages.Progress progress) throws IOException {
        return DisassemblyKit.write(input, output, roms, dpcmClosed, replaceDac, progress);
    }

    static void cli(String[] args, java.io.PrintStream out, java.io.PrintStream err) throws IOException {
        if (args.length < 5) throw new IllegalArgumentException("export expects INPUT OUTPUT --format FORMAT");
        String formatName = null;
        boolean dpcmClosed = false;
        var replaceDac = new ArrayList<Integer>();
        Double start = null, end = null, fade = null;
        Integer loops = null;
        boolean stems = false, zip = false;
        var seen = new HashSet<String>();
        var flags = new PortableOptions.Cli();
        for (int i = 3; i < args.length; i++) {
            if (flags.recognizes(args[i])) {
                i = flags.read(args, i) - 1;
                continue;
            }
            String option = args[i];
            if (option.equals("--replace-dac")) {
                if (++i == args.length || !args[i].matches("(?:0[xX]|\\$)?[0-9A-Fa-f]{2}"))
                    throw new IllegalArgumentException("--replace-dac requires a two-digit hexadecimal DAC note");
                replaceDac.add(Integer.parseInt(args[i].substring(args[i].length() - 2), 16));
                continue;
            }
            if (!seen.add(option)) throw new IllegalArgumentException("Duplicate option " + option);
            if (option.equals("--stems")) { stems = true; continue; }
            if (option.equals("--zip")) { zip = true; continue; }
            if (option.equals("--dpcm-closed")) { dpcmClosed = true; continue; }
            if (++i == args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[i];
            switch (option) {
                case "--format" -> formatName = value;
                case "--start" -> start = Double.valueOf(value);
                case "--end" -> end = Double.valueOf(value);
                case "--fade" -> fade = Double.valueOf(value);
                case "--loops" -> loops = Integer.valueOf(value);
                default -> throw new IllegalArgumentException("Unknown option " + option);
            }
        }
        if (formatName == null) throw new IllegalArgumentException("Choose --format wav|mp3|vgm|smps|package|portable|mod|disasm");
        if (formatName.equalsIgnoreCase("disasm")) {
            if (flags.hasPackageOptions() || start != null || end != null || fade != null || loops != null || stems || zip)
                throw new IllegalArgumentException("Disassembly export accepts --rom, --dpcm-closed and --replace-dac");
            var warnings = exportDisasm(Path.of(args[1]), Path.of(args[2]), flags.roms(), dpcmClosed, replaceDac,
                    (done, total, message) -> {});
            for (String code : warnings) err.println("Warning [" + code + "]: " + DisassemblyKit.warningMessage(code));
            out.println("Exported disassembly kit to " + args[2]);
            return;
        }
        if (dpcmClosed || !replaceDac.isEmpty())
            throw new IllegalArgumentException("--dpcm-closed and --replace-dac require --format disasm");
        var format = Format.valueOf(formatName.toUpperCase(Locale.ROOT));
        boolean portable = format == Format.PORTABLE || format == Format.MOD;
        if (flags.portable() || !portable && flags.hasPackageOptions())
            throw new IllegalArgumentException("Use --format portable or --format mod for portable package options");
        Path input = Path.of(args[1]);
        Path work = Files.isDirectory(input) ? input : null;
        if (work != null) input = work.resolve("arrangement.smps.json");
        Path output = Path.of(args[2]);
        var options = new Options(format, start, end, fade, loops, stems, zip);
        if (portable) {
            var project = ProjectFiles.loadSource(input);
            var settings = flags.options(project.target());
            var payload = PortablePackageWriter.build(project, settings);
            preparePortable(format, settings, payload).write(output, (done, total, message) -> {});
            PortablePackages.printWriteWarnings(payload, err);
        } else {
            var project = format == Format.PACKAGE ? ProjectFiles.loadSource(input)
                    : PortablePackages.openExportProject(input, flags.roms(),
                            warning -> ProjectProvenance.printWarnings(List.of(warning), err));
            var settings = new PortableSettings(null, true, null, null, null, List.of(), List.of(),
                    null, false, false, flags.roms());
            var configured = new Options(format, options.start(), options.end(), options.fade(), options.loops(),
                    options.stems(), options.zip(), settings);
            var warnings = write(project, work, output, configured, (done, total, message) -> {});
            ProjectProvenance.printWarnings(warnings, err);
        }
        out.println("Exported " + format + " to " + args[2]);
    }
}

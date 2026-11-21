package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;
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
        WAV("wav"), MP3("mp3"), VGM("vgm"), SMPS("bin"), PACKAGE("smps.zip"), PORTABLE("smps.zip"), MOD("jar");

        private final String extension;

        Format(String extension) { this.extension = extension; }
        public String extension() { return extension; }
        public boolean audio() { return this == WAV || this == MP3; }
    }

    public record Options(Format format, Double start, Double end, Double fade, Integer loops,
                          boolean stems, boolean zip, PortableSettings portable) {
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

    public record PortableSettings(String driver, boolean includeSource, String license, String id,
                                   String version, List<String> authors, List<Integer> replacements) {
        PortableOptions options() {
            return new PortableOptions(driver, includeSource, license, id, version, authors, replacements);
        }
    }

    public record Source(StudioProject project, Path work) {}

    private record Stem(String name, S3kRenderer.ChannelMask mask) {}

    private static final List<Stem> STEMS = List.of(
            fm("FM1", 0), fm("FM2", 1), fm("FM3", 2), fm("FM4", 3), fm("FM5", 4), fm("DAC", 5),
            psg("PSG1", 0), psg("PSG2", 1), psg("PSG3", 2), psg("Noise", 3));

    private ProjectExport() {}

    public static Source open(Path input) throws IOException {
        Path work = Files.isDirectory(input) ? input : null;
        return new Source(ProjectFiles.load(work == null ? input : work.resolve("arrangement.smps.json")).project(), work);
    }

    public static List<String> write(StudioProject project, Path work, Path output, Options options,
                             ReconstructionStages.Progress progress) throws IOException {
        output = output.toAbsolutePath().normalize();
        if (Files.exists(output, NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());
        if (options.format == Format.PACKAGE) {
            progress.report(0, 1, "Writing SMPS package");
            var warnings = ProjectPackage.write(project, output);
            progress.report(1, 1, "Export complete");
            return warnings;
        }
        if (options.format == Format.PORTABLE || options.format == Format.MOD) {
            var settings = options.portable == null ? PortableOptions.DEFAULT : options.portable.options();
            return writePortable(project, output, options.format, settings, progress).warnings();
        }
        var compiled = ProjectCompiler.compileWithAssets(project);
        if (options.format == Format.SMPS) {
            progress.report(0, 1, "Writing raw S3K track");
            Main.writeNewBinary(output, rawBytes(compiled));
            progress.report(1, 1, "Export complete");
            return List.of();
        }

        int length = length(project, work);
        NativeExportLength.Loop loop = null;
        if (compiled.nativeProgram() != null) {
            var playback = NativeExportLength.measure(compiled, options.loops == null ? 1 : options.loops);
            if (!playback.finished() && options.loops != null)
                throw new IllegalArgumentException("Native export could not complete the requested loop passes or reach the song end within ten minutes");
            loop = playback.loop();
            length = playback.end();
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
                VgmFile.write(compiled, project.name(), range.end(), loop, vgm, progress);
                publish(vgm, output);
            } else if (!options.stems) {
                var file = stage.resolve("track." + options.format.extension());
                audio(compiled, file, range, fadeSamples, S3kRenderer.ChannelMask.NONE, options.format,
                        progress, 0, 1, options.format.name());
                publish(file, output);
            } else {
                var folder = Files.createDirectory(stage.resolve("stems"));
                var names = new ArrayList<String>();
                for (var stem : STEMS) {
                    String name = stem.name() + "." + options.format.extension();
                    audio(compiled, folder.resolve(name), range, fadeSamples, stem.mask(), options.format,
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

    private static PortablePackageWriter.Payload writePortable(StudioProject project, Path output, Format format,
                                                                 PortableOptions options, ReconstructionStages.Progress progress)
            throws IOException {
        progress.report(0, 1, "Writing portable SMPS package");
        PortablePackageWriter.Payload payload;
        if (format == Format.MOD) {
            payload = PortablePackageWriter.build(project, options);
            ModPackageWriter.write(payload, output, options.replacements());
        } else {
            if (!options.replacements().isEmpty()) throw new IllegalArgumentException("--replace requires --format mod");
            payload = PortablePackageWriter.write(project, output, options);
        }
        progress.report(1, 1, "Export complete");
        return payload;
    }

    public static com.google.gson.JsonObject exportPortable(StudioProject project, Path output, Options options,
                                                            ReconstructionStages.Progress progress) throws IOException {
        var settings = options.portable == null ? PortableOptions.DEFAULT : options.portable.options();
        var payload = writePortable(project, output, options.format, settings, progress);
        var result = new com.google.gson.JsonObject();
        var json = new com.google.gson.Gson();
        result.add("warnings", json.toJsonTree(payload.warnings()));
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

    private static void audio(CompiledProject compiled, Path output, WaveFile.Range range, int fade,
                              S3kRenderer.ChannelMask mask, Format format, ReconstructionStages.Progress progress,
                              int index, int count, String name) throws IOException {
        var wav = output.resolveSibling(output.getFileName() + ".pcm.wav");
        render(compiled, wav, range, fade, mask, progress, index, count, name);
        FfmpegAudio.encode(wav, output, format == Format.MP3);
        Files.delete(wav);
        progress.report((index + 1) * (range.end() + 1), audioProgressTotal(range, count), "Wrote " + name);
    }

    private static void render(CompiledProject compiled, Path output, WaveFile.Range range, int fade,
                               S3kRenderer.ChannelMask mask, ReconstructionStages.Progress progress,
                               int index, int count, String label) throws IOException {
        int total = audioProgressTotal(range, count);
        int offset = index * (range.end() + 1);
        int samples = range.count() * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION;
        String message = "Rendering " + label;
        if (index == 0) progress.report(0, total, message);
        WaveFile.write(output, range.count(), sink -> {
            int[] frame = {0};
            S3kRenderer.render(compiled, range.end(), mask, pcm -> {
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

    static void cli(String[] args, java.io.PrintStream out, java.io.PrintStream err) throws IOException {
        if (args.length < 5) throw new IllegalArgumentException("export expects INPUT OUTPUT --format FORMAT");
        Format format = null;
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
            if (!seen.add(option)) throw new IllegalArgumentException("Duplicate option " + option);
            if (option.equals("--stems")) { stems = true; continue; }
            if (option.equals("--zip")) { zip = true; continue; }
            if (++i == args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[i];
            switch (option) {
                case "--format" -> format = Format.valueOf(value.toUpperCase(Locale.ROOT));
                case "--start" -> start = Double.valueOf(value);
                case "--end" -> end = Double.valueOf(value);
                case "--fade" -> fade = Double.valueOf(value);
                case "--loops" -> loops = Integer.valueOf(value);
                default -> throw new IllegalArgumentException("Unknown option " + option);
            }
        }
        if (format == null) throw new IllegalArgumentException("Choose --format wav|mp3|vgm|smps|package|portable|mod");
        boolean portable = format == Format.PORTABLE || format == Format.MOD;
        if (flags.portable() || !portable && flags.hasPackageOptions())
            throw new IllegalArgumentException("Use --format portable or --format mod for portable package options");
        Path input = Path.of(args[1]);
        Path work = Files.isDirectory(input) ? input : null;
        if (work != null) input = work.resolve("arrangement.smps.json");
        var project = PortablePackages.openProject(input, flags.roms());
        var options = new Options(format, start, end, fade, loops, stems, zip);
        Path output = Path.of(args[2]);
        if (portable) {
            var payload = writePortable(project, output, options.format(), flags.options(), (done, total, message) -> {});
            PortablePackages.printWriteWarnings(payload, err);
        } else {
            var warnings = write(project, work, output, options, (done, total, message) -> {});
            ProjectProvenance.printWarnings(warnings, err);
        }
        out.println("Exported " + format + " to " + args[2]);
    }
}

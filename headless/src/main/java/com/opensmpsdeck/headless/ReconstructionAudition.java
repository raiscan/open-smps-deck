package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.GroupArrangement;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import static com.opensmpsdeck.headless.S3kRenderer.*;

/** Ephemeral FM patch overlays on the last assembled arrangement. */
public final class ReconstructionAudition {
    private static final int BINS = 7;
    private static final int MAX_VARIANTS = 16;
    private static final int FRAME_SAMPLES = STEREO_FRAMES_PER_PRESENTATION * 2;

    public record Candidate(String variantId, String name, String output, List<Double> rms) {}
    public record Result(int startTick, int endTick, boolean solo, List<Double> referenceRms, List<Candidate> candidates) {}

    private ReconstructionAudition() {}

    static void cli(String[] args, PrintStream out) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("Expected audition WORK_DIR [options] OUT.wav");
        String lane = null;
        Double start = null, end = null, gain = null;
        Boolean solo = null;
        Path reference = null;
        var variants = new ArrayList<String>();
        for (int i = 1; i < args.length - 1; i++) {
            String key = args[i];
            if (key.equals("--solo") || key.equals("--in-mix")) {
                if (solo != null) throw new IllegalArgumentException("Choose --solo or --in-mix");
                solo = key.equals("--solo");
                continue;
            }
            if (i + 1 >= args.length - 1) throw new IllegalArgumentException("Missing value for " + key);
            String value = args[++i];
            switch (key) {
                case "--lane" -> lane = value;
                case "--start" -> start = Double.parseDouble(value);
                case "--end" -> end = Double.parseDouble(value);
                case "--variant" -> variants.add(value);
                case "--gain" -> gain = Double.parseDouble(value);
                case "--reference" -> reference = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown audition option " + key);
            }
        }
        if (start == null || end == null) throw new IllegalArgumentException("Expected --start and --end");
        var result = run(Path.of(args[0]), lane, start, end, variants, gain, Boolean.TRUE.equals(solo),
                reference, Path.of(args[args.length - 1]));
        out.printf(Locale.ROOT, "Ticks %d–%d, %d equal-width RMS bins (dBFS, before A/B matching)%n",
                result.startTick(), result.endTick(), Math.min(BINS, result.endTick() - result.startTick()));
        for (var candidate : result.candidates()) {
            out.println(candidate.name() + "  " + db(candidate.rms()) + "  " + candidate.output());
        }
        if (result.referenceRms() != null) out.println("Reference  " + db(result.referenceRms()));
    }

    private static List<String> db(List<Double> rms) {
        return rms.stream().map(value -> value == 0 ? "-inf" : String.format(Locale.ROOT, "%.1f", 20 * Math.log10(value))).toList();
    }

    public static Path outputPath(Path output, int index, int count) {
        String name = output.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".wav"))
            throw new IllegalArgumentException("Audition output must end in .wav");
        return count == 1 ? output : output.resolveSibling(name.substring(0, name.length() - 4) + "-" + (index + 1) + ".wav");
    }

    public static Result run(Path work, String lane, double startSeconds, double endSeconds, List<String> variants,
                             Double gain, boolean solo, Path reference, Path output) throws IOException {
        if (lane == null || !ReconstructionAllocation.FM.contains(lane))
            throw new IllegalArgumentException("Audition lane must be FM1–FM5");
        if (!(startSeconds >= 0 && endSeconds > startSeconds && endSeconds <= MAX_PRESENTATIONS / 60.0))
            throw new IllegalArgumentException("Expected 0 <= start < end <= 600 project seconds");
        if (gain != null && !(gain >= 0 && gain <= 4))
            throw new IllegalArgumentException("Audition gain must be in 0–4");
        if (variants.isEmpty() || variants.size() > MAX_VARIANTS || new HashSet<>(variants).size() != variants.size())
            throw new IllegalArgumentException("Expected 1–" + MAX_VARIANTS + " distinct variant IDs");
        int start = ReconstructionOverrides.tick(startSeconds), end = ReconstructionOverrides.tick(endSeconds);
        int length = end - start;
        if (length < 1 || length > 3600)
            throw new IllegalArgumentException("Audition must span 1 tick to 60 seconds");

        var report = ReconstructionArtifacts.readTree(work.resolve("report.json")).getAsJsonObject();
        if (end > report.get("presentations").getAsInt())
            throw new IllegalArgumentException("Audition ends after the assembled render");
        var project = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        if (project.nativeProgram() != null || project.arrangementVersion() < 4 || project.tempo() != 0)
            throw new IllegalArgumentException("Audition requires an editable reconstruction at 60 project ticks per second");
        var clips = project.formatVersion() == 6 ? GroupArrangement.flatten(project) : project.clips();
        if (clips.stream().noneMatch(clip -> lane.equals(clip.lane()) && overlaps(clip, start, end)))
            throw new IllegalArgumentException("No " + lane + " clip overlaps the audition range");
        var score = ReconstructionStages.readScore(work.resolve("score.json"));
        var catalogue = InstrumentCatalogue.open(Path.of(score.inputs().catalogue()));
        var matches = variants.stream().map(catalogue::match).toList();
        var outputs = new ArrayList<Path>();
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).asset().kind() != InstrumentCatalogue.Kind.FM_VOICE)
                throw new IllegalArgumentException("Expected an FM variant: " + variants.get(i));
            var file = outputPath(output, i, matches.size());
            if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new java.nio.file.FileAlreadyExistsException(file.toString());
            outputs.add(file);
        }

        var banks = matches.stream().map(match -> ProjectCompiler.compileWithAssets(
                overlay(project, clips, lane, start, end, match.asset(), gain))).toList();
        float[] referencePcm = null;
        if (reference != null) {
            if (ReconstructionStages.usesSteadyReference(work.resolve("report.json"), reference))
                reference = work.resolve(ReconstructionStages.STEADY_REFERENCE);
            referencePcm = ReferenceAudio.stereoRange(reference, SAMPLE_RATE,
                    (long) start * STEREO_FRAMES_PER_PRESENTATION, length * STEREO_FRAMES_PER_PRESENTATION);
        }
        var results = new ArrayList<Candidate>();
        int fmBit = 1 << ReconstructionAllocation.FM.indexOf(lane);
        var mask = solo ? new ChannelMask(63 ^ fmBit, 15) : ChannelMask.NONE;
        for (int i = 0; i < matches.size(); i++) {
            var match = matches.get(i);
            var pcm = new float[length * FRAME_SAMPLES];
            int[] tick = {0};
            S3kRenderer.render(banks.get(i), end, mask, samples -> {
                int t = tick[0]++ - start;
                if (t >= 0) for (int sample = 0; sample < samples.length; sample++)
                    pcm[t * FRAME_SAMPLES + sample] = samples[sample] / 32768f;
            });
            PhraseAudio.write(outputs.get(i), length, pcm, referencePcm);
            results.add(new Candidate(match.variant().id(), match.variant().name(), outputs.get(i).toString(), envelope(pcm)));
        }
        return new Result(start, end, solo, referencePcm == null ? null : envelope(referencePcm), List.copyOf(results));
    }

    private static boolean overlaps(Clip clip, int start, int end) {
        return clip.startTick() < end && (long) clip.startTick() + clip.lengthTicks() > start;
    }

    static StudioProject overlay(StudioProject project, List<Clip> clips, String lane, int start, int end,
                                 InstrumentCatalogue.Asset asset, Double gain) {
        var sequences = new ArrayList<>(project.sequences());
        var sources = new HashMap<String, Sequence>();
        for (var source : sequences) sources.put(source.id(), source);
        var voices = new ArrayList<>(project.voices());
        var voiceById = new HashMap<String, Voice>();
        for (var voice : voices) voiceById.put(voice.id(), voice);
        var placements = new ArrayList<Clip>();
        byte[] bytes = asset.bytes();
        var patch = new ArrayList<Integer>(bytes.length);
        for (byte value : bytes) patch.add(Byte.toUnsignedInt(value));
        int volumeFloor = ReconstructionEcho.fmVolumeFloor(bytes);
        var outputVoices = new HashMap<List<Integer>, String>();
        for (var voice : voices) outputVoices.putIfAbsent(voice.bytes(), voice.id());
        for (var clip : clips) {
            if (!lane.equals(clip.lane()) || !overlaps(clip, start, end)) {
                placements.add(clip);
                continue;
            }
            var source = sources.get(clip.sequenceId());
            if (clip.lengthTicks() > source.lengthTicks() && !source.repeatState().equals("RESET"))
                throw new IllegalArgumentException("Audition cannot isolate a CARRY repeat in " + clip.id());
            // Only overlapping instances are unlinked. RESET repetitions retain their
            // original reset points, with the same source before and after the phrase.
            for (int offset = 0; offset < clip.lengthTicks();) {
                int length = Math.min(source.lengthTicks(), clip.lengthTicks() - offset);
                int tick = clip.startTick() + offset;
                int from = Math.max(0, start - tick), to = Math.min(length, end - tick);
                String id = clip.id() + "-audition-" + offset;
                String sequence = source.id();
                if (from < to) {
                    sequence = uniqueSource(sources, sequences.size());
                    var changed = overlaySource(source, sequence, from, to, length, patch, asset.name(), gain, volumeFloor,
                            voices, voiceById, outputVoices);
                    sequences.add(changed);
                    sources.put(sequence, changed);
                }
                placements.add(new Clip(id, sequence, lane, tick, length));
                offset += length;
            }
        }
        return new StudioProject(project.arrangementVersion(), project.name(), project.mode(), project.ticksPerBeat(),
                project.tempo(), voices, sequences, placements, project.envelopes(), project.samples());
    }

    private static String uniqueSource(HashMap<String, Sequence> sources, int index) {
        while (sources.containsKey("audition-source-" + index)) index++;
        return "audition-source-" + index;
    }

    private static Sequence overlaySource(Sequence source, String id, int from, int to, int clipLength,
                                          List<Integer> patch, String name, Double gain, int volumeFloor, List<Voice> voices,
                                          HashMap<String, Voice> voiceById, HashMap<List<Integer>, String> outputVoices) {
        var timeline = new TreeMap<Integer, List<CommandEvent>>();
        for (var command : source.commands())
            timeline.computeIfAbsent(command.tick(), ignored -> new ArrayList<>()).add(command);
        timeline.putIfAbsent(from, List.of());
        if (to < clipLength) timeline.putIfAbsent(to, List.of());
        String basePatch = source.voiceId();
        int baseVolume = source.volume();
        String activePatch = basePatch;
        int activeVolume = baseVolume;
        var commands = new ArrayList<CommandEvent>();
        for (var entry : timeline.entrySet()) {
            int tick = entry.getKey();
            if (tick >= clipLength) break;
            boolean candidate = tick >= from && tick < to;
            for (var command : entry.getValue()) {
                switch (command.command()) {
                    case FM_VOICE -> {
                        basePatch = command.assetId();
                        if (!candidate) {
                            commands.add(command);
                            activePatch = basePatch;
                        }
                    }
                    case VOLUME -> {
                        baseVolume = command.arguments().getFirst() & 127;
                        if (!candidate || gain == null) {
                            commands.add(command);
                            activeVolume = baseVolume;
                        }
                    }
                    case FM_VOLUME -> {
                        baseVolume = adjustVolume(baseVolume, command);
                        if (!candidate || gain == null) {
                            activeVolume = adjustVolume(activeVolume, command);
                            commands.add(command);
                        }
                    }
                    default -> commands.add(command);
                }
            }
            String desiredPatch = candidate ? auditionVoice(patch, name, voices, voiceById, outputVoices) : basePatch;
            int desiredVolume = candidate && gain != null
                    ? Math.max(volumeFloor, ReconstructionEcho.scaleVolume(baseVolume, gain)) : baseVolume;
            if (!desiredPatch.equals(activePatch)) {
                commands.add(new CommandEvent("audition-voice-" + tick, tick, NativeCommand.FM_VOICE, List.of(), desiredPatch));
                activePatch = desiredPatch;
            }
            if (desiredVolume != activeVolume) {
                commands.add(new CommandEvent("audition-volume-" + tick, tick, NativeCommand.VOLUME, List.of(desiredVolume)));
                activeVolume = desiredVolume;
            }
        }
        return new Sequence(id, source.name(), source.type(), source.lengthTicks(), source.voiceId(), source.volume(),
                source.notes(), commands, source.repeatState(), source.psgEnvelopeId(), source.modulationEnvelopeId());
    }

    private static int adjustVolume(int volume, CommandEvent command) {
        return 127 - Math.clamp(127 - volume + (byte) (int) command.arguments().getFirst(), 0, 127);
    }

    private static String auditionVoice(List<Integer> patch, String name, List<Voice> voices,
                                        HashMap<String, Voice> voiceById, HashMap<List<Integer>, String> outputVoices) {
        return outputVoices.computeIfAbsent(patch, key -> {
            if (voices.size() >= 128)
                throw new IllegalArgumentException("Audition requires an additional local FM voice (S3K limit is 128)");
            int suffix = voices.size();
            while (voiceById.containsKey("audition-voice-" + suffix)) suffix++;
            var voice = new Voice("audition-voice-" + suffix, name, key);
            voices.add(voice);
            voiceById.put(voice.id(), voice);
            return voice.id();
        });
    }

    private static List<Double> envelope(float[] pcm) {
        int frames = pcm.length / FRAME_SAMPLES;
        int bins = Math.min(BINS, frames);
        var result = new ArrayList<Double>();
        for (int i = 0; i < bins; i++) {
            int from = i * frames / bins * FRAME_SAMPLES, to = (i + 1) * frames / bins * FRAME_SAMPLES;
            result.add(PhraseAudio.rms(pcm, from, to));
        }
        return List.copyOf(result);
    }
}

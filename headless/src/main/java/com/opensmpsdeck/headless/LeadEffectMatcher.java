package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fits native pan and pitch movement to a lead and its existing FM double. */
final class LeadEffectMatcher {
    private static final int RATE = 12_000;
    private static final int FRAME_RATE = S3kRenderer.PRESENTATIONS_PER_SECOND;
    private static final int[] FNUM = {644,683,723,766,813,860,911,965,1023,1084,1148,1216};
    static int fmFnum(int pitch) { return FNUM[pitch % 12]; }

    private static final int BINS = 4096;
    private static final int FIT_FRAME_CAP = 60 * FRAME_RATE;
    private static final double IMPROVEMENT = .008;

    private enum Pan { CENTER, OPPOSITE, ONE_SIDE }
    private record Profile(String name, Pan pan, int offset, int depth, boolean quiet, boolean synchronous) {}
    private static final List<Profile> PROFILES = List.of(
            new Profile("stereo-pan", Pan.OPPOSITE, 0, 0, false, false),
            new Profile("stereo-double", Pan.OPPOSITE, 8, 0, false, false),
            new Profile("chorus-light", Pan.OPPOSITE, 4, 4, false, false),
            new Profile("chorus-medium", Pan.OPPOSITE, 9, 7, false, false),
            new Profile("chorus-heavy", Pan.OPPOSITE, 14, 10, false, false),
            new Profile("chorus-center-plus-side", Pan.ONE_SIDE, 9, 7, false, false),
            new Profile("stereo-low-blend", Pan.ONE_SIDE, 0, 0, true, false),
            new Profile("double-low-blend", Pan.ONE_SIDE, 6, 0, true, false),
            new Profile("chorus-low-blend", Pan.ONE_SIDE, 6, 5, true, false),
            new Profile("chorus-centered", Pan.CENTER, 9, 7, false, false),
            new Profile("vibrato", Pan.CENTER, 0, 13, false, true));

    record Score(String profile, double fit, double check) {}
    record Report(String status, String selected, int fitNotes, int checkNotes, List<Score> scores) {}
    record Result(StudioProject project, Report report) {}
    private record Lane(Sequence sequence, Clip clip) {}
    private record Band(int low, int high, double frequency) {}
    private record Probe(int tick, int sample, List<Band> bands) {}
    private record Reading(int tick, double energy, double side, double balance, double split, double drift) {}
    private record Partition(List<Integer> fit, List<Integer> check, int fitNotes, int checkNotes) {}

    private LeadEffectMatcher() {}

    static Result match(StudioProject project, Path reference, String leadId, String doubleId) throws IOException {
        var lanes = pair(project, leadId, doubleId);
        int frames = (int) Math.min(Math.min(FIT_FRAME_CAP, ProjectRenderTiming.presentationCount(project)),
                ReferenceAudio.durationMicros(reference) * FRAME_RATE / 1_000_000L);
        if (frames < FRAME_RATE) return unchanged(project, "insufficient-reference");
        float[] source = ReferenceAudio.stereoPrefix(reference, S3kRenderer.SAMPLE_RATE,
                frames * S3kRenderer.STEREO_FRAMES_PER_PRESENTATION);
        if (source == null) return unchanged(project, "stereo-reference-required");
        var downsampled = new float[source.length / 4];
        for (int i = 0; i < downsampled.length; i += 2)
            for (int j = 0; j < 4; j++) {
                downsampled[i] += source[4*i + 2*j] / 4;
                downsampled[i+1] += source[4*i + 2*j+1] / 4;
            }
        var probes = probes(project, lanes, frames);
        var observed = read(downsampled, probes);
        var partition = partition(observed);
        if (partition.fitNotes() < 6 || partition.checkNotes() < 6)
            return new Result(project, new Report("insufficient-paired-notes", "baseline",
                    partition.fitNotes(), partition.checkNotes(), List.of()));

        var fit = describe(observed, partition.fit());
        var check = describe(observed, partition.check());
        var baseline = score("baseline", read(render(ProjectCompiler.compileWithAssets(project), frames), probes),
                partition, fit, check);
        var scores = new ArrayList<Score>();
        scores.add(baseline);
        Score best = baseline;
        StudioProject chosen = project;
        for (var profile : PROFILES) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Effect matching interrupted");
            StudioProject trial = apply(project, lanes, profile);
            com.opensmpsdeck.project.CompiledProject compiled;
            try {
                compiled = ProjectCompiler.compileWithAssets(trial);
            } catch (ProjectCompiler.BankLimitException overflow) {
                continue;
            }
            var score = score(profile.name(), read(render(compiled, frames), probes), partition, fit, check);
            scores.add(score);
            if (score.fit() < best.fit() && score.fit() + IMPROVEMENT < baseline.fit()
                    && score.check() + IMPROVEMENT < baseline.check()) {
                best = score;
                chosen = trial;
            }
        }
        return new Result(chosen, new Report(chosen == project ? "no-supported-change" : "matched",
                best.profile(), partition.fitNotes(), partition.checkNotes(), List.copyOf(scores)));
    }

    private static Result unchanged(StudioProject project, String status) {
        return new Result(project, new Report(status, "baseline", 0, 0, List.of()));
    }

    static StudioProject candidate(StudioProject project, String leadId, String doubleId, String name) {
        var lanes = pair(project, leadId, doubleId);
        if (name.equals("baseline")) return project;
        var profile = PROFILES.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
        return apply(project, lanes, profile);
    }

    private static List<Lane> pair(StudioProject project, String leadId, String doubleId) {
        if (project.nativeProgram() != null || project.tempo() != 0 || leadId.equals(doubleId))
            throw new IllegalArgumentException("Effect matching requires an editable tempo-zero project and two distinct FM sequences");
        var pair = List.of(lane(project, leadId), lane(project, doubleId));
        if (pair.getFirst().clip().startTick() != pair.getLast().clip().startTick()
                || pair.getFirst().clip().lane().equals(pair.getLast().clip().lane()))
            throw new IllegalArgumentException("Lead and double must start together on separate FM lanes");
        return pair;
    }

    private static Lane lane(StudioProject project, String id) {
        var sequence = project.sequences().stream().filter(s -> s.id().equals(id) && s.type().equals("FM"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown FM sequence: " + id));
        var clips = project.clips().stream().filter(c -> c.sequenceId().equals(id)).toList();
        if (clips.size() != 1 || clips.getFirst().lengthTicks() != sequence.lengthTicks())
            throw new IllegalArgumentException("Effect matching requires one untrimmed clip per lead sequence: " + id);
        if (sequence.commands().stream().anyMatch(c -> switch (c.command()) {
            case PAN, DETUNE, TRANSPOSE, TRANSPOSE_ADD, FM_VOLUME, FM_VOLUME_ENVELOPE -> true;
            default -> false;
        })) throw new IllegalArgumentException("Lead sequence already has pan, detune, transpose or relative/envelope volume: " + id);
        return new Lane(sequence, clips.getFirst());
    }

    private static StudioProject apply(StudioProject project, List<Lane> lanes, Profile profile) {
        var sequences = new ArrayList<>(project.sequences());
        for (int side = 0; side < lanes.size(); side++) {
            Lane lane = lanes.get(side);
            Sequence s = lane.sequence();
            int pan = switch (profile.pan()) {
                case CENTER -> 0xc0;
                case OPPOSITE -> side == 0 ? 0x80 : 0x40;
                case ONE_SIDE -> side == 0 ? 0xc0 : 0x40;
            };
            int attenuation = side == 1 && profile.quiet() ? 16 : 0;
            int minVolume = quietFloor(project, s);
            int volume = attenuate(s.volume(), minVolume, attenuation);
            var commands = new ArrayList<CommandEvent>();
            for (var c : s.commands()) {
                if (c.command() == NativeCommand.VOLUME && attenuation != 0)
                    commands.add(new CommandEvent(c.id(), c.tick(), c.command(),
                            List.of(attenuate(c.arguments().getFirst(), minVolume, attenuation))));
                else commands.add(c);
            }
            // Prefix is chosen against all existing command IDs so an authored
            // command cannot collide with a generated source entity.
            String prefix = "matched-effect-";
            while (startsWith(commands, prefix)) prefix += "_";
            commands.add(new CommandEvent(prefix + "pan", 0, NativeCommand.PAN, List.of(pan)));
            int previous = 0;
            boolean firstCurve = side == 0 || profile.synchronous();
            double rate = firstCurve ? .82 : 1.09;
            double phase = firstCurve ? .15 : 1.1;
            for (var note : s.notes()) {
                for (int tick = note.tick(); tick < note.tick() + note.duration(); tick += 2) {
                    double seconds = (lane.clip().startTick() + tick) / (double) FRAME_RATE;
                    int direction = side == 0 && !profile.synchronous() ? -1 : 1;
                    double cents = direction * (profile.offset() + profile.depth() * Math.sin(Math.TAU * rate * seconds + phase));
                    int delta = (int) Math.round(fmFnum(note.pitch()) * (Math.pow(2, cents / 1200) - 1));
                    if (delta == previous) continue;
                    commands.add(new CommandEvent(prefix + "detune-" + tick, tick, NativeCommand.DETUNE, List.of(delta & 255)));
                    previous = delta;
                }
            }
            commands.sort(Comparator.comparingInt(CommandEvent::tick));
            sequences.set(sequences.indexOf(s), new Sequence(s.id(), s.name(), s.type(), s.lengthTicks(), s.voiceId(),
                    volume, s.notes(), commands, s.repeatState(), s.psgEnvelopeId(), s.modulationEnvelopeId(), s.triggers()));
        }
        return new StudioProject(Math.max(2, project.formatVersion()), project.name(), project.mode(), project.ticksPerBeat(),
                project.tempo(), project.voices(), sequences, project.clips(), project.envelopes(), project.samples()).withTarget(project.target());
    }

    private static boolean startsWith(List<CommandEvent> commands, String prefix) {
        return commands.stream().anyMatch(c -> c.id().startsWith(prefix));
    }

    private static int quietFloor(StudioProject project, Sequence sequence) {
        return project.voices().stream().filter(v -> v.id().equals(sequence.voiceId()) ||
                sequence.commands().stream().anyMatch(c -> c.command() == NativeCommand.FM_VOICE && v.id().equals(c.assetId())))
                .flatMapToInt(v -> v.bytes().subList(21, 25).stream().filter(x -> (x & 128) != 0)
                        .mapToInt(x -> x & 127)).max().orElse(0);
    }

    private static int attenuate(int volume, int floor, int attenuation) {
        return attenuation == 0 ? volume : Math.max(floor, (volume & 127) - attenuation);
    }

    private static List<Probe> probes(StudioProject project, List<Lane> lanes, int frames) {
        var lead = lanes.getFirst();
        var doubleNotes = new java.util.HashMap<Integer, NoteEvent>();
        for (var note : lanes.getLast().sequence().notes()) doubleNotes.put(note.tick(), note);
        var byId = new java.util.HashMap<String, Sequence>();
        for (var s : project.sequences()) byId.put(s.id(), s);
        var backing = project.clips().stream().filter(c -> lanes.stream().noneMatch(l -> l.clip().equals(c)))
                .filter(c -> !byId.get(c.sequenceId()).notes().isEmpty()).toList();
        var candidates = new ArrayList<Probe>();
        for (var note : lead.sequence().notes()) {
            var twin = doubleNotes.get(note.tick());
            if (twin == null || twin.pitch() != note.pitch()) continue;
            int duration = Math.min(note.duration(), twin.duration());
            if (duration < 7) continue;
            double center = lead.clip().startTick() + note.tick() + duration / 2.0;
            if (center < 5 || center >= frames - 5) continue;
            var occupied = occupiedPitches(backing, byId, center);
            double fundamental = frequency(note.pitch());
            var bands = new ArrayList<Band>();
            for (int h = 1; h <= 6; h++) {
                double hz = fundamental * h;
                double width = Math.max(23, hz * .027);
                if (hz < 480 || hz >= 4400 || collides(occupied, hz, width)) continue;
                bands.add(new Band((int) Math.ceil((hz - width) * BINS / RATE),
                        (int) Math.floor((hz + width) * BINS / RATE), hz));
            }
            if (!bands.isEmpty()) candidates.add(new Probe(lead.clip().startTick() + note.tick(),
                    (int) Math.round(center * RATE / FRAME_RATE), List.copyOf(bands)));
        }
        if (candidates.size() <= 192) return candidates;
        var bounded = new ArrayList<Probe>();
        for (int i = 0; i < 192; i++) bounded.add(candidates.get(i * candidates.size() / 192));
        return bounded;
    }

    private static List<Integer> occupiedPitches(List<Clip> clips, java.util.Map<String, Sequence> byId, double tick) {
        var pitches = new ArrayList<Integer>();
        for (var clip : clips) {
            double phase = tick - clip.startTick();
            if (phase < 0 || phase >= clip.lengthTicks()) continue;
            var sequence = byId.get(clip.sequenceId());
            double offset = phase % sequence.lengthTicks();
            for (var note : sequence.notes()) {
                if (note.tick() > offset + 2) break;
                if (note.tick() + note.duration() >= offset - 2) pitches.add(note.pitch());
            }
        }
        return pitches;
    }

    private static boolean collides(List<Integer> pitches, double target, double width) {
        for (int pitch : pitches) {
            double fundamental = frequency(pitch);
            long harmonic = Math.round(target / fundamental);
            if (harmonic >= 1 && harmonic <= 4 && Math.abs(target - harmonic * fundamental) < width + 12) return true;
        }
        return false;
    }

    private static double frequency(int midi) {
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }

    private static float[] render(com.opensmpsdeck.project.CompiledProject compiled, int frames) throws IOException {
        var audio = new float[frames * (RATE / FRAME_RATE) * 2];
        int[] cursor = {0};
        S3kRenderer.render(compiled, frames, pcm -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Effect rendering interrupted");
            for (int i = 0; i < pcm.length; i += 8) {
                audio[cursor[0]++] = (pcm[i] + pcm[i+2] + pcm[i+4] + pcm[i+6]) / 131072f;
                audio[cursor[0]++] = (pcm[i+1] + pcm[i+3] + pcm[i+5] + pcm[i+7]) / 131072f;
            }
        });
        return audio;
    }

    private static List<Reading> read(float[] audio, List<Probe> probes) throws IOException {
        var features = new ArrayList<Reading>();
        var left = new Spectrum();
        var right = new Spectrum();
        var earlyLeft = new Spectrum();
        var earlyRight = new Spectrum();
        var lateLeft = new Spectrum();
        var lateRight = new Spectrum();
        for (var p : probes) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Effect analysis interrupted");
            left.transform(audio, 0, p.sample(), 1152);
            right.transform(audio, 1, p.sample(), 1152);
            earlyLeft.transform(audio, 0, p.sample() - 210, 720);
            earlyRight.transform(audio, 1, p.sample() - 210, 720);
            lateLeft.transform(audio, 0, p.sample() + 210, 720);
            lateRight.transform(audio, 1, p.sample() + 210, 720);
            for (var band : p.bands()) {
                double l = 0, r = 0, cross = 0, lCenter = 0, rCenter = 0;
                for (int b = band.low(); b <= band.high(); b++) {
                    double lp = left.power(b), rp = right.power(b);
                    l += lp;
                    r += rp;
                    lCenter += lp * b;
                    rCenter += rp * b;
                    cross += left.real[b] * right.real[b] + left.imag[b] * right.imag[b];
                }
                double total = l + r;
                double split = l > 1e-12 && r > 1e-12 ? Math.abs(lCenter / l - rCenter / r) : 0;
                double drift = Math.abs(centroid(band, earlyLeft, earlyRight) - centroid(band, lateLeft, lateRight));
                double cents = 1200 * RATE / (BINS * band.frequency() * Math.log(2));
                features.add(new Reading(p.tick(), total, total > 1e-12 ? (total - 2 * cross) / (2 * total) : 0,
                        total > 1e-12 ? Math.abs(l - r) / total : 0,
                        Math.min(40, split * cents) / 40, Math.min(30, drift * cents) / 30));
            }
        }
        return features;
    }

    private static double centroid(Band band, Spectrum left, Spectrum right) {
        double total = 0, weighted = 0;
        for (int b = band.low(); b <= band.high(); b++) {
            double power = left.power(b) + right.power(b);
            total += power;
            weighted += b * power;
        }
        return total > 1e-12 ? weighted / total : (band.low() + band.high()) / 2.0;
    }

    private static Partition partition(List<Reading> source) {
        double[] energy = source.stream().filter(r -> r.tick() / 240 % 2 == 0).mapToDouble(Reading::energy).sorted().toArray();
        if (energy.length == 0) return new Partition(List.of(), List.of(), 0, 0);
        double threshold = Math.max(1e-9, energy[(int) (energy.length * .3)]);
        var fit = new ArrayList<Integer>();
        var check = new ArrayList<Integer>();
        var fitNotes = new java.util.HashSet<Integer>();
        var checkNotes = new java.util.HashSet<Integer>();
        for (int i = 0; i < source.size(); i++) {
            var r = source.get(i);
            if (r.energy() < threshold) continue;
            if (r.tick() / 240 % 2 == 0) {
                fit.add(i);
                fitNotes.add(r.tick());
            } else {
                check.add(i);
                checkNotes.add(r.tick());
            }
        }
        return new Partition(fit, check, fitNotes.size(), checkNotes.size());
    }

    private static double[] describe(List<Reading> features, List<Integer> indices) {
        var samples = indices.stream().map(features::get).toList();
        return new double[]{
                median(samples.stream().mapToDouble(Reading::side).sorted().toArray()),
                median(samples.stream().mapToDouble(Reading::balance).sorted().toArray()),
                median(samples.stream().mapToDouble(Reading::split).sorted().toArray()),
                median(samples.stream().mapToDouble(Reading::drift).sorted().toArray())};
    }

    private static double median(double[] values) {
        return (values[(values.length - 1) / 2] + values[values.length / 2]) / 2;
    }

    private static Score score(String name, List<Reading> features, Partition partition, double[] fit, double[] check) {
        return new Score(name, distance(fit, describe(features, partition.fit())),
                distance(check, describe(features, partition.check())));
    }

    private static double distance(double[] a, double[] b) {
        return .46 * Math.abs(a[0] - b[0]) + .12 * Math.abs(a[1] - b[1])
                + .25 * Math.abs(a[2] - b[2]) + .17 * Math.abs(a[3] - b[3]);
    }

    /** Reusable radix-2 transform for short, zero-padded stereo windows. */
    static final class Spectrum {
        private static final SpectrumPlan MATCHER_PLAN = new SpectrumPlan(BINS);
        private final SpectrumPlan plan;
        final double[] real;
        final double[] imag;

        Spectrum() {
            this(MATCHER_PLAN);
        }

        Spectrum(int bins) {
            this(new SpectrumPlan(bins));
        }

        private Spectrum(SpectrumPlan plan) {
            this.plan = plan;
            this.real = new double[plan.size];
            this.imag = new double[plan.size];
        }

        private static final class SpectrumPlan {
            final int size;
            final int shift;
            final double[] cos;
            final double[] sin;

            SpectrumPlan(int size) {
                this.size = size;
                this.shift = 32 - Integer.numberOfTrailingZeros(size);
                this.cos = new double[size / 2];
                this.sin = new double[size / 2];
                for (int i = 0; i < cos.length; i++) {
                    cos[i] = Math.cos(Math.TAU * i / size);
                    sin[i] = -Math.sin(Math.TAU * i / size);
                }
            }
        }

        void transform(float[] audio, int channel, int center, int length) {
            java.util.Arrays.fill(real, 0);
            java.util.Arrays.fill(imag, 0);
            for (int i = 0; i < length; i++) {
                double weight = .5 - .5 * Math.cos(Math.TAU * i / (length - 1));
                real[Integer.reverse(i) >>> plan.shift] = audio[2 * (center - length / 2 + i) + channel] * weight;
            }
            fft();
        }

        void transform(float[] mono, int start, double[] window) {
            java.util.Arrays.fill(real, 0);
            java.util.Arrays.fill(imag, 0);
            for (int i = Math.max(0, -start); i < Math.min(window.length, mono.length - start); i++)
                real[Integer.reverse(i) >>> plan.shift] = mono[start + i] * window[i];
            fft();
        }

        private void fft() {
            for (int width = 2; width <= plan.size; width *= 2) {
                int half = width / 2;
                int stride = plan.size / width;
                for (int i = 0; i < plan.size; i += width) {
                    for (int offset = 0; offset < half; offset++) {
                        int a = i + offset, b = a + half, k = offset * stride;
                        double re = real[b] * plan.cos[k] - imag[b] * plan.sin[k];
                        double im = imag[b] * plan.cos[k] + real[b] * plan.sin[k];
                        real[b] = real[a] - re;
                        imag[b] = imag[a] - im;
                        real[a] += re;
                        imag[a] += im;
                    }
                }
            }
        }

        double power(int i) {
            return real[i] * real[i] + imag[i] * imag[i];
        }
    }
}

package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.CompiledProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;
import static com.opensmpsdeck.headless.VoiceSpectra.SAMPLES_PER_TICK;

/** Disjoint excerpts packed into short renders, with chip pre-roll at each boundary. */
final class VoiceWindows {
    private static final int MAX_WINDOWS = 8;
    private static final int WINDOW_TICKS = 36;
    private static final int HISTORY = 12;
    private static final int SEPARATION = 10;
    private static final int DECIMATE = S3kRenderer.SAMPLE_RATE / VoiceSpectra.RATE;

    record Window(int start, int length, int renderStart, boolean check) {}
    record Reference(float[][] features, float[][] weights) {}

    private final List<Window> windows;
    private final int frames;
    private final int songFrames;
    private final VoiceSpectra spectra = new VoiceSpectra();

    VoiceWindows(int songFrames, Map<String, List<ReconstructionAllocation.Entry>> lanes) {
        this.songFrames = songFrames;
        int count = Math.clamp(songFrames / 12, 1, MAX_WINDOWS);
        int length = Math.min(WINDOW_TICKS, songFrames / count);
        var activity = new int[songFrames];
        for (var lane : lanes.values()) {
            for (var entry : lane) {
                for (int tick = entry.startTick(); tick < entry.endTick(); tick++) activity[tick]++;
            }
        }
        int packed = 0;
        var chosen = new ArrayList<Window>();
        for (int i = 0; i < count; i++) {
            int low = i * songFrames / count, high = (i + 1) * songFrames / count - length;
            int start = low + (high - low) / 2;
            int best = -1;
            // Prefer windows with audible material over sparsely occupied edges.
            for (int candidate = low; candidate <= high; candidate += Math.max(1, length / 3)) {
                int active = Arrays.stream(activity, candidate, candidate + length).sum();
                if (active > best || active == best && Math.abs(candidate - (low + high) / 2)
                        < Math.abs(start - (low + high) / 2)) {
                    best = active;
                    start = candidate;
                }
            }
            int preroll = Math.min(start, HISTORY);
            packed += preroll;
            chosen.add(new Window(start, length, packed, i % 2 == 1));
            packed += length + SEPARATION;
        }
        frames = packed - SEPARATION;
        // A single cut point keeps fit/check operations deterministic and cache-friendly.
        windows = chosen.stream().sorted(java.util.Comparator.comparing(Window::check)).toList();
    }

    int songFrames() { return songFrames; }

    boolean trainingAttack(int tick, int span) {
        return windows.stream().noneMatch(w -> w.check() && tick - span < w.start() + w.length()
                && tick + span > w.start());
    }

    ReconstructionAllocation.Result pack(List<ReconstructionAllocation.Entry> notes) {
        var result = new ArrayList<ReconstructionAllocation.Entry>();
        for (var window : windows) {
            int from = Math.max(0, window.start() - HISTORY);
            int to = window.start() + window.length();
            for (var note : notes) {
                if (note.startTick() >= to) break;
                if (note.endTick() <= from || note.lane().equals("DAC") && note.startTick() < from) continue;
                int shift = window.renderStart() - window.start();
                result.add(new ReconstructionAllocation.Entry(note.note(), note.role(), note.lane(),
                        Math.max(from, note.startTick()) + shift, Math.min(to, note.endTick()) + shift,
                        ReconstructionAllocation.Outcome.kept, null, null));
            }
        }
        result.sort(java.util.Comparator.comparingInt(ReconstructionAllocation.Entry::startTick));
        return new ReconstructionAllocation.Result(ReconstructionStages.VERSION, result);
    }

    Reference read(Path path, double importance) throws IOException {
        long audioLength = ReferenceAudio.durationMicros(path);
        var features = new float[windows.size()][];
        var weights = new float[windows.size()][];
        for (int i = 0; i < windows.size(); i++) {
            var window = windows.get(i);
            double start = window.start() / (double) PRESENTATIONS_PER_SECOND;
            double end = Math.min((window.start() + window.length()) / (double) PRESENTATIONS_PER_SECOND, audioLength / 1e6);
            int samples = window.length() * SAMPLES_PER_TICK;
            float[] magnitude;
            if (end - start >= .001) {
                var audio = ReferenceAudio.read(path, start, end - start);
                magnitude = spectra.extract(VoiceSpectra.resample(audio, samples));
            } else {
                magnitude = spectra.extract(new float[samples]);
            }
            features[i] = magnitude;
            float peak = 0;
            for (float value : magnitude) peak = Math.max(peak, value);
            var weight = new float[magnitude.length];
            // Whiten to keep loud backing tracks from drowning out a quiet lane.
            // The floor avoids excessive weight on bins below the local noise floor.
            if (peak > .001) {
                for (int b = 0; b < magnitude.length; b++)
                    weight[b] = (float) (importance / Math.sqrt((magnitude[b] + .025 * peak) * peak));
            }
            weights[i] = weight;
        }
        return new Reference(features, weights);
    }

    float[][] render(S3kReconstruction.Score score, InstrumentCatalogue catalogue,
                     ReconstructionAllocation.Result allocation, String lane,
                     ReconstructionVoicing.Lane settings) throws IOException {
        if (allocation.notes().isEmpty()) return silence();
        var choices = ReconstructionVoiceStage.choices(catalogue, settings, allocation.notes());
        if (choices.values().stream().noneMatch(choice -> choice.assetId() != null)
                && allocation.notes().stream().noneMatch(note -> note.role() == ReconstructionRoles.Role.drums
                        && "PSG3".equals(note.lane()))) return silence();
        var project = S3kReconstruction.arrangeAllocated("Voicing probe", 30, frames, score, allocation,
                null, catalogue, Map.of(lane, choices), Map.of(), Map.of(lane, settings), true).project();
        var compiled = ProjectCompiler.compileWithAssets(project);
        var mono = renderMono(compiled, frames);
        var result = new float[windows.size()][];
        for (int i = 0; i < windows.size(); i++) {
            var window = windows.get(i);
            int start = window.renderStart() * SAMPLES_PER_TICK;
            result[i] = spectra.extract(Arrays.copyOfRange(mono, start, start + window.length() * SAMPLES_PER_TICK));
        }
        return result;
    }

    static float[] renderMono(CompiledProject project, int frames) throws IOException {
        var mono = new float[frames * SAMPLES_PER_TICK];
        int[] at = {0};
        S3kRenderer.render(project, frames, pcm -> {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("voicing fit cancelled");
            for (int i = 0; i < pcm.length; i += DECIMATE * 2) {
                int sum = 0;
                for (int j = 0; j < DECIMATE * 2; j++) sum += pcm[i + j];
                mono[at[0]++] = sum / (32768f * DECIMATE * 2);
            }
        });
        return mono;
    }

    private float[][] silence() {
        return windows.stream().map(w -> spectra.extract(new float[w.length() * SAMPLES_PER_TICK])).toArray(float[][]::new);
    }

    int split(List<Reference> references) {
        int size = 0;
        for (int w = 0; w < windows.size() && !windows.get(w).check(); w++)
            for (Reference reference : references) size += reference.features()[w].length;
        return size;
    }

    float[] vector(List<Reference> references, Map<Integer, float[][]> laneSpectra) {
        int size = references.stream().flatMap(reference -> Arrays.stream(reference.features()))
                .mapToInt(values -> values.length).sum();
        var vector = new float[size];
        int at = 0;
        for (int w = 0; w < windows.size(); w++) {
            for (int index = 0; index < references.size(); index++) {
                var reference = references.get(index);
                var values = laneSpectra == null ? reference.features() : laneSpectra.get(index);
                float[] weights = reference.weights()[w];
                if (values != null) {
                    for (int b = 0; b < weights.length; b++) vector[at + b] = values[w][b] * weights[b];
                }
                at += weights.length;
            }
        }
        return vector;
    }
}

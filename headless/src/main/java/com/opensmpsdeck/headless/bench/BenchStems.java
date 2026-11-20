package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.ReferenceStretch;
import com.opensmpsdeck.headless.S3kRenderer;
import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.project.CompiledProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class BenchStems {
    static final List<String> STEMS = List.of("lead", "bass", "backing", "drums");
    private static final List<String> OUTPUTS = List.of("FM1", "FM2", "FM3", "FM4", "FM5", "FM6-DAC",
            "PSG1", "PSG2", "PSG3", "noise");

    private BenchStems() {}

    static Map<String, List<String>> groups(BenchTimeline.Truth truth) {
        var tonal = truth.channels().stream()
                .filter(channel -> !channel.kind().equals("noise") && !channel.notes().isEmpty()).toList();
        var notes = tonal.stream().flatMap(channel -> channel.notes().stream().map(note ->
                new BenchMetrics.Event(channel.kind(), note.start(), note.end(), note.pitch(), null, null))).toList();
        var melodySeconds = BenchMetrics.melody(notes).stream().collect(Collectors.groupingBy(
                BenchMetrics.Event::lane, Collectors.summingDouble(note -> note.end() - note.start())));
        // Keep one native melody lane per song for a consistent predominant-pitch target.
        var lead = melodySeconds.keySet().stream().max(Comparator
                .<String>comparingDouble(melodySeconds::get).thenComparing(Comparator.reverseOrder())).stream().toList();
        var bass = tonal.stream().filter(channel -> !lead.contains(channel.kind()))
                .min(Comparator.comparingDouble(BenchStems::register).thenComparing(BenchTimeline.Channel::kind))
                .map(BenchTimeline.Channel::kind).stream().toList();
        var drums = List.of("FM6-DAC", "noise");
        var backing = OUTPUTS.stream().filter(lane -> !lead.contains(lane) && !bass.contains(lane)
                && !drums.contains(lane)).toList();
        var groups = new LinkedHashMap<String, List<String>>();
        groups.put("lead", lead);
        groups.put("bass", bass);
        groups.put("backing", backing);
        groups.put("drums", drums);
        return groups;
    }

    private static double register(BenchTimeline.Channel channel) {
        double duration = 0, weighted = 0;
        for (var note : channel.notes()) {
            double span = note.end() - note.start();
            duration += span;
            weighted += span * note.pitch();
        }
        return weighted / duration;
    }

    static S3kRenderer.ChannelMask mask(List<String> lanes) {
        int solo = 0;
        for (String lane : lanes) solo |= 1 << OUTPUTS.indexOf(lane);
        // Like render_clip, mute the complement of the solo set. Noise has its own hardware bit.
        return new S3kRenderer.ChannelMask(~solo & 0b111111, (~solo >> 6) & 0b1111);
    }

    static void build(CompiledProject project, BenchTimeline.Truth truth, BenchDrums drums, Path folder,
                      int song, int frames, BenchStructure.Grid grid, BenchCorpus.Accelerando clock,
                      Map<BenchAudio.Variant, ReferenceStretch.Alignment> alignments) throws IOException {
        var groups = groups(truth);
        var stemByLane = new LinkedHashMap<String, String>();
        for (var stem : groups.entrySet()) {
            stem.getValue().forEach(lane -> stemByLane.put(lane, stem.getKey()));
            String role = stem.getKey();
            var mask = mask(stem.getValue());
            Path nativeWav = folder.resolve(role + "-native.wav");
            WaveFile.write(nativeWav, frames, sink -> S3kRenderer.render(project, frames, mask, sink));
            var pcm = BenchAudio.read(nativeWav);
            Files.delete(nativeWav);
            if (grid != null) pcm = BenchAudio.shift(pcm, grid.offsetFrames());
            for (var variant : BenchAudio.Variant.values()) {
                Path wav = folder.resolve(role + "-" + variant + ".wav");
                BenchAudio.write(BenchAudio.degrade(pcm, variant), wav);
                if (clock.drift() != 0) {
                    alignments.get(variant).writeStem(wav, BenchCorpus.driftPath(wav));
                    Files.delete(wav);
                }
            }
        }
        for (var profile : List.of(BenchMidi.Profile.sunoLike, BenchMidi.Profile.clean)) {
            var events = BenchMidi.events(truth, drums, profile, clock.duration(), song, stemByLane);
            BenchMidi.write(BenchCorpus.inputClock(events, grid, clock), profile,
                    folder.resolve(profile + "-stems.mid"), STEMS, true);
        }
        BenchFiles.write(folder.resolve("stems.json"), groups);
    }
}

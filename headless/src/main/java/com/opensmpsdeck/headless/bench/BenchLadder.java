package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.BenchCartridge;
import com.opensmpsdeck.headless.BenchTranscriber;
import com.opensmpsdeck.headless.ExternalSeparators;
import com.opensmpsdeck.headless.ExternalTranscribers;
import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.ReferenceStretch;
import com.opensmpsdeck.headless.S3kRenderer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BenchLadder {
    enum Rung {
        EXACT("R0", "exact"), GROUPED("R1", "grouped"), MIX_EFFECTS("R2", "mix-effects"),
        TRANSCRIBED("R3", "transcribed"), SUNO_LIKE("R4", "suno-like"),
        DRIFT("R5", "suno-like+drift"), LOCAL("R6", "local");

        final String number;
        final String label;

        Rung(String number, String label) {
            this.number = number;
            this.label = label;
        }
    }

    record Input(BenchCartridge.Game game, Rung rung, boolean torture, int frames, String end,
                 Integer loopStartFrame, String leadLane, String leadTrack, Map<String, BenchCorpus.File> stems,
                 BenchCorpus.File transcription) {}

    record Options(BenchCartridge.Game game, BenchAudio.Variant effects, boolean torture, boolean allRungs, int loops,
                   String transcriber, Path config, String device, String separator, Path separatorConfig,
                   String separatorDevice) {
        Options(BenchCartridge.Game game, BenchAudio.Variant effects, boolean torture, boolean allRungs, int loops,
                String transcriber, Path config, String device) {
            this(game, effects, torture, allRungs, loops, transcriber, config, device, null, null, "auto");
        }
    }

    private BenchLadder() {}

    static void build(Path rom, Path index, Path root, List<Integer> ids, int limit, PrintStream out,
                      Options options) throws IOException {
        var catalogue = InstrumentCatalogue.open(index);
        String romHash = BenchFiles.hash(rom);
        var drums = new BenchDrums(catalogue, "sha256:" + romHash, options.game);
        String transcriber = options.transcriber;
        if (options.allRungs && transcriber == null && options.config == null
                && ExternalTranscribers.configuredNames().contains("muscriptor")) transcriber = "muscriptor";
        if (options.allRungs && transcriber == null)
            out.println("Skipping R3 transcribed: no MuScriptor transcriber configured (use --transcriber NAME).");
        Files.createDirectories(root.getParent());
        Files.createDirectory(root);
        var cases = new ArrayList<BenchCorpus.Case>();
        try (var cartridge = new BenchCartridge(rom, options.game)) {
            for (int id : ids) {
                String key = options.game + "/%02x".formatted(id);
                out.printf("Rendering %s%n", key);
                Path dir = Files.createDirectories(root.resolve(key));
                var song = cartridge.read(id);
                var playback = BenchTimeline.read(song, options.game, limit, options.loops);
                int frames = options.loops == 0 ? limit : playback.frames();
                var truth = playback.truth();
                Path reference = dir.resolve("reference.wav");
                song.render().write(reference, frames, S3kRenderer.ChannelMask.NONE);
                Path truthPath = dir.resolve("truth.json");
                BenchFiles.write(truthPath, truth);
                var inputs = new SongCases(cases, root, key, id, song.name(), playback, frames, options,
                        BenchCorpus.File.at(root, truthPath), BenchCorpus.File.at(root, reference));
                buildSong(song, truth, drums, inputs, dir, reference, transcriber, out);
                out.printf("Built %s %s (%.2f s, %s)%n", key, song.name(), frames / 60.0, inputs.end());
            }
        }
        BenchFiles.write(root.resolve("manifest.json"),
                new BenchCorpus.Manifest(1, romHash, BenchFiles.hash(index), limit, List.copyOf(cases)));
    }

    private static void buildSong(BenchCartridge.Song song, BenchTimeline.Truth truth, BenchDrums drums,
                                  SongCases cases, Path dir, Path reference, String transcriber, PrintStream out)
            throws IOException {
        var groups = BenchStems.groups(truth);
        var lead = groups.get("lead").stream().findFirst().orElse(null);
        var laneToGroup = new LinkedHashMap<String, String>();
        // Derive the backing lanes from actual playback, including tonal FM6 where present.
        var backing = truth.channels().stream().filter(channel -> !channel.notes().isEmpty())
                .map(BenchTimeline.Channel::kind).filter(lane -> !groups.get("lead").contains(lane)
                        && !groups.get("bass").contains(lane) && !groups.get("drums").contains(lane)).toList();
        groups.put("backing", backing);
        groups.forEach((role, lanes) -> lanes.forEach(lane -> laneToGroup.put(lane, role)));
        var channels = new LinkedHashMap<String, List<String>>();
        truth.channels().forEach(channel -> {
            if (!channel.notes().isEmpty() || !channel.hits().isEmpty())
                channels.put(channel.kind(), List.of(channel.kind()));
        });
        int frames = cases.frames;
        double seconds = frames / 60.0;
        var exactTracks = new LinkedHashMap<String, String>();
        channels.keySet().forEach(lane -> exactTracks.put(lane, lane));
        Path exact = dir.resolve("exact.mid");
        BenchMidi.write(BenchMidi.events(truth, drums, BenchMidi.Profile.clean, seconds, cases.id, exactTracks),
                BenchMidi.Profile.clean, exact, List.copyOf(channels.keySet()), true);
        var solo = renderStems(song, dir, "exact", frames, channels);
        cases.add(Rung.EXACT, BenchMidi.Profile.clean, BenchAudio.Variant.dry, exact, reference, lead, lead, solo, null);

        Path grouped = dir.resolve("grouped.mid");
        BenchMidi.writeGrouped(BenchMidi.events(truth, drums, BenchMidi.Profile.clean, seconds, cases.id, laneToGroup),
                BenchMidi.Profile.clean, grouped);
        var dry = renderStems(song, dir, "grouped", frames, groups);
        cases.add(Rung.GROUPED, BenchMidi.Profile.clean, BenchAudio.Variant.dry, grouped, reference, lead, "lead", dry, null);
        BenchFiles.write(dir.resolve("groups.json"), groups);
        if (!cases.options.allRungs) return;

        var variant = cases.options.effects;
        Path effects = dir.resolve(variant + ".wav");
        BenchAudio.write(BenchAudio.degrade(BenchAudio.read(reference), variant), effects);
        var degraded = new LinkedHashMap<String, Path>();
        for (var entry : dry.entrySet()) {
            Path file = dir.resolve(entry.getKey() + "-" + variant + ".wav");
            BenchAudio.write(BenchAudio.degrade(BenchAudio.read(entry.getValue()), variant), file);
            degraded.put(entry.getKey(), file);
        }
        cases.add(Rung.MIX_EFFECTS, BenchMidi.Profile.clean, variant, grouped, effects, lead, "lead", degraded, null);

        var simulated = BenchMidi.events(truth, drums, BenchMidi.Profile.sunoLike, seconds, cases.id, laneToGroup);
        if (transcriber != null) {
            out.printf("Transcribing %s with %s%n", cases.key, transcriber);
            var output = BenchTranscriber.collect(grouped, degraded, transcriber, cases.options.config, cases.options.device);
            Path evidence = dir.resolve("transcription.json");
            BenchFiles.write(evidence, output.provenance());
            var notes = new ArrayList<BenchMidi.Event>();
            simulated.stream().filter(BenchMidi.Event::drum).forEach(notes::add);
            output.notes().forEach((part, events) -> events.stream().filter(note -> note.start() < seconds)
                    .forEach(note -> notes.add(new BenchMidi.Event(part, false, note.start(),
                            Math.min(seconds, note.end()), note.pitch(), note.velocity()))));
            Path midi = dir.resolve("transcribed.mid");
            BenchMidi.writeGrouped(notes, BenchMidi.Profile.sunoLike, midi);
            cases.add(Rung.TRANSCRIBED, BenchMidi.Profile.sunoLike, variant, midi, effects, lead, "lead", degraded, evidence);
        }

        Path suno = dir.resolve("suno-like.mid");
        BenchMidi.writeGrouped(simulated, BenchMidi.Profile.sunoLike, suno);
        cases.add(Rung.SUNO_LIKE, BenchMidi.Profile.sunoLike, variant, suno, effects, lead, "lead", degraded, null);
        var drift = new BenchCorpus.Accelerando(seconds, .07);
        Path driftMix = BenchCorpus.driftPath(effects);
        var alignment = ReferenceStretch.writeAndCaptureAlignment(effects, driftMix,
                (int) Math.ceil(drift.inputDuration() * 60), drift::truthTime);
        var warped = new LinkedHashMap<String, Path>();
        for (var entry : degraded.entrySet()) {
            Path file = BenchCorpus.driftPath(entry.getValue());
            alignment.writeStem(entry.getValue(), file);
            warped.put(entry.getKey(), file);
        }
        Path driftMidi = dir.resolve("suno-like-drift.mid");
        BenchMidi.writeGrouped(BenchCorpus.inputClock(simulated, null, drift), BenchMidi.Profile.sunoLike, driftMidi);
        cases.add(Rung.DRIFT, BenchMidi.Profile.sunoLike, variant, driftMidi, driftMix, lead, "lead", warped, null);
        if (cases.options.separator != null) addLocal(cases, dir, lead, out);
    }

    // R6 is the shipped local route: separate the dry native render, then let
    // audio-only reconstruction transcribe the stems. No MIDI or oracle stems.
    private static void addLocal(SongCases cases, Path dir, String lead, PrintStream out) throws IOException {
        var options = cases.options;
        out.printf("Separating %s with %s%n", cases.key, options.separator);
        Path folder = ExternalSeparators.separate(cases.reference.resolve(cases.root), dir.resolve("separated"),
                options.separator, options.separatorDevice, options.separatorConfig, (done, total, message) -> { });
        Path provenance = folder.resolve("separation.json");
        var order = BenchFiles.JSON.fromJson(Files.readString(provenance), Separation.class).stemOrder();
        var stems = new LinkedHashMap<String, Path>();
        for (String name : order) stems.put(name, folder.resolve("stems").resolve(name + ".wav"));
        cases.add(Rung.LOCAL, BenchMidi.Profile.clean, BenchAudio.Variant.dry, null, folder.resolve("mix.wav"),
                lead, null, stems, provenance);
    }

    private record Separation(List<String> stemOrder) {}

    private static Map<String, Path> renderStems(BenchCartridge.Song song, Path dir, String prefix, int frames,
                                                  Map<String, List<String>> groups) throws IOException {
        var files = new LinkedHashMap<String, Path>();
        for (var entry : groups.entrySet()) {
            Path stem = dir.resolve(prefix + "-" + entry.getKey() + ".wav");
            song.render().write(stem, frames, BenchStems.mask(entry.getValue()));
            files.put(entry.getKey(), stem);
        }
        return files;
    }

    private record SongCases(List<BenchCorpus.Case> cases, Path root, String key, int id, String title,
                             BenchTimeline.Playback playback, int frames, Options options, BenchCorpus.File truth,
                             BenchCorpus.File reference) {
        String end() {
            if (options.loops == 0) return "duration";
            return playback.end().equals("duration") ? "duration cap" : playback.end();
        }

        void add(Rung rung, BenchMidi.Profile profile, BenchAudio.Variant variant, Path midi, Path audio,
                 String lead, String leadTrack, Map<String, Path> stems, Path transcription) throws IOException {
            var files = new TreeMap<String, BenchCorpus.File>();
            for (var entry : stems.entrySet()) files.put(entry.getKey(), BenchCorpus.File.at(root, entry.getValue()));
            var input = new Input(options.game, rung, options.torture, frames, end(), playback.loopStart(), lead,
                    leadTrack, files, transcription == null ? null : BenchCorpus.File.at(root, transcription));
            cases.add(new BenchCorpus.Case(key + "/" + rung.label, id, title, profile, variant, truth,
                    midi == null ? null : BenchCorpus.File.at(root, midi), BenchCorpus.File.at(root, audio), reference, null,
                    rung == Rung.DRIFT ? .07 : null, input));
        }
    }
}

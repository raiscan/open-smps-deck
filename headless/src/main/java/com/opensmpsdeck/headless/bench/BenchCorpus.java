package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.InstrumentCatalogue;
import com.opensmpsdeck.headless.S3kRomImporter;
import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.project.ProjectCompiler;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BenchCorpus {
    static final List<Integer> QUICK = List.of(0x01, 0x04, 0x0b, 0x15);
    static final List<Integer> DEFAULT = List.of(0x01, 0x04, 0x05, 0x07, 0x09, 0x0b,
            0x0f, 0x11, 0x14, 0x15, 0x1a, 0x24);

    record File(String path, String sha256) {
        static File at(Path root, Path path) throws IOException {
            return new File(root.relativize(path).toString(), BenchFiles.hash(path));
        }

        Path resolve(Path root) {
            if (path == null || Path.of(path).isAbsolute() || !root.resolve(path).normalize().startsWith(root))
                throw new IllegalArgumentException("Manifest paths must stay within the corpus");
            return root.resolve(path).normalize();
        }
    }

    record Case(String id, int song, String songName, BenchMidi.Profile profile, BenchAudio.Variant variant,
                File truth, File midi, File audio, File reference) {}
    record Manifest(int version, String romSha256, String catalogueSha256, int frames, List<Case> cases) {}

    private BenchCorpus() {}

    static void build(Path rom, Path index, Path root, List<Integer> songs, int frames, PrintStream out,
                      BenchCli.SequencerFactory factory) throws IOException {
        var catalogue = InstrumentCatalogue.open(index);
        String romHash = BenchFiles.hash(rom);
        var drums = new BenchDrums(catalogue, "sha256:" + romHash);
        Files.createDirectories(root);
        if (Files.exists(root.resolve("manifest.json"))) throw new IOException("Corpus already has a manifest: " + root);
        var cases = new ArrayList<Case>();
        for (int id : songs) {
            var source = S3kRomImporter.readSong(rom, id);
            var compiled = ProjectCompiler.compileWithAssets(source);
            var truth = BenchTimeline.read(compiled, frames, factory);
            String key = String.format(Locale.ROOT, "%02x", id);
            Path folder = Files.createDirectory(root.resolve(key));
            Path reference = folder.resolve("reference.wav");
            WaveFile.render(compiled, reference, frames);
            Path truthPath = folder.resolve("truth.json");
            BenchFiles.write(truthPath, truth);
            var truthFile = File.at(root, truthPath);
            var referenceFile = File.at(root, reference);
            var audio = BenchAudio.read(reference);
            var variants = new ArrayList<File>();
            for (var variant : BenchAudio.Variant.values()) {
                Path path = folder.resolve(variant + ".wav");
                if (variant == BenchAudio.Variant.dry) Files.copy(reference, path);
                else BenchAudio.write(BenchAudio.degrade(audio, variant), path);
                variants.add(File.at(root, path));
            }
            for (var profile : BenchMidi.Profile.values()) {
                Path midi = folder.resolve(profile + ".mid");
                BenchMidi.write(BenchMidi.events(truth, drums, profile, frames / 60.0, id), profile, midi);
                var midiFile = File.at(root, midi);
                for (var variant : BenchAudio.Variant.values()) {
                    cases.add(new Case(key + "/" + profile + "/" + variant, id, source.name(), profile, variant,
                            truthFile, midiFile, variants.get(variant.ordinal()), referenceFile));
                }
            }
            out.printf("Built %s %s%n", key, source.name());
        }
        BenchFiles.write(root.resolve("manifest.json"),
                new Manifest(1, romHash, BenchFiles.hash(index), frames, List.copyOf(cases)));
    }
}

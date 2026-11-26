package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CatalogueCliTest {
    @TempDir Path directory;

    @Test void indexesValidatedRipsAndRunsVersionedSearchShowAuditionAndApply() throws Exception {
        Path rips = Files.createDirectory(directory.resolve("rips"));
        Path examples = Files.createDirectory(rips.resolve("album"));
        var demo = DemoProject.create();
        var alias = new Voice("alias", "Glass keys at another slot", demo.voices().getFirst().bytes());
        var project = ProjectEdits.setSourceSettings(ProjectEdits.addVoice(demo, alias), "seq-lead-b", alias.id(), 112);
        Path source = examples.resolve("theme.smps.json");
        ProjectFiles.save(source, project, null);
        Files.write(examples.resolve("linear.s3k"), ProjectCompiler.compile(project));
        ProjectPackage.write(project, examples.resolve("packaged.smps.zip"));
        Files.write(examples.resolve("legacy.sm2"), new byte[]{1});
        Files.write(examples.resolve("invalid.s3k"), new byte[]{2});
        // A reachable DAC note without its sample binding is a missing companion.
        Files.write(examples.resolve("drums.s3k"), new byte[]{0, 0, 1, 0, 1, 0, 10, 0, 0, 0,
                (byte) 0x81, 3, (byte) 0xF2});

        Path index = directory.resolve("library.json");
        JsonObject scan = json("scan", index.toString(), "--rips", rips.toString());
        assertEquals(1, scan.get("formatVersion").getAsInt());
        assertEquals(3, scan.get("indexedSongs").getAsInt());
        var catalogue = InstrumentCatalogue.open(index);
        assertEquals(Set.of("unsupported-input", "invalid-or-unsupported-bank", "missing-companion"),
                catalogue.issues().stream().map(InstrumentCatalogue.Issue::reason).collect(Collectors.toSet()));
        var voices = json("search", index.toString(), "--kind", "fm-voice", "--source", "album/",
                "--role", "tone", "--driver", "S3K", "--limit", "1", "--json");
        assertEquals(2, voices.get("total").getAsInt());
        var measured = json("search", index.toString(), "--role", "lead", "--min-centroid-hz", "1",
                "--max-decay-db", "120", "--limit", "1");
        assertTrue(measured.get("total").getAsInt() > 0);
        var held = measured.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("heldNote");
        assertTrue(held.get("centroidHz").getAsDouble() > 1);
        assertTrue(held.get("decayDb").getAsDouble() <= 120);
        assertTrue(measured.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonArray("roles")
                .contains(new com.google.gson.JsonPrimitive("lead")));
        assertEquals(0, json("search", index.toString(), "--role", "bass").get("total").getAsInt());
        assertEquals(0, json("search", index.toString(), "--role", "tone", "--min-centroid-hz", "6001")
                .get("total").getAsInt());
        var first = voices.getAsJsonArray("items").get(0).getAsJsonObject();
        assertFalse(first.has("payload"));
        String variant = first.get("variantId").getAsString();
        var show = json("show", index.toString(), variant);
        assertTrue(show.getAsJsonObject("asset").has("payload"));
        assertTrue(show.toString().contains("album/linear.s3k"));
        var localSlots = catalogue.match(variant).variant().occurrences().stream()
                .filter(item -> "local-fm".equals(item.table())).map(InstrumentCatalogue.Occurrence::slot)
                .collect(Collectors.toSet());
        assertEquals(Set.of(0, 2), localSlots);
        assertFalse(show.toString().contains(directory.toString()));

        Path wav = directory.resolve("voice.wav");
        assertEquals(3, json("audition", index.toString(), variant, wav.toString(), "--frames", "3")
                .get("frames").getAsInt());
        assertTrue(Files.size(wav) > 44);
        Path output = directory.resolve("applied.smps.json");
        json("apply", index.toString(), variant, source.toString(), output.toString());
        assertEquals(project, ProjectFiles.load(output).project()); // Already owns this exact voice.
        var before = Files.readAllBytes(index);
        assertEquals(2, run("scan", index.toString(), "--rips", rips.toString()).code());
        assertArrayEquals(before, Files.readAllBytes(index));
        Path duplicate = directory.resolve("library-again.json");
        json("scan", duplicate.toString(), "--rips", rips.toString());
        assertArrayEquals(before, Files.readAllBytes(duplicate));
    }

    @Test void separatesClassicAndS3kVariantsOfTheSamePayload() throws Exception {
        Path rips = Files.createDirectory(directory.resolve("mixed"));
        var voice = DemoProject.create().voices().getFirst();
        var source = new Sequence("tone", "Tone", "FM", 12, voice.id(), 127,
                List.of(new NoteEvent("note", 0, 12, 60)));
        var clip = new Clip("tone-clip", source.id(), "FM1", 0, 12);
        for (var target : List.of(TargetGame.S1, TargetGame.S2, TargetGame.S3K)) {
            var project = new StudioProject(4, target.id(), "S3K", 24, 0, List.of(voice),
                    List.of(source), List.of(clip), target, null);
            ProjectFiles.save(rips.resolve(target.id() + ".smps.json"), project, null);
            if (target != TargetGame.S3K) {
                var options = new PortableOptions(target.id(), false, null, null, null, List.of(), List.of());
                PortablePackageWriter.write(project, rips.resolve(target.id() + ".smps.zip"), options);
            }
        }
        Path index = directory.resolve("mixed.json");
        assertEquals(5, json("scan", index.toString(), "--rips", rips.toString()).get("indexedSongs").getAsInt());
        var catalogue = InstrumentCatalogue.open(index);
        assertEquals(1, catalogue.assets().size());
        assertEquals(3, catalogue.variants().size());
        for (var target : List.of(TargetGame.S1, TargetGame.S2, TargetGame.S3K)) {
            var results = json("search", index.toString(), "--target", target.id());
            assertEquals(1, results.get("total").getAsInt());
            var summary = results.getAsJsonArray("items").get(0).getAsJsonObject();
            assertEquals(target.id(), summary.get("driver").getAsString().toLowerCase(java.util.Locale.ROOT));
            String variant = summary.get("variantId").getAsString();
            var occurrences = catalogue.match(variant).variant().occurrences();
            assertTrue(occurrences.stream().anyMatch(InstrumentCatalogue.Occurrence::played));
            if (target != TargetGame.S3K)
                assertTrue(occurrences.stream().anyMatch(o -> o.source().endsWith(".smps.zip") && o.played()));
            assertEquals(List.of(variant), catalogue.forTarget(target).variants().stream()
                    .map(InstrumentCatalogue.Variant::id).toList());
            assertEquals(target.id() + "-ntsc-v1", json("audition", index.toString(), variant,
                    directory.resolve(target.id() + ".wav").toString(), "--frames", "3")
                    .get("auditionProfile").getAsString());
        }
    }

    @Test void rejectsS3kOnlyRecipeCommandsForClassicAuditions() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var voice = DemoProject.create().voices().getFirst();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, voice.name(), StockTable.bytes(voice.bytes()));
        for (var command : List.of(NativeCommand.SSG_EG, NativeCommand.MODULATION_ENVELOPE,
                NativeCommand.FM_VOLUME_ENVELOPE)) {
            var recipe = new InstrumentCatalogue.Recipe(null, null, 127, 30, null, null, List.of(),
                    List.of(new CommandEvent("setup", 0, command, command.defaultArguments())));
            builder.variant(asset, voice.name(), recipe, null);
        }
        Path index = directory.resolve("recipes.json");
        var catalogue = builder.build();
        catalogue.save(index);
        for (var variant : catalogue.variants()) {
            String command = variant.recipe().commands().getFirst().command().name();
            for (var target : List.of(TargetGame.S1, TargetGame.S2)) {
                var result = run("audition", index.toString(), variant.id(), directory.resolve("probe.wav").toString(),
                        "--target", target.id());
                assertEquals(2, result.code(), result.stderr());
                assertTrue(result.stderr().contains(target.id() + ": " + command), result.stderr());
            }
        }
        assertFalse(Files.exists(directory.resolve("probe.wav")));
    }

    @Test void tracesAPlayedPsgRecipeFromSourceAndSavesADeployableCopy() throws Exception {
        Path rips = Files.createDirectory(directory.resolve("psg-rips"));
        var envelope = new Envelope("contour", "Contour", Envelope.PSG_VOLUME, List.of(0, 0x80));
        var source = new Sequence("noise", "Noise", "PSG", 12, null, 127,
                List.of(new NoteEvent("first", 0, 6, 69), new NoteEvent("held", 6, 6, 72)), List.of(
                new CommandEvent("noise", 0, NativeCommand.PSG_NOISE, List.of(0xE7)),
                new CommandEvent("hold", 6, NativeCommand.HOLD, List.of())), "RESET", envelope.id(), null);
        var project = new StudioProject(4, "Pitch-linked sizzle", "S3K", 24, 0, List.of(), List.of(source),
                List.of(new Clip("noise-clip", source.id(), "PSG3", 0, 12)), List.of(envelope), List.of(), TargetGame.S3K, null);
        ProjectFiles.save(rips.resolve("noise.smps.json"), project, null);
        Path index = directory.resolve("noise-library.json");
        json("scan", index.toString(), "--rips", rips.toString());
        var catalogue = InstrumentCatalogue.open(index);
        var search = catalogue.search(new InstrumentCatalogue.Query(null, InstrumentCatalogue.Kind.PSG_NOISE,
                "S3K", "tone-linked", "noise", null, 0, 10));
        assertEquals(1, search.total());
        var match = search.items().getFirst();
        var recipe = match.variant().recipe();
        assertEquals(List.of(69, 72), recipe.notes().stream().map(NoteEvent::pitch).toList());
        assertTrue(recipe.commands().stream().anyMatch(event -> event.tick() == 6 && event.command() == NativeCommand.HOLD));
        assertEquals("PSG3", match.variant().occurrences().stream()
                .filter(InstrumentCatalogue.Occurrence::played).findFirst().orElseThrow().lane());
        Path target = directory.resolve("target.smps.json");
        ProjectFiles.save(target, new StudioProject(4, "Target", "S3K", 24, 0, List.of(), List.of(), List.of(), TargetGame.S3K, null), null);
        Path deployed = directory.resolve("deployed.smps.json");
        json("apply", index.toString(), match.variant().id(), target.toString(), deployed.toString());
        var applied = ProjectFiles.load(deployed).project();
        assertEquals("PSG3", applied.clips().getLast().lane());
        assertEquals(List.of(69, 72), applied.sequences().getLast().notes().stream().map(NoteEvent::pitch).toList());
        boolean[] heard = {false};
        S3kRenderer.render(ProjectCompiler.compileWithAssets(applied), 12, pcm -> {
            for (short sample : pcm) heard[0] |= sample != 0;
        });
        assertTrue(heard[0]);
    }

    private record Result(int code, String stdout, String stderr) {}

    private JsonObject json(String... args) {
        var result = run(args);
        assertEquals(0, result.code(), result.stderr());
        return JsonParser.parseString(result.stdout()).getAsJsonObject();
    }

    private Result run(String... arguments) {
        String[] args = new String[arguments.length + 1];
        args[0] = "catalog";
        System.arraycopy(arguments, 0, args, 1, arguments.length);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}

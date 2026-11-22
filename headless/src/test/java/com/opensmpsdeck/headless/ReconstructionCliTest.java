package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionCliTest {
    @TempDir Path directory;

    @Test void rendersAnEditableArrangementAndAccountsForPolyphonyAndDuration() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        String variant = builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        String envelope = builder.asset(InstrumentCatalogue.Kind.PSG_VOLUME, "Tone contour",
                new byte[]{0, 2, (byte) 0x83});
        builder.variant(envelope, "Tone contour", InstrumentCatalogue.Recipe.plain(), null);
        InstrumentCatalogue catalogue = builder.build();
        Path index = directory.resolve("index.json");
        catalogue.save(index);
        Path wav = directory.resolve("input.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(CataloguePlayback.matchProbe(catalogue, variant, 72, 24)), wav, 60);
        Path midi = directory.resolve("score.mid");
        var source = new Sequence(Sequence.PPQ, 480);
        var track = source.createTrack();
        for (int pitch = 66; pitch <= 72; pitch++) {
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, 85), 0));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0), 384));
        }
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 72, 85), 700));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 72, 0), 800));
        MidiSystem.write(source, 0, midi.toFile());

        Path output = directory.resolve("output");
        String[] args = {"reconstruct", midi.toString(), wav.toString(), index.toString(), output.toString(),
                "--seconds", "0.5", "--stem", "0=" + wav, "--fm-only"};
        var errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args, System.out, new PrintStream(errors)), errors.toString());
        var project = ProjectFiles.load(output.resolve("arrangement.smps.json")).project();
        assertEquals(5, project.clips().size());
        assertEquals(List.of(68, 69, 70, 71, 72), project.sequences().stream()
                .flatMap(s -> s.notes().stream()).map(n -> n.pitch()).sorted().toList());
        // Synthetic fixture fingerprints recorded on master before introducing target selection.
        assertEquals("f04feb6f4d7c720d17c2bf3797a4298e80801b551ff71ea01fcd7567860259ff",
                ReconstructionCli.audioHash(output.resolve("arrangement.smps.json")));
        assertEquals("2e1b0ff357ffe977c65daf359395e89bdc1fca5168ddc02be9e09dcb0592ca54",
                InstrumentCatalogue.digest(ProjectCompiler.compile(project)));
        assertEquals("3ea2786afbed30e9ea3be0f1d115f1e0b30409eaa74efc469dc5a2d2e91cce44",
                ReconstructionCli.audioHash(output.resolve("render.wav")));
        assertEquals(500_000, ReferenceAudio.durationMicros(output.resolve("render.wav")));
        assertTargetRoundTrip(midi, wav, index);
        assertTrue(Files.isRegularFile(output.resolve("arrangement.smps.zip")));
        String reportText = Files.readString(output.resolve("report.json"));
        var report = JsonParser.parseString(reportText).getAsJsonObject();
        assertEquals(7, report.get("sourceNotes").getAsInt());
        assertEquals(5, report.get("fmNotes").getAsInt());
        assertEquals(2, report.getAsJsonArray("reductions").size());
        assertEquals("FM_BUSY", report.getAsJsonArray("reductions").get(0).getAsJsonObject().get("change").getAsString());
        assertEquals(2, Main.run(args, System.out, new PrintStream(errors)));
        assertEquals(reportText, Files.readString(output.resolve("report.json")));

        Path spillOutput = directory.resolve("with-psg");
        String[] withPsg = java.util.Arrays.copyOf(args, args.length - 1);
        withPsg[4] = spillOutput.toString();
        assertEquals(0, Main.run(withPsg, System.out, new PrintStream(errors)), errors.toString());
        var withPsgProject = ProjectFiles.load(spillOutput.resolve("arrangement.smps.json")).project();
        assertEquals(List.of(66, 67), withPsgProject.sequences().stream().filter(s -> s.type().equals("PSG"))
                .flatMap(s -> s.notes().stream()).map(n -> n.pitch()).sorted().toList());
        var spillReport = JsonParser.parseString(Files.readString(spillOutput.resolve("report.json"))).getAsJsonObject();
        assertEquals(2, spillReport.get("psgNotes").getAsInt());
        assertTrue(spillReport.getAsJsonArray("reductions").isEmpty());
        for (var match : spillReport.getAsJsonArray("matches")) {
            for (var probe : match.getAsJsonObject().getAsJsonObject("choice").getAsJsonArray("probes")) {
                var window = probe.getAsJsonObject();
                assertTrue(window.get("startUs").getAsLong() + window.get("durationUs").getAsLong() <= 500_000);
            }
        }

        Path work = directory.resolve("stages");
        Path candidates = directory.resolve("candidates.json");
        Files.writeString(candidates, """
                {"version":1,"notes":[{"id":"external-1","part":0,"start":0.06,"end":0.2,"pitch":80,"velocity":90}]}
                """);
        assertEquals(0, Main.run(new String[]{"reconstruct", "transcribe", midi.toString(), wav.toString(),
                index.toString(), work.toString(), "--seconds", ".5", "--candidates", candidates.toString()},
                System.out, new PrintStream(errors)), errors.toString());
        var score = JsonParser.parseString(Files.readString(work.resolve("score.json"))).getAsJsonObject();
        assertEquals(60, score.get("ticksPerSecond").getAsInt());
        assertEquals(30, score.get("frames").getAsInt());
        Path explicitLead = directory.resolve("explicit-lead");
        assertEquals(0, Main.run(new String[]{"reconstruct", "transcribe", midi.toString(), wav.toString(),
                index.toString(), explicitLead.toString(), "--seconds", ".5", "--lead-part", "0"},
                System.out, new PrintStream(errors)), errors.toString());
        assertEquals(0, ReconstructionStages.readScore(explicitLead.resolve("score.json")).inputs().leadPart());
        assertEquals(2, Main.run(new String[]{"reconstruct", "transcribe", midi.toString(), wav.toString(),
                index.toString(), directory.resolve("unknown-lead").toString(), "--lead-part", "9"},
                System.out, new PrintStream(errors)));
        assertTrue(errors.toString().contains("--lead-part must name an existing non-drum MIDI part ID"));
        assertEquals("external", score.getAsJsonArray("notes").asList().stream()
                .map(n -> n.getAsJsonObject()).filter(n -> n.get("pitch").getAsInt() == 80)
                .findFirst().orElseThrow().get("source").getAsString());

        Path overrideWork = directory.resolve("override");
        assertEquals(0, Main.run(new String[]{"reconstruct", "transcribe", midi.toString(), wav.toString(),
                index.toString(), overrideWork.toString(), "--seconds", ".5", "--candidates", candidates.toString(),
                "--tempo", "150", "--bar-offset", ".15"}, System.out, new PrintStream(errors)), errors.toString());
        var override = JsonParser.parseString(Files.readString(overrideWork.resolve("score.json"))).getAsJsonObject();
        assertEquals(score.get("notes"), override.get("notes"));
        var firstBar = override.getAsJsonArray("bars").get(0).getAsJsonObject();
        assertEquals(.15, firstBar.get("end").getAsDouble(), 1e-9);
        assertFalse(firstBar.get("complete").getAsBoolean());

        Path steady = directory.resolve("steady");
        assertEquals(0, Main.run(new String[]{"reconstruct", "run", midi.toString(), wav.toString(),
                index.toString(), steady.toString(), "--seconds", ".5", "--tempo", "150", "--steady-tempo", "75"},
                System.out, new PrintStream(errors)), errors.toString());
        var steadyReport = JsonParser.parseString(Files.readString(steady.resolve("report.json"))).getAsJsonObject();
        var curve = steadyReport.getAsJsonObject("tempoCurve");
        assertTrue(curve.get("warped").getAsBoolean());
        assertEquals(75, curve.get("steadyBpm").getAsDouble());
        assertEquals(1, curve.get("durationSeconds").getAsDouble());
        Path steadyWav = steady.resolve("reference-steady.wav");
        assertEquals(1_000_000, ReferenceAudio.durationMicros(steadyWav));
        Path steadyProject = steady.resolve("arrangement.smps.json");
        assertEquals(steadyWav, ProjectFiles.load(steadyProject).project().references().getFirst().resolve(steadyProject));
        for (var direction : List.of("--source", "--project")) {
            var mapped = new ByteArrayOutputStream();
            boolean fromSource = direction.equals("--source");
            assertEquals(0, Main.run(new String[]{"reconstruct", "time-map", steady.toString(), direction,
                    fromSource ? ".25" : ".5"}, new PrintStream(mapped), new PrintStream(errors)), errors.toString());
            assertEquals(fromSource ? .5 : .25, Double.parseDouble(mapped.toString()), 1e-10);
        }


        for (String stage : List.of("structure", "roles"))
            assertEquals(0, Main.run(new String[]{"reconstruct", stage, work.toString()},
                    System.out, new PrintStream(errors)), errors.toString());
        Path structureFile = work.resolve("structure.json");
        var structure = JsonParser.parseString(Files.readString(structureFile)).getAsJsonObject();
        var structureNote = structure.getAsJsonArray("notes").get(0).getAsJsonObject();
        String structureNoteId = structureNote.get("id").getAsString();
        structureNote.addProperty("velocity", 77);
        String editedStructure = structure.toString();
        Files.writeString(structureFile, editedStructure);
        assertEquals(0, Main.run(new String[]{"reconstruct", "run", "--from", "roles", work.toString()},
                System.out, new PrintStream(errors)), errors.toString());
        assertEquals(editedStructure, Files.readString(structureFile));
        assertUnplannedArtifacts(work);
        var resumedAllocation = JsonParser.parseString(Files.readString(work.resolve("allocation.json"))).getAsJsonObject();
        assertEquals(77, resumedAllocation.getAsJsonArray("notes").asList().stream()
                .map(e -> e.getAsJsonObject().getAsJsonObject("note"))
                .filter(n -> n.get("id").getAsString().equals(structureNoteId))
                .findFirst().orElseThrow().get("velocity").getAsInt());

        Path roleFile = work.resolve("roles.json");
        var roles = JsonParser.parseString(Files.readString(roleFile)).getAsJsonObject();
        var assignments = roles.getAsJsonArray("notes");
        assertEquals(7, assignments.size());
        var lead = assignments.asList().stream().map(element -> element.getAsJsonObject())
                .filter(assignment -> assignment.get("role").getAsString().equals("melody")).findFirst().orElseThrow();
        var note = lead.getAsJsonObject("note");
        String editedId = note.get("id").getAsString();
        note.addProperty("pitch", 73);
        String edited = roles.toString();
        Files.writeString(roleFile, edited);
        assertEquals(0, Main.run(new String[]{"reconstruct", "run", "--from", "allocate", work.toString()},
                System.out, new PrintStream(errors)), errors.toString());
        assertEquals(edited, Files.readString(roleFile));
        var allocation = JsonParser.parseString(Files.readString(work.resolve("allocation.json"))).getAsJsonObject();
        var placed = allocation.getAsJsonArray("notes").asList().stream().map(n -> n.getAsJsonObject())
                .filter(entry -> entry.getAsJsonObject("note").get("id").getAsString().equals(editedId))
                .findFirst().orElseThrow();
        assertEquals("FM1", placed.get("lane").getAsString());
        assertEquals(73, placed.getAsJsonObject("note").get("pitch").getAsInt());
        var updated = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        var fm1 = updated.clips().stream().filter(c -> c.lane().equals("FM1")).findFirst().orElseThrow();
        assertEquals(73, updated.sequences().stream().filter(s -> s.id().equals(fm1.sequenceId()))
                .findFirst().orElseThrow().notes().getFirst().pitch());

        placed.addProperty("lane", "FM4");
        Files.writeString(work.resolve("allocation.json"), allocation.toString());
        errors.reset();
        String goodReport = Files.readString(work.resolve("report.json"));
        assertEquals(2, Main.run(new String[]{"reconstruct", "assemble", work.toString()}, System.out, new PrintStream(errors)));
        assertTrue(errors.toString().contains("invalid lane for melody"), errors.toString());
        assertEquals(goodReport, Files.readString(work.resolve("report.json")));

        Files.writeString(work.resolve("allocation.json"), "{");
        errors.reset();
        assertEquals(2, Main.run(new String[]{"reconstruct", "assemble", work.toString()},
                System.out, new PrintStream(errors)));
        assertTrue(errors.toString().contains("allocation.json"), errors.toString());

        assertEquals(0, Main.run(new String[]{"reconstruct", "run", work.toString(), "--from", "allocate"},
                System.out, new PrintStream(errors)), errors.toString());
        assertEquals(goodReport, Files.readString(work.resolve("report.json")));
    }

    @Test void classicReconstructionKeepsLongClickTrackOnsetsWithinOneFrame() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        Path index = directory.resolve("click-index.json");
        builder.build().save(index);

        int frames = 10_320;
        int samplesPerFrame = 200;
        var pcm = new byte[frames * samplesPerFrame * 2];
        var midi = new Sequence(Sequence.PPQ, 30);
        var track = midi.createTrack();
        var attacks = new java.util.ArrayList<Integer>();
        // With default MIDI tempo, one PPQ unit here is one NTSC presentation.
        // Revisit both sides of a hold, then sample the full song.
        for (int at : new int[]{0, 125, 254, 509, 768, 1021, 2047, 4095, 8191, 10_200}) {
            attacks.add(at);
            pcm[at * samplesPerFrame * 2 + 1] = 0x70;
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 72, 96), at));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 72, 0), at + 12));
        }
        Path wav = directory.resolve("click.wav"), score = directory.resolve("click.mid");
        try (var audio = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(pcm),
                new javax.sound.sampled.AudioFormat(12_000, 16, 1, true, false), pcm.length / 2)) {
            javax.sound.sampled.AudioSystem.write(audio, javax.sound.sampled.AudioFileFormat.Type.WAVE, wav.toFile());
        }
        MidiSystem.write(midi, 0, score.toFile());

        for (var driver : ClassicPackageDriver.values()) {
            var output = directory.resolve(driver.id());
            var errors = new ByteArrayOutputStream();
            assertEquals(0, Main.run(new String[]{"reconstruct", "run", score.toString(), wav.toString(),
                    index.toString(), output.toString(), "--target", driver.id(), "--fm-only",
                    "--steady-tempo", "off", "--lane-policy-preset", "off"}, System.out, new PrintStream(errors)),
                    errors.toString());
            var project = ProjectFiles.load(output.resolve("arrangement.smps.json")).project();
            var compiled = ReconstructionCli.compileTarget(project);
            var observed = new java.util.ArrayList<Integer>();
            int[] frame = {0};
            driver.render(compiled, frames, ignored -> frame[0]++, new com.openggf.audio.synth.ChipWriteObserver() {
                @Override public void onYm2612Write(int port, int register, int value) {
                    if (register == 0x28 && (value & 0xF7) == 0xF0) observed.add(frame[0]);
                }
                @Override public void onPsgWrite(int value) {}
            });
            assertEquals(attacks.size(), observed.size(), driver.id());
            for (int i = 0; i < attacks.size(); i++)
                assertEquals(attacks.get(i), observed.get(i), 1, driver.id() + " onset " + i);
        }
    }

    private void assertTargetRoundTrip(Path midi, Path wav, Path index) throws Exception {
        Path staged = directory.resolve("target-s3k");
        Path classic = directory.resolve("target-s1");
        Path replay = directory.resolve("target-replay");
        Path imported = directory.resolve("target-import");
        var errors = new ByteArrayOutputStream();
        var err = new PrintStream(errors);
        Path download = Files.createDirectory(directory.resolve("download"));
        Files.copy(midi, download.resolve("song.mid"));
        Files.copy(wav, download.resolve("mix.wav"));
        assertEquals(0, Main.run(new String[]{"reconstruct", "import", download.toString(), imported.toString(),
                "--catalogue", index.toString(), "--target", "s2"}, System.out, err), errors.toString());
        assertEquals("s2", artifact(imported, "import.json").getAsJsonObject("runRequest")
                .getAsJsonObject("options").get("target").getAsString());

        for (var target : List.of("s3k", "s1")) {
            Path work = target.equals("s3k") ? staged : classic;
            assertEquals(0, Main.run(new String[]{"reconstruct", "run", midi.toString(), wav.toString(),
                    index.toString(), work.toString(), "--seconds", ".5", "--fm-only", "--lane-policy-preset", "off",
                    "--target", target}, System.out, err), errors.toString());
        }
        assertFalse(artifact(staged, "score.json").getAsJsonObject("inputs").has("target"));
        assertFalse(artifact(staged, "manifest.json").getAsJsonObject("options").has("target"));
        assertEquals("181141a52786c09aa266918b07bdd1306a956e812e6e6a0010b5c678e3433fe2",
                ReconstructionCli.audioHash(staged.resolve("arrangement.smps.json")));
        assertEquals("c113fbbd7ac4e9dac388a500a43784f0d0eb3e81fd13b84f47603565d2f1352f",
                ReconstructionCli.audioHash(staged.resolve("render.wav")));
        assertEquals(0, Main.run(new String[]{"reconstruct", "structure", classic.toString(), "--target", "s1"},
                System.out, err), errors.toString());
        assertEquals(2, Main.run(new String[]{"reconstruct", "structure", classic.toString(), "--target", "s2"},
                System.out, err));
        assertTrue(errors.toString().contains("Saved reconstruction target is s1"));
        assertEquals(0, Main.run(new String[]{"reconstruct", "rerun", classic.toString(), replay.toString()},
                System.out, err), errors.toString());

        for (var work : List.of(classic, replay)) {
            assertEquals("s1", artifact(work, "score.json").getAsJsonObject("inputs").get("target").getAsString());
            assertEquals("s1", artifact(work, "manifest.json").getAsJsonObject("options").get("target").getAsString());
            assertEquals("s1", artifact(work, "arrangement.smps.json").get("target").getAsString());
            try (var zip = new java.util.zip.ZipFile(work.resolve("arrangement.smps.zip").toFile());
                 var input = zip.getInputStream(zip.getEntry("manifest.json"))) {
                assertEquals("s1", JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject().get("driver").getAsString());
            }
        }
        assertEquals(-1, Files.mismatch(classic.resolve("render.wav"), replay.resolve("render.wav")));
    }

    private static com.google.gson.JsonObject artifact(Path dir, String name) throws java.io.IOException {
        return JsonParser.parseString(Files.readString(dir.resolve(name))).getAsJsonObject();
    }

    private void assertUnplannedArtifacts(Path work) throws Exception {
        // The voicing default affects the musical outputs. Keep the upstream stage baselines.
        var expected = java.util.Map.of(
                "allocation.json", "7bf830b90c59fb781aae61f577b624deb53aea4be799eda811893957c2e33cc8",
                "roles.json", "5e08f63fe79f62cdacfaf8552fbe4ddc6612de1a2c7d6323927fa2ffa5324dd2",
                "score.json", "c99fa96c50b6231654f9638622d064fd27c7cfb7c7c6386ac6752ad86615b1b2",
                "structure.json", "b1de076dad1f84fac16e556f9364dada60e0c8001374243d6ee806ad9022cbd1");
        try (var paths = Files.list(work)) {
            var files = new java.util.HashSet<>(expected.keySet());
            files.addAll(List.of("manifest.json", "snapshots", "allocation-affinity.json", "assembly-layout.json",
                    "voices.json", "voicing.json", "report.json", "arrangement.smps.json", "arrangement.smps.zip", "render.wav"));
            assertEquals(files, paths.map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet()));
        }
        var report = JsonParser.parseString(Files.readString(work.resolve("report.json"))).getAsJsonObject();
        assertTrue(report.has("sustainProbe"));
        var voicing = ReconstructionArtifacts.read(work.resolve("voicing.json"), ReconstructionVoicing.Result.class);
        assertEquals(30, voicing.fitWindows().frames());
        assertFalse(voicing.sourceInstruments().isEmpty());
        voicing.lanes().values().forEach(lane -> assertEquals(1, lane.gain()));
        assertTrue(report.getAsJsonArray("allocationAffinityChanges").isEmpty());
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        for (var entry : expected.entrySet()) {
            Path path = work.resolve(entry.getKey());
            byte[] bytes = Files.readAllBytes(path);
            if (entry.getKey().equals("score.json")) {
                bytes = Files.readString(path).replace(directory.toString(), "@INPUT@")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else if (List.of("structure.json", "roles.json", "allocation.json").contains(entry.getKey())) {
                // Compare the original payload byte for byte apart from the new time-base label.
                String json = Files.readString(path);
                assertEquals("source", JsonParser.parseString(json).getAsJsonObject().get("timeBase").getAsString());
                bytes = json.replace(",\n  \"timeBase\": \"source\"", "")
                        .replace(",\"timeBase\":\"source\"", "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            // Ignore the newer audit-only fields in the upstream stages.
            json = switch (entry.getKey()) {
                case "structure.json" -> json.replaceFirst(",\\s*\"scoreNoteHashes\"\\s*:\\s*[{][^}]*}", "");
                case "roles.json" -> json.replaceFirst(",\n  \"structureNoteHashes\": [{][^}]*}", "")
                        .replaceAll(",\n +\"assignmentReason\": \"[^\"]+\"", "");
                case "allocation.json" -> json.replaceAll(",\n +\"(competitorNoteId|relocatedFrom)\": \"[^\"]+\"", "")
                        .replaceAll(",\n +\"blockingNoteIds\": \\x5B[^\\x5D]*\\x5D", "")
                        .replaceAll(",\n +\"reason\": \"psg-spill\"", "");
                default -> json;
            };
            if (entry.getKey().equals("allocation.json")) {
                var gapFill = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("melodyGapFill");
                assertEquals(0, gapFill.get("beforeSeconds").getAsDouble());
                assertEquals(0, gapFill.get("afterSeconds").getAsDouble());
                json = json.replaceFirst(",\n +\"melodyGapFill\": [{][^}]*}", "");
            }
            var compared = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(entry.getValue(), java.util.HexFormat.of().formatHex(digest.digest(compared)), entry.getKey());
        }
    }

    @Test void emitsEditedVoicingOnBothResumePaths() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        byte[] bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String asset = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, patch.name(), bytes);
        String variant = builder.variant(asset, patch.name(), InstrumentCatalogue.Recipe.plain(), null);
        bytes[1] ^= 1;
        String other = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Edited", bytes);
        String editVariant = builder.variant(other, "Edited", InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();
        Path index = directory.resolve("index.json");
        catalogue.save(index);
        Path wav = directory.resolve("input.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(CataloguePlayback.matchProbe(catalogue, variant, 72, 24)), wav, 60);
        Path midi = directory.resolve("lead.mid");
        var source = new Sequence(Sequence.PPQ, 480);
        var track = source.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 72, 96), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 72, 0), 384));
        MidiSystem.write(source, 0, midi.toFile());

        Path work = directory.resolve("voice-work");
        var errors = new ByteArrayOutputStream();
        var err = new PrintStream(errors);
        assertEquals(0, Main.run(new String[]{"reconstruct", "run", midi.toString(), wav.toString(),
                index.toString(), work.toString()}, System.out, err), errors.toString());
        Path file = work.resolve("voicing.json");
        String original = Files.readString(file);
        assertEquals(0, Main.run(new String[]{"reconstruct", "voice", work.toString()}, System.out, err), errors.toString());
        assertEquals(original, Files.readString(file));
        Files.move(file, work.resolve("previous-voicing.json"));
        assertEquals(0, Main.run(new String[]{"reconstruct", "voice", work.toString()}, System.out, err), errors.toString());
        assertEquals(original, Files.readString(file));
        var artifact = JsonParser.parseString(original).getAsJsonObject();
        var lane = artifact.getAsJsonObject("lanes").getAsJsonObject("FM1");
        var art = lane.getAsJsonObject("articulation");
        lane.addProperty("variantId", editVariant);
        lane.addProperty("gain", .5);
        art.addProperty("gate", .5);
        art.addProperty("minimumReleaseRate", 15);
        lane.add("effects", new com.google.gson.Gson().toJsonTree(ReconstructionVoicing.idiom(
                ReconstructionRoles.Role.melody, "FM5")));
        String edited = artifact.toString();
        Files.writeString(file, edited);

        for (String stage : List.of("voice", "assemble")) {
            assertEquals(0, Main.run(new String[]{"reconstruct", "run", "--from", stage, work.toString()},
                    System.out, err), errors.toString());
            assertEquals(edited, Files.readString(file));
            var project = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
            var lead = project.sequences().stream().filter(s -> s.id().startsWith("FM1-")).findFirst().orElseThrow();
            assertEquals(12, lead.notes().getFirst().duration());
            var compiled = ProjectCompiler.compileWithAssets(project).song();
            int voiceOffset = Byte.toUnsignedInt(compiled[0]) | Byte.toUnsignedInt(compiled[1]) << 8;
            assertEquals(bytes[1], compiled[voiceOffset + 1]);
            for (int offset = 17; offset < 21; offset++)
                assertEquals((bytes[offset] & 0xf0) | 15, Byte.toUnsignedInt(compiled[voiceOffset + offset]));
            var nativeBytes = java.util.HexFormat.of().formatHex(compiled);
            assertTrue(nativeBytes.contains("f00a020204"), "native delayed vibrato");
            var report = JsonParser.parseString(Files.readString(work.resolve("report.json"))).getAsJsonObject();
            assertEquals("FM5", report.getAsJsonArray("effectCopyLanes").get(0).getAsString());
            assertEquals(1, report.get("echoNotes").getAsInt());
        }
    }

    @Test void scopedVoicingRestoresInsideAHeldNoteAndSurvivesRefit() throws Exception {
        var builder = new InstrumentCatalogue.Builder();
        var patch = DemoProject.create().voices().getFirst();
        var bytes = new byte[patch.bytes().size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = patch.bytes().get(i).byteValue();
        String original = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Original", bytes);
        String originalVariant = builder.variant(original, "Original", InstrumentCatalogue.Recipe.plain(), null);
        bytes[1] ^= 1;
        String replacement = builder.asset(InstrumentCatalogue.Kind.FM_VOICE, "Replacement", bytes);
        String candidate = builder.variant(replacement, "Replacement", InstrumentCatalogue.Recipe.plain(), null);
        var catalogue = builder.build();
        var index = directory.resolve("scoped-catalogue.json");
        catalogue.save(index);
        var wav = directory.resolve("scoped-reference.wav");
        WaveFile.render(ProjectCompiler.compileWithAssets(CataloguePlayback.matchProbe(catalogue, candidate, 72, 24)), wav, 60);
        var midi = directory.resolve("scoped.mid");
        var source = new Sequence(Sequence.PPQ, 480);
        var track = source.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 72, 96), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 72, 0), 768));
        MidiSystem.write(source, 0, midi.toFile());
        var work = directory.resolve("scoped-work");
        var errors = new ByteArrayOutputStream();
        var err = new PrintStream(errors);
        assertEquals(0, Main.run(new String[]{"reconstruct", "run", midi.toString(), wav.toString(),
                index.toString(), work.toString()}, System.out, err), errors.toString());
        var file = work.resolve("voicing.json");
        String saved = Files.readString(file);
        var artifact = JsonParser.parseString(saved).getAsJsonObject();
        var current = artifact.getAsJsonObject("sourceInstruments").getAsJsonObject("part-0")
                .getAsJsonObject("fm").get("variantId").getAsString();
        var other = current.equals(candidate) ? originalVariant : candidate;
        var auditionOut = work.resolve("audition.wav");
        assertEquals(0, Main.run(new String[]{"reconstruct", "audition", work.toString(), "--lane", "FM1",
                "--start", "0.1", "--end", "0.5", "--variant", current, "--variant", other, auditionOut.toString()},
                System.out, err), errors.toString());
        byte[] full = Files.readAllBytes(work.resolve("render.wav"));
        byte[] excerpt = Files.readAllBytes(work.resolve("audition-1.wav"));
        assertEquals(44 + 24 * 3200, excerpt.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(full, 44 + 6 * 3200, 44 + 30 * 3200),
                java.util.Arrays.copyOfRange(excerpt, 44, excerpt.length));
        assertEquals(saved, Files.readString(file));
        assertFalse(java.util.Arrays.equals(excerpt, Files.readAllBytes(work.resolve("audition-2.wav"))));
        var lane = artifact.getAsJsonObject("lanes").getAsJsonObject("FM1");
        lane.addProperty("variantId", originalVariant);
        lane.addProperty("gain", .4);
        var scopes = JsonParser.parseString("""
                [{"startTick":10,"endTick":20,"variantId":"%s","gain":0.2,"minimumReleaseRate":15},
                 {"startTick":25,"endTick":35,"vibrato":{"delay":1,"rate":2,"delta":3,"steps":4}},
                 {"startTick":37,"endTick":54,"gain":0.8}]
                """.formatted(candidate)).getAsJsonArray();
        lane.add("overrides", scopes);
        ReconstructionStages.writeArtifact(file, artifact);
        assertEquals(0, Main.run(new String[]{"reconstruct", "assemble", work.toString()},
                System.out, err), errors.toString());
        var project = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        var data = ProjectCompiler.compile(project);
        String nativeBytes = java.util.HexFormat.of().formatHex(data);
        assertTrue(nativeBytes.matches("(?s).*ef01e4..e70aef00e4..e7.*"), nativeBytes);
        assertEquals(2, project.voices().size());
        assertTrue(nativeBytes.contains("f001020304"), nativeBytes);
        var commands = project.sequences().getFirst().commands();
        assertTrue(commands.stream().anyMatch(command -> command.tick() == 54
                && command.command() == com.opensmpsdeck.project.NativeCommand.VOLUME),
                "Restore base volume on the time boundary even after key-off");
        assertEquals(0, Main.run(new String[]{"reconstruct", "voice", work.toString(), "--lane", "FM1"},
                System.out, err), errors.toString());
        var refitted = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .getAsJsonObject("lanes").getAsJsonObject("FM1");
        assertEquals(.4, refitted.get("gain").getAsDouble());
        assertEquals(scopes, refitted.get("overrides"));

        var prior = Files.createDirectory(directory.resolve("prior-scopes"));
        for (String name : List.of("score.json", "structure.json", "roles.json", "allocation.json", "voicing.json",
                "arrangement.smps.json", "report.json", "render.wav"))
            Files.copy(work.resolve(name), prior.resolve(name));

        var overlapping = JsonParser.parseString("""
                [{"startTick":10,"endTick":54,"variantId":"%s"},
                 {"startTick":12,"endTick":20,"gain":0.2},
                 {"startTick":25,"endTick":35,"vibrato":{"delay":1,"rate":2,"delta":4,"steps":4}},
                 {"startTick":37,"endTick":50,"gain":0.8}]
                """.formatted(candidate)).getAsJsonArray();
        lane.add("overrides", overlapping);
        ReconstructionStages.writeArtifact(file, artifact);
        assertEquals(0, Main.run(new String[]{"reconstruct", "assemble", work.toString()},
                System.out, err), errors.toString());
        var diff = ReconstructionTrace.diff(prior, work, null);
        var scopeChanges = diff.getAsJsonArray("voicingChanges").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject).filter(entry -> entry.has("scope")).toList();
        assertEquals(List.of("removed", "changed", "removed", "added", "added", "added"),
                scopeChanges.stream().map(change -> change.get("change").getAsString()).toList());
        var changed = scopeChanges.get(1);
        assertEquals("FM1", changed.get("lane").getAsString());
        assertEquals(25, changed.getAsJsonObject("scope").get("startTick").getAsInt());
        assertEquals(4, changed.getAsJsonObject("after").getAsJsonObject("vibrato").get("delta").getAsInt());
        assertTrue(diff.getAsJsonObject("renderedPcm").get("changedFrames").getAsLong() > 0);
        var held = ProjectFiles.load(work.resolve("arrangement.smps.json")).project();
        var labels = EvidenceNotes.patchChanges(held,
                new ReconstructionView.Span(0, 1, ReconstructionTimeMap.TimeBase.project, null), List.of("FM1"));
        assertEquals(List.of(0.0, 10 / 60.0, 54 / 60.0),
                labels.stream().map(EvidenceNotes.PatchChange::time).toList());
        assertEquals(labels.getFirst().name(), labels.getLast().name());
        assertNotEquals(labels.getFirst().name(), labels.get(1).name());
        lane.add("overrides", scopes);
        scopes.add(scopes.get(0).deepCopy());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ReconstructionStages.writeArtifact(file, artifact))
                .getMessage().contains("overlap"));
        scopes.remove(scopes.size() - 1);
        scopes.get(0).getAsJsonObject().add("noteIds", JsonParser.parseString("[\"missing\"]"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ReconstructionStages.writeArtifact(file, artifact))
                .getMessage().contains("missing"));
    }

    @Test void keepVoicingDropsAndReportsOnlyStalePartEntries() {
        var harmony = ReconstructionRoles.Role.harmony;
        var retained = new ReconstructionVoicing.Instrument(0, null, "envelope");
        var stale = new ReconstructionVoicing.Instrument(1, null, "old-envelope");
        var lane = new ReconstructionVoicing.Lane(harmony, null, List.of(), .6,
                ReconstructionVoicing.WRITTEN, ReconstructionVoicing.DRY, "", "", "", List.of(retained, stale))
                .withPartGains(java.util.Map.of(0, .8, 1, .9));
        var previous = new ReconstructionVoicing.Result(1, java.util.Map.of("PSG2", lane), null);
        var note = new ReconstructionStages.Note("note", 0, .1, .2, 64, 90, "midi", 1);
        var entry = new ReconstructionAllocation.Entry(note, ReconstructionRoles.Role.counter, "PSG2", 6, 12,
                ReconstructionAllocation.Outcome.kept, null, null);
        var allocated = java.util.Map.of("PSG2", List.of(entry));
        var messages = new java.util.ArrayList<String>();

        assertTrue(ReconstructionVoicing.lanesNeedingFit(previous, allocated).isEmpty());
        var result = ReconstructionVoicing.keepCompatible(previous, null, allocated, messages::add);
        var kept = result.lanes().get("PSG2");
        assertEquals(entry.role(), kept.role());
        assertEquals(.6, kept.gain());
        assertEquals(List.of(retained), kept.instruments());
        assertEquals(java.util.Map.of(0, .8), kept.partGains());
        assertEquals(List.of("Dropped PSG2 partGains[1] (part no longer allocated)",
                "Dropped PSG2 instrument for part 1 (no longer allocated)"), messages);
    }

    @Test void onlyRefitsLevelsOnExplicitlyTargetedLanes() {
        var dry = ReconstructionVoicing.DRY;
        var art = ReconstructionVoicing.WRITTEN;
        var melody = ReconstructionRoles.Role.melody;
        var harmony = ReconstructionRoles.Role.harmony;
        var lead = new ReconstructionVoicing.Lane(melody, "lead", List.of(), .5, art, dry, "", "", "", List.of())
                .withPartGains(java.util.Map.of(0, .8));
        var backing = new ReconstructionVoicing.Lane(harmony, null, List.of(), 1.5, art, dry, "", "", "", List.of());
        var fit = new ReconstructionVoicing.Lane(melody, "next", List.of(), .8, art, dry, "", "", "", List.of());
        var before = new ReconstructionVoicing.Result(1, java.util.Map.of("FM1", lead, "PSG2", backing), null);
        var proposed = new ReconstructionVoicing.Result(1, java.util.Map.of("FM1", fit, "PSG2", fit), null);
        var note = new ReconstructionStages.Note("lead", 0, .1, .2, 64, 90, "midi", 1);
        var allocated = new ReconstructionAllocation.Entry(note, melody, "FM1", 6, 12,
                ReconstructionAllocation.Outcome.kept, null, null);
        var placement = java.util.Map.of("FM1", List.of(allocated));
        var targets = java.util.Set.of("FM1");
        var keep = ReconstructionVoicing.preserveRefit(proposed, before, targets, false, java.util.Set.of(), placement);
        assertEquals(.5, keep.lanes().get("FM1").gain());
        assertEquals(lead.partGains(), keep.lanes().get("FM1").partGains());
        assertEquals("next", keep.lanes().get("FM1").variantId());
        assertSame(backing, keep.lanes().get("PSG2"));
        var levels = ReconstructionVoicing.preserveRefit(proposed, before, targets, true, java.util.Set.of(), placement);
        assertEquals(.8, levels.lanes().get("FM1").gain());
        assertSame(backing, levels.lanes().get("PSG2"));
        var swapped = new ReconstructionVoicing.Lane(harmony, "pad", List.of(), 1.2, art, dry, "", "", "", List.of());
        var withRoleChange = new ReconstructionVoicing.Result(1, java.util.Map.of("FM1", swapped, "PSG2", fit), null);
        var sameNotes = new ReconstructionAllocation.Entry(note, harmony, "FM1", 6, 12,
                ReconstructionAllocation.Outcome.kept, null, null);
        var oldAllocation = new ReconstructionAllocation.Result(1, List.of(allocated));
        var sameMaterial = ReconstructionVoicing.changedMaterial(oldAllocation,
                new ReconstructionAllocation.Result(1, List.of(sameNotes)));
        assertTrue(sameMaterial.isEmpty());
        var roleChange = ReconstructionVoicing.preserveRefit(withRoleChange, before, targets, false, sameMaterial, placement);
        assertEquals(.5, roleChange.lanes().get("FM1").gain());
        assertEquals(lead.partGains(), roleChange.lanes().get("FM1").partGains());

        var shorter = sameNotes.shorten(10, "test", null);
        var changedMaterial = ReconstructionVoicing.changedMaterial(oldAllocation,
                new ReconstructionAllocation.Result(1, List.of(shorter)));
        assertEquals(targets, changedMaterial);
        var gateChange = ReconstructionVoicing.preserveRefit(withRoleChange, before, targets, false, changedMaterial, placement);
        assertEquals(1.2, gateChange.lanes().get("FM1").gain());
        assertNull(gateChange.lanes().get("FM1").partGains());
    }

    @Test void removesSystematicCrossPartBassOctavesButPreservesThemOnRequest() {
        var bass = new MidiScore.Part(1, "Bass", 34, false);
        var lead = new MidiScore.Part(2, "Lead", 81, false);
        var notes = new java.util.ArrayList<MidiScore.Note>();
        for (int i = 0; i < 20; i++) {
            notes.add(new MidiScore.Note(bass.id(), 36, 85, i * 200_000L, i * 200_000L + 100_000));
            notes.add(new MidiScore.Note(lead.id(), 48, 85, i * 200_000L, i * 200_000L + 100_000));
        }
        notes.add(new MidiScore.Note(lead.id(), 48, 85, 4_000_000, 4_100_000));
        var score = new MidiScore(List.of(bass, lead), notes, List.of());
        var prepared = S3kReconstruction.prepare(score, 4_200_000, false);
        assertEquals(21, prepared.notes().size());
        assertEquals(20, prepared.reductions().size());
        assertTrue(prepared.reductions().stream().allMatch(change -> change.change() == S3kReconstruction.Change.OCTAVE_DOUBLE));
        assertTrue(prepared.notes().contains(notes.getLast()));
        assertEquals(41, S3kReconstruction.prepare(score, 4_200_000, true).notes().size());
    }
}

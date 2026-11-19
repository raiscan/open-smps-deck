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
        assertEquals(500_000, ReferenceAudio.durationMicros(output.resolve("render.wav")));
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

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

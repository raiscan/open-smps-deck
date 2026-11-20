package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionViewTest {
    @Test void resolvesSyntheticFundamentalOnThePitchAxis() {
        var samples = new float[48_000];
        for (int i = 0; i < samples.length; i++)
            samples[i] = (float) Math.sin(Math.TAU * 440 * i / 48_000);
        var spectrum = EvidenceSpectrum.analyze(new EvidenceSpectrum.Audio(samples, 48_000, 0, 1),
                new double[]{.5}, 65, 73, 81, 1, false);
        assertEquals(255, spectrum.getRaster().getSample(0, 40, 0));
        assertTrue(spectrum.getRaster().getSample(0, 20, 0) < 80);
        assertTrue(spectrum.getRaster().getSample(0, 60, 0) < 80);
    }

    @Test void harmonicSumEmphasizesAPresentFundamental() {
        var samples = new float[48_000];
        for (int i = 0; i < samples.length; i++) {
            double phase = Math.TAU * 440 * i / 48_000;
            samples[i] = (float) (.25 * Math.sin(phase) + Math.sin(2 * phase));
        }
        var audio = new EvidenceSpectrum.Audio(samples, 48_000, 0, 1);
        var raw = EvidenceSpectrum.analyze(audio, new double[]{.5}, 69, 81, 130, 1, false);
        var emphasized = EvidenceSpectrum.analyze(audio, new double[]{.5}, 69, 81, 130, 1, true);
        assertTrue(emphasized.getRaster().getSample(0, 125, 0) > raw.getRaster().getSample(0, 125, 0));
    }

    @Test void mapsWarpedOverlaysAndHarmonicsToPixelsAndReturnsLegend(@TempDir Path work) throws Exception {
        Path wav = work.resolve("tone.wav");
        short[] silence = new short[1600];
        WaveFile.write(wav, 60, sink -> {
            for (int tick = 0; tick < 60; tick++) sink.accept(silence);
        });
        var note = new MidiScore.Note(0, 69, 100, 125_000, 375_000);
        String id = ReconstructionStages.midiNoteKey(note);
        var grid = new ReconstructionTempo.Options(false, null, null, "60");
        var inputs = new ReconstructionStages.Inputs("notes.mid", wav.toString(), "catalogue.json", "Synthetic",
                30, false, false, false, false, Map.of(), null, 7.5, grid, null);
        var beats = new ReconstructionBeatMap(List.of(0.0, .5, 1.0), 0, 4, 1);
        var score = new ReconstructionStages.Score(1, 1_000_000, 60, 60, inputs, beats.bars(1),
                new MidiScore(List.of(new MidiScore.Part(0, "Tone", 0, false)), List.of(note), List.of()),
                Map.of(0, 1), List.of(new ReconstructionStages.Note(id, 0, .125, .375, 69, 100, "midi", 1)),
                List.of(), List.of(), null, null, beats);
        ReconstructionArtifacts.write(work.resolve("score.json"), score);
        java.nio.file.Files.writeString(work.resolve("edits.json"), """
                {"version":1,"edits":[{"id":"pickup","op":"insertNote","reason":"Synthetic onset",
                  "part":0,"start":0.42,"end":0.47,"pitch":72}]}
                """);

        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var png = work.resolve("view.png");
        assertEquals(0, Main.run(new String[]{"reconstruct", "view", work.toString(), "--start", "0",
                "--end", "1", "--time", "project", "--wav", wav.toString(), "--pitch-min", "67", "--pitch-max", "83",
                "--harmonics", id, "--width", "800", png.toString()},
                new PrintStream(output), new PrintStream(errors)), errors.toString());

        var result = JsonParser.parseString(output.toString()).getAsJsonObject();
        var drawn = result.getAsJsonArray("notes").get(0).getAsJsonObject();
        assertEquals(id, drawn.get("id").getAsString());
        assertEquals("A4", drawn.get("pitchName").getAsString());
        assertEquals("kept", drawn.get("status").getAsString());
        assertEquals(0, drawn.get("part").getAsInt());
        assertTrue(result.get("warped").getAsBoolean());
        assertEquals(.125, drawn.getAsJsonObject("timing").get("sourceStart").getAsDouble());
        assertEquals(.25, drawn.getAsJsonObject("timing").get("projectStart").getAsDouble());
        assertEquals(.75, drawn.getAsJsonObject("timing").get("projectEnd").getAsDouble());
        assertEquals(15, drawn.getAsJsonObject("timing").get("startTick").getAsInt());
        var insertion = result.getAsJsonArray("notes").get(1).getAsJsonObject();
        assertEquals("edit-pickup", insertion.get("id").getAsString());
        assertEquals("pending", insertion.get("evidence").getAsString());
        var harmonics = result.getAsJsonArray("harmonics");
        assertEquals(81, harmonics.get(1).getAsJsonObject().get("pitch").getAsDouble(), 1e-8);
        assertEquals(880, harmonics.get(1).getAsJsonObject().get("hz").getAsDouble(), 1e-8);

        var image = ImageIO.read(png.toFile());
        assertEquals(800, image.getWidth());
        var plot = result.getAsJsonObject("plot");
        int left = plot.get("left").getAsInt(), width = plot.get("width").getAsInt();
        int top = plot.get("sourceTop").getAsInt(), height = plot.get("pitchHeight").getAsInt();
        int attack = left + width / 4;
        int harmonicY = top + (int) Math.round(height * 2.5 / 17);
        int noteY = top + (int) Math.round(height * 14.5 / 17);
        assertNotEquals(image.getRGB(attack - 6, noteY), image.getRGB(attack, noteY));
        int harmonic = image.getRGB(attack + 2, harmonicY);
        assertTrue(((harmonic >> 16) & 255) > (harmonic & 255) + 40);
    }
}

package com.opensmpsdeck.headless.bench;

import com.opensmpsdeck.headless.Main;
import com.opensmpsdeck.headless.WaveFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchGroupedMidiTest {
    @TempDir Path directory;

    @Test void nestedSamePitchGatesKeepTheirReleasesAndOneLogicalPart() throws Exception {
        Path midi = directory.resolve("grouped.mid");
        BenchMidi.writeGrouped(List.of(
                new BenchMidi.Event("backing", false, .1, .7, 64),
                new BenchMidi.Event("backing", false, .2, .3, 64),
                new BenchMidi.Event("drums", true, .2, .4, 38)), BenchMidi.Profile.clean, midi);
        Path mix = directory.resolve("mix.wav");
        WaveFile.write(mix, 60, sink -> {
            for (int frame = 0; frame < 60; frame++) sink.accept(new short[1600]);
        });
        Path catalogue = directory.resolve("catalogue.json");
        Files.writeString(catalogue, "{}");
        Path work = directory.resolve("work");
        var bytes = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"reconstruct", "transcribe", midi.toString(), mix.toString(),
                catalogue.toString(), work.toString()}, new PrintStream(bytes), new PrintStream(bytes)), bytes::toString);
        var prepared = BenchFiles.read(work.resolve("score.json"), BenchLadderTrace.Score.class);
        var score = prepared.source();
        assertEquals(2, score.parts().size());
        assertEquals("backing", score.parts().getFirst().name());
        var tones = score.notes().stream().filter(note -> note.partId() == 0).toList();
        assertEquals(List.of(new BenchReconstruction.Note(0, 64, 100_000, 700_000),
                new BenchReconstruction.Note(0, 64, 200_000, 300_000)), tones);
    }
}

package com.opensmpsdeck.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionStemsTest {
    @TempDir Path work;

    @Test void readsEveryDeclaredStemInOrderAndSumsNativeFramesWithoutChangingQuietLevels() throws Exception {
        var mix = write("mix.wav", 1000, 2000, 3000, 4000);
        Files.createDirectory(work.resolve("stems"));
        write("stems/vocals.wav", 10000, -8000, 3000, 5000);
        write("stems/bass.wav", -4000, 6000, 7000, 5000);
        write("stems/piano.wav", 0, 0, 0, 0);
        Files.writeString(work.resolve("separation.json"), """
                {"tool":"synthetic","model":"fixture","version":"1","checkpointSha256":"%s",
                 "settings":{},"stemOrder":["vocals","bass","piano"]}
                """.formatted("0".repeat(64)));
        var stems = ReconstructionStems.open(mix);
        assertEquals(List.of("vocals", "bass", "piano"), stems.names());
        var subset = stems.mix(List.of("vocals", "bass"));
        assertEquals(6000 / 32768.0, subset.sample(0, 0));
        assertEquals(-2000 / 32768.0, subset.sample(0, 1));
        assertEquals(10000 / 32768.0, subset.sample(1.0 / 48000, 0));
        assertEquals(0, subset.sample(2.0 / 48000, 1));
        assertThrows(IllegalArgumentException.class, () -> stems.mix(List.of("unknown")));
    }

    @Test void appliesOnePeakGainToBothChannelsAndRejectsMisalignedStems() throws Exception {
        var mix = write("mix.wav", 0, 0, 0, 0);
        Files.createDirectory(work.resolve("stems"));
        write("stems/one.wav", 24576, -8192, 2048, 0);
        write("stems/two.wav", 24576, -16384, 10240, 0);
        Files.writeString(work.resolve("separation.json"), """
                {"tool":"synthetic","model":"fixture","version":"1","checkpointSha256":"%s",
                 "settings":{},"stemOrder":["one","two"]}
                """.formatted("0".repeat(64)));
        var subset = ReconstructionStems.open(mix).mix(List.of("one", "two"));
        assertEquals(32767 / 32768.0, subset.sample(0, 0));
        assertEquals(-32767 / 65536.0, subset.sample(0, 1));
        assertEquals(32767 / 131072.0, subset.sample(1.0 / 48000, 0));

        var mismatched = work.resolve("stems/two.wav");
        Files.delete(mismatched);
        var silence = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
        WaveFile.write(mismatched, 2, sink -> {
            sink.accept(silence);
            sink.accept(silence);
        });
        assertThrows(IllegalArgumentException.class, () -> ReconstructionStems.open(mix));
    }

    private Path write(String name, int... pcm) throws Exception {
        var path = work.resolve(name);
        var block = new short[S3kRenderer.STEREO_FRAMES_PER_PRESENTATION * 2];
        for (int i = 0; i < pcm.length; i++) block[i] = (short) pcm[i];
        WaveFile.write(path, 1, sink -> sink.accept(block));
        return path;
    }
}

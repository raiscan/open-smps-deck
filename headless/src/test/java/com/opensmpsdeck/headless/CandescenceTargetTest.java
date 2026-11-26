package com.opensmpsdeck.headless;

import com.opensmpsdeck.headless.rom.RomGame;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Candescence is a separate target that follows the stock S3K rulebook. */
class CandescenceTargetTest {
    @TempDir Path directory;

    @Test void playsAndCompilesExactlyLikeS3k() throws Exception {
        var s3k = DemoProject.create();
        var candescence = s3k.withTarget(TargetGame.CANDESCENCE);
        var playback = TargetPlayback.prepare(candescence);
        assertEquals(TargetGame.CANDESCENCE, playback.target());
        assertArrayEquals(ProjectCompiler.compileWithAssets(s3k).song(), playback.compiled().song());

        Path expected = directory.resolve("s3k.wav");
        Path actual = directory.resolve("candescence.wav");
        TargetPlayback.prepare(s3k).writeWave(expected, 60);
        playback.writeWave(actual, 60);
        assertEquals(-1, Files.mismatch(expected, actual));
    }

    @Test void portablePackagesDeclareCandescenceAndReadBack() throws Exception {
        Path source = directory.resolve("song.smps.json");
        var project = DemoProject.create().withTarget(TargetGame.CANDESCENCE);
        Files.writeString(source, ProjectFormat.write(project));
        Path portable = directory.resolve("song.smps.zip");
        var errors = new ByteArrayOutputStream();
        try (var err = new PrintStream(errors)) {
            assertEquals(0, Main.run(new String[]{"export", source.toString(), portable.toString(),
                    "--format", "portable"}, System.out, err), errors.toString());
        }
        assertEquals("candescence", PortablePackages.inspect(portable).get("driver").getAsString());
        var artifact = PortablePackageReader.read(portable);
        assertEquals("candescence", artifact.manifest().driver());
        var assembled = PortablePackageReader.assemble(artifact, List.of());
        assertTrue(assembled.song().length > 0);
    }

    @Test void retailOnlyExportsStayWithRetailGames() throws Exception {
        var project = DemoProject.create().withTarget(TargetGame.CANDESCENCE);
        // Test in game previews the song in the retail game whose rules it follows.
        assertEquals(RomGame.S3K, TestInGame.game(project));

        Path source = directory.resolve("song.smps.json");
        Files.writeString(source, ProjectFormat.write(project));
        var errors = new ByteArrayOutputStream();
        try (var err = new PrintStream(errors)) {
            assertNotEquals(0, Main.run(new String[]{"export", source.toString(), directory.resolve("song.jar").toString(),
                    "--format", "mod", "--replace", "1"}, System.out, err));
        }
        assertTrue(errors.toString().contains("no retail music to replace"), errors::toString);
    }
}

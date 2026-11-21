package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectCliTest {
    @TempDir Path directory;

    @Test void testInGameRequiresAnOutputAndRejectsTheUnreleasedPositionalForm() {
        assertTrue(run("rom-patch", "song.smps.json").err().contains("--out or --bps"));
        assertTrue(run("rom-patch", "song.smps.json", "clean.gen", "patched.gen").err()
                .contains("Unknown option clean.gen"));
        assertTrue(run("rom-patch", "song.smps.json", "--out", "--loop").err()
                .contains("Missing --out value"));
        assertTrue(run("rom-patch", "song.smps.json", "--bps", "song.bps", "--slot", "0x34").err()
                .contains("Music ID must be in"));
    }

    @Test void validatesProjectWithoutCreatingOutputAndReportsWhatWasValidated() throws Exception {
        Path source = source("phrase.json", validSource());
        Result result = run("validate-project", source.toString());
        assertEquals(0, result.exit(), result.err());
        assertTrue(result.out().contains("CLI phrase"), result.out());
        assertTrue(result.out().contains("S3K"), result.out());
        assertTrue(result.out().contains("bytes"), result.out());
        assertEquals("", result.err());
        assertEquals(validSource(), Files.readString(source));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void demoProjectCreatesReusableSourceAndPreservesAnExistingDestination() throws Exception {
        Path source = directory.resolve("demo.json");
        Result created = run("demo-project", source.toString());
        assertEquals(0, created.exit(), created.err());
        byte[] before = Files.readAllBytes(source);
        assertEquals(0, run("validate-project", source.toString()).exit());
        Result repeated = run("demo-project", source.toString());
        assertEquals(2, repeated.exit());
        assertTrue(repeated.err().contains("already exists"), repeated.err());
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test void compilationPreservesSparseHardwareLanesAndSourceRenderingMatchesThePinnedCore() throws Exception {
        Path source = source("phrase.json", validSource());
        Path binary = directory.resolve("phrase.bin");
        Result compiled = run("compile-project", source.toString(), binary.toString());
        assertEquals(0, compiled.exit(), compiled.err());
        byte[] song = Files.readAllBytes(binary);
        assertArrayEquals(song, S3kBinary.validateAndCopy(song));
        assertEquals(6, song[2] & 255, "DAC and every FM slot through FM5");
        assertEquals(3, song[3] & 255, "every PSG slot through PSG3");
        for (int slot = 0; slot < 5; slot++) {
            assertEquals(0xF2, song[read16(song, 6 + slot * 4)] & 255,
                    "unused DAC/FM slot " + slot + " must stop");
        }
        for (int slot = 0; slot < 2; slot++) {
            assertEquals(0xF2, song[read16(song, 6 + 6 * 4 + slot * 6)] & 255,
                    "unused PSG slot " + slot + " must stop");
        }
        Path wave = directory.resolve("phrase.wav");
        Result rendered = run("render-project", source.toString(), wave.toString(), "--frames", "3");
        assertEquals(0, rendered.exit(), rendered.err());
        byte[] wav = Files.readAllBytes(wave);
        assertArrayEquals(render(song, 3), Arrays.copyOfRange(wav, 44, wav.length));
        assertEquals(validSource(), Files.readString(source));
    }

    @Test void compiledPsgMiddleCRendersAtItsMidiPitchThroughThePinnedCore() throws Exception {
        var sequence = new Sequence("psg", "Middle C", "PSG", 96, null, 127,
                List.of(new NoteEvent("middle-c", 0, 96, 60)));
        var project = new StudioProject(1, "PSG pitch", "S3K", 24, 0, List.of(),
                List.of(sequence), List.of(new Clip("clip", "psg", "PSG1", 0, 96)));
        byte[] pcm = render(ProjectCompiler.compile(project), 60);

        // The middle half-second excludes key-on settling. A midpoint threshold
        // removes the pinned mixer/chip's DC offset without changing the period.
        int start = S3kRenderer.SAMPLE_RATE / 4, end = S3kRenderer.SAMPLE_RATE * 3 / 4;
        int[] left = new int[end];
        int minimum = Short.MAX_VALUE, maximum = Short.MIN_VALUE;
        for (int sample = start; sample < end; sample++) {
            left[sample] = (short) ((pcm[sample * 4] & 255) | (pcm[sample * 4 + 1] & 255) << 8);
            minimum = Math.min(minimum, left[sample]);
            maximum = Math.max(maximum, left[sample]);
        }
        assertTrue(maximum - minimum > 32, "PSG must produce a measurable signal");
        double midpoint = (minimum + maximum) / 2.0;
        int crossings = 0, first = -1, last = -1;
        for (int sample = start + 1; sample < end; sample++) {
            if (left[sample - 1] < midpoint && left[sample] >= midpoint) {
                if (first < 0) first = sample;
                last = sample;
                crossings++;
            }
        }
        assertTrue(crossings > 50, "PSG must sustain throughout the steady region");
        double frequency = (crossings - 1.0) * S3kRenderer.SAMPLE_RATE / (last - first);
        assertEquals(261.63, frequency, 3.0,
                "MIDI 60 is C4; a shared FM/PSG note-byte offset incorrectly produces C6");
    }

    @Test void invalidAndUnresolvedProjectsFailBeforeAnyOutputIsCreated() throws Exception {
        List<String> invalid = List.of("{broken", validSource().replace("\"tempo\": 128", "\"tempo\": 128, \"unsupported\": true"),
                validSource().replace("\"sequenceId\": \"fm-source\"", "\"sequenceId\": \"missing-source\""));
        List<String> diagnostic = List.of("JSON", "unsupported", "missing-source");
        for (int i = 0; i < invalid.size(); i++) {
            Path source = source("invalid-" + i + ".json", invalid.get(i));
            for (String command : List.of("validate-project", "compile-project", "render-project")) {
                Path output = directory.resolve(command + "-" + i + ".output");
                Result result = command.equals("validate-project") ? run(command, source.toString())
                        : run(command, source.toString(), output.toString());
                assertEquals(2, result.exit(), result.out());
                assertTrue(result.err().toLowerCase().contains(diagnostic.get(i).toLowerCase()), result.err());
                assertFalse(Files.exists(output));
                assertEquals(invalid.get(i), Files.readString(source));
            }
        }
    }

    @Test void sourceAndExistingOutputsSurviveSameFileAliasesAndOutputCollisions() throws Exception {
        Path source = source("phrase.json", validSource());
        Path alias = directory.resolve("same-source.json");
        Files.createLink(alias, source);
        for (String command : List.of("compile-project", "render-project")) {
            for (Path output : List.of(source, directory.resolve(".").resolve("phrase.json"), alias)) {
                Result result = run(command, source.toString(), output.toString());
                assertEquals(2, result.exit());
                assertTrue(result.err().contains("different files"), result.err());
                assertEquals(validSource(), Files.readString(source));
            }
            Path existing = source(command + ".existing", "keep me");
            Result collision = run(command, source.toString(), existing.toString());
            assertEquals(2, collision.exit());
            assertTrue(collision.err().contains("already exists"), collision.err());
            assertEquals("keep me", Files.readString(existing));
        }
    }

    @Test void compilerBankLimitIsReportedBeforeAnyOutputIsCreated() throws Exception {
        Path source = source("too-long.json", validSource().replace("\"startTick\": 0, \"lengthTicks\": 36",
                "\"startTick\": 0, \"lengthTicks\": 2147483647"));
        var error = assertThrows(ProjectCompiler.BankLimitException.class,
                () -> ProjectCompiler.compile(ProjectFormat.read(Files.readString(source))));
        for (String command : List.of("validate-project", "compile-project", "compile-package", "render-project", "export")) {
            Path output = directory.resolve(command + ".output");
            var format = command.equals("export") ? List.of("wav", "smps", "package") : List.of("");
            for (String target : format) {
                Result result = switch (command) {
                    case "validate-project" -> run(command, source.toString());
                    case "export" -> run(command, source.toString(), output.toString(), "--format", target);
                    default -> run(command, source.toString(), output.toString());
                };
                assertEquals(2, result.exit(), command + " " + target);
                assertEquals("Error: " + error.getMessage(), result.err().strip());
                assertFalse(Files.exists(output));
            }
        }
    }

    @Test void oversizedSourceAndInvalidFrameArgumentsFailWithoutCreatingOutput() throws Exception {
        int limit = ProjectFiles.maxSourceBytes();
        Path oversized = directory.resolve("oversized.json");
        try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(limit + 1L);
        }
        Path source = source("phrase.json", validSource());
        for (String command : List.of("validate-project", "compile-project", "render-project")) {
            Path output = directory.resolve(command + ".output");
            Result result = command.equals("validate-project") ? run(command, oversized.toString())
                    : run(command, oversized.toString(), output.toString());
            assertEquals(2, result.exit());
            assertTrue(result.err().contains(limit + " byte UTF-8 source limit"), result.err());
            assertTrue(result.err().contains(ProjectFiles.SOURCE_LIMIT_PROPERTY), result.err());
            assertFalse(Files.exists(output));
        }
        for (String frames : List.of("0", "36001", "many")) {
            Path output = directory.resolve("bad-frames-" + frames + ".wav");
            assertEquals(2, run("render-project", source.toString(), output.toString(), "--frames", frames).exit());
            assertFalse(Files.exists(output));
        }
    }

    private Path source(String name, String text) throws Exception {
        return Files.writeString(directory.resolve(name), text);
    }

    private static Result run(String... arguments) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int exit = Main.run(arguments, new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Result(exit, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private static byte[] render(byte[] song, int frames) throws Exception {
        var output = new ByteArrayOutputStream();
        S3kRenderer.render(song, frames, pcm -> {
            for (short sample : pcm) { output.write(sample); output.write(sample >>> 8); }
        });
        return output.toByteArray();
    }

    private static int read16(byte[] bytes, int at) { return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8; }

    private static String validSource() {
        return """
                {
                  "formatVersion": 1, "name": "CLI phrase", "mode": "S3K", "ticksPerBeat": 24, "tempo": 128,
                  "voices": [{"id": "voice", "name": "Original patch", "bytes":
                    [7, 1, 2, 3, 4, 31, 31, 31, 31, 0, 0, 0, 0, 0, 0, 0, 0, 15, 15, 15, 15, 32, 40, 48, 56]}],
                  "sequences": [
                    {"id": "fm-source", "name": "FM phrase", "type": "FM", "lengthTicks": 24,
                     "voiceId": "voice", "volume": 0, "notes": [{"id": "fm-note", "tick": 0, "duration": 12, "pitch": 60}]},
                    {"id": "psg-source", "name": "PSG phrase", "type": "PSG", "lengthTicks": 24,
                     "voiceId": null, "volume": 88, "notes": [{"id": "psg-note", "tick": 0, "duration": 12, "pitch": 48}]}
                  ],
                  "clips": [
                    {"id": "fm-clip", "sequenceId": "fm-source", "lane": "FM5", "startTick": 0, "lengthTicks": 36},
                    {"id": "psg-clip", "sequenceId": "psg-source", "lane": "PSG3", "startTick": 12, "lengthTicks": 24}
                  ]
                }
                """;
    }

    private record Result(int exit, String out, String err) {}
}

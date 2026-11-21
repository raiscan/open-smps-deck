package com.opensmpsdeck.headless;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class FfmpegAudio {
    private FfmpegAudio() {}

    static void checkAvailable() throws IOException {
        run(List.of("ffmpeg", "-version"), null);
    }

    static void encode(Path wav, Path output, boolean mp3) throws IOException {
        var command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-v", "error", "-n", "-i", wav.toString()));
        if (mp3) command.addAll(List.of("-codec:a", "libmp3lame", "-q:a", "2", "-f", "mp3"));
        else command.addAll(List.of("-codec:a", "copy", "-fflags", "+bitexact", "-map_metadata", "-1", "-f", "wav"));
        command.add(output.toString());
        var diagnostic = Files.createTempFile(output.toAbsolutePath().getParent(), ".ffmpeg-", ".log");
        try {
            run(command, diagnostic);
        } finally {
            Files.deleteIfExists(diagnostic);
        }
    }

    private static void run(List<String> command, Path diagnostic) throws IOException {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.redirectOutput(diagnostic == null ? ProcessBuilder.Redirect.DISCARD
                : ProcessBuilder.Redirect.to(diagnostic.toFile()));
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new IOException("WAV and MP3 export require ffmpeg on PATH (with libmp3lame for MP3)", failure);
        }
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                String detail = diagnostic == null ? "" : Files.readString(diagnostic).strip();
                throw new IOException("ffmpeg audio export failed (exit " + exit + ")" + (detail.isEmpty() ? "" : ": " + detail));
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Audio export cancelled");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}

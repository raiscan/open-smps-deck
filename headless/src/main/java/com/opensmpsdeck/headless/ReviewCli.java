package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.AudioReference;
import com.opensmpsdeck.project.ProjectEdits;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.ReviewNote;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ReviewCli {
    private ReviewCli() {}

    static void run(String[] args, PrintStream out) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException("Expected a subcommand and project path");
        Path file = Path.of(args[2]);
        var loaded = ProjectFiles.load(file);
        var project = loaded.project();
        switch (args[0] + " " + args[1]) {
            case "notes list" -> {
                if (args.length > 4 || args.length == 4 && !args[3].equals("--open")) {
                    throw new IllegalArgumentException("Usage: notes list PROJECT [--open]");
                }
                out.print(ProjectFormat.writeReviewNotes(args.length == 4
                        ? project.notes().stream().filter(ReviewNote::isOpen).toList() : project.notes()));
            }
            case "notes add" -> {
                var options = options(args, 3, Set.of("--at", "--end", "--lane", "--clip", "--text", "--author"));
                var note = new ReviewNote(id("note"), Double.parseDouble(required(options, "--at")),
                        options.containsKey("--end") ? Double.parseDouble(options.get("--end")) : null,
                        options.get("--lane"), options.get("--clip"), required(options, "--text"),
                        required(options, "--author"), ReviewNote.OPEN, Instant.now().toString(), null);
                ProjectFiles.save(file, ProjectEdits.addReviewNote(project, note), loaded.revision());
                out.println(note.id());
            }
            case "notes resolve" -> {
                if (args.length < 4) throw new IllegalArgumentException("Expected a note id");
                var options = options(args, 4, Set.of("--reply", "--author"));
                var reply = options.isEmpty() ? null : new ReviewNote.Reply(required(options, "--reply"),
                        required(options, "--author"), Instant.now().toString());
                ProjectFiles.save(file, ProjectEdits.resolveReviewNote(project, args[3], reply), loaded.revision());
                out.println(args[3]);
            }
            case "reference add" -> {
                if (args.length < 4) throw new IllegalArgumentException("Expected a WAV path");
                var options = options(args, 4, Set.of("--offset", "--label", "--role"));
                Path wav = Path.of(args[3]);
                if (!Files.isRegularFile(wav) || Files.isSameFile(file, wav)) {
                    throw new IllegalArgumentException("Expected a separate WAV file: " + wav);
                }
                requireWave(wav);
                var reference = new AudioReference(id("ref"), options.getOrDefault("--label", wav.getFileName().toString()),
                        AudioReference.pathFor(file, wav), Double.parseDouble(options.getOrDefault("--offset", "0")),
                        0, options.get("--role"));
                ProjectFiles.save(file, ProjectEdits.addReference(project, reference), loaded.revision());
                out.println(reference.id());
            }
            default -> throw new IllegalArgumentException("Unknown review command: " + args[0] + " " + args[1]);
        }
    }

    private static void requireWave(Path wav) throws IOException {
        try {
            if (!AudioFileFormat.Type.WAVE.equals(AudioSystem.getAudioFileFormat(wav.toFile()).getType())) {
                throw new IllegalArgumentException("Expected a WAV file: " + wav);
            }
        } catch (UnsupportedAudioFileException failure) {
            throw new IllegalArgumentException("Unsupported WAV file: " + wav, failure);
        }
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static Map<String, String> options(String[] args, int start, Set<String> allowed) {
        Map<String, String> result = new HashMap<>();
        for (int i = start; i < args.length; i += 2) {
            String key = args[i];
            if (!allowed.contains(key) || i + 1 == args.length || result.putIfAbsent(key, args[i + 1]) != null) {
                throw new IllegalArgumentException("Invalid option: " + key);
            }
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    static void usage(PrintStream out) {
        out.println("       notes list PROJECT [--open]");
        out.println("       notes add PROJECT --at S [--end S] [--lane L] [--clip C] --text T --author A");
        out.println("       notes resolve PROJECT ID [--reply T --author A]");
        out.println("       reference add PROJECT WAV [--offset S] [--label L] [--role R]");
    }
}

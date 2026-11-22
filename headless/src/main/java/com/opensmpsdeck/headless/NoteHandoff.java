package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** The line-oriented, plain-text contract shared by Studio and the describe commands. */
public final class NoteHandoff {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String MAGIC = "opensmpsdeck-notes v1";
    private static final int LIMIT = 8;

    public record Project(String path, String song, String view, String revision, Boolean draft,
                          String sequence, List<String> clip) {}

    public record Context(Integer firstBar, Integer lastSelectedBar, Integer throughBar,
                          int followingBars, int selectedCount, int omittedCount) {}

    public record ViewRange(double from, double to, Integer minMidi, Integer maxMidi, Map<String, Object> viewState) {}

    public record Area(String clock, double startSeconds, double endSeconds, long startTick, long endTick,
                       Integer firstBar, Integer lastBar, Integer minMidi, Integer maxMidi, Map<String, Object> viewState) {}

    public record Capture(String payload, String path, String reference, String footer) {}

    public record Note(String id, String event, String part, String role, String voice, String lane,
                       Integer bar, Double beat, Double sourceSeconds, Double projectSeconds,
                       long tick, Integer sequenceTick, long durationTicks, Double durationSeconds,
                       String pitch, int midi, Integer velocity, String source, Double confidence,
                       String playback, String reason, String allocationReason, String previousLane, List<String> competitors,
                       List<String> edits, String bend, String vibrato) {}

    record Block(Project project, Context context, List<Note> notes, String command, Area lasso, String reference) {
        Block(Project project, Context context, List<Note> notes, String command) {
            this(project, context, notes, command, null, null);
        }

        String text() {
            var lines = new StringJoiner("\n", "", "\n").add(MAGIC)
                    .add("project: " + JSON.toJson(project)).add("context: " + JSON.toJson(context));
            if (lasso != null) {
                lines.add("lasso: " + JSON.toJson(lasso));
                lines.add("ref: " + reference);
            }
            notes.forEach(note -> lines.add("note: " + JSON.toJson(note)));
            if (command != null) lines.add("fetch: " + command);
            return lines.toString();
        }

        Capture capture(Area area) {
            String ref = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
            var block = new Block(project, context, notes, command, area, ref);
            var leaf = java.nio.file.Path.of(project.path()).getFileName();
            String place = area.firstBar() == null ? "" : "bars " + area.firstBar()
                    + (area.lastBar().equals(area.firstBar()) ? "" : "–" + area.lastBar()) + "  •  ";
            String footer = String.format(Locale.ROOT, "%s · %s%n%s%.3f–%.3f s%nRef %s",
                    project.song(), leaf, place, area.startSeconds(), area.endSeconds(), ref);
            return new Capture(block.text(), project.path(), ref, footer);
        }
    }

    static Block parse(String text) {
        var lines = text.lines().toList();
        if (lines.size() < 3 || !MAGIC.equals(lines.getFirst())
                || !lines.get(1).startsWith("project: ") || !lines.get(2).startsWith("context: "))
            throw new IllegalArgumentException("Expected " + MAGIC + " with project and context");
        var project = JSON.fromJson(lines.get(1).substring(9), Project.class);
        var context = JSON.fromJson(lines.get(2).substring(9), Context.class);
        var notes = new java.util.ArrayList<Note>();
        String cmd = null, ref = null;
        Area area = null;
        for (String line : lines.subList(3, lines.size())) {
            if (line.startsWith("note: ")) notes.add(JSON.fromJson(line.substring(6), Note.class));
            else if (line.startsWith("fetch: ")) cmd = line.substring(7);
            else if (line.startsWith("lasso: ")) area = JSON.fromJson(line.substring(7), Area.class);
            else if (line.startsWith("ref: ")) ref = line.substring(5);
            else throw new IllegalArgumentException("Unknown line in " + MAGIC);
        }
        if (project == null || context == null || area == null && ref != null || area != null && !NoteClipRegistry.valid(ref))
            throw new IllegalArgumentException("Invalid " + MAGIC + " header");
        return new Block(project, context, List.copyOf(notes), cmd, area, ref);
    }

    static List<Note> sample(List<Note> selected) {
        if (selected.size() <= LIMIT) return selected;
        var sample = new java.util.ArrayList<>(selected.subList(0, LIMIT - 1));
        sample.add(selected.getLast());
        return List.copyOf(sample);
    }

    static List<String> ids(String value) {
        if (value == null) throw new IllegalArgumentException("Expected --notes IDS");
        var ids = new LinkedHashSet<String>();
        if (value.stripLeading().startsWith("[")) {
            ids.addAll(idArray(value));
        } else {
            Arrays.stream(value.split(",", -1)).map(String::strip).forEach(ids::add);
        }
        if (ids.isEmpty() || ids.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Expected nonempty note IDs");
        return List.copyOf(ids);
    }

    static List<String> idArray(String value) {
        try {
            var array = JsonParser.parseString(value).getAsJsonArray();
            return array.asList().stream().map(id -> {
                if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || id.getAsString().isBlank())
                    throw new IllegalArgumentException("Expected nonempty string IDs");
                return id.getAsString();
            }).toList();
        } catch (com.google.gson.JsonParseException | IllegalStateException malformed) {
            throw new IllegalArgumentException("Expected a JSON array of IDs", malformed);
        }
    }

    static String noteArgument(List<Note> notes) {
        var ids = notes.stream().map(Note::id).distinct().toList();
        return quote(ids.stream().allMatch(id -> id.matches("[a-zA-Z0-9_.:-]+"))
                ? String.join(",", ids) : JSON.toJson(ids));
    }

    static String quote(String argument) {
        return "'" + argument.replace("'", "'\\''") + "'";
    }

    static int contextBars(String value) {
        if (value == null) return 1;
        if (!value.matches("(?:[0-9]|1[0-6])bars?"))
            throw new IllegalArgumentException("Context must be 0bar through 16bars");
        return Integer.parseInt(value.substring(0, value.indexOf('b')));
    }

    static double round(double value) { return Math.round(value * 1000) / 1000.0; }

    static String noteText(Note note) { return "note: " + JSON.toJson(note) + "\n"; }
}

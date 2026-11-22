package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionVoicePlan.Kind;
import javafx.scene.paint.Color;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/** The voice legend and note tags are two projections of the same draft membership. */
record ReconstructionVoices(List<Entry> entries, Map<String, Entry> byNote) {
    static final String UNASSIGNED = "";
    private static final Color NEUTRAL = Color.web("#aab1bc");
    // Light, saturated colors drawn from the Tol bright/light and Okabe–Ito palettes.
    private static final List<Color> PALETTE = List.of("#56b4e9", "#e69f00", "#cc79a7", "#44bb99",
            "#ee6677", "#88ccee", "#f0e442", "#aa99ee", "#ee8866", "#99ddff", "#bbcc33",
            "#ff99aa", "#77aadd", "#ddbbee", "#44dddd", "#eeaa55").stream().map(Color::web).toList();

    record Entry(ReconstructionBulk.Voice voice, String shortName, Color color, List<Overlay> notes) {
        String id() { return voice == null ? UNASSIGNED : voice.id(); }
        String name() { return voice == null ? "Unassigned" : voice.name(); }

        String roles() {
            if (notes.isEmpty()) return voice != null && voice.kind() == Kind.drum ? "Drums" : "No role";
            return notes.stream().map(Overlay::role).distinct().sorted()
                    .map(ReconstructionCanvas::roleName).collect(Collectors.joining(", "));
        }
    }

    private static String shortName(ReconstructionBulk.Voice voice) {
        if (!voice.id().startsWith("part-")) return voice.name();
        String compact = voice.name().replaceFirst("^Drum ", "")
                .replaceFirst(" \\([^)]*\\.(?i:wav|mp3|flac|ogg)\\)", "")
                .replace(" — counter line", " line")
                .replace(" — ", " ")
                .replace(" stem ", " / ")
                .replaceFirst("\\.(?i:wav|mp3|flac|ogg)$", "");
        return compact.isBlank() ? voice.name() : Character.toUpperCase(compact.charAt(0)) + compact.substring(1);
    }

    static ReconstructionVoices of(List<Overlay> built, List<Overlay> pending,
                                   Map<String, ReconstructionBulk.Voice> voices, Map<String, String> membership, Palette palette) {
        var notes = new LinkedHashMap<String, Overlay>();
        for (var note : built) if (note.part() >= 0) notes.putIfAbsent(note.id(), note);
        for (var note : pending) {
            if (note.fate().equals("Superseded draft")) notes.remove(note.id());
            else if (note.part() >= 0) notes.put(note.id(), note);
        }
        var groups = new HashMap<String, List<Overlay>>();
        for (var note : notes.values()) {
            String id = membership.getOrDefault(note.id(), UNASSIGNED);
            groups.computeIfAbsent(voices.containsKey(id) ? id : UNASSIGNED, key -> new ArrayList<>()).add(note);
        }

        var entries = new ArrayList<Entry>();
        var colors = palette.colors(voices.keySet().stream().sorted().toList());
        for (var voice : voices.values()) {
            entries.add(new Entry(voice, shortName(voice), colors.get(voice.id()),
                    List.copyOf(groups.getOrDefault(voice.id(), List.of()))));
        }
        entries.sort(Comparator.comparing(Entry::shortName, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id));
        if (groups.containsKey(UNASSIGNED)) entries.add(new Entry(null, "?", NEUTRAL, List.copyOf(groups.get(UNASSIGNED))));
        var byNote = new HashMap<String, Entry>();
        for (var entry : entries) for (var note : entry.notes()) byNote.put(note.id(), entry);
        return new ReconstructionVoices(List.copyOf(entries), Map.copyOf(byNote));
    }

    /** Keep swatches local to Studio and keyed by voice identity, including after a rename or merge. */
    static final class Palette {
        private final Preferences preferences;
        private final Map<String, Color> assigned = new HashMap<>();

        Palette(Path work) {
            preferences = Preferences.userNodeForPackage(ReconstructionVoices.class)
                    .node("reconstruction-voice-colors").node(key(work.toAbsolutePath().normalize().toString()));
        }

        Map<String, Color> colors(List<String> ids) {
            var used = new HashSet<Color>();
            for (String id : ids) {
                var previous = assigned.get(id);
                if (previous == null) {
                    int rgb = preferences.getInt(key(id), -1);
                    if (rgb >= 0) previous = Color.rgb(rgb >> 16, rgb >> 8 & 255, rgb & 255);
                }
                if (previous != null && used.add(previous)) assigned.put(id, previous);
                else assigned.remove(id);
            }
            for (String id : ids) {
                if (assigned.containsKey(id)) continue;
                var color = distinctColor(id, used);
                assigned.put(id, color);
                preferences.putInt(key(id), Integer.parseInt(color.toString().substring(2, 8), 16));
            }
            return assigned;
        }

        private static String key(String value) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private static Color distinctColor(String id, HashSet<Color> used) {
        int index = Math.floorMod(id.hashCode(), PALETTE.size());
        for (int offset = 0; offset < PALETTE.size(); offset++) {
            var color = PALETTE.get((index + offset) % PALETTE.size());
            if (used.add(color)) return color;
        }
        // Use a larger range of light RGB colors once the common palette is full.
        int seed = id.hashCode();
        Color color;
        do {
            seed += 0x9e3779b9;
            color = Color.rgb(128 + (seed >>> 14 & 127), 128 + (seed >>> 7 & 127), 128 + (seed & 127));
        } while (!used.add(color));
        return color;
    }
}

package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import com.opensmpsdeck.headless.ReconstructionVoicePlan.Kind;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/** The voice legend and note tags are two projections of the same draft membership. */
record ReconstructionVoices(List<Entry> entries, Map<String, Entry> byNote) {
    static final String UNASSIGNED = "";
    private static final Style NEUTRAL = new Style(Color.web("#aab1bc"), Pattern.DOTS);
    // Okabe–Ito categories with a brighter blue and a purple in place of black.
    private static final List<Color> HUES = List.of("#56b4e9", "#e69f00", "#cc79a7", "#009e73",
            "#e76333", "#7595ff", "#f0e442", "#b49bef").stream().map(Color::web).toList();
    private static final double COLLISION_DISTANCE = .18;

    enum Pattern { SOLID, STRIPES, DOTS, HOLLOW }
    private static final Color PATTERN_INK = Color.web("#101522");

    record Style(Color color, Pattern pattern) {
        private static final java.util.Map<Color, java.util.Map<Double, Color>> FILLS = new java.util.concurrent.ConcurrentHashMap<>();

        private Color translucent(double fill) {
            return FILLS.computeIfAbsent(color, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(fill, value -> color.deriveColor(0, 1, 1, value));
        }

        void draw(GraphicsContext g, double x, double y, double width, double height, double fill) {
            g.setStroke(color);
            g.setFill(pattern == Pattern.HOLLOW ? Color.TRANSPARENT : translucent(fill));
            g.fillRect(x, y, width, height);
            g.strokeRect(x + .5, y + .5, width - 1, height - 1);
            if (pattern == Pattern.STRIPES || pattern == Pattern.DOTS) {
                g.setFill(PATTERN_INK);
                g.setStroke(PATTERN_INK);
                // Only the visible part of a long note needs its pattern.
                double first = x + 1.5, limit = Math.min(x + width - 1, g.getCanvas().getWidth() + 5);
                if (first < -5) first += Math.floor((-5 - first) / 5) * 5;
                for (double at = first; at < limit; at += 5) {
                    if (pattern == Pattern.DOTS) g.fillRect(at, y + height / 2 - 1, 2, 2);
                    else g.strokeLine(at, y + height - 1, Math.min(at + 2, x + width - 1), y + 1);
                }
            }
        }
    }

    enum DrumMark {
        KICK, SNARE, HAT, CYMBAL, OTHER;

        static DrumMark of(String name, int pitch) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("kick")) return KICK;
            if (lower.contains("snare") || lower.contains("clap")) return SNARE;
            if (lower.contains("hat")) return HAT;
            if (lower.contains("crash") || lower.contains("ride") || lower.contains("cymbal")) return CYMBAL;
            return switch (pitch) {
                case 35, 36 -> KICK;
                case 38, 40 -> SNARE;
                case 42, 44, 46 -> HAT;
                case 49, 51, 52, 55, 57, 59 -> CYMBAL;
                default -> OTHER;
            };
        }

        void draw(GraphicsContext g, Color color, double x, double y) {
            g.setFill(color);
            switch (this) {
                case KICK -> g.fillRect(x - 1.5, y - 3, 3, 6);
                case SNARE -> g.fillPolygon(new double[]{x, x + 3, x, x - 3},
                        new double[]{y - 3, y, y + 3, y}, 4);
                case HAT -> g.fillRect(x - 3, y - 1, 6, 2);
                case CYMBAL -> g.fillPolygon(new double[]{x - 3, x + 3, x + 3, x - 3},
                        new double[]{y + 1, y - 3, y - 1, y + 3}, 4);
                case OTHER -> g.fillOval(x - 2, y - 2, 4, 4);
            }
        }
    }

    record Entry(ReconstructionBulk.Voice voice, String shortName, String tag, Style style, List<Overlay> notes) {
        String id() { return voice == null ? UNASSIGNED : voice.id(); }
        String name() { return voice == null ? "Unassigned" : voice.name(); }
        boolean drum() { return voice != null && voice.kind() == Kind.drum; }
        DrumMark drumMark() { return DrumMark.of(name(), notes.isEmpty() ? -1 : notes.getFirst().pitch()); }

        String roles() {
            if (notes.isEmpty()) return drum() ? "Drums" : "No role";
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

    static String autoTag(String name) {
        var words = name.split("[^\\p{L}\\p{N}]+");
        var initials = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) initials.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
            if (initials.codePointCount(0, initials.length()) == 3) break;
        }
        return initials.isEmpty() ? "?" : initials.toString();
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
        var ids = voices.keySet().stream().sorted().toList();
        var styles = palette.styles(ids, groups, voices);
        var usedTags = new HashSet<String>();
        for (String id : ids) {
            String custom = palette.customTag(id);
            if (!custom.isEmpty()) usedTags.add(custom.toUpperCase(Locale.ROOT));
        }
        for (String id : ids) {
            var voice = voices.get(id);
            String name = shortName(voice);
            String tag = palette.customTag(id);
            if (tag.isEmpty()) {
                String base = autoTag(name);
                tag = base;
                for (int suffix = 2; !usedTags.add(tag); suffix++) tag = base + suffix;
            }
            entries.add(new Entry(voice, name, tag, styles.get(id), List.copyOf(groups.getOrDefault(id, List.of()))));
        }
        entries.sort(Comparator.comparing(Entry::shortName, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id));
        if (groups.containsKey(UNASSIGNED))
            entries.add(new Entry(null, "Unassigned", "?", NEUTRAL, List.copyOf(groups.get(UNASSIGNED))));
        var byNote = new HashMap<String, Entry>();
        for (var entry : entries) for (var note : entry.notes()) byNote.put(note.id(), entry);
        return new ReconstructionVoices(List.copyOf(entries), Map.copyOf(byNote));
    }

    private record PitchSpan(int low, int high) {
        PitchSpan union(PitchSpan other) { return new PitchSpan(Math.min(low, other.low), Math.max(high, other.high)); }
        boolean near(PitchSpan other) { return low <= other.high + 3 && other.low <= high + 3; }
    }

    // Weight simultaneous register conflicts, using two-second windows to include adjacent attacks.
    private record Profile(String family, Map<Integer, PitchSpan> windows) {
        static Profile of(String id, List<Overlay> notes) {
            var parts = new HashMap<Integer, Integer>();
            var windows = new HashMap<Integer, PitchSpan>();
            for (var note : notes) {
                parts.merge(note.part(), 1, Integer::sum);
                if (note.role() == Role.DRUMS) continue;
                var span = new PitchSpan(note.pitch(), note.pitch());
                int first = (int) (note.interval().sourceStart() / 2);
                int last = (int) (note.interval().sourceEnd() / 2);
                for (int window = first; window <= last; window++)
                    windows.merge(window, span, PitchSpan::union);
            }
            String family = parts.entrySet().stream().max(Map.Entry.<Integer, Integer>comparingByValue()
                    .thenComparing(Map.Entry.comparingByKey())).map(part -> "part:" + part.getKey()).orElse(id);
            return new Profile(family, windows);
        }

        int overlap(Profile other) {
            int weight = 0;
            for (var window : windows.entrySet()) {
                var span = other.windows.get(window.getKey());
                if (span != null && span.near(window.getValue())) weight++;
            }
            return weight;
        }
    }

    /** Keep styles and tags local to Studio, keyed by work directory and voice identity. */
    static final class Palette {
        private final Preferences preferences;

        Palette(Path work) {
            preferences = Preferences.userNodeForPackage(ReconstructionVoices.class)
                    .node("reconstruction-voice-colors").node(key(work.toAbsolutePath().normalize().toString()));
        }

        String customTag(String id) { return preferences.get("tag-" + key(id), ""); }

        void tag(String id, String tag) {
            if (tag.isEmpty()) preferences.remove("tag-" + key(id));
            else preferences.put("tag-" + key(id), tag);
        }

        Map<String, Style> styles(List<String> ids, Map<String, List<Overlay>> notes,
                                  Map<String, ReconstructionBulk.Voice> voices) {
            var assigned = new LinkedHashMap<String, Style>();
            var slots = new HashSet<Integer>();
            var familyHues = new HashMap<String, Integer>();
            var profiles = new HashMap<String, Profile>();
            for (String id : ids) {
                int slot = preferences.getInt("style-" + key(id), -1);
                if (slot < 0 || !slots.add(slot)) continue;
                Color rgb = storedColor(id);
                int pattern = preferences.getInt("pattern-" + key(id), slot % HUES.size() + slot / HUES.size());
                assigned.put(id, new Style(rgb == null ? variantColor(slot) : rgb, Pattern.values()[pattern % Pattern.values().length]));
            }
            if (assigned.size() == ids.size()) return assigned;
            for (String id : ids) {
                var profile = Profile.of(id, notes.getOrDefault(id, List.of()));
                profiles.put(id, profile);
                if (assigned.containsKey(id))
                    familyHues.putIfAbsent(profile.family(), preferences.getInt("style-" + key(id), 0) % HUES.size());
            }
            // Retain separable legacy swatches. Migrate close swatches to a new category first.
            for (String id : ids) {
                if (assigned.containsKey(id)) continue;
                Color old = storedColor(id);
                if (old == null || assigned.values().stream().anyMatch(style -> distance(old, style.color()) < COLLISION_DISTANCE))
                    continue;
                int hue = nearestHue(old);
                if (slots.add(hue)) {
                    assigned.put(id, save(id, hue, old, assigned));
                    familyHues.putIfAbsent(profiles.get(id).family(), hue);
                }
            }
            long drums = ids.stream().filter(id -> voices.get(id).kind() == Kind.drum).count();
            // Give tonal voices first choice. Drum glyphs already have a separate strip and shape.
            var priority = Comparator.<String, Boolean>comparing(id -> voices.get(id).kind() == Kind.drum)
                    .thenComparing(Comparator.<String>comparingInt(id -> profiles.get(id).windows().size()).reversed());
            var remaining = ids.stream().filter(id -> !assigned.containsKey(id)).sorted(priority).toList();
            for (String id : remaining) {
                var profile = profiles.get(id);
                boolean drum = voices.get(id).kind() == Kind.drum;
                boolean spare = java.util.stream.IntStream.range(0, HUES.size()).anyMatch(hue -> !slots.contains(hue));
                Integer familyHue = !drum && (!spare || ids.size() - drums > HUES.size())
                        ? familyHues.get(profile.family()) : null;
                int slot;
                if (familyHue != null) slot = nextSlot(familyHue, slots);
                else {
                    // Drum voices represent individual kit pieces. Compare their variants against
                    // the other pieces in the strip once at least one swatch has been assigned.
                    var peers = assigned;
                    if (drum) {
                        var kit = new LinkedHashMap<String, Style>();
                        assigned.forEach((other, style) -> {
                            if (voices.get(other).kind() == Kind.drum) kit.put(other, style);
                        });
                        if (!kit.isEmpty()) peers = kit;
                    }
                    var comparison = peers;
                    var weights = new HashMap<String, Integer>();
                    comparison.keySet().forEach(other -> weights.put(other, profile.overlap(profiles.get(other))));
                    slot = java.util.stream.IntStream.range(0, HUES.size())
                            .filter(hue -> !spare || !slots.contains(hue))
                            .map(hue -> nextSlot(hue, slots)).boxed()
                            .max(Comparator.<Integer>comparingDouble(candidate -> separation(variantColor(candidate), comparison, weights))
                                    .thenComparingInt(candidate -> -candidate)).orElseThrow();
                }
                slots.add(slot);
                assigned.put(id, save(id, slot, variantColor(slot), assigned));
                familyHues.putIfAbsent(profile.family(), slot % HUES.size());
            }
            return assigned;
        }

        private Color storedColor(String id) {
            int rgb = preferences.getInt(key(id), -1);
            return rgb < 0 ? null : Color.rgb(rgb >> 16, rgb >> 8 & 255, rgb & 255);
        }

        private Style save(String id, int slot, Color color, Map<String, Style> assigned) {
            var patterns = Pattern.values();
            int preferred = (slot % HUES.size() + slot / HUES.size()) % patterns.length;
            var nearby = new HashSet<Pattern>();
            for (var style : assigned.values()) {
                if (distance(color, style.color()) < COLLISION_DISTANCE) nearby.add(style.pattern());
            }
            if (distance(color, NEUTRAL.color()) < COLLISION_DISTANCE) nearby.add(NEUTRAL.pattern());
            int pattern = preferred;
            for (int step = 0; step < patterns.length && nearby.contains(patterns[pattern]); step++)
                pattern = (pattern + 1) % patterns.length;
            preferences.putInt(key(id), Integer.parseInt(color.toString().substring(2, 8), 16));
            preferences.putInt("style-" + key(id), slot);
            preferences.putInt("pattern-" + key(id), pattern);
            return new Style(storedColor(id), patterns[pattern]);
        }

        private static String key(String value) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private static int nextSlot(int hue, HashSet<Integer> used) {
        int slot = hue;
        while (used.contains(slot)) slot += HUES.size();
        return slot;
    }

    static Color variantColor(int slot) {
        Color hue = HUES.get(slot % HUES.size());
        int variant = slot / HUES.size();
        if (variant == 0) return hue;
        return hue.interpolate(variant % 2 == 0 ? Color.BLACK : Color.WHITE,
                .14 + .2 * (1 - Math.pow(.7, (variant - 1) / 2)));
    }

    private static int nearestHue(Color color) {
        int best = 0;
        for (int hue = 1; hue < HUES.size(); hue++)
            if (distance(color, HUES.get(hue)) < distance(color, HUES.get(best))) best = hue;
        return best;
    }

    private static double separation(Color color, Map<String, Style> styles, Map<String, Integer> overlap) {
        return styles.entrySet().stream()
                .mapToDouble(entry -> distance(color, entry.getValue().color())
                        / (1 + Math.log1p(overlap.get(entry.getKey())))).min().orElse(1);
    }

    private static double distance(Color a, Color b) {
        double red = a.getRed() - b.getRed(), green = a.getGreen() - b.getGreen(), blue = a.getBlue() - b.getBlue();
        return Math.sqrt(.3 * red * red + .5 * green * green + .2 * blue * blue);
    }
}

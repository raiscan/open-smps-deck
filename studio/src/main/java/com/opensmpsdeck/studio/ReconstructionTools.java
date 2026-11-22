package com.opensmpsdeck.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionEditing;
import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.Interval;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;

import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Tool and selection state shared by the viewport and inspector. */
final class ReconstructionTools {
    enum Mode {
        SELECT("Select"), INSERT("Insert"), CUT("Cut"),
        BEND("Bend / slide"), VIBRATO("Vibrato");

        final String label;
        Mode(String label) { this.label = label; }
    }

    record Placement(double start, double end, int pitch) {
        static Placement of(Overlay note) {
            return new Placement(note.interval().sourceStart(), note.interval().sourceEnd(), note.pitch());
        }

        Interval interval(ReconstructionInspection model) {
            return new Interval(start, end, model.toProject(start), model.toProject(end));
        }
    }

    static JsonObject cut(String id, double seconds, Integer pitch) {
        var edit = ReconstructionDraft.human("splitNote", "Cut note in Studio");
        edit.addProperty("noteId", id);
        edit.addProperty("at", seconds);
        if (pitch != null) edit.addProperty("pitch", pitch);
        return edit;
    }

    static JsonObject bend(String id, int pitch, List<ReconstructionInspection.BendPoint> curve) {
        var edit = ReconstructionDraft.human("bend", "Draw pitch slide in Studio");
        edit.addProperty("noteId", id);
        var points = new JsonArray();
        for (var point : curve) {
            var pair = new JsonArray();
            pair.add(point.sourceSeconds());
            pair.add(point.pitch() - pitch);
            points.add(pair);
        }
        edit.add("points", points);
        return edit;
    }

    static JsonObject vibrato(String id, List<ReconstructionEditing.VibratoSegment> segments) {
        var edit = ReconstructionDraft.human("noteVoicing", "Set note vibrato envelope in Studio");
        var ids = new JsonArray();
        ids.add(id);
        edit.add("noteIds", ids);
        if (segments.isEmpty()) edit.addProperty("modulationOff", true);
        else {
            var envelope = new JsonArray();
            for (var segment : segments) {
                var stage = new JsonObject();
                stage.addProperty("startOffset", segment.startOffset());
                stage.addProperty("rateHz", segment.rateHz());
                stage.addProperty("depthCents", segment.depthCents());
                envelope.add(stage);
            }
            edit.add("vibratoEnvelope", envelope);
        }
        return edit;
    }

    private final Set<String> selected = new LinkedHashSet<>();
    private String primary;

    void select(Collection<String> eventIds, String primary) {
        selected.clear();
        selected.addAll(eventIds);
        this.primary = selected.contains(primary) ? primary : selected.stream().findFirst().orElse(null);
    }

    void modify(Collection<String> eventIds, boolean add, boolean toggle) {
        if (!add && !toggle) selected.clear();
        for (String id : eventIds)
            if (!toggle || !selected.remove(id)) selected.add(id);
        if (!selected.contains(primary)) primary = selected.stream().findFirst().orElse(null);
    }

    boolean contains(Overlay note) { return selected.contains(note.eventId()); }
    boolean primary(Overlay note) { return Objects.equals(primary, note.eventId()); }
    Set<String> eventIds() { return Set.copyOf(selected); }
}

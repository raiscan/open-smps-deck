package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;
import com.opensmpsdeck.headless.ReconstructionInspection.Role;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The classification projection of the same overlays used by the note editor. */
record ReconstructionRoleRows(List<Row> rows, Map<String, Integer> indices) {
    record Row(ReconstructionBulk.Voice voice, Role role, List<Overlay> notes) {
        String label() {
            String label = role == null ? "" : ReconstructionCanvas.roleName(role);
            return voice == null ? label : label.isEmpty() ? voice.name() : voice.name() + " · " + label;
        }

        Color color() {
            return voice == null ? ReconstructionCanvas.color(role)
                    : Color.hsb(Math.floorMod(voice.id().hashCode() * 137, 360), .48, .98);
        }
    }

    private record Group(ReconstructionBulk.Voice voice, Role role) {}

    static ReconstructionRoleRows of(List<Overlay> built, List<Overlay> pending,
                                     Map<String, ReconstructionBulk.Voice> voices, Map<String, String> membership) {
        var notes = new LinkedHashMap<String, Overlay>();
        for (var note : built) if (note.part() >= 0) notes.putIfAbsent(note.id(), note);
        for (var note : pending) {
            if (note.fate().equals("Superseded draft")) notes.remove(note.id());
            else notes.put(note.id(), note);
        }
        var groups = new LinkedHashMap<Group, List<Overlay>>();
        var populated = new java.util.HashSet<String>();
        for (var note : notes.values()) {
            String owner = membership.get(note.id());
            var voice = owner == null ? null : voices.get(owner);
            if (voice != null) populated.add(voice.id());
            var role = note.fate().equals("Pending deletion") ? Role.UNASSIGNED : note.role();
            groups.computeIfAbsent(new Group(voice, role), key -> new ArrayList<>()).add(note);
        }
        for (var voice : voices.values())
            if (!populated.contains(voice.id())) groups.put(new Group(voice, null), List.of());
        var rows = groups.entrySet().stream()
                .map(entry -> new Row(entry.getKey().voice(), entry.getKey().role(), List.copyOf(entry.getValue())))
                .sorted(Comparator.comparing((Row row) -> row.voice() == null ? 1 : 0)
                        .thenComparing(Row::label, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(row -> row.voice() == null ? "" : row.voice().id())).toList();
        var indices = new HashMap<String, Integer>();
        for (int index = 0; index < rows.size(); index++)
            for (var note : rows.get(index).notes()) indices.put(note.id(), index);
        return new ReconstructionRoleRows(rows, Map.copyOf(indices));
    }
}

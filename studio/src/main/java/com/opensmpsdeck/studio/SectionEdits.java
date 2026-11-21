package com.opensmpsdeck.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionSections.Section;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Translates gestures into the same batch mutation used by the sections CLI. */
final class SectionEdits {
    record Mutation(String action, JsonArray entries) {}
    private static final double EPSILON = 1e-6;

    private SectionEdits() {}

    static Section neighbor(List<Section> all, Section section, boolean right) {
        return all.stream().filter(other -> other.level() == section.level() && !other.id().equals(section.id())
                && Math.abs((right ? other.sourceStart() - section.sourceEnd()
                        : other.sourceEnd() - section.sourceStart())) < EPSILON).findFirst().orElse(null);
    }

    static Mutation resize(List<Section> all, Section section, boolean end, double at) {
        var adjacent = neighbor(all, section, end);
        if (adjacent != null && !Objects.equals(adjacent.parentId(), section.parentId()))
            throw new IllegalArgumentException("Move the shared boundary on its parent row.");
        var changes = new JsonArray();
        changes.add(span(section, section.id(), end ? section.sourceStart() : at,
                end ? at : section.sourceEnd(), section.label(), "Move boundary in Studio"));
        if (adjacent != null) changes.add(span(adjacent, adjacent.id(), end ? at : adjacent.sourceStart(),
                end ? adjacent.sourceEnd() : at, adjacent.label(), "Move shared boundary in Studio"));
        return new Mutation("set", changes);
    }

    static Mutation split(Section section, double at) {
        var changes = new JsonArray();
        changes.add(span(section, section.id(), section.sourceStart(), at, section.label(), "Split in Studio"));
        changes.add(span(section, "studio-" + UUID.randomUUID(), at, section.sourceEnd(),
                section.label() + " (2)", "Split in Studio"));
        return new Mutation("set", changes);
    }

    static Mutation merge(Section left, Section right) {
        if (!Objects.equals(left.parentId(), right.parentId()))
            throw new IllegalArgumentException("Choose adjacent spans with the same parent.");
        var changes = new JsonArray();
        changes.add(span(left, left.id(), left.sourceStart(), right.sourceEnd(), left.label(), "Merge in Studio"));
        var remove = identity(right.id(), "Merge in Studio");
        remove.addProperty("removed", true);
        changes.add(remove);
        return new Mutation("set", changes);
    }

    static Mutation rename(Section section, String name) {
        var changes = new JsonArray();
        var entry = identity(section.id(), "Rename in Studio");
        entry.addProperty("name", name);
        changes.add(entry);
        return new Mutation("rename", changes);
    }

    private static JsonObject span(Section section, String id, double start, double end, String name, String reason) {
        if (!(end - start > EPSILON)) throw new IllegalArgumentException("A section must have a positive length.");
        var edit = identity(id, reason);
        edit.addProperty("level", section.level().name());
        edit.addProperty("sourceStart", start);
        edit.addProperty("sourceEnd", end);
        edit.addProperty("name", name);
        return edit;
    }

    private static JsonObject identity(String id, String reason) {
        var edit = new JsonObject();
        edit.addProperty("id", id);
        edit.addProperty("author", "human");
        edit.addProperty("reason", reason);
        return edit;
    }
}

package com.opensmpsdeck.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionLaneType;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** The edit stack works on whole journal snapshots, including agent intents. */
final class ReconstructionDraft {
    private final EditHistory<JsonArray> history = new EditHistory<>();
    private JsonArray saved;
    private JsonArray entries;

    ReconstructionDraft(JsonArray saved) { discard(saved); }

    JsonArray entries() { return entries.deepCopy(); }
    boolean pending() { return !saved.equals(entries); }
    boolean changed(JsonElement entry) { return !saved.contains(entry); }
    List<JsonElement> removed() { return saved.asList().stream().filter(e -> !entries.contains(e)).toList(); }
    boolean canUndo(boolean forward) { return history.canStep(forward); }

    void undo(boolean forward) { entries = history.step(entries, forward); }
    void add(JsonObject edit) { change(next -> next.add(edit.deepCopy())); }
    void remove(int index) { change(next -> next.remove(index)); }

    void change(Consumer<JsonArray> mutation) {
        var next = entries.deepCopy();
        mutation.accept(next);
        if (!entries.equals(next)) {
            history.record(entries);
            entries = next;
        }
    }

    void applied(JsonArray published) { saved = entries = published.deepCopy(); }

    boolean adopt(JsonArray incoming, boolean sectionsChanged) {
        if (saved.equals(incoming) && !sectionsChanged) return true;
        if (pending()) return false;
        discard(incoming);
        return true;
    }

    void discard(JsonArray incoming) {
        saved = incoming.deepCopy();
        entries = saved;
        history.clear();
    }

    static JsonObject human(String op, String reason) {
        var edit = new JsonObject();
        edit.addProperty("id", "studio-" + UUID.randomUUID());
        edit.addProperty("op", op);
        edit.addProperty("author", "human");
        edit.addProperty("reason", reason);
        return edit;
    }

    static JsonObject transpose(List<String> noteIds, int semitones, ReconstructionLaneType laneType) {
        var edit = human("transpose", "Transpose notes in Studio");
        var ids = new JsonArray();
        noteIds.forEach(ids::add);
        edit.add("noteIds", ids);
        edit.addProperty("semitones", semitones);
        edit.addProperty("laneType", laneType.name());
        return edit;
    }

    static JsonObject insert(int part, double start, double end, int pitch) {
        var edit = human("insertNote", "Insert note in Studio");
        edit.addProperty("part", part);
        edit.addProperty("start", start);
        edit.addProperty("end", end);
        edit.addProperty("pitch", pitch);
        edit.addProperty("velocity", 96);
        return edit;
    }

    static JsonObject target(String id, String op, String field, String value, String reason) {
        var edit = human(op, reason);
        var notes = new JsonArray();
        notes.add(id);
        edit.add("noteIds", notes);
        edit.addProperty(field, value);
        return edit;
    }

    static String text(JsonElement entry, String key, String fallback) {
        if (!entry.isJsonObject()) return fallback;
        var value = entry.getAsJsonObject().get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}

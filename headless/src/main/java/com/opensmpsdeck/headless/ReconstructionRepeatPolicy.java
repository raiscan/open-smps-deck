package com.opensmpsdeck.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionEdits.*;
import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;

/** Whole-part repeat settings, replayed in journal order. */
public record ReconstructionRepeatPolicy(Fill fill, Lanes lanes) {
    public enum Fill {
        off(0), low(-4), medium(0), high(3);

        final int allowanceDb;

        Fill(int allowanceDb) { this.allowanceDb = allowanceDb; }
    }

    public enum Lanes { off, on, strict }

    public static final ReconstructionRepeatPolicy OFF = new ReconstructionRepeatPolicy(Fill.off, Lanes.off);

    static ReconstructionRepeatPolicy parse(JsonObject json, String at) {
        require(json.has("fill"), at + ".fill", "required");
        require(json.has("lanes"), at + ".lanes", "required");
        return new ReconstructionRepeatPolicy(choice(Fill.class, json, "fill", at),
                choice(Lanes.class, json, "lanes", at));
    }

    public static ReconstructionRepeatPolicy forPart(JsonArray journal, int part) {
        var policy = OFF;
        for (var entry : journal) {
            if (!entry.isJsonObject()) continue;
            var json = entry.getAsJsonObject();
            if (!new JsonPrimitive("repeatPolicy").equals(json.get("op"))
                    || !new JsonPrimitive(part).equals(json.get("part"))
                    || ReconstructionProposals.isInactive(json)) continue;
            try {
                policy = parse(json, FILE);
            } catch (IllegalArgumentException malformed) {
                // The editor reports malformed entries in its journal.
            }
        }
        return policy;
    }

    static boolean supports(ReconstructionStages.Score score, int part) {
        var input = score.inputs();
        if (input.audioOnly() == null || !input.stems().containsKey(part)) return false;
        var stem = input.audioOnly().stems().get(part);
        return stem != null && !"vocals".equals(stem.transcriberRole())
                && score.source().parts().stream().anyMatch(candidate -> candidate.id() == part && !candidate.drums());
    }

    static Map<Integer, ReconstructionRepeatPolicy> resolve(ReconstructionStages.Score score,
            ReconstructionEdits journal, List<Outcome> outcomes) {
        var policies = new HashMap<Integer, ReconstructionRepeatPolicy>();
        for (var edit : journal.accepted().intents()) {
            if (edit.kind() != Kind.repeatPolicy) continue;
            if (!supports(score, edit.part())) {
                outcomes.add(new Outcome(edit.id(), edit.kind(), Status.orphaned, List.of(), null,
                        "Repeat settings require a pitched, non-vocal audio-only part with a mapped stem", null));
                continue;
            }
            var policy = parse(edit.json(), FILE + "." + edit.id());
            if (policy.equals(OFF)) policies.remove(edit.part());
            else policies.put(edit.part(), policy);
            outcomes.add(new Outcome(edit.id(), edit.kind(), Status.applied, List.of(), null, null, null));
        }
        return Map.copyOf(policies);
    }
}

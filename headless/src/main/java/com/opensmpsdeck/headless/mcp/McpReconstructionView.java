package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opensmpsdeck.headless.ReconstructionStages;
import com.opensmpsdeck.headless.ReconstructionSections;
import com.opensmpsdeck.headless.ReconstructionTrace;
import com.opensmpsdeck.headless.ReconstructionView;

import java.io.IOException;
import java.util.List;

import static com.opensmpsdeck.headless.mcp.McpArguments.flag;
import static com.opensmpsdeck.headless.mcp.McpArguments.string;

final class McpReconstructionView {
    private McpReconstructionView() {}

    static McpTools.Result view(JsonObject args) throws IOException {
        var work = McpPaths.directory(string(args, "workDir"));
        for (String file : ReconstructionTrace.FILES) McpPaths.managed(work, file);
        for (String file : List.of("arrangement.smps.json", "edits.json", ReconstructionSections.FILE, ReconstructionStages.STEADY_REFERENCE))
            McpPaths.managed(work, file);
        var output = McpPaths.output(work, string(args, "output"));
        if (flag(args, "ridges") || args.has("ridgeCount")) McpPaths.output(work, ReconstructionView.ridgesPath(output).toString());
        String time = args.has("time") ? string(args, "time") : "source";
        var bounds = ReconstructionSections.bounds(work, args.has("section") ? string(args, "section") : null,
                args.has("start") ? args.get("start").getAsDouble() : null,
                args.has("end") ? args.get("end").getAsDouble() : null, time);
        var options = new ReconstructionView.Options(bounds[0], bounds[1], time, integer(args, "stem"),
                args.has("wav") ? McpPaths.input(string(args, "wav")) : null,
                flag(args, "render"), integer(args, "pitchMin"), integer(args, "pitchMax"),
                args.has("lanes") ? args.getAsJsonArray("lanes").asList().stream()
                        .map(JsonElement::getAsString).toList() : List.of(),
                args.has("parts") ? args.getAsJsonArray("parts").asList().stream()
                        .map(McpReconstructionView::exactInteger).toList() : List.of(),
                args.has("harmonics") ? string(args, "harmonics") : null, flag(args, "harmonicSum"),
                args.has("width") ? exactInteger(args.get("width")) : 1440,
                args.has("notes") ? McpPaths.input(string(args, "notes")) : null,
                args.has("harmonicsAt") ? args.getAsJsonArray("harmonicsAt").asList().stream()
                        .map(JsonElement::getAsString).toList() : List.of(),
                flag(args, "ridges") || args.has("ridgeCount"), flag(args, "sharedDb"), flag(args, "db"),
                args.has("pitchPreset") ? string(args, "pitchPreset") : null, args.has("ridgeCount") ? exactInteger(args.get("ridgeCount")) : 3);
        var result = ReconstructionView.run(work, options, output, args.has("section") || flag(args, "sections"));
        return new McpTools.Result(ReconstructionView.legendJson(result.legend()), result.png());
    }

    private static Integer integer(JsonObject args, String key) {
        return args.has(key) ? exactInteger(args.get(key)) : null;
    }

    private static int exactInteger(JsonElement value) {
        return value.getAsBigDecimal().intValueExact();
    }
}

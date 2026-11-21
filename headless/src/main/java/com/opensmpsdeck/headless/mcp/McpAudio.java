package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.opensmpsdeck.headless.RenderComparison;
import com.opensmpsdeck.headless.ReconstructionView;
import com.opensmpsdeck.headless.ReconstructionStages;
import com.opensmpsdeck.headless.WaveFile;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.S3kRenderer.*;
import static com.opensmpsdeck.headless.mcp.McpArguments.string;
import static com.opensmpsdeck.headless.mcp.McpJson.object;

final class McpAudio {
    static final String[] LANES = {"FM1", "FM2", "FM3", "FM4", "FM5", "DAC", "PSG1", "PSG2", "PSG3"};
    private McpAudio() {}

    static JsonObject clip(JsonObject args) throws IOException {
        var range = range(args);
        var project = McpPaths.project(string(args, "project"));
        var output = McpPaths.output(project.getParent(), string(args, "output"));
        var solo = lanes(args, "solo");
        var mute = args.has("solo") && solo.isEmpty() ? List.of(LANES) : lanes(args, "mute");
        var mask = ReconstructionView.laneMask(solo, mute);
        var compiled = ProjectCompiler.compileWithAssets(ProjectFiles.load(project).project());
        WaveFile.renderRange(compiled, output, range, mask);
        return object(Map.of("output", output.toString(), "startFrame", range.start(), "endFrame", range.end(),
                "presentations", range.count(), "sampleRate", SAMPLE_RATE));
    }

    static JsonObject compare(JsonObject args) throws IOException {
        var render = McpPaths.input(string(args, "render"));
        var reference = McpPaths.input(string(args, "reference"));
        var work = McpPaths.directory(string(args, "workDir"));
        var output = McpPaths.output(work, string(args, "output"));
        for (String file : List.of("score.json", "report.json", ReconstructionStages.STEADY_REFERENCE))
            McpPaths.managed(work, file);
        return object(RenderComparison.run(work, render, reference, output, args.get("start").getAsDouble(),
                args.get("end").getAsDouble(), args.has("time") ? string(args, "time") : "source",
                args.has("referenceOffset") ? args.get("referenceOffset").getAsDouble() : null));
    }

    private static WaveFile.Range range(JsonObject args) {
        return WaveFile.Range.seconds(args.get("start").getAsDouble(), args.get("end").getAsDouble());
    }

    private static List<String> lanes(JsonObject args, String key) {
        return args.has(key) ? args.getAsJsonArray(key).asList().stream().map(JsonElement::getAsString).toList()
                : List.of();
    }
}

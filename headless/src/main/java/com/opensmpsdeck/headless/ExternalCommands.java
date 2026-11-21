package com.opensmpsdeck.headless;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.opensmpsdeck.headless.ReconstructionArtifacts.require;

/** Local executable templates shared by the optional audio adapters. */
final class ExternalCommands {
    static final Pattern LABEL = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.:-]*");

    private ExternalCommands() {}

    static Path defaultConfig(String environment, String filename) {
        String override = System.getenv(environment);
        if (override != null && !override.isBlank()) return Path.of(override);
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path root = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
        return root.resolve("opensmpsdeck/" + filename);
    }

    static Map<String, List<String>> commands(Path file, Pattern placeholders, String... requiredArguments) throws IOException {
        JsonElement tree = ReconstructionArtifacts.readTree(file);
        require(tree != null && tree.isJsonObject(), file.toString(), "expected an object mapping tool names to commands");
        return commands(tree.getAsJsonObject(), file.toString(), placeholders, requiredArguments);
    }

    static Map<String, List<String>> commands(JsonObject tree, String source, Pattern placeholders,
                                               String... requiredArguments) {
        var commands = new TreeMap<String, List<String>>();
        for (var entry : tree.entrySet()) {
            String at = source + "." + entry.getKey();
            var value = entry.getValue();
            require(LABEL.matcher(entry.getKey()).matches(), at, "invalid tool name");
            require(value.isJsonArray() && !value.getAsJsonArray().isEmpty(), at, "expected a nonempty argument array");
            var command = new ArrayList<String>();
            for (var arg : value.getAsJsonArray()) {
                require(arg instanceof JsonPrimitive scalar && scalar.isString() && !scalar.getAsString().contains("\0"),
                        at, "expected string arguments");
                command.add(arg.getAsString());
            }
            require(!command.getFirst().isBlank() && !placeholders.matcher(command.getFirst()).find(),
                    at, "expected a fixed executable");
            for (String required : requiredArguments)
                require(command.stream().skip(1).anyMatch(arg -> arg.contains(required)), at, "missing " + required);
            commands.put(entry.getKey(), List.copyOf(command));
        }
        return commands;
    }

    static List<String> expand(List<String> template, Pattern placeholders, Map<String, String> values) {
        return template.stream().map(arg -> placeholders.matcher(arg).replaceAll(match ->
                Matcher.quoteReplacement(values.get(match.group(1))))).toList();
    }
}

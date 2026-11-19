package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.mcp.McpJson.object;

final class McpArguments {
    private McpArguments() {}

    record Field(String name, JsonObject schema, boolean required) {
        Field optional() {
            return new Field(name, schema, false);
        }
    }

    static Field field(String name, String type, String description) {
        return field(name, object(Map.of("type", type)), description);
    }

    static Field field(String name, JsonObject schema, String description) {
        var described = schema.deepCopy();
        described.addProperty("description", description);
        return new Field(name, described, true);
    }

    static Field choice(String name, String description, String... choices) {
        return field(name, object(Map.of("type", "string", "enum", choices)), description);
    }

    static JsonObject schema(Field... fields) {
        var properties = new JsonObject();
        var required = new JsonArray();
        for (var field : fields) {
            properties.add(field.name(), field.schema());
            if (field.required()) required.add(field.name());
        }
        return object(Map.of("type", "object", "properties", properties, "required", required,
                "additionalProperties", false));
    }

    /** Checks the JSON Schema vocabulary used by the tool definitions below. */
    static void validate(String at, JsonObject schema, JsonElement value) {
        if (!schema.has("type")) return;
        String type = schema.get("type").getAsString();
        boolean matches = switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> McpJson.string(value);
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> McpJson.number(value);
            case "integer" -> McpJson.number(value) && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
            default -> throw new IllegalStateException("Unknown tool schema type " + type);
        };
        if (!matches) throw new IllegalArgumentException(at + ": expected " + type);
        if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value))
            throw new IllegalArgumentException(at + ": expected one of " + schema.get("enum"));
        if (value.isJsonArray() && schema.has("items")) {
            var items = value.getAsJsonArray();
            for (int i = 0; i < items.size(); i++)
                validate(at + "[" + i + "]", schema.getAsJsonObject("items"), items.get(i));
        }
        if (value.isJsonObject()) validateObject(at, schema, value.getAsJsonObject());
    }

    private static void validateObject(String at, JsonObject schema, JsonObject value) {
        var properties = schema.getAsJsonObject("properties");
        var required = schema.getAsJsonArray("required");
        if (required != null) {
            for (var name : required) {
                if (!value.has(name.getAsString()))
                    throw new IllegalArgumentException(at + "." + name.getAsString() + ": required");
            }
        }
        for (var entry : value.entrySet()) {
            String member = at + "." + entry.getKey();
            if (properties != null && properties.has(entry.getKey())) {
                validate(member, properties.getAsJsonObject(entry.getKey()), entry.getValue());
            } else if (schema.has("additionalProperties")) {
                var extra = schema.get("additionalProperties");
                if (extra.isJsonObject()) validate(member, extra.getAsJsonObject(), entry.getValue());
                else if (!extra.getAsBoolean()) throw new IllegalArgumentException(member + ": unknown argument");
            }
        }
    }

    static String string(JsonObject args, String name) {
        return args.get(name).getAsString();
    }

    static boolean flag(JsonObject args, String name) {
        return args.has(name) && args.get(name).getAsBoolean();
    }

    static void options(List<String> cli, JsonObject args, String... names) {
        for (String name : names) {
            if (!args.has(name)) continue;
            cli.add("--" + name.replaceAll("([A-Z])", "-$1").toLowerCase(java.util.Locale.ROOT));
            var value = args.get(name).getAsJsonPrimitive();
            if (value.isNumber()) {
                // CLI integer flags expect plain integers, even if JSON used 1.0 or 1e0.
                var decimal = value.getAsBigDecimal().stripTrailingZeros();
                cli.add(decimal.scale() <= 0 ? decimal.toPlainString() : value.getAsString());
            } else if (value.isString()) {
                cli.add(value.getAsString());
            } else if (!value.getAsBoolean()) {
                cli.removeLast();
            }
        }
    }
}

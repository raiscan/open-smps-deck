package com.opensmpsdeck.headless.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

final class McpJson {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private McpJson() {}

    static JsonObject object(Object value) {
        return JSON.toJsonTree(value).getAsJsonObject();
    }

    static JsonElement parse(String text) throws IOException {
        return parse(new StringReader(text));
    }

    static JsonElement parse(Reader input) throws IOException {
        var reader = new JsonReader(input);
        var result = JSON.getAdapter(JsonElement.class).read(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing content after JSON value");
        return result;
    }

    static boolean string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    static boolean number(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && Double.isFinite(value.getAsDouble());
    }
}

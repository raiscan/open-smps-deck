package com.opensmpsdeck.headless.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.opensmpsdeck.headless.ReconstructionStages;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.mcp.McpJson.*;

public final class McpServer {
    private static final String MODERN = "2026-07-28";
    private static final String LEGACY = "2025-11-25";
    private static final List<String> LEGACY_VERSIONS = List.of(LEGACY, "2025-06-18");
    private static final String PROTOCOL = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    private static final Map<String, ?> INFO = Map.of("name", "opensmpsdeck", "version", "0.1.0");
    private static final Map<String, ?> CAPABILITIES = Map.of("tools", Map.of());
    private static final JsonObject EMPTY = new JsonObject();

    private final PrintStream output;
    // Only transport mode is remembered. Tools resolve all project state from the call's files.
    private boolean modern;

    private McpServer(PrintStream output) {
        this.output = output;
    }

    public static void run(InputStream input, PrintStream output) throws IOException {
        var server = new McpServer(output);
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        for (String line; (line = reader.readLine()) != null;) {
            server.receive(line);
            if (output.checkError()) throw new IOException("Could not write the MCP response");
        }
    }

    private void receive(String line) {
        JsonElement message;
        try {
            message = parse(line);
        } catch (IOException | JsonParseException invalid) {
            error(JsonNull.INSTANCE, -32700, "Parse error", null);
            return;
        }

        if (message == null || !message.isJsonObject()) {
            error(JsonNull.INSTANCE, -32600, "Expected a JSON-RPC 2.0 request", null);
            return;
        }
        var request = message.getAsJsonObject();
        var id = request.get("id");
        var method = request.get("method");
        var version = request.get("jsonrpc");
        if (!string(version) || !version.getAsString().equals("2.0") || !string(method)
                || id != null && !string(id) && !number(id)) {
            error(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC 2.0 request", null);
            return;
        }
        if (id == null) return;  // Includes initialized and cancellation notifications.

        var rawParams = request.get("params");
        if (rawParams != null && !rawParams.isJsonObject()) {
            error(id, -32602, "Expected object params", null);
            return;
        }
        var params = rawParams == null ? EMPTY : rawParams.getAsJsonObject();
        var rawMeta = params.get("_meta");
        if (rawMeta != null && !rawMeta.isJsonObject()) {
            error(id, -32602, "Expected object _meta", null);
            return;
        }
        var meta = rawMeta == null ? EMPTY : rawMeta.getAsJsonObject();
        String name = method.getAsString();
        if (name.equals("server/discover") || meta.has(PROTOCOL)) modern = true;
        if (modern) {
            if (!string(meta.get(PROTOCOL)) || !meta.has(CLIENT_CAPABILITIES)
                    || !meta.get(CLIENT_CAPABILITIES).isJsonObject()) {
                error(id, -32602, "Expected protocolVersion and clientCapabilities in modern request _meta", null);
                return;
            }
            String requested = meta.get(PROTOCOL).getAsString();
            if (!requested.equals(MODERN)) {
                error(id, -32022, "Unsupported protocol version",
                        Map.of("supported", List.of(MODERN), "requested", requested));
                return;
            }
        }

        JsonObject result;
        switch (name) {
            case "initialize" -> {
                if (modern) {
                    error(id, -32601, "Use server/discover with 2026-07-28", null);
                    return;
                }
                var offered = params.get("protocolVersion");
                if (!string(offered) || !isObject(params, "clientInfo") || !isObject(params, "capabilities")) {
                    error(id, -32602, "Expected protocolVersion, clientInfo and capabilities", null);
                    return;
                }
                String requested = offered.getAsString();
                String negotiated = LEGACY_VERSIONS.contains(requested) ? requested : LEGACY;
                result = object(Map.of("protocolVersion", negotiated, "capabilities", CAPABILITIES, "serverInfo", INFO));
            }
            case "ping" -> {
                if (modern) {
                    error(id, -32601, "Use server/discover with 2026-07-28", null);
                    return;
                }
                result = new JsonObject();
            }
            case "server/discover" -> result = object(Map.of("supportedVersions", List.of(MODERN), "capabilities", CAPABILITIES));
            case "tools/list" -> {
                if (params.has("cursor")) {
                    error(id, -32602, "Unexpected tools/list cursor", null);
                    return;
                }
                result = object(Map.of("tools", McpTools.descriptions()));
            }
            case "tools/call" -> {
                var toolName = params.get("name");
                if (!string(toolName) || params.has("arguments") && !isObject(params, "arguments")) {
                    error(id, -32602, "Expected a tool name and object arguments", null);
                    return;
                }
                if (!McpTools.contains(toolName.getAsString())) {
                    error(id, -32602, "Unknown tool: " + toolName.getAsString(), null);
                    return;
                }
                result = tool(params, meta);
            }
            default -> {
                error(id, -32601, "Unknown method: " + name, null);
                return;
            }
        }
        if (modern) {
            result.addProperty("resultType", "complete");
            result.add("_meta", object(Map.of("io.modelcontextprotocol/serverInfo", INFO)));
            if (name.equals("server/discover") || name.equals("tools/list")) {
                result.addProperty("ttlMs", 0);
                result.addProperty("cacheScope", "public");
            }
        }
        send(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }

    private static boolean isObject(JsonObject params, String key) {
        return params.has(key) && params.get(key).isJsonObject();
    }

    private JsonObject tool(JsonObject params, JsonObject meta) {
        try {
            var arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : EMPTY;
            var result = McpTools.call(params.get("name").getAsString(), arguments, progress(meta));
            return toolResult(result.value(), false, result.png());
        } catch (IOException | RuntimeException failed) {
            String message = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
            return toolResult(object(Map.of("error", message)), true, null);
        }
    }

    private ReconstructionStages.Progress progress(JsonObject meta) {
        var token = meta.get("progressToken");
        if (!string(token) && !number(token)) return (completed, total, message) -> {};
        return (completed, total, message) -> send(Map.of("jsonrpc", "2.0", "method", "notifications/progress", "params",
                Map.of("progressToken", token, "progress", completed, "total", total, "message", message)));
    }

    private static JsonObject toolResult(JsonObject value, boolean error, byte[] png) {
        var content = new java.util.ArrayList<Map<String, String>>();
        if (png != null) content.add(Map.of("type", "image", "mimeType", "image/png",
                "data", java.util.Base64.getEncoder().encodeToString(png)));
        content.add(Map.of("type", "text", "text", error ? value.get("error").getAsString() : JSON.toJson(value)));
        return object(Map.of("content", content, "structuredContent", value, "isError", error));
    }

    private void error(JsonElement id, int code, String message, Object data) {
        var error = object(Map.of("code", code, "message", message));
        if (data != null) error.add("data", JSON.toJsonTree(data));
        send(Map.of("jsonrpc", "2.0", "id", id, "error", error));
    }

    private void send(Map<String, ?> message) {
        output.writeBytes((JSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}

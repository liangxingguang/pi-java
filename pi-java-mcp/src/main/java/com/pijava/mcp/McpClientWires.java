package com.pijava.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.protocol.ClientCapabilities;
import com.pijava.mcp.protocol.Resource;
import com.pijava.mcp.protocol.ResourceTemplate;

/**
 * Wire-map construction and conversion for {@link McpClient}.
 */
final class McpClientWires {

    private McpClientWires() {
    }

    /**
     * Client capabilities, adding the roots capability when roots are
     * configured (client.ts:215-216).
     */
    static ClientCapabilities withRootsCapability(
            @Nullable ClientCapabilities capabilities, boolean hasRoots) {
        if (!hasRoots) {
            return capabilities == null
                    ? new ClientCapabilities(null, null, null, null) : capabilities;
        }
        var caps = capabilities == null
                ? new ClientCapabilities(null, null, null, null) : capabilities;
        if (caps.roots() != null) {
            return caps;
        }
        return new ClientCapabilities(caps.experimental(),
                new ClientCapabilities.Roots(null), caps.sampling(), caps.elicitation());
    }

    /** Request wire. */
    static Map<String, Object> request(long id, String method, Object params) {
        var wire = new LinkedHashMap<String, Object>();
        wire.put("jsonrpc", "2.0");
        wire.put("id", id);
        wire.put("method", method);
        if (params != null) {
            wire.put("params", params);
        }
        return wire;
    }

    /** Notification wire. */
    static Map<String, Object> notification(String method, Object params) {
        var wire = new LinkedHashMap<String, Object>();
        wire.put("jsonrpc", "2.0");
        wire.put("method", method);
        if (params != null) {
            wire.put("params", params);
        }
        return wire;
    }

    /** Success response wire. */
    static Map<String, Object> response(Object id, Object result) {
        var wire = new LinkedHashMap<String, Object>();
        wire.put("jsonrpc", "2.0");
        wire.put("id", id);
        wire.put("result", result);
        return wire;
    }

    /** Error response wire. */
    static Map<String, Object> error(Object id, int code, String message, Object data) {
        var error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.put("data", data);
        }
        var wire = new LinkedHashMap<String, Object>();
        wire.put("jsonrpc", "2.0");
        wire.put("id", id);
        wire.put("error", error);
        return wire;
    }

    /** Convert a typed params object to a wire map. */
    static Map<String, Object> toWire(Object value) {
        return McpJson.mapper().convertValue(value,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    /** View a verified JSON object as a String-keyed map. */
    static Map<String, Object> asObjectMap(Object value) {
        // Verified object by caller; JSON objects always have String keys.
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) value;
        return map;
    }

    /** Attach {@code _meta.progressToken=id} to params (client.ts:404-407). */
    static Object withProgressToken(Object rawParams, long id) {
        var params = rawParams == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<>(asObjectMap(rawParams));
        var meta = params.get("_meta") instanceof Map<?, ?> old
                ? new LinkedHashMap<>(asObjectMap(old))
                : new LinkedHashMap<String, Object>();
        meta.put("progressToken", id);
        params.put("_meta", meta);
        return params;
    }

    /** Convert a raw resource, defaulting a missing name to its URI (client.ts:116-118). */
    static Resource toResource(Map<String, Object> raw) {
        if (!raw.containsKey("name")) {
            raw = new LinkedHashMap<>(raw);
            raw.put("name", raw.get("uri"));
        }
        return McpJson.mapper().convertValue(raw, Resource.class);
    }

    /** Convert a raw resource template, defaulting a missing name (client.ts:120-122). */
    static ResourceTemplate toResourceTemplate(Map<String, Object> raw) {
        if (!raw.containsKey("name")) {
            raw = new LinkedHashMap<>(raw);
            raw.put("name", raw.get("uriTemplate"));
        }
        return McpJson.mapper().convertValue(raw, ResourceTemplate.class);
    }
}

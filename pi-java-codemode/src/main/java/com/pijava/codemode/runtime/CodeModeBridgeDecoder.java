package com.pijava.codemode.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.codemode.api.CodeModeData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodes the JSON payloads produced inside the VM, with the TypeScript error messages. */
final class CodeModeBridgeDecoder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CodeModeBridgeDecoder() {
    }

    /** Error thrown when a payload cannot be decoded. */
    static final class BridgeError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BridgeError(String message) {
            super(message);
        }
    }

    /**
     * Serializes an object to JSON, returning null when Jackson cannot serialize it.
     *
     * @param value value to serialize
     * @return JSON text, or null when undefined
     */
    static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** Parses a bridge JSON string or throws a BridgeError. */
    static JsonNode parseBridgeJson(String json, String what) {
        if (json == null) {
            throw new BridgeError(what + " is not valid JSON");
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BridgeError(what + " is not valid JSON");
        }
    }

    /** Parses the store writes payload with the exact TypeScript validation. */
    static CodeModeData.StoreWrites parseStoreWrites(String json) {
        JsonNode entries = parseBridgeJson(json, "store writes");
        if (!entries.isArray()) {
            throw new BridgeError("store writes are not an array");
        }
        Map<String, Object> set = new LinkedHashMap<>();
        List<String> delete = new ArrayList<>();
        for (JsonNode entry : entries) {
            if (!entry.isArray() || !entry.get(0).isTextual()
                    || (entry.size() == 2 ? !entry.get(1).isTextual() : entry.size() != 1)) {
                throw new BridgeError("store writes contain a malformed entry");
            }
            String key = entry.get(0).asText();
            if (entry.size() == 1) {
                delete.add(key);
            } else {
                set.put(key, toObject(parseBridgeJson(entry.get(1).asText(),
                        "store value for " + stringify(key))));
            }
        }
        return new CodeModeData.StoreWrites(set, delete);
    }

    /** Parses a script error payload with the exact TypeScript validation. */
    static CodeModeData.CodemodeError parseScriptError(String json) {
        JsonNode parsed = parseBridgeJson(json, "script error");
        if (!parsed.isObject()) {
            throw new BridgeError("script error is not an object");
        }
        JsonNode name = parsed.get("name");
        JsonNode message = parsed.get("message");
        JsonNode stack = parsed.get("stack");
        if (message == null || !message.isTextual()
                || (name != null && !name.isNull() && !name.isTextual())
                || (stack != null && !stack.isNull() && !stack.isTextual())) {
            throw new BridgeError("script error is malformed");
        }
        return new CodeModeData.CodemodeError(
                "script",
                name == null || name.isNull() ? null : name.asText(),
                message.asText(),
                stack == null || stack.isNull() ? null : stack.asText());
    }

    /** Converts a decoded JSON node to a plain Java value. */
    static Object toObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode child : node) {
                list.add(toObject(child));
            }
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> map.put(entry.getKey(), toObject(entry.getValue())));
        return map;
    }
}

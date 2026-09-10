package com.pijava.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON-Schema subset validator for tool-call arguments
 * (agent-loop optimization plan §3.2, aligning pi {@code agent-loop.ts:618
 * validateToolArguments}).
 *
 * <p>Only the keywords the built-in tools actually use are supported:
 * {@code type}, {@code required}, {@code properties}, {@code items}. The full
 * {@code json-schema-validator} dependency is deliberately avoided — it is
 * reflection-heavy and costs GraalVM native-image configuration, while a
 * subset validator is enough to catch truncated or malformed tool arguments
 * before they are executed.</p>
 *
 * <p>Validation operates on the raw {@code Map<String, Object>} handed to
 * {@link ToolRegistry#execute} — the JSON shape described by the schema, not
 * the typed record produced by {@link AgentTool#prepareArguments}.</p>
 *
 * <p>On the first violation it throws {@link IllegalArgumentException}; the
 * tool pipeline encodes that as an error result fed back to the model, so a
 * malformed call is never executed and the model can retry.</p>
 */
public final class ToolArgumentsValidator {

    private ToolArgumentsValidator() {}

    /**
     * Validate {@code args} against {@code schema}.
     *
     * @param schema a JSON-Schema object ({@code {type, properties, required, items}})
     * @param args   the raw tool-call arguments
     * @throws IllegalArgumentException when a required field is missing or a
     *         field does not match its declared type
     */
    public static void validate(Map<String, Object> schema, Map<String, Object> args) {
        if (schema == null) {
            return; // No schema means no constraints.
        }
        requireType(schema.get("type"), args, "arguments");
        checkRequired(schema, args);
        checkProperties(schema, args);
    }

    /** Enforce the {@code required} list — every name must be present and non-null. */
    private static void checkRequired(Map<String, Object> schema, Map<String, Object> args) {
        Object requiredObj = schema.get("required");
        if (!(requiredObj instanceof List<?> required)) {
            return;
        }
        // Stream adapters fall back to {"_raw": "<raw json>"} when the model's
        // tool arguments are truncated or malformed (BashTool.prepareArguments).
        // Those recover the command themselves, so schema enforcement is
        // deliberately skipped to preserve the recovery path.
        boolean hasRawFallback = args != null && args.containsKey("_raw");
        for (Object name : required) {
            if (name instanceof String key
                    && (args == null || !args.containsKey(key) || args.get(key) == null)
                    && !hasRawFallback) {
                throw new IllegalArgumentException(
                    "missing required argument \"" + key + "\"");
            }
        }
    }

    /** For object-typed fields, validate each property against its subschema. */
    private static void checkProperties(Map<String, Object> schema, Map<String, Object> args) {
        if (args == null) {
            return;
        }
        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map<?, ?> properties)) {
            return;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !(entry.getValue() instanceof Map<?, ?> propSchema)
                    || !args.containsKey(name) || args.get(name) == null) {
                continue;
            }
            // Normalize nested schema maps so generics line up.
            Map<String, Object> normalized = normalizeSchema(propSchema);
            Object value = args.get(name);
            String type = schemaType(normalized);
            requireTypeFor(value, type, name);
            if ("object".equals(type)) {
                if (value instanceof Map<?, ?> obj) {
                    Map<String, Object> objArgs = normalizeSchema(obj);
                    checkRequired(normalized, objArgs);
                    checkProperties(normalized, objArgs);
                }
            } else if ("array".equals(type) && value instanceof List<?> list) {
                Object items = normalized.get("items");
                if (items instanceof Map<?, ?> itemSchema) {
                    Map<String, Object> itemNorm = normalizeSchema(itemSchema);
                    String itemType = schemaType(itemNorm);
                    for (Object element : list) {
                        requireTypeFor(element, itemType, name);
                    }
                }
            }
        }
    }

    /** Validate the top-level value against the schema's {@code type}. */
    private static void requireType(Object typeObj, Map<String, Object> args, String path) {
        if (typeObj instanceof String type) {
            requireTypeFor(args, type, path);
        }
    }

    /** Check a single value against a declared type keyword. */
    private static void requireTypeFor(Object value, String type, String name) {
        boolean ok = switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number
                && ((Number) value).longValue() == ((Number) value).doubleValue();
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true; // Unknown keyword types are not validated.
        };
        if (!ok) {
            throw new IllegalArgumentException(
                "argument \"" + name + "\" has wrong type: expected " + type);
        }
    }

    private static String schemaType(Map<String, Object> schema) {
        return schema.get("type") instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")  // Jackson produces Map<String,Object> trees
    private static Map<String, Object> normalizeSchema(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}

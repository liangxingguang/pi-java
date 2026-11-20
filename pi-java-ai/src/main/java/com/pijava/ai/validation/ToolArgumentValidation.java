package com.pijava.ai.validation;

import java.util.List;

/**
 * pi's tool-call argument validation: coerce, check, then throw a model-visible error.
 *
 * <p>Mirrors {@code packages/ai/src/utils/validation.ts:37-68}. The returned value is a copy; the
 * caller's arguments are never modified.
 */
public final class ToolArgumentValidation {

    private ToolArgumentValidation() {}

    /** Reject a terminal stream parse error before applying any schema rules. */
    public static Object parse(String toolName, Object schema, Object arguments, String argumentsError) {
        if (argumentsError != null) {
            throw new IllegalArgumentException(
                    "Invalid arguments for tool \"" + toolName + "\": " + argumentsError);
        }
        return parse(toolName, schema, arguments);
    }

    /** Validate and coerce {@code arguments}; throws pi's {@code Validation failed ...} text. */
    public static Object parse(String toolName, Object schema, Object arguments) {
        Object cloned = JsonValues.deepCopy(arguments);
        var resolver = new SchemaRefResolver(schema);
        Object coerced = new SchemaCoerce(resolver).coerce(schema, cloned);
        if (new SchemaCheck(resolver).check(schema, coerced)) {
            return coerced;
        }
        List<SchemaErrors.Error> errors = new SchemaErrors(resolver).errors(schema, coerced);
        StringBuilder message = new StringBuilder();
        message.append("Validation failed for tool \"").append(toolName).append("\":\n");
        for (SchemaErrors.Error error : errors) {
            message.append("  - ").append(formatPath(error.path())).append(": ")
                    .append(error.message()).append('\n');
        }
        message.append("\nReceived arguments:\n").append(JsJson.stringify(cloned));
        throw new IllegalArgumentException(message.toString());
    }

    /** A pi-schema error path (JSON Pointer) as the dotted path the model sees. */
    static String formatPath(String pointer) {
        if (pointer.isEmpty()) {
            return "root";
        }
        String body = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        StringBuilder out = new StringBuilder();
        for (String segment : body.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(segment.replace("~1", "/").replace("~0", "~"));
        }
        return out.toString();
    }

    /** Convenience for Java call sites that always have object arguments. */
    @SuppressWarnings("unchecked") // JSON maps from Jackson are Map<String,Object> at runtime.
    public static java.util.Map<String, Object> parseMap(String toolName, Object schema,
                                                         java.util.Map<String, Object> arguments) {
        return (java.util.Map<String, Object>) parse(toolName, schema, arguments);
    }
}

package com.pijava.agent.tool;

import com.pijava.ai.validation.ToolArgumentValidation;

import java.util.Map;

/**
 * Thin agent-side entry to pi-schema-compatible tool argument validation.
 *
 * <p>pi validates in the AI layer ({@code packages/ai/src/utils/validation.ts:37-68}); the Java
 * implementation lives in {@code com.pijava.ai.validation}. This class keeps the existing
 * agent-core call sites and their return value: the coerced arguments the tool must execute with.
 *
 * <p>On the first failure {@link ToolArgumentValidation} throws {@link IllegalArgumentException};
 * the tool pipeline encodes that as an error result fed back to the model.
 */
public final class ToolArgumentsValidator {

    private ToolArgumentsValidator() {}

    /** Validate without a tool name (legacy two-argument call). */
    public static Map<String, Object> validate(Map<String, Object> schema, Map<String, Object> args) {
        return validate(schema, args, "unknown", null);
    }

    /**
     * Validate and coerce {@code args}; returns the coerced arguments.
     *
     * @param schema         a JSON Schema object, or {@code null} when the tool declares none
     * @param args           the raw tool-call arguments
     * @param toolName       the name used in pi's model-visible error text
     * @param argumentsError a terminal stream parse error, rejected before schema rules
     */
    @SuppressWarnings("unchecked") // pi-schema returns the same JSON shape it was given.
    public static Map<String, Object> validate(Map<String, Object> schema, Map<String, Object> args,
                                               String toolName, String argumentsError) {
        if (schema == null) {
            if (argumentsError != null) {
                throw new IllegalArgumentException(
                        "Invalid arguments for tool \"" + toolName + "\": " + argumentsError);
            }
            return args;
        }
        Object result = ToolArgumentValidation.parse(toolName, schema, args, argumentsError);
        if (result == null) {
            return null;
        }
        if (!(result instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        return (Map<String, Object>) result;
    }
}

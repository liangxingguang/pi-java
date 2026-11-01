package com.pijava.ai.stream;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** Raw text and incremental parser state owned by exactly one provider tool-call slot. */
public final class ToolCallArgumentsStream {

    /** Final complete-document result; arguments are empty whenever argumentsError is present. */
    public record Finish(Map<String, Object> arguments, @Nullable String argumentsError) {
        public Finish {
            arguments = JsonSnapshots.copyObject(arguments);
        }
    }

    /** Authoritative replacement result; delta is null for a non-prefix reset. */
    public record Replacement(@Nullable String delta, Map<String, Object> arguments) {
        public Replacement {
            arguments = JsonSnapshots.copyObject(arguments);
        }
    }

    private final StringBuilder text = new StringBuilder();
    private StreamingJsonParser parser = new StreamingJsonParser();
    private Object snapshot;
    private Map<String, Object> arguments = Map.of();

    /** Complete raw argument text received so far. */
    public String text() {
        return text.toString();
    }

    /** Append one provider delta and return the latest partial object snapshot. */
    public Map<String, Object> append(String delta) {
        Objects.requireNonNull(delta, "delta");
        text.append(delta);
        parser.append(delta);
        return partial();
    }

    /** Consume a provider's complete text, returning only a prefix-extension tail for publication. */
    public Replacement replace(String completeText) {
        Objects.requireNonNull(completeText, "completeText");
        String current = text.toString();
        if (completeText.startsWith(current)) {
            String delta = completeText.substring(current.length());
            return new Replacement(delta, delta.isEmpty() ? partial() : append(delta));
        }
        text.setLength(0);
        parser = new StreamingJsonParser();
        return new Replacement(null, append(completeText));
    }

    /** Parse the complete raw text once, reporting malformed or non-object arguments. */
    public Finish finish() {
        return FinalToolArguments.parse(text.toString());
    }

    private Map<String, Object> partial() {
        Object current = parser.snapshot();
        if (current == snapshot) {
            return arguments;
        }
        snapshot = current;
        arguments = current instanceof Map<?, ?> ? JsonSnapshots.castObject(current) : Map.of();
        return arguments;
    }
}

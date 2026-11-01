package com.pijava.ai.stream;

import java.util.List;
import java.util.Objects;

/** One immutable incremental change to a streaming JSON value. */
public sealed interface JsonChange {

    int MAX_REPORTED_DEPTH = 32;

    /** Set a completed value or a newly opened container/string. */
    record Set(List<Object> path, Object value) implements JsonChange {
        public Set {
            path = checkedPath(path);
            value = JsonSnapshots.copyValue(value);
        }
    }

    /** Append text to an open string. */
    record Append(List<Object> path, String text) implements JsonChange {
        public Append {
            path = checkedPath(path);
            Objects.requireNonNull(text, "text");
        }
    }

    private static List<Object> checkedPath(List<Object> path) {
        Objects.requireNonNull(path, "path");
        if (path.size() > MAX_REPORTED_DEPTH) {
            throw new IllegalArgumentException("JSON change path exceeds depth 32");
        }
        for (Object segment : path) {
            if (!(segment instanceof String) && !(segment instanceof Number)) {
                throw new IllegalArgumentException("JSON change path segments must be strings or numbers");
            }
        }
        return List.copyOf(path);
    }
}

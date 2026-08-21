package com.pijava.ai.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** Termination reason for an image generation run — pi {@code ImagesStopReason}。
 *  纯常量闭集 → enum（CLAUDE.md 规范）。 */
public enum ImageStopReason {
    STOP, ERROR, ABORTED;

    /** pi: "stop" | "error" | "aborted"。 */
    @JsonValue
    public String wireName() {
        return switch (this) {
            case STOP -> "stop";
            case ERROR -> "error";
            case ABORTED -> "aborted";
        };
    }
}

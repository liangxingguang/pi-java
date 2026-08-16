package com.pijava.agent.hook;

import java.util.Map;

/** Hook triggered before serializing the API payload. Can modify the JSON payload. */
@FunctionalInterface
public interface BeforePayloadHook {
    /** Invoked before serializing the API payload; may return a modified payload. */
    Map<String, Object> beforePayload(Map<String, Object> payload);
}

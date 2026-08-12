package com.pijava.agent.hook;

import java.util.Map;

/** Hook triggered before serializing the API payload. Can modify the JSON payload. */
@FunctionalInterface
public interface BeforePayloadHook {
    Map<String, Object> beforePayload(Map<String, Object> payload);
}

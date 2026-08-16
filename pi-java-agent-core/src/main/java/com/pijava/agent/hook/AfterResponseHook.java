package com.pijava.agent.hook;

/** Hook triggered after receiving an LLM response. Read-only. */
@FunctionalInterface
public interface AfterResponseHook {
    /** Invoked after receiving an LLM response. */
    void afterResponse(ResponseContext ctx);
}

package com.pijava.agent.hook;

/** Hook triggered after receiving an LLM response. Read-only. */
@FunctionalInterface
public interface AfterResponseHook {
    void afterResponse(ResponseContext ctx);
}

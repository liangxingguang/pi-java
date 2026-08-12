package com.pijava.agent.hook;

/** Hook triggered before sending an LLM API request. */
@FunctionalInterface
public interface BeforeRequestHook {
    void beforeRequest(RequestContext ctx);
}

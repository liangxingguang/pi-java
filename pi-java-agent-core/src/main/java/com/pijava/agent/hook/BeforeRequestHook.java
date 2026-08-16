package com.pijava.agent.hook;

/** Hook triggered before sending an LLM API request. */
@FunctionalInterface
public interface BeforeRequestHook {
    /** Invoked before sending an LLM API request. */
    void beforeRequest(RequestContext ctx);
}

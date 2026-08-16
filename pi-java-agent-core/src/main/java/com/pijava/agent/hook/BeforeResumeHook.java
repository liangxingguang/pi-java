package com.pijava.agent.hook;

/** Hook triggered when resuming a suspended operation. */
@FunctionalInterface
public interface BeforeResumeHook {
    /** Invoked when resuming a suspended operation. */
    void beforeResume(ResumeContext ctx);
}

package com.pijava.agent.hook;

/** Hook triggered when resuming a suspended operation. */
@FunctionalInterface
public interface BeforeResumeHook {
    void beforeResume(ResumeContext ctx);
}

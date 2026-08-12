package com.pijava.agent.hook;

/** Context passed to {@code before_resume} hook. */
public record ResumeContext(String lane, String runId, String reason) {}

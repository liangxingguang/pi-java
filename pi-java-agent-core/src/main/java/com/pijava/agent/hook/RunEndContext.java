package com.pijava.agent.hook;

/** Context passed to {@code before_run_end} hook. */
public record RunEndContext(String lane, String runId, String outcome) {}

package com.pijava.coding.agent.core;

/**
 * Terminal status of one {@code processPrompt} run (Phase 3 §10).
 *
 * @param exitCode process exit code for print mode (0 = success)
 * @param reason   stop reason, aligned with {@code StreamEvent.StreamDone.reason}
 */
public record RunStatus(
    int exitCode,
    String reason
) {}

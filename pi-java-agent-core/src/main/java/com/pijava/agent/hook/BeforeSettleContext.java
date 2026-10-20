package com.pijava.agent.hook;

/**
 * Context for {@code before_settle} hooks（docs/15）。
 *
 * @param lane  lane name
 * @param runId current run identifier（本 pass 的 runId）
 */
public record BeforeSettleContext(String lane, String runId) {}

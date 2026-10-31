package com.pijava.evals.harness;

import java.util.Objects;

/**
 * 批 32（docs/44 §3.2）：harness 输入的一步 —— pi {@code PiCodingAgentInput}
 * 的 {@code {type:"prompt"}} / {@code {type:"reload"}} 对位。
 *
 * @param kind    {@code "prompt"} or {@code "reload"}
 * @param content prompt text (null for reload)
 */
public record HarnessStep(String kind, String content) {

    /** Normalizes kind. */
    public HarnessStep {
        Objects.requireNonNull(kind, "kind");
        if (!"prompt".equals(kind) && !"reload".equals(kind)) {
            throw new IllegalArgumentException("Unknown harness step kind: " + kind);
        }
    }

    /**
     * Prompt step.
     *
     * @param content prompt text
     * @return step
     */
    public static HarnessStep prompt(String content) {
        return new HarnessStep("prompt", Objects.requireNonNull(content, "content"));
    }

    /**
     * Reload step.
     *
     * @return step
     */
    public static HarnessStep reload() {
        return new HarnessStep("reload", null);
    }

    /** @return {@code true} for a reload step */
    public boolean isReload() {
        return "reload".equals(kind);
    }
}

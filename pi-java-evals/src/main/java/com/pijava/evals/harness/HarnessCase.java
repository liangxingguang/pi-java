package com.pijava.evals.harness;

import java.util.Objects;

import com.pijava.evals.plan.EvalCaseDescriptor;

/**
 * 批 32（docs/44 §6）：一个可由 {@link PiCodingAgentHarness} 执行的用例定义。
 *
 * <p>{@link #expected()} 原样交给 suite 的 {@code judges()}（形状随判分器而定）。</p>
 *
 * @param descriptor eval set + case name
 * @param prompt     prompt sent to the coding agent
 * @param expected   judge expectation, {@code null} when the suite has no judge
 */
public record HarnessCase(EvalCaseDescriptor descriptor, String prompt, Object expected) {

    /** Rejects nulls and blank prompts. */
    public HarnessCase {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
    }
}

package com.pijava.ai.model;

import java.util.Objects;

/**
 * scope 解析期的一条警告（pi {@code ModelScopeDiagnostic}，model-resolver.ts:264-269）。
 *
 * <p>两码闭集＝纯常量判别 ⇒ enum。文案与 pi <b>逐字一致</b>（打印面在 coding-agent
 * 装配层补 {@code "Warning: "} 前缀，对应 pi {@code chalk.yellow("Warning: " + msg)}）。</p>
 *
 * @param code    诊断码
 * @param message 逐字文案
 * @param pattern 触发诊断的<b>原始</b>模式（未剥后缀）
 */
public record ModelScopeDiagnostic(Code code, String message, String pattern) {

    /** pi {@code code: "no-match" | "invalid-thinking-level"}（model-resolver.ts:266）。 */
    public enum Code {
        NO_MATCH,
        INVALID_THINKING_LEVEL
    }

    public ModelScopeDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(pattern, "pattern");
    }

    /** `No models match pattern "<p>"`（pi model-resolver.ts:323,341 逐字）。 */
    static ModelScopeDiagnostic noMatch(String pattern) {
        return new ModelScopeDiagnostic(Code.NO_MATCH,
            "No models match pattern \"" + pattern + "\"", pattern);
    }

    /** `Invalid thinking level "<suffix>" in pattern "<pattern>". Using default instead.`（pi :332）。 */
    static ModelScopeDiagnostic invalidLevel(String suffix, String pattern) {
        return new ModelScopeDiagnostic(Code.INVALID_THINKING_LEVEL,
            "Invalid thinking level \"" + suffix + "\" in pattern \"" + pattern
                + "\". Using default instead.",
            pattern);
    }
}

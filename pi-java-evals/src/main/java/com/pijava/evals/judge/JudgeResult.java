package com.pijava.evals.judge;

import java.util.Map;

/**
 * 批 32（docs/44 §4）：单次判分结果。
 *
 * @param score     {@code 0..1} 分（1.0 = 完全匹配）
 * @param rationale 人类可读理由；无话可说时为空串
 * @param metadata  判分细节（永不 {@code null}）
 */
public record JudgeResult(double score, String rationale, Map<String, Object> metadata) {

    /** Rejects nulls and normalizes rationale/metadata. */
    public JudgeResult {
        rationale = rationale == null ? "" : rationale;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** {@code true} when the judge awarded a full score. */
    public boolean passed() {
        return score >= 1.0;
    }
}

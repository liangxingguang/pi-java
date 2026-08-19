package com.pijava.protocol;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会话阶段（对齐 pi {@code SessionPhaseSchema}：idle/turn/compaction/
 * branch_summary/retry）。与 AgentHarnessPhase 对齐，适配层无需第二套词汇。
 */
public enum SessionPhase {
    IDLE, TURN, COMPACTION, BRANCH_SUMMARY, RETRY;

    /** wire 值：snake_case。 */
    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}

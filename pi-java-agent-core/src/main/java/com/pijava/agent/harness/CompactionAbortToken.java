package com.pijava.agent.harness;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * pi {@code AbortController} 对位的 per-operation 压缩取消令牌（#98/B36）：
 * {@code agent-session.ts:403-404} 的手动/自动双槽各持一枚，在飞窗口内置位、
 * 窗口退出清位；{@code abortCompaction()}（:2914-2917）对两槽同时 abort。
 *
 * <p>窄差（docs/24 §12.2#2）：生效点与 pi 相同（生成后、落库前，:2843-2845），
 * 但 {@code SummaryGenerator} 不接信号 —— LLM 流内不可中断，触发重裁条件＝
 * 用户实测长摘要不可取消。</p>
 */
public final class CompactionAbortToken {

    private final AtomicBoolean aborted = new AtomicBoolean();

    /** pi {@code controller.abort()}。 */
    public void abort() {
        aborted.set(true);
    }

    /** pi {@code controller.signal.aborted}。 */
    public boolean isAborted() {
        return aborted.get();
    }
}

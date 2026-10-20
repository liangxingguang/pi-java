package com.pijava.agent.hook;

import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.CompactionAbortToken;

import java.util.List;

/**
 * Context passed to {@code before_compaction} hook.
 *
 * <p>docs/15 裁决⑥ 扩形：pi {@code SessionBeforeCompactEvent}（types.ts:771-784）
 * 的载荷对位 —— {@code plan} 即 pi 的 {@code preparation}（{@code CompactionService.Plan}：
 * firstKeptEntryId/messagesToSummarize/turnPrefixMessages/isSplitTurn/previousSummary
 * 全在其中），{@code transcript} 即 {@code branchEntries}。{@code plan} 在守卫之后
 * 必有（prepare 空返回的路径根本不进 {@code applyCompaction}），仅防御性可空。</p>
 *
 * @param lane               lane name
 * @param transcript         当前 branch 的条目（pi {@code branchEntries}）
 * @param currentTokens      压缩前 token 估算（pi {@code preparation.tokensBefore}）
 * @param plan               切点规划（pi {@code preparation}；无 fileOps —— java 的
 *                           fileOps 在 {@code compact()} 内收集，prepare 时点缺席，窄差 D22）
 * @param reason             {@code "manual"}/{@code "threshold"}/{@code "overflow"}
 * @param willRetry          压缩后是否重试被中止的轮（溢出恢复路）
 * @param customInstructions 手动路的自定义指令；自动路恒 null（pi 同）
 * @param abortToken         per-operation 取消令牌（pi {@code signal} 的对位）
 */
public record CompactionContext(String lane, List<Entry> transcript, int currentTokens,
                                CompactionService.Plan plan, String reason, boolean willRetry,
                                String customInstructions, CompactionAbortToken abortToken) {

    /** 兼容批前 3 组件形状：扩展载荷全空。 */
    public CompactionContext(String lane, List<Entry> transcript, int currentTokens) {
        this(lane, transcript, currentTokens, null, "manual", false, null, null);
    }
}

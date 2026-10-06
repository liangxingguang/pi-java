package com.pijava.coding.agent.rpc;

import java.util.Map;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.ai.Usage;

/**
 * RPC {@code compact} 响应载荷（pi {@code compaction.ts:926-931}）：恰好
 * <b>5 个键</b> —— {@code summary / firstKeptEntryId / tokensBefore / usage /
 * details}。agent-core 的 {@link CompactionResult} 另有内部字段
 * {@code estimatedTokensAfter}，pi 侧该量只用于内部判断、不上线，故经此 DO 投影。
 */
record CompactResultWire(
    String summary,
    String firstKeptEntryId,
    long tokensBefore,
    Usage usage,
    Map<String, Object> details
) {
    static CompactResultWire of(CompactionResult result) {
        return new CompactResultWire(
            result.summary(),
            result.firstKeptEntryId(),
            result.tokensBefore(),
            result.usage(),
            result.details());
    }
}

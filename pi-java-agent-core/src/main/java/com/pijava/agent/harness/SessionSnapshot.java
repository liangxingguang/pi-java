package com.pijava.agent.harness;

import java.util.List;

import com.pijava.agent.context.ContextUsage;

/**
 * Immutable point-in-time snapshot of the entire session.
 *
 * <p>{@code contextUsage} 对位 pi {@code ContextUsage}（内置 footer 的
 * {@code 42.2%/272k (auto)} 数据源）；模型缺失或窗口未知时为 {@code null}。
 * {@code autoCompact} 对位 footer 的 {@code (auto)} 指示（压缩设置启用时为 true）。</p>
 */
public record SessionSnapshot(
    String name,
    String model,
    String phase,
    long totalTokens,
    int turnCount,
    List<String> activeTools,
    List<LaneInfo> lanes,
    ContextUsage contextUsage,
    boolean autoCompact
) {}

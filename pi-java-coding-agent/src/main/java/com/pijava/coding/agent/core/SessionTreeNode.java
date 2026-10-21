package com.pijava.coding.agent.core;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.Entry;

/**
 * pi {@code SessionTreeNode}（{@code session-manager.ts:200-207}）对位：
 * entry 树节点＋解析后的标签。
 *
 * @param entry          会话条目
 * @param children       子节点（构建后冻结为 {@code List.copyOf}）
 * @param label          解析后的当前标签（{@code null} ≙ pi 的 undefined）
 * @param labelTimestamp 最近一次 label 变更的时间戳（{@code null} ≙ undefined；
 *                       pi 是 ISO 串，java 原生 {@link Instant}）
 */
public record SessionTreeNode(
    Entry entry,
    List<SessionTreeNode> children,
    String label,
    Instant labelTimestamp
) {
    /** Defensively freezes {@code children}. */
    public SessionTreeNode {
        children = List.copyOf(children);
    }
}

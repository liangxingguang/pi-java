package com.pijava.coding.agent.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

import com.pijava.agent.entry.Entry;

/**
 * pi {@code sessionManager.getTree()}（{@code session-manager.ts:1529-1567}）
 * 的纯函数对位：两轮建点再链接、孤儿/自指 ⇒ 根、子表按时间戳升序
 * （迭代栈防爆栈，pi :111-112 同纪律）、label＋labelTimestamp 解析。
 */
public final class SessionTreeBuilder {

    private SessionTreeBuilder() {}

    /** 构建期的可变节点（冻结在底向上第二程）。 */
    private static final class MutableNode {
        final Entry entry;
        final String label;
        final Instant labelTimestamp;
        final List<MutableNode> children = new ArrayList<>();

        MutableNode(Entry entry, String label, Instant labelTimestamp) {
            this.entry = entry;
            this.label = label;
            this.labelTimestamp = labelTimestamp;
        }
    }

    /**
     * 由全分支条目（任意顺序）组装 entry 树。
     *
     * @param entries             会话全部条目（pi {@code getEntries()} 对位）
     * @param labelById           entryId → 当前标签（pi {@code labelsById}）
     * @param labelTimestampById  entryId → 标签时间戳（pi {@code labelTimestampsById}）
     */
    public static List<SessionTreeNode> build(List<Entry> entries,
                                              Function<String, String> labelById,
                                              Function<String, Instant> labelTimestampById) {
        // pi :89-93：先全量建节点（label 解析在这一程）。
        var nodeById = new LinkedHashMap<String, MutableNode>();
        for (var entry : entries) {
            nodeById.put(entry.id(), new MutableNode(
                entry, labelById.apply(entry.id()), labelTimestampById.apply(entry.id())));
        }
        // pi :96-108：再按 parentId 链接；parentId==null、自指、父缺失（孤儿）⇒ 根。
        var roots = new ArrayList<MutableNode>();
        for (var entry : entries) {
            var node = nodeById.get(entry.id());
            if (entry.parentId() == null || entry.parentId().equals(entry.id())) {
                roots.add(node);
            } else {
                var parent = nodeById.get(entry.parentId());
                if (parent != null) {
                    parent.children.add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        // pi :110-117：children 按 timestamp 升序（迭代栈；java sort 稳定，
        // 与 pi Array.sort（ES2019 起稳定）在等时间戳时同序）。
        var sortStack = new ArrayDeque<>(roots);
        while (!sortStack.isEmpty()) {
            var node = sortStack.pop();
            node.children.sort(Comparator.comparing(n -> n.entry.timestamp()));
            sortStack.addAll(node.children);
        }
        // 底向上冻结：逆前序保证孩子先于父亲处理。
        var order = new ArrayDeque<MutableNode>();
        var walk = new ArrayDeque<>(roots);
        while (!walk.isEmpty()) {
            var node = walk.pop();
            order.push(node);
            for (var child : node.children) {
                walk.push(child);
            }
        }
        var frozen = new IdentityHashMap<MutableNode, SessionTreeNode>();
        while (!order.isEmpty()) {
            var node = order.pop();
            var children = node.children.stream().map(frozen::get).toList();
            frozen.put(node, new SessionTreeNode(
                node.entry, children, node.label, node.labelTimestamp));
        }
        return roots.stream().map(frozen::get).toList();
    }
}

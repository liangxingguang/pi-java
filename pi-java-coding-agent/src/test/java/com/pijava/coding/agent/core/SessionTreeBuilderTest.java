package com.pijava.coding.agent.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SessionTreeBuilder} 的树形边界（pi {@code getTree()}，
 * {@code session-manager.ts:1529-1567} 逐字对位）：孤儿/自指 ⇒ 根、
 * 子表按时间戳升序、label＋labelTimestamp 解析、children 冻结。
 */
class SessionTreeBuilderTest {

    private static final Instant T0 = Instant.parse("2026-03-28T14:00:00Z");

    private static Entry.Message user(String id, String parentId, Instant timestamp, String text) {
        return new Entry.Message(id, 0, parentId, timestamp,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    private static List<SessionTreeNode> build(List<Entry> entries) {
        return SessionTreeBuilder.build(entries, id -> null, id -> null);
    }

    private static SessionTreeNode onlyRoot(List<SessionTreeNode> tree) {
        assertThat(tree).hasSize(1);
        return tree.get(0);
    }

    @Test
    void orphanEntryBecomesRoot() {
        // pi :104-106：父缺失 ⇒ 当根（"Orphan - treat as root"）。
        var tree = build(List.of(
            user("a", "missing-parent", T0, "orphan")));
        var root = onlyRoot(tree);
        assertThat(root.entry().id()).isEqualTo("a");
        assertThat(root.children()).isEmpty();
    }

    @Test
    void selfParentedEntryBecomesRoot() {
        // pi :98：parentId === entry.id ⇒ 根。
        var tree = build(List.of(
            user("a", "a", T0, "self")));
        assertThat(onlyRoot(tree).entry().id()).isEqualTo("a");
    }

    @Test
    void childrenSortedByTimestampAscending() {
        // pi :110-117：children 按 timestamp 升序（旧在前），与追加序无关。
        var tree = build(List.of(
            user("root", null, T0, "root"),
            user("late", "root", T0.plusSeconds(20), "late"),
            user("early", "root", T0.plusSeconds(5), "early"),
            user("mid", "root", T0.plusSeconds(10), "mid")));
        var root = onlyRoot(tree);
        assertThat(root.children()).extracting(n -> n.entry().id())
            .containsExactly("early", "mid", "late");
    }

    @Test
    void labelsAndTimestampsResolveFromLookup() {
        var labelTs = T0.plusSeconds(99);
        Map<String, String> labels = Map.of("b", "marked");
        Map<String, Instant> timestamps = Map.of("b", labelTs);
        var tree = SessionTreeBuilder.build(
            List.of(user("a", null, T0, "a"), user("b", "a", T0.plusSeconds(1), "b")),
            labels::get, timestamps::get);
        var root = onlyRoot(tree);
        assertThat(root.label()).isNull();
        assertThat(root.labelTimestamp()).isNull();
        var child = root.children().get(0);
        assertThat(child.label()).isEqualTo("marked");
        assertThat(child.labelTimestamp()).isEqualTo(labelTs);
    }

    @Test
    void childrenListsAreFrozen() {
        var tree = build(List.of(
            user("root", null, T0, "root"),
            user("child", "root", T0.plusSeconds(1), "child")));
        assertThatThrownBy(() -> onlyRoot(tree).children()
            .add(new SessionTreeNode(user("x", "root", T0, "x"), List.of(), null, null)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void multiRootKeepsInsertionOrder() {
        // pi 根不重排（只有 children 排序）；多根保持 entries 序。
        var tree = build(List.of(
            user("r1", null, T0.plusSeconds(10), "r1"),
            user("r2", null, T0, "r2")));
        assertThat(tree).extracting(n -> n.entry().id()).containsExactly("r1", "r2");
    }

    @Test
    void emptyEntriesYieldEmptyTree() {
        assertThat(build(List.of())).isEmpty();
    }

    @Test
    void deepTreeBuildsIteratively() {
        // pi :111-112 迭代栈防爆栈：深链（>1000）必须能建。
        List<Entry> entries = new java.util.ArrayList<>();
        String parent = null;
        for (int i = 0; i < 5000; i++) {
            String id = "e" + i;
            entries.add(user(id, parent, T0.plusSeconds(i), "x"));
            parent = id;
        }
        var tree = build(entries);
        var node = onlyRoot(tree);
        int depth = 0;
        while (!node.children().isEmpty()) {
            node = node.children().get(0);
            depth++;
        }
        assertThat(depth).isEqualTo(4999);
    }

    @Test
    void nullLookupResultsMeanNoLabel() {
        Function<String, String> noLabels = id -> null;
        var tree = SessionTreeBuilder.build(
            List.of(user("a", null, T0, "a")), noLabels, id -> null);
        assertThat(onlyRoot(tree).label()).isNull();
    }
}

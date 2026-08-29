package com.pijava.agent.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 压缩感知的 LLM 上下文构建（对齐 pi {@code session-manager.ts} 的
 * {@code buildSessionPath} / {@code buildContextEntries} /
 * {@code sessionEntryToContextMessages}）。
 *
 * <p>沿叶子路径（{@code parentId} 链，叶默认取末条目）取最新 compaction：
 * 无 → 全路径；有 → 路径裁剪为 {@code [compaction, firstKeptId 起至 compaction 前,
 * compaction 后全部]}。当截断后的 transcript（live 压缩把被摘要条目移出 lane）
 * 使父链断开时，回退为按列表顺序构建路径（存储条目即提交序，等价 pi 的
 * leaf-path 语义）。压缩/分支摘要条目转为带 pi 确切前缀/后缀的 user 消息，
 * 其余非消息条目不产消息。</p>
 *
 * <p>live 构建（{@code buildMessagesForLane}）与 resume seed
 * （{@code SessionPersistence.attach}）共用本类——单一事实源，对齐 pi 的
 * {@code buildSessionContext()}。</p>
 */
public final class ContextEntries {

    private static final String COMPACTION_SUMMARY_PREFIX =
        "The conversation history before this point was compacted into the following summary:\n\n<summary>\n";
    private static final String COMPACTION_SUMMARY_SUFFIX = "\n</summary>";
    private static final String BRANCH_SUMMARY_PREFIX =
        "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n";
    private static final String BRANCH_SUMMARY_SUFFIX = "</summary>";

    private ContextEntries() {}

    /**
     * Leaf-to-root path (oldest first) from {@code entries}. Dangling parent
     * references and cycles terminate the walk gracefully (pi
     * {@code buildSessionPath}). When the walk cannot reach the oldest
     * compaction entry on the list (truncated-transcript case), the list order
     * itself is the path.
     */
    public static List<Entry> pathToLeaf(List<Entry> entries, String leafId) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (var e : entries) {
            byId.putIfAbsent(e.id(), e);
        }
        Entry leaf = leafId != null ? byId.get(leafId) : null;
        if (leaf == null) {
            leaf = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        }
        if (leaf == null) {
            return List.of();
        }
        List<Entry> reversed = new ArrayList<>();
        Entry current = leaf;
        while (current != null) {
            reversed.add(current);
            String parentId = current.parentId();
            Entry parent = parentId != null ? byId.get(parentId) : null;
            if (parent == null || reversed.size() > byId.size()) {
                break;
            }
            current = parent;
        }
        // Truncated-transcript fallback: a compaction entry earlier on the list
        // is not reachable via parent links (live compaction drops summarized
        // entries, breaking the chain at the compaction boundary). List order
        // is commit order, so use it as the path.
        boolean listHasUnreachableCompaction = false;
        for (Entry e : entries) {
            if (e instanceof Entry.Compaction && !reversed.contains(e)) {
                listHasUnreachableCompaction = true;
                break;
            }
        }
        if (listHasUnreachableCompaction) {
            return List.copyOf(entries);
        }
        List<Entry> path = new ArrayList<>(reversed);
        java.util.Collections.reverse(path);
        return path;
    }

    /**
     * Compaction-aware context entries from a leaf path (pi
     * {@code buildContextEntries}): the latest compaction on the path wins;
     * older summarized entries are omitted.
     */
    public static List<Entry> contextEntries(List<Entry> leafPath) {
        Entry.Compaction compaction = null;
        for (var e : leafPath) {
            if (e instanceof Entry.Compaction c) {
                compaction = c;
            }
        }
        if (compaction == null) {
            return leafPath;
        }
        String firstKeptId = compaction.firstKeptEntryId();
        int compactionIdx = -1;
        for (int i = 0; i < leafPath.size(); i++) {
            if (leafPath.get(i).id().equals(compaction.id())) {
                compactionIdx = i;
                break;
            }
        }
        if (compactionIdx < 0) {
            return leafPath;
        }
        List<Entry> context = new ArrayList<>();
        context.add(compaction);
        boolean foundFirstKept = false;
        for (int i = 0; i < compactionIdx; i++) {
            Entry e = leafPath.get(i);
            if (e.id().equals(firstKeptId)) {
                foundFirstKept = true;
            }
            if (foundFirstKept) {
                context.add(e);
            }
        }
        context.addAll(leafPath.subList(compactionIdx + 1, leafPath.size()));
        return context;
    }

    /**
     * Convert a leaf path to LLM messages: message entries pass through,
     * compaction/branch summaries become user messages with pi's exact
     * prefix/suffix, other entry types produce nothing.
     */
    public static List<Message> toMessages(List<Entry> leafPath) {
        List<Message> messages = new ArrayList<>();
        for (var e : contextEntries(leafPath)) {
            Message projected = project(e);
            if (projected != null) {
                messages.add(projected);
            }
        }
        return messages;
    }

    /** Per-entry projection (pi {@code sessionEntryToContextMessages}); {@code null} = no message. */
    private static Message project(Entry e) {
        if (e instanceof Entry.Message m) {
            return m.message();
        }
        if (e instanceof Entry.Compaction c) {
            return userMessage(compactionText(c.summary()));
        }
        if (e instanceof Entry.BranchSummary bs && bs.summary() != null) {
            return userMessage(branchSummaryText(bs.summary()));
        }
        return null;
    }

    /** pi {@code COMPACTION_SUMMARY_PREFIX + summary + COMPACTION_SUMMARY_SUFFIX}. */
    public static String compactionText(String summary) {
        return COMPACTION_SUMMARY_PREFIX + summary + COMPACTION_SUMMARY_SUFFIX;
    }

    /** pi {@code BRANCH_SUMMARY_PREFIX + summary + BRANCH_SUMMARY_SUFFIX}. */
    public static String branchSummaryText(String summary) {
        return BRANCH_SUMMARY_PREFIX + summary + BRANCH_SUMMARY_SUFFIX;
    }

    private static Message userMessage(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}

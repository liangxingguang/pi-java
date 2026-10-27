package com.pijava.agent.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
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
 * custom_message 条目按 pi 语义转为 user 消息（display/details 不进上下文），
 * bashExecution 条目按 pi {@code convertToLlm} 转 user 文本
 * （{@code excludeFromContext} 跳过），其余非消息条目不产消息。</p>
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
     * prefix/suffix, custom_message entries become user messages carrying
     * their content, other entry types produce nothing.
     *
     * <p>A1（{@code docs/08} §A1）：系统消息（{@link Message.SystemMessage}）走
     * 「message entries pass through」这一支 —— 它仍然是 {@code Entry.Message} 的载荷，
     * A1 <b>不</b>新增 {@code Entry.SystemMessage} 子类型。工具增删也**不是** entry：
     * 包 A3 的裁决 R2 删掉了 {@code Entry.ActiveToolsChange}（pi 主线从不发射它，
     * {@code 原 docs/51 §9}）—— 这条线走的是系统消息的 {@code toolsAdded}/{@code toolsRemoved}，
     * 因此它天然落在上面那一支里，本方法无需为它单开分支。</p>
     */
    /**
     * 一个源条目投影出的模型可见消息（pi {@code ProjectedSessionEntry}，
     * {@code session-manager.ts:209-213}）。
     *
     * @param source   原始 append-only 条目
     * @param messages 投影后的模型可见消息；state-only 条目与被 omission 的条目为空
     */
    public record ProjectedEntry(Entry source, List<Message> messages) {

        /** Defensively copies {@code messages}. */
        public ProjectedEntry {
            messages = List.copyOf(messages);
        }
    }

    /** pi {@code buildSessionProjection(entries).entries}（{@code session-manager.ts:543-575}）。 */
    public static List<ProjectedEntry> projectEntries(List<Entry> entries) {
        return projectEntries(entries, null);
    }

    /**
     * 沿叶子路径构建「源条目 × 投影消息」：先压缩裁剪（{@link #contextEntries}），
     * 再从裁剪后的条目收集 edit（同目标后者胜，pi {@code session-manager.ts:551-554}），
     * 逐条投影并应用 edit。edit 自身不产消息；孤儿 edit（目标不在投影里）不被取用。
     */
    public static List<ProjectedEntry> projectEntries(List<Entry> entries, String leafId) {
        List<Entry> context = contextEntries(pathToLeaf(entries, leafId));
        Map<String, Entry.ContextEdit> edits = new LinkedHashMap<>();
        for (var e : context) {
            if (e instanceof Entry.ContextEdit edit) {
                edits.put(edit.targetId(), edit);
            }
        }
        List<ProjectedEntry> projected = new ArrayList<>();
        for (int index = 0; index < context.size(); index++) {
            Entry e = context.get(index);
            List<Message> messages;
            if (e instanceof Entry.Compaction && index > 0) {
                // contextEntries 可能在最新 compaction 的保留段内留着旧 compaction
                // 标记；pi 规定只有（下标 0 的）最新 compaction 贡献摘要消息。
                messages = List.of();
            } else {
                Message projectedMessage = project(e);
                messages = projectedMessage == null ? List.of() : List.of(projectedMessage);
            }
            Entry.ContextEdit edit = edits.get(e.id());
            if (edit != null) {
                Message base = messages.isEmpty() ? null : messages.getFirst();
                Message effective = base == null ? null : applyEdit(base, edit);
                messages = effective == null ? List.of() : List.of(effective);
            }
            projected.add(new ProjectedEntry(e, messages));
        }
        return projected;
    }

    /** 投影的扁平视图（pi {@code buildSessionProjection().messages}）。 */
    public static List<Message> toMessages(List<Entry> leafPath) {
        return projectEntries(leafPath).stream()
            .flatMap(entry -> entry.messages().stream())
            .toList();
    }

    /**
     * pi {@code projectContextEntry}（session-manager.ts:519-542）：replacement
     * 为 null ⇒ 剔除目标；非 null ⇒ 只换 content，消息元数据（usage/timestamp/api…）
     * 保留。edit 白名单仅 user/assistant/toolResult 消息与 custom_message
     * （appendContextEdit 的 editable 判定）；custom_message 在投影时已是
     * {@link Message.UserMessage}，走第一支。裸串 content 经
     * {@link CustomMessageContent#toBlocks()} 归一成 text 块（导入形兜底，:533-536）。
     */
    private static Message applyEdit(Message message, Entry.ContextEdit edit) {
        if (edit.replacement() == null) {
            return null;
        }
        List<ContentBlock> blocks = edit.replacement().content().toBlocks();
        if (message instanceof Message.UserMessage user) {
            return new Message.UserMessage(blocks, user.timestamp());
        }
        if (message instanceof Message.AssistantMessage assistant) {
            return new Message.AssistantMessage(blocks, assistant.stopReason(), assistant.deferred(),
                assistant.api(), assistant.provider(), assistant.model(),
                assistant.responseModel(), assistant.providerThinkingLevel(),
                assistant.thinkingLevel(), assistant.diagnostics(),
                assistant.usage(),
                assistant.timestamp(), assistant.errorMessage(), assistant.rawStopReason(),
                assistant.durationMs());
        }
        if (message instanceof Message.ToolResultMessage tool) {
            return new Message.ToolResultMessage(tool.toolUseId(), tool.toolName(), blocks,
                tool.details(), tool.usage(), tool.addedToolNames(), tool.isError(),
                tool.timestamp(), tool.durationMs(), tool.nestedCalls());
        }
        return message;
    }

    /**
     * Per-entry projection (pi {@code sessionEntryToContextMessages}); {@code null} = no message.
     *
     * <p><b>#58（本锚 ea448f454 重核）</b>：消息条目<b>恒透传</b> —— 现行 pi 主流
     * <b>不过滤</b> stopReason：循环层只终止（{@code agent-loop.ts:245-256}）、写回
     * context（{@code :441-450}），转换链无过滤（{@code :389-397}、{@code agent.ts:38-46}
     * 只按 role），session-manager 投影侧零 stopReason 处理（{@code :440} 起
     * {@code return [message]}）。本类此前的 {@code NON_PROJECTED_STOP_REASONS}
     * （error/aborted/deferred 剔除）移植自已删除的 harness {@code isContextMessage}
     * —— 参照物被删 ⇒ 移植作废（R1），已随 #58 移除。</p>
     */
    private static Message project(Entry e) {
        if (e instanceof Entry.Message m) {
            return m.message();
        }
        if (e instanceof Entry.Compaction c) {
            return userMessage(compactionText(c.summary()), c.timestamp());
        }
        if (e instanceof Entry.BranchSummary bs && bs.summary() != null) {
            return userMessage(branchSummaryText(bs.summary()), bs.timestamp());
        }
        if (e instanceof Entry.CustomMessage cm) {
            // pi messages.ts convertToLlm "custom" case: string -> single text
            // block, block list passes through; display/details never reach the LLM.
            return new Message.UserMessage(cm.content().toBlocks(), cm.timestamp());
        }
        if (e instanceof Entry.BashExecution be) {
            // pi convertToLlm "bashExecution" 支：excludeFromContext（!! 前缀）⇒ 不产消息；
            // 否则 bashExecutionToText 的 user 文本（messages.ts:82-99,147-160，docs/10 §3.3 ③）。
            if (Boolean.TRUE.equals(be.excludeFromContext())) {
                return null;
            }
            return userMessage(bashExecutionText(be), be.timestamp());
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

    /**
     * pi {@code bashExecutionToText}（{@code messages.ts:82-99}）逐字：
     * {@code Ran \`cmd\`\n} ＋输出三态（非空 ⇒ 围栏块；空 ⇒ {@code (no output)}），
     * cancelled 优先于非零 exitCode，截断尾注仅在
     * {@code truncated && fullOutputPath != null} 时追加。
     */
    public static String bashExecutionText(Entry.BashExecution be) {
        StringBuilder text = new StringBuilder("Ran `").append(be.command()).append("`\n");
        if (be.output() != null && !be.output().isEmpty()) {
            text.append("```\n").append(be.output()).append("\n```");
        } else {
            text.append("(no output)");
        }
        if (be.cancelled()) {
            text.append("\n\n(command cancelled)");
        } else if (be.exitCode() != null && be.exitCode().intValue() != 0) {
            text.append("\n\nCommand exited with code ").append(be.exitCode().intValue());
        }
        if (be.truncated() && be.fullOutputPath() != null) {
            text.append("\n\n[Output truncated. Full output: ").append(be.fullOutputPath()).append("]");
        }
        return text.toString();
    }

    /**
     * 原 docs/71 G1：合成的 user 消息取**entry 自己的**时间戳 —— pi
     * {@code messages.ts:141-160} 的 {@code timestamp: m.timestamp}（不是 {@code Date.now()}：
     * 这些是从**已落盘**的 entry 投影出来的，它们在写入时就有时刻）。
     */
    private static Message userMessage(String text, java.time.Instant timestamp) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)), timestamp);
    }
}

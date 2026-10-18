package com.pijava.coding.agent.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.pijava.agent.compaction.ConversationSerializer;
import com.pijava.agent.compaction.FileOperationLists;
import com.pijava.agent.compaction.LlmSummaryGenerator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.Session;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;

/**
 * pi {@code branch-summarization.ts}（382 行）对位（B1，map #24，docs/24 §7）：
 * 收集被放弃的分支条目 → 预算装填 → 摘要调用 → 文件清单。
 *
 * <p>LLM 车道复用 {@link LlmSummaryGenerator}（同款 StreamFn/retry 环注入，
 * reason 传 {@code "branchSummary"}）；文件清单与压缩共用
 * {@link FileOperationLists}（pi 两侧共用 {@code compaction/utils.ts}）。</p>
 */
public final class BranchSummarization {

    /** pi {@code BRANCH_SUMMARY_PREAMBLE}（branch-summarization.ts:253-256）逐字。 */
    static final String BRANCH_SUMMARY_PREAMBLE = """
        The user explored a different conversation branch before returning here.
        Summary of that exploration:

        """;

    /** pi {@code BRANCH_SUMMARY_PROMPT}（branch-summarization.ts:258-285）逐字。 */
    static final String BRANCH_SUMMARY_PROMPT = """
        Create a structured summary of this conversation branch for context when returning later.

        Use this EXACT format:

        ## Goal
        [What was the user trying to accomplish in this branch?]

        ## Constraints & Preferences
        - [Any constraints, preferences, or requirements mentioned]
        - [Or "(none)" if none were mentioned]

        ## Progress
        ### Done
        - [x] [Completed tasks/changes]

        ### In Progress
        - [ ] [Work that was started but not finished]

        ### Blocked
        - [Issues preventing progress, if any]

        ## Key Decisions
        - **[Decision]**: [Brief rationale]

        ## Next Steps
        1. [What should happen next to continue this work]

        Keep each section concise. Preserve exact file paths, function names, and error messages.""";

    /** pi {@code reserveTokens = 16384}（:305）。 */
    public static final int DEFAULT_RESERVE_TOKENS = 16_384;

    private final LlmSummaryGenerator generator;

    /** 以 LLM 摘要生成器（StreamFn/retry 环同源）构造。 */
    public BranchSummarization(LlmSummaryGenerator generator) {
        this.generator = generator;
    }

    /** pi {@code BranchSummaryResult} 三态（摘要 / 中止 / 失败）。 */
    public sealed interface Outcome {
        /** 成功：文本（PREAMBLE+正文+文件清单尾块）、usage、文件两列表。 */
        record Summary(String text, Usage usage, List<String> readFiles,
                       List<String> modifiedFiles) implements Outcome {}

        /** pi {@code { aborted: true }}（response.stopReason === "aborted"）。 */
        record Aborted() implements Outcome {}

        /** pi {@code { error }}（failure 文案 / toolCall 守卫）。 */
        record Failed(String error) implements Outcome {}
    }

    /** 生成选项。{@code contextWindow}/{@code maxOutputTokens} 由调用方从目录解析
     *  （pi {@code model.contextWindow \|\| 128000}；{@code maxTokens > 0} 守卫的假
     *  分支 ⇒ 0）。 */
    public record Options(ModelId<?> model, int contextWindow, int maxOutputTokens,
                          int reserveTokens, String customInstructions,
                          boolean replaceInstructions) {}

    /** pi {@code CollectEntriesResult}。 */
    public record Collected(List<Entry> entries, String commonAncestorId) {}

    /**
     * pi {@code collectEntriesForBranchSummary}（:108-146）：从 oldLeafId 回溯到与
     * targetId 的**最深公共祖先**，收途中 entries（**不停在压缩边界** —— 压缩条目的
     * 摘要就是上下文），反转为时间序。{@code oldLeafId == null} ⇒ 无摘要对象。
     */
    public static Collected collectEntries(String oldLeafId, String targetId,
                                           Session<?> session) {
        if (oldLeafId == null) {
            return new Collected(List.of(), null);
        }
        var all = session.findEntries(
            new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null));
        var oldPathIds = new HashSet<String>();
        for (var e : ContextEntries.pathToLeaf(all, oldLeafId)) {
            oldPathIds.add(e.id());
        }
        var targetPath = ContextEntries.pathToLeaf(all, targetId);
        String commonAncestorId = null;
        for (int i = targetPath.size() - 1; i >= 0; i--) {
            if (oldPathIds.contains(targetPath.get(i).id())) {
                commonAncestorId = targetPath.get(i).id();
                break;
            }
        }
        var byId = new java.util.HashMap<String, Entry>();
        for (var e : all) {
            byId.put(e.id(), e);
        }
        var entries = new ArrayList<Entry>();
        String current = oldLeafId;
        while (current != null && !current.equals(commonAncestorId)) {
            var entry = byId.get(current);
            if (entry == null) {
                break;
            }
            entries.add(entry);
            current = entry.parentId();
        }
        Collections.reverse(entries);
        return new Collected(List.copyOf(entries), commonAncestorId);
    }

    /**
     * pi {@code generateBranchSummary}（:293-381）。
     *
     * @param branchEntries {@link #collectEntries} 的产物（时间序）
     */
    public Outcome generate(List<Entry> branchEntries, Options options) {
        // pi :304-306：tokenBudget = contextWindow - reserveTokens。
        int tokenBudget = Math.max(0, options.contextWindow() - options.reserveTokens());
        var prepared = prepareBranchEntries(branchEntries, tokenBudget);
        if (prepared.messages().isEmpty()) {
            return new Outcome.Summary("No content to summarize", null, List.of(), List.of());
        }
        // pi :312-315：序列化防模型把摘要对象当成「要继续的对话」。
        String conversationText = ConversationSerializer.serialize(prepared.messages());
        // pi :317-325 三分支逐字序。
        String instructions;
        if (options.replaceInstructions() && options.customInstructions() != null) {
            instructions = options.customInstructions();
        } else if (options.customInstructions() != null) {
            instructions = BRANCH_SUMMARY_PROMPT + "\n\nAdditional focus: "
                + options.customInstructions();
        } else {
            instructions = BRANCH_SUMMARY_PROMPT;
        }
        String promptText =
            "<conversation>\n" + conversationText + "\n</conversation>\n\n" + instructions;
        // pi :338：min(4096, model.maxTokens > 0 ? model.maxTokens : ∞)。
        int maxTokens = options.maxOutputTokens() > 0
            ? Math.min(4096, options.maxOutputTokens()) : 4096;
        var response = generator.callBranch(promptText, maxTokens, "branchSummary");
        if (response.stopReason() == StopReason.ABORTED) {
            return new Outcome.Aborted();
        }
        var failure = LlmSummaryGenerator.summarizationFailure(response, "Branch summarization");
        if (failure != null) {
            return new Outcome.Failed(failure);
        }
        for (var block : response.content()) {
            if (block instanceof ContentBlock.ToolUseContent) {
                return new Outcome.Failed("Branch summarization attempted to call a tool");
            }
        }
        // pi :370-376：PREAMBLE + 正文 + 文件清单尾块。
        String summary = BRANCH_SUMMARY_PREAMBLE
            + LlmSummaryGenerator.contentTextOf(response.content());
        var lists = prepared.fileOps().compute();
        summary += lists.formatted();
        return new Outcome.Summary(summary.isEmpty() ? "No summary generated" : summary,
            response.usage(), lists.readFiles(), lists.modifiedFiles());
    }

    // ═══════════════════════════════════════════════════════════
    // prepareBranchEntries（pi :195-247）两遍扫
    // ═══════════════════════════════════════════════════════════

    /** pi {@code BranchPreparation}。 */
    record Preparation(List<Message> messages, FileOperationLists.Accumulator fileOps) {}

    /**
     * pi {@code prepareBranchEntries}（:195-247）：**第一遍**从所有非 fromHook 的
     * branch_summary 的 details 累计文件操作（跨分支摘要累计守卫，
     * branch-summarization.ts:204 与 compaction.ts:70 同形）；**第二遍**从新到旧
     * 装消息直到预算，摘要类 entry 在 0.9× 预算内可破格装入。
     */
    static Preparation prepareBranchEntries(List<Entry> entries, int tokenBudget) {
        var messages = new ArrayList<Message>();
        var fileOps = new FileOperationLists.Accumulator();
        int totalTokens = 0;
        for (var entry : entries) {
            if (entry instanceof Entry.BranchSummary bs
                    && !Boolean.TRUE.equals(bs.fromHook()) && bs.details() != null) {
                fileOps.addFromDetails(bs.details());
            }
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            var message = messageFromEntry(entry);
            if (message == null) {
                continue;
            }
            fileOps.addFromMessage(message);
            int tokens = com.pijava.agent.context.ContextUsageEstimator
                .estimateMessagesTokens(List.of(message));
            if (tokenBudget > 0 && totalTokens + tokens > tokenBudget) {
                // pi :234-241：摘要类 entry 在 0.9× 预算内破格装入（重要上下文）。
                if ((entry instanceof Entry.Compaction || entry instanceof Entry.BranchSummary)
                        && totalTokens < tokenBudget * 0.9) {
                    messages.add(0, message);
                    totalTokens += tokens;
                }
                break; // 预算到顶即停
            }
            messages.add(0, message);
            totalTokens += tokens;
        }
        return new Preparation(List.copyOf(messages), fileOps);
    }

    /**
     * pi {@code getMessageFromEntry}（:153-181）：message（**跳过 toolResult** —
     * 上下文在 assistant 的 toolCall 里）、custom_message、branch_summary、
     * compaction 四支；其余 entry 不产消息。
     */
    private static Message messageFromEntry(Entry entry) {
        if (entry instanceof Entry.Message m) {
            // pi 判 entry.message.role === "toolResult"；Java 侧 wire role 写作
            // "tool"（Message.ToolResultMessage 类注释）⇒ 按类型判，不按字符串。
            if (m.message() instanceof Message.ToolResultMessage) {
                return null;
            }
            return m.message();
        }
        if (entry instanceof Entry.CustomMessage cm) {
            return new Message.UserMessage(cm.content().toBlocks(), cm.timestamp());
        }
        if (entry instanceof Entry.BranchSummary bs) {
            return userMessage(ContextEntries.branchSummaryText(bs.summary()), bs.timestamp());
        }
        if (entry instanceof Entry.Compaction c) {
            return userMessage(ContextEntries.compactionText(c.summary()), c.timestamp());
        }
        return null;
    }

    /** 合成 user 消息（pi messages.ts:141-160：用 entry 自己的时间戳）。 */
    private static Message userMessage(String text, java.time.Instant timestamp) {
        return new Message.UserMessage(
            List.of(new ContentBlock.TextContent(text)), timestamp);
    }

    /** details 两键（pi {@code BranchSummaryDetails}；键序固定供逐字节比对）。 */
    public static Map<String, Object> detailsOf(List<String> readFiles, List<String> modifiedFiles) {
        return new FileOperationLists.Lists(readFiles, modifiedFiles).details();
    }
}

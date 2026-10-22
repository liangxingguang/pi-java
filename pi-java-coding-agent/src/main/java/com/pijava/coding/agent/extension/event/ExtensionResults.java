package com.pijava.coding.agent.extension.event;

import java.util.List;
import java.util.Map;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.SuppliedCompaction;
import com.pijava.agent.session.ContextEntries.ProjectedEntry;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 扩展事件的结果类型与支撑类型（pi types.ts:1415-1510 的 {@code Event Results} 族
 * ＋ :942-1044 的 boundary 族）—— 顶层 record 集合，与 {@link ExtensionEvent} 同包；
 * 拆出只为单文件 ≤500 行（批 5 事件面翻倍）。{@code null ≙ pi 的 undefined}。
 */
public final class ExtensionResults {

    private ExtensionResults() {}

    // ═══════════════════════════════════════════════════════════
    // 批 4 既有（自 ExtensionEvent 迁入）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code ContextEventResult}（types.ts:1415）。 */
    public record ContextEventResult(List<Message> messages) {}

    /**
     * pi {@code ToolCallEventResult}（types.ts:1426）——{@code block=true} 短路后续处理器，
     * 该次调用转错误结果（文本 {@code reason}，空则 pi 兜底文案）；
     * {@code terminate} 仅在 block 时生效（pi agent-loop.ts:640-647）。
     */
    public record ToolCallEventResult(Boolean block, String reason, Boolean terminate) {}

    /**
     * pi {@code ToolResultEventResult}（types.ts:1455）——逐字段合并：null=不发言；
     * content 被替换而 structuredContent 未同换 ⇒ structuredContent 被丢弃
     * （pi runner.ts:1194-1197）。
     */
    public record ToolResultEventResult(List<ContentBlock> content, Object details,
                                        Object structuredContent, Boolean isError,
                                        ToolResult.UsageInfo usage) {}

    // ═══════════════════════════════════════════════════════════
    // 批 5：结果臂族（pi types.ts:1463-1490）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code MessageEndEventResult}（types.ts:1463-1465）——替换定案消息；
     * <b>必须保持原 role</b>（java＝同 variant 类，否则 emitError 文本逐字＋跳过，
     * pi runner.ts:1157-1163）。
     */
    public record MessageEndEventResult(Message message) {}

    /** pi {@code SessionBeforeSwitchResult}（types.ts:1473-1475）。 */
    public record SessionBeforeSwitchResult(Boolean cancel) {}

    /**
     * pi {@code SessionBeforeForkResult}（types.ts:1476-1479）——
     * {@code skipConversationRestore} 收下不消费（java fork 无恢复跳过面，窄差 D16）。
     */
    public record SessionBeforeForkResult(Boolean cancel, Boolean skipConversationRestore) {}

    /**
     * pi {@code SessionBeforeCompactResult}（types.ts:1481-1484）——{@code compaction}
     * 直接复用引擎的 {@link SuppliedCompaction}（裁决⑥，类型同一免映射）。
     */
    public record SessionBeforeCompactResult(Boolean cancel, SuppliedCompaction compaction) {}

    /** pi {@code InputEventResult}（types.ts:1156-1160）判别联合。 */
    public sealed interface InputEventResult {
        /** pi {@code {action:"continue"}}。 */
        record Continue() implements InputEventResult {}
        /** pi {@code {action:"transform", text, images?}}。 */
        record Transform(String text, List<ContentBlock> images) implements InputEventResult {}
        /** pi {@code {action:"handled"}} —— 提示已被扩展处置，不再展开/排队/驱动。 */
        record Handled() implements InputEventResult {}
    }

    /**
     * pi {@code UserBashEventResult}（types.ts:1437-1450）的 {@code {result}} 臂
     * （全替换：扩展已执行，直接用此结果）；{@code {operations}} 臂不移植
     * （java 无 per-call BashOperations 缝，窄差 D10）。
     */
    public record UserBashEventResult(ShellResult result) {}

    /** pi {@code BeforeAgentStartEventResult}（types.ts:1467-1471）。 */
    public record BeforeAgentStartEventResult(CustomMessageDraft message, String systemPrompt) {}

    /**
     * pi {@code Pick<CustomMessage,"customType"|"content"|"display"|"details">}
     * （types.ts:1467）。{@code content} 为 null ≙ 缺失（java record 不收 null 内容，
     * 归一为 {@code CustomMessageContent.Text("")} —— pi 的 {@code content ?? []} 同位）。
     */
    public record CustomMessageDraft(String customType, CustomMessageContent content,
                                     boolean display, Map<String, Object> details) {}

    // ═══════════════════════════════════════════════════════════
    // 批 5：boundary 族（pi types.ts:942-1044）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code SessionBoundaryDraft}（types.ts:973-978）四型判别联合。 */
    public sealed interface SessionBoundaryDraft {
        /** pi {@code CustomEntryDraft}（:944-948）。 */
        record Custom(String customType, Map<String, Object> data)
                implements SessionBoundaryDraft {}
        /** pi {@code CustomMessageEntryDraft}（:949-955）。 */
        record CustomMessage(String customType, CustomMessageContent content, boolean display,
                             Map<String, Object> details) implements SessionBoundaryDraft {}
        /** pi {@code ContextEditEntryDraft}（:957-961）；replacement=null ⇒ 剔除目标。 */
        record ContextEdit(String targetId, Entry.ContextEdit.Replacement replacement)
                implements SessionBoundaryDraft {}
        /**
         * pi {@code CompactionEntryDraft}（:964-971）——{@code firstKeptEntryId} 为 null
         * 创建自留压缩（pi 注释逐字）。{@code usage} 是 LLM 用量（pi {@code Usage}）。
         */
        record Compaction(String summary, String firstKeptEntryId,
                          Map<String, Object> details, com.pijava.ai.Usage usage)
                implements SessionBoundaryDraft {}
    }

    /**
     * pi {@code BoundaryContextPreview}（types.ts:979-986）。java 的
     * {@code contextMessages}/{@code llmMessages} 同源（无 pi 的 AgentMessage→LLM
     * 二次转换缝，窄差 D15）。
     */
    public record BoundaryContextPreview(List<ProjectedEntry> contextEntries,
                                         List<Message> contextMessages,
                                         List<Message> llmMessages,
                                         List<Message> pendingMessages,
                                         boolean canContinue) {}

    /** pi {@code BoundaryResult}（types.ts:994-997）；键名 {@code continue} ⇒ 组件名规避关键字。 */
    public record BoundaryResult(List<SessionBoundaryDraft> entries, Boolean shouldContinue) {}

    // ═══════════════════════════════════════════════════════════
    // 批 5：session_before_compact 载荷视图（pi compaction.ts:772-788）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code CompactionPreparation}（compaction.ts:772-788）——java 对位是
     * {@code CompactionService.Plan} 的视图记录（firstKeptEntryId=firstKeptId、
     * messagesToSummarize=toSummarize、turnPrefixMessages=turnPrefix、
     * isSplitTurn=splitTurn）。{@code fileOps} 不译（prepare 时点缺席，窄差 D22）。
     */
    public record CompactionPreparation(String firstKeptEntryId,
                                        List<Message> messagesToSummarize,
                                        List<Message> turnPrefixMessages,
                                        boolean isSplitTurn,
                                        long tokensBefore,
                                        String previousSummary,
                                        CompactionSettings settings) {}

    // ═══════════════════════════════════════════════════════════
    // 批 8：tree 导航（pi types.ts:818-830、1488-1501）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code TreePreparation}（types.ts:818-830）——导航准备数据。
     * {@code entriesToSummarize} 是<b>活列表</b>（与默认生成器共用同一引用，
     * pi 处理器原地改它会影响摘要输入，agent-session.ts:3995/4056 同构）。
     */
    public record TreePreparation(String targetId, String oldLeafId, String commonAncestorId,
                                  List<Entry> entriesToSummarize, boolean userWantsSummary,
                                  String customInstructions, Boolean replaceInstructions,
                                  String label) {}

    /** pi {@code SessionBeforeTreeResult.summary}（types.ts:1490-1494）。 */
    public record ExtensionTreeSummary(String summary, Object details,
                                       com.pijava.ai.Usage usage) {}

    /**
     * pi {@code SessionBeforeTreeResult}（types.ts:1488-1501）——cancel 短路；
     * summary 仅在用户本来要摘要时生效；三个 override 不受 summarize 门限制。
     */
    public record SessionBeforeTreeResult(Boolean cancel, ExtensionTreeSummary summary,
                                          String customInstructions, Boolean replaceInstructions,
                                          String label) {}

    /**
     * pi {@code CacheWarmingDecisionEventResult}（cache-warmer.ts:117-120，批 13）——
     * 覆写本次刷新是否发出；{@code null} ≙ pi 的 {@code action?: undefined}（不发言）。
     * "stop" 结束本轮保温直到下一个真实请求。
     */
    public record CacheWarmingDecisionEventResult(
            com.pijava.coding.agent.core.CacheWarming.Action action) {}
}

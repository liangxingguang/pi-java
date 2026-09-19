package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * 会话级事件 —— 对齐 pi {@code AgentSessionEvent}（{@code _eventListeners} + {@code _emit}）。
 *
 * <p>变体字段不同 → sealed interface + record（CLAUDE.md 规范）。P6-5a 只发射
 * {@link MessageUpdate} / {@link AgentEnd} / {@link AgentSettled} /
 * {@link EntryAppended} 四种（覆盖 RPC 首批 8 个命令所需），其余事件随对应命令批次补齐。</p>
 */
public sealed interface AgentSessionEvent {

    /** 包装底层 StreamEvent；序列化时剥除 partial（RPC §4.2）。 */
    record MessageUpdate(StreamEvent streamEvent) implements AgentSessionEvent {}

    /** 用户消息即时回显（run 启动即发，不等 agent_end；对齐 pi message_end(user)）。 */
    record UserMessageReceived(Message message) implements AgentSessionEvent {}

    /** Agent 一次 run 结束（转录完成）。 */
    record AgentEnd(List<Message> messages, boolean willRetry) implements AgentSessionEvent {}

    /** Agent 完全静默（无后续 follow-up / 重试待处理）。 */
    record AgentSettled() implements AgentSessionEvent {}

    /** 一条新 entry 追加进会话转录。 */
    record EntryAppended(Entry entry) implements AgentSessionEvent {}

    /** 队列变化（steering / follow-up 待处理）。 */
    record QueueUpdate(List<String> steering, List<String> followUp)
        implements AgentSessionEvent {}

    /** 会话名变更。 */
    record SessionInfoChanged(String name) implements AgentSessionEvent {}

    /** 思考等级变更。 */
    record ThinkingLevelChanged(ThinkingLevel level) implements AgentSessionEvent {}

    record CompactionStart(CompactionReason reason) implements AgentSessionEvent {}

    record CompactionEnd(CompactionReason reason, CompactionResult result,
                         boolean aborted, boolean willRetry, String errorMessage)
        implements AgentSessionEvent {}

    record AutoRetryStart(int attempt, int maxAttempts, long delayMs, String errorMessage)
        implements AgentSessionEvent {}

    record AutoRetryEnd(boolean success, int attempt, String finalError)
        implements AgentSessionEvent {}

    /** pi {@code summarization_retry_scheduled}（环 B，agent-session.ts:2894-2900）。 */
    record SummarizationRetryScheduled(int attempt, int maxAttempts, long delayMs,
                                       String errorMessage) implements AgentSessionEvent {}

    /**
     * pi {@code summarization_retry_attempt_start}（:2902-2905，载荷 = source 对象）。
     * compaction 路带 reason；branchSummary 路 reason 为 null（线格式省略该键）。
     */
    record SummarizationRetryAttemptStart(String source, String reason)
        implements AgentSessionEvent {}

    /** pi {@code summarization_retry_finished}（:2907-2909，无载荷字段）。 */
    record SummarizationRetryFinished() implements AgentSessionEvent {}

    record BashExecutionUpdate(String id, String delta) implements AgentSessionEvent {}

    // ═══════════════════════════════════════════════════════════
    // 工具执行生命周期（包⑦，docs/34）
    // ═══════════════════════════════════════════════════════════
    //
    // pi 的这三条是 AgentEvent 联合的成员（agent/src/types.ts:443-446），会话层
    // 联合**复用** agent 联合（agent-session.ts:143-145 的
    // `Exclude<AgentEvent, {type:"agent_end"}> | {...}`）⇒ 它们就是 AgentSessionEvent。
    //
    // ⚠️ **每个字段都必填，pi 侧一个 `?` 都没有**（对照 package ④ 那套「按 `?` 省略
    // 可空键」的纪律 —— 这里**一个键都不许省**，别照抄邻行）。
    //
    // 时刻语义（pi 已核，docs/34 §2.1 P3/P4）：
    //   start 在**校验与 beforeToolCall 钩子之前**、工具即将执行时发；
    //   end   在**执行并定稿（含 afterToolCall）之后**发，带完整 result 与另立的 isError。

    /** pi {@code {type:"tool_execution_start"; toolCallId; toolName; args}}。 */
    record ToolExecutionStart(String toolCallId, String toolName,
                              Map<String, Object> args) implements AgentSessionEvent {}

    /** pi {@code {type:"tool_execution_update"; …; args; partialResult}}。 */
    record ToolExecutionUpdate(String toolCallId, String toolName,
                               Map<String, Object> args, Object partialResult)
        implements AgentSessionEvent {}

    /** pi {@code {type:"tool_execution_end"; …; result; isError}}。 */
    record ToolExecutionEnd(String toolCallId, String toolName,
                            Object result, boolean isError) implements AgentSessionEvent {}

    /** pi: "manual" | "threshold" | "overflow" */
    enum CompactionReason { MANUAL, THRESHOLD, OVERFLOW }
}

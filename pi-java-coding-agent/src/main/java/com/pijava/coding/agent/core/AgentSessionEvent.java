package com.pijava.coding.agent.core;

import java.util.List;

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

    record BashExecutionUpdate(String id, String delta) implements AgentSessionEvent {}

    /** pi: "manual" | "threshold" | "overflow" */
    enum CompactionReason { MANUAL, THRESHOLD, OVERFLOW }
}

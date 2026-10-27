package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

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

    // ═══════════════════════════════════════════════════════════
    // 包装的 AgentEvent（pi agent-session.ts:193-234 的 9 个
    // WithParentToolCallId<Exclude<AgentEvent, {type:"agent_end"}>> 变体
    // 中此前缺的 5 个；工具三支在下文「工具执行生命周期」节）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code { type: "agent_start" }}（types.ts:511）。 */
    record AgentStart() implements AgentSessionEvent {}

    /** pi {@code { type: "turn_start" }}（types.ts:514）。 */
    record TurnStart() implements AgentSessionEvent {}

    /** pi {@code { type: "turn_end"; message; toolResults }}（types.ts:517）——两字段必填。 */
    record TurnEnd(Message.AssistantMessage message, List<Message.ToolResultMessage> toolResults)
        implements AgentSessionEvent {}

    /** pi {@code { type: "message_start"; message }}（types.ts:520）——system/user/assistant/toolResult 全角色。 */
    record MessageStart(Message message) implements AgentSessionEvent {}

    /** pi {@code { type: "message_end"; message }}（types.ts:526）。 */
    record MessageEnd(Message message) implements AgentSessionEvent {}

    /** Agent 一次 run 结束（pi 的覆写版 {@code agent_end}：本 pass 的 newMessages + willRetry）。 */
    record AgentEnd(List<Message> messages, boolean willRetry) implements AgentSessionEvent {}

    /**
     * pi {@code { type: "agent_settled"; aborted: boolean }}（agent-session.ts:203）——
     * 每次驱动（≙ pi {@code _runAgentPrompt}）恰一条；aborted 取自会话层 abort 请求旗
     * （pi {@code _agentRunAbortRequested}，:1083）。
     */
    record AgentSettled(boolean aborted) implements AgentSessionEvent {}

    /** 一条新 entry 追加进会话转录。 */
    record EntryAppended(Entry entry) implements AgentSessionEvent {}

    /** 队列变化（steering / follow-up 待处理）。 */
    record QueueUpdate(List<String> steering, List<String> followUp)
        implements AgentSessionEvent {}

    /** 会话名变更。 */
    record SessionInfoChanged(String name) implements AgentSessionEvent {}

    /**
     * 思考等级变更（pi {@code agent-session.ts:1955} 的 {@code thinking_level_changed}）。
     *
     * <p>⚠️ 包H5 步8：组件类型由 {@code ThinkingLevel} 改为 {@link ModelThinkingLevel}
     * —— pi 的载荷可以是 {@code "off"}，而 {@code ThinkingLevel} <b>表达不了 off</b>
     * （它是 {@code ModelThinkingLevel} 才有的状态）。线格式由
     * {@code JsonEventMapper} 写 {@code label()}（小写字符串，与 pi 逐字一致）。</p>
     */
    record ThinkingLevelChanged(ModelThinkingLevel level) implements AgentSessionEvent {}

    record CompactionStart(CompactionReason reason) implements AgentSessionEvent {}

    /**
     * @param compactionEntry 落库的压缩条目（docs/15 —— pi {@code session_compact} 的
     *                        {@code compactionEntry} 载荷链）；成功路非 null，
     *                        中止/失败/R1 路 null
     */
    record CompactionEnd(CompactionReason reason, CompactionResult result,
                         boolean aborted, boolean willRetry, String errorMessage,
                         com.pijava.agent.entry.Entry.Compaction compactionEntry)
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

    /**
     * 扩展错误上报（docs/14 裁决⑥）—— pi {@code extension_error}
     * （rpc-mode.ts:349）：pi 的 {@code emitError} 不进事件流、走独立 listener，
     * RPC 写成线上事件；java 把它桥成本事件变体，RPC 经既有 {@code subscribe}
     * 通道上线（线形逐字 pi），TUI 暂不展示（窄差 D5，归 D′ 域）。
     */
    record ExtensionError(com.pijava.coding.agent.extension.ExtensionError error)
            implements AgentSessionEvent {}

    /**
     * 交互层状态文本（<b>方言，pi 无此事件</b>）。
     *
     * <p>B175（原 docs/26 §3.4）：pi 的 InteractiveMode 是 UI/会话混合对象，
     * 缓冲时直接 {@code showStatus("Queued message for after compaction")}；
     * Java 刻意分层（{@code InteractiveMode} 无 TUI 类型），状态文本必须经会话
     * 事件面到 {@code ChatScreen}。RPC 映射<b>忽略</b>本事件、不上线。</p>
     */
    record FlashStatus(String message) implements AgentSessionEvent {}

    // ═══════════════════════════════════════════════════════════
    // 工具执行生命周期（包⑦，原 docs/34）
    // ═══════════════════════════════════════════════════════════
    //
    // pi 的这三条是 AgentEvent 联合的成员（agent/src/types.ts:443-446），会话层
    // 联合**复用** agent 联合（agent-session.ts:143-145 的
    // `Exclude<AgentEvent, {type:"agent_end"}> | {...}`）⇒ 它们就是 AgentSessionEvent。
    //
    // start/update 的字段全部必填；**end 自 pi #10549 起多一个可选 {@code durationMs}**
    // （只围 tool.execute() 本体，未执行的调用无该键），与「按 `?` 省略可空键」同族。
    //
    // 时刻语义（pi 已核，原 docs/34 §2.1 P3/P4）：
    //   start 在**校验与 beforeToolCall 钩子之前**、工具即将执行时发；
    //   end   在**执行并定稿（含 afterToolCall）之后**发，带完整 result 与另立的 isError。

    /** pi {@code {type:"tool_execution_start"; toolCallId; toolName; args}}。 */
    record ToolExecutionStart(String toolCallId, String toolName,
                              Map<String, Object> args,
                              /**
                               * 批 27（B162；pi {@code WithParentToolCallId}，
                               * agent-session.ts:188-193）：嵌套调用的父调用 id；
                               * 顶层调用为 null（≙ pi 的键省略）。
                               */
                              String parentToolCallId) implements AgentSessionEvent {
        /** 批 27 之前的三参形状（顶层调用；存量构造点零改签）。 */
        public ToolExecutionStart(String toolCallId, String toolName,
                                  Map<String, Object> args) {
            this(toolCallId, toolName, args, null);
        }
    }

    /** pi {@code {type:"tool_execution_update"; …; args; partialResult}}。 */
    record ToolExecutionUpdate(String toolCallId, String toolName,
                               Map<String, Object> args, Object partialResult,
                               String parentToolCallId)
        implements AgentSessionEvent {
        /** 批 27 之前的四参形状（顶层调用；存量构造点零改签）。 */
        public ToolExecutionUpdate(String toolCallId, String toolName,
                                   Map<String, Object> args, Object partialResult) {
            this(toolCallId, toolName, args, partialResult, null);
        }
    }

    /**
     * pi {@code {type:"tool_execution_end"; …; result; isError; durationMs?}}。
     *
     * @param durationMs B234：execute() 本体单调钟毫秒；未执行（unknown/校验失败/
     *                   blocked/执行前 abort/截断）为 null ⇒ 线上键省略
     */
    record ToolExecutionEnd(String toolCallId, String toolName,
                            Object result, boolean isError, Long durationMs,
                            String parentToolCallId)
            implements AgentSessionEvent {
        /** B234 之前的四参形状（存量构造点零改签）。 */
        public ToolExecutionEnd(String toolCallId, String toolName,
                                Object result, boolean isError) {
            this(toolCallId, toolName, result, isError, null);
        }

        /** 批 27 之前的五参形状（顶层调用；存量构造点零改签）。 */
        public ToolExecutionEnd(String toolCallId, String toolName,
                                Object result, boolean isError, Long durationMs) {
            this(toolCallId, toolName, result, isError, durationMs, null);
        }
    }

    /** pi: "manual" | "threshold" | "overflow" */
    enum CompactionReason { MANUAL, THRESHOLD, OVERFLOW }
}

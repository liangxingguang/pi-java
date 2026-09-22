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

    /** 用户消息即时回显（run 启动即发，不等 agent_end；对齐 pi message_end(user)）。 */
    record UserMessageReceived(Message message) implements AgentSessionEvent {}

    /** Agent 一次 run 结束（转录完成）。 */
    record AgentEnd(List<Message> messages, boolean willRetry) implements AgentSessionEvent {}

    /**
     * Agent 一次 run 结束（转录完成）。
     *
     * <p>包⑨（docs/36，B42）：pi 的 {@code turn_end} 是
     * {@code { message: AgentMessage; toolResults: ToolResultMessage[] }}，
     * <b>两个字段都必填、一个 {@code ?} 都没有</b>（{@code agent/src/types.ts:438}），
     * 且<b>原样上 RPC/JSON 线</b>（{@code json-event.ts:48-51}）。</p>
     *
     * <p>⚠️ <b>颗粒度偏差（如实）</b>：pi 的 {@code turn_end} 是<b>每回合</b>一条，
     * 而这里是<b>每次驱动</b>一条。若照字面取「最后一回合的工具结果」，该字段在常见
     * 形状下<b>反而恒空</b>（最后那回合通常是纯文本收尾、没有工具调用）⇒ 本类按
     * <b>本次驱动</b>装。颗粒度差异本身另登记。</p>
     *
     * @param message     终局助手消息（pi 的错误/中止路发合成的失败消息）；无转写时可为 null
     * @param toolResults 本次驱动的工具结果（pi 无工具回合同样发空数组，非 null）
     */
    record AgentSettled(Message message, List<Message> toolResults) implements AgentSessionEvent {

        /** 防 null 的紧凑构造器（pi 恒发数组）。 */
        public AgentSettled {
            toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        }

        /**
         * 兼容构造器：错误/中止路拿不到转写时用。
         *
         * <p>pi 那条路也发 {@code {message(合成失败消息), toolResults: []}}
         * （{@code agent-loop.ts:216}、{@code agent.ts:511-527}）—— Java 侧该处没有
         * 合成消息可给，故 message 为 null，投影时按 pi 的可空键纪律省略。</p>
         */
        public AgentSettled() {
            this(null, List.of());
        }
    }

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

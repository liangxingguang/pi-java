package com.pijava.coding.agent.extension.event;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * pi {@code ExtensionEvent} 联合（types.ts:1377-1409）的 docs/14 子集 —— 只收已有
 * firing 源的 18 个事件，其余随 B248（provider 请求面）/B249（结果臂族）/B250
 * （会话生命周期）/B245（tree 两事件）逐批扩。
 *
 * <p>两族语义（pi runner.ts）：</p>
 * <ul>
 *   <li><b>引擎钩子族</b>（{@link ContextEvent}/{@link ContextWithSystemEvent}/
 *       {@link ToolCallEvent}/{@link ToolResultEvent}）—— 结果型：处理器可返回结果对象
 *       改写行为（合并语义见 {@code EngineHookBridge}，pi runner.ts:1183-1359 逐字）。</li>
 *   <li><b>宿主事件族</b>（其余 14 个）—— 观察型：处理器只读，返回值被忽略
 *       （pi agent-session.ts:1284-1362 的 {@code _emitExtensionEvent}）。</li>
 * </ul>
 *
 * <p>变体携带不同字段 ⇒ sealed interface + record（CLAUDE.md 规范）。
 * {@code null ≙ pi 的 undefined}。</p>
 */
public sealed interface ExtensionEvent {

    // ═══════════════════════════════════════════════════════════
    // 引擎钩子族（结果型）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code ContextEvent}（types.ts:871）。{@code messages} 是**活的可变列表**
     * （裁决④：pi 处理器可原地改 {@code event.messages}，{@code runner.ts:1313} 注释
     * 同构）；系统消息已被滤除，处理器看不到。
     */
    record ContextEvent(List<Message> messages) implements ExtensionEvent {}

    /**
     * pi {@code ContextWithSystemEvent}（types.ts:882）。{@code messages} 含系统消息
     * （全量，原地可变同 {@link ContextEvent}）。
     */
    record ContextWithSystemEvent(List<Message> messages) implements ExtensionEvent {}

    /**
     * pi {@code ToolCallEvent}（types.ts:1165-1228）。{@code input} 原地可变
     * （pi types.ts:1224-1227 注释：后面的处理器看得到前面的改写）。
     * java 不做 pi 的逐工具判别子类型（{@code BashToolCallEvent} 等 9 支），
     * {@code input} 恒 map 形（窄差 D2）；{@code parentToolCallId} 不带（G-10 零对应，D7）。
     */
    record ToolCallEvent(String toolCallId, String toolName,
                         Map<String, Object> input) implements ExtensionEvent {}

    /**
     * pi {@code ToolResultEvent}（types.ts:1239-1316）。{@code input} 是改写后参数
     * （pi 的 {@code prepared.args}）。
     */
    record ToolResultEvent(String toolCallId, String toolName, Map<String, Object> input,
                           List<ContentBlock> content, Object details, Object structuredContent,
                           boolean isError, ToolResult.UsageInfo usage)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // 宿主事件族（观察型）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code agent_start}（types.ts:929，无载荷）。 */
    record AgentStartEvent() implements ExtensionEvent {}

    /** pi {@code agent_end}（types.ts:934）。 */
    record AgentEndEvent(List<Message> messages) implements ExtensionEvent {}

    /** pi {@code agent_settled}（types.ts:956-960 族；aborted 为两锚点间新增字段）。 */
    record AgentSettledEvent(boolean aborted) implements ExtensionEvent {}

    /** pi {@code turn_start}（types.ts:1030）。 */
    record TurnStartEvent(int turnIndex, long timestamp) implements ExtensionEvent {}

    /** pi {@code message_start}（types.ts:1046）。 */
    record MessageStartEvent(Message message) implements ExtensionEvent {}

    /** pi {@code message_update}（types.ts:1052）：partial 快照＋底层流事件。 */
    record MessageUpdateEvent(Message message, StreamEvent assistantMessageEvent)
            implements ExtensionEvent {}

    /** pi {@code message_end}（types.ts:1062）——观察臂；替换臂（{@code MessageEndEventResult}）挂 B249。 */
    record MessageEndEvent(Message message) implements ExtensionEvent {}

    /** pi {@code tool_execution_start}（types.ts:1067）。 */
    record ToolExecutionStartEvent(String toolCallId, String toolName,
                                   Map<String, Object> args) implements ExtensionEvent {}

    /** pi {@code tool_execution_update}（types.ts:1076）。 */
    record ToolExecutionUpdateEvent(String toolCallId, String toolName,
                                    Map<String, Object> args, Object partialResult)
            implements ExtensionEvent {}

    /** pi {@code tool_execution_end}（types.ts:1087；{@code durationMs} 可空 ≙ 键省略）。 */
    record ToolExecutionEndEvent(String toolCallId, String toolName, Map<String, Object> args,
                                 Object result, boolean isError, Long durationMs)
            implements ExtensionEvent {}

    /**
     * pi {@code session_compact}（types.ts:787）——暂**无 {@code compactionEntry} 字段**：
     * java 宿主事件给不出条目本体（窄差 D4，挂 B249）。
     */
    record SessionCompactEvent(String reason, boolean willRetry, boolean fromExtension)
            implements ExtensionEvent {}

    /** pi {@code session_compact_failed}（types.ts:799）。 */
    record SessionCompactFailedEvent(String reason, String errorMessage, boolean aborted,
                                     boolean willRetry, boolean fromExtension)
            implements ExtensionEvent {}

    /** pi {@code session_info_changed}（types.ts:750；{@code name} 为 null ≙ undefined）。 */
    record SessionInfoChangedEvent(String name) implements ExtensionEvent {}

    /** pi {@code thinking_level_select}（types.ts:1114-1118）。 */
    record ThinkingLevelSelectEvent(ModelThinkingLevel level, ModelThinkingLevel previousLevel)
            implements ExtensionEvent {}

    // ═══════════════════════════════════════════════════════════
    // 结果类型（pi types.ts:1415-1464）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code ContextEventResult}（types.ts:1415）。 */
    record ContextEventResult(List<Message> messages) {}

    /**
     * pi {@code ToolCallEventResult}（types.ts:1426）——{@code block=true} 短路后续处理器，
     * 该次调用转错误结果（文本 {@code reason}，空则 pi 兜底文案）；
     * {@code terminate} 仅在 block 时生效（pi agent-loop.ts:640-647）。
     */
    record ToolCallEventResult(Boolean block, String reason, Boolean terminate) {}

    /**
     * pi {@code ToolResultEventResult}（types.ts:1455）——逐字段合并：null=不发言；
     * content 被替换而 structuredContent 未同换 ⇒ structuredContent 被丢弃
     * （pi runner.ts:1194-1197）。
     */
    record ToolResultEventResult(List<ContentBlock> content, Object details,
                                 Object structuredContent, Boolean isError,
                                 ToolResult.UsageInfo usage) {}
}

package com.pijava.coding.agent.extension.event;

import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentSettledEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ContextEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ContextEventResult;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ContextWithSystemEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageUpdateEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionCompactEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionCompactFailedEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionInfoChangedEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ThinkingLevelSelectEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolCallEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolCallEventResult;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionUpdateEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolResultEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolResultEventResult;
import com.pijava.coding.agent.extension.event.ExtensionEvent.TurnStartEvent;

/**
 * 事件规格令牌：线名 + 事件/结果类型参数（裁决① —— pi types.ts:1569-1636 的 41 个
 * {@code on()} 重载在 java 收敛为 {@code ExtensionContext.on(spec, handler)} 单方法，
 * spec 常量是类型令牌，扩事件不动接口）。
 *
 * <p>观察型事件的结果类型是 {@link Void}（处理器返回值被忽略）。常量随
 * {@link ExtensionEvent} ADT 逐批扩。</p>
 *
 * @param <E> 事件载荷类型
 * @param <R> 处理器结果类型（观察型为 {@link Void}）
 */
public final class ExtensionEventSpec<E extends ExtensionEvent, R> {

    // ── 引擎钩子族（结果型）──

    /** pi {@code "context"}。 */
    public static final ExtensionEventSpec<ContextEvent, ContextEventResult> CONTEXT =
        new ExtensionEventSpec<>("context");

    /** pi {@code "context_with_system"}。 */
    public static final ExtensionEventSpec<ContextWithSystemEvent, ContextEventResult>
        CONTEXT_WITH_SYSTEM = new ExtensionEventSpec<>("context_with_system");

    /** pi {@code "tool_call"}。 */
    public static final ExtensionEventSpec<ToolCallEvent, ToolCallEventResult> TOOL_CALL =
        new ExtensionEventSpec<>("tool_call");

    /** pi {@code "tool_result"}。 */
    public static final ExtensionEventSpec<ToolResultEvent, ToolResultEventResult> TOOL_RESULT =
        new ExtensionEventSpec<>("tool_result");

    // ── 宿主事件族（观察型）──

    /** pi {@code "agent_start"}。 */
    public static final ExtensionEventSpec<AgentStartEvent, Void> AGENT_START =
        new ExtensionEventSpec<>("agent_start");

    /** pi {@code "agent_end"}。 */
    public static final ExtensionEventSpec<AgentEndEvent, Void> AGENT_END =
        new ExtensionEventSpec<>("agent_end");

    /** pi {@code "agent_settled"}。 */
    public static final ExtensionEventSpec<AgentSettledEvent, Void> AGENT_SETTLED =
        new ExtensionEventSpec<>("agent_settled");

    /** pi {@code "turn_start"}。 */
    public static final ExtensionEventSpec<TurnStartEvent, Void> TURN_START =
        new ExtensionEventSpec<>("turn_start");

    /** pi {@code "message_start"}。 */
    public static final ExtensionEventSpec<MessageStartEvent, Void> MESSAGE_START =
        new ExtensionEventSpec<>("message_start");

    /** pi {@code "message_update"}。 */
    public static final ExtensionEventSpec<MessageUpdateEvent, Void> MESSAGE_UPDATE =
        new ExtensionEventSpec<>("message_update");

    /** pi {@code "message_end"}。 */
    public static final ExtensionEventSpec<MessageEndEvent, Void> MESSAGE_END =
        new ExtensionEventSpec<>("message_end");

    /** pi {@code "tool_execution_start"}。 */
    public static final ExtensionEventSpec<ToolExecutionStartEvent, Void> TOOL_EXECUTION_START =
        new ExtensionEventSpec<>("tool_execution_start");

    /** pi {@code "tool_execution_update"}。 */
    public static final ExtensionEventSpec<ToolExecutionUpdateEvent, Void> TOOL_EXECUTION_UPDATE =
        new ExtensionEventSpec<>("tool_execution_update");

    /** pi {@code "tool_execution_end"}。 */
    public static final ExtensionEventSpec<ToolExecutionEndEvent, Void> TOOL_EXECUTION_END =
        new ExtensionEventSpec<>("tool_execution_end");

    /** pi {@code "session_compact"}。 */
    public static final ExtensionEventSpec<SessionCompactEvent, Void> SESSION_COMPACT =
        new ExtensionEventSpec<>("session_compact");

    /** pi {@code "session_compact_failed"}。 */
    public static final ExtensionEventSpec<SessionCompactFailedEvent, Void>
        SESSION_COMPACT_FAILED = new ExtensionEventSpec<>("session_compact_failed");

    /** pi {@code "session_info_changed"}。 */
    public static final ExtensionEventSpec<SessionInfoChangedEvent, Void> SESSION_INFO_CHANGED =
        new ExtensionEventSpec<>("session_info_changed");

    /** pi {@code "thinking_level_select"}。 */
    public static final ExtensionEventSpec<ThinkingLevelSelectEvent, Void>
        THINKING_LEVEL_SELECT = new ExtensionEventSpec<>("thinking_level_select");

    private final String name;

    private ExtensionEventSpec(String name) {
        this.name = name;
    }

    /** pi 的事件线名（{@code ExtensionEvent["type"]}）。 */
    public String name() {
        return name;
    }
}

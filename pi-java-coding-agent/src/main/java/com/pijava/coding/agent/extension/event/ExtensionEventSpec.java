package com.pijava.coding.agent.extension.event;

import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentBeforeSettleEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentSettledEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AfterProviderResponseEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeAgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeProviderHeadersEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeProviderRequestEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ContextEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ContextWithSystemEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.InputEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageUpdateEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ProviderStreamEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionBeforeCompactEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionBeforeForkEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionBeforeSwitchEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionCompactEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionCompactFailedEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionInfoChangedEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionShutdownEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ThinkingLevelSelectEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolCallEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionUpdateEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolResultEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.TurnEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.TurnStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.UserBashEvent;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.BoundaryResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.ContextEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.InputEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.MessageEndEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeCompactResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeForkResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeSwitchResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolCallEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolResultEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

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

    /**
     * pi {@code "message_end"} —— docs/15 起 R 由 Void 改为
     * {@link MessageEndEventResult}（替换臂，裁决①）；纯观察处理器返回 null 即不发言。
     */
    public static final ExtensionEventSpec<MessageEndEvent, MessageEndEventResult> MESSAGE_END =
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

    // ── 生命周期（docs/15）──

    /** pi {@code "session_start"}（types.ts:1574）。 */
    public static final ExtensionEventSpec<SessionStartEvent, Void> SESSION_START =
        new ExtensionEventSpec<>("session_start");

    /** pi {@code "session_shutdown"}（types.ts:1590）。 */
    public static final ExtensionEventSpec<SessionShutdownEvent, Void> SESSION_SHUTDOWN =
        new ExtensionEventSpec<>("session_shutdown");

    /** pi {@code "session_before_switch"}（types.ts:1577，可取消）。 */
    public static final ExtensionEventSpec<SessionBeforeSwitchEvent, SessionBeforeSwitchResult>
        SESSION_BEFORE_SWITCH = new ExtensionEventSpec<>("session_before_switch");

    /** pi {@code "session_before_fork"}（types.ts:1581，可取消）。 */
    public static final ExtensionEventSpec<SessionBeforeForkEvent, SessionBeforeForkResult>
        SESSION_BEFORE_FORK = new ExtensionEventSpec<>("session_before_fork");

    // ── 结果臂族（docs/15）──

    /** pi {@code "session_before_compact"}（types.ts:1585，可取消/可接管）。 */
    public static final ExtensionEventSpec<SessionBeforeCompactEvent, SessionBeforeCompactResult>
        SESSION_BEFORE_COMPACT = new ExtensionEventSpec<>("session_before_compact");

    /** pi {@code "input"}（types.ts:1636）。 */
    public static final ExtensionEventSpec<InputEvent, InputEventResult> INPUT =
        new ExtensionEventSpec<>("input");

    /** pi {@code "user_bash"}（types.ts:1635）。 */
    public static final ExtensionEventSpec<UserBashEvent, UserBashEventResult> USER_BASH =
        new ExtensionEventSpec<>("user_bash");

    /** pi {@code "before_agent_start"}（types.ts:1611）。 */
    public static final ExtensionEventSpec<BeforeAgentStartEvent, BeforeAgentStartEventResult>
        BEFORE_AGENT_START = new ExtensionEventSpec<>("before_agent_start");

    /** pi {@code "turn_end"}（types.ts:1624，boundary 派发）。 */
    public static final ExtensionEventSpec<TurnEndEvent, BoundaryResult> TURN_END =
        new ExtensionEventSpec<>("turn_end");

    /** pi {@code "agent_before_settle"}（types.ts:1617，boundary 派发）。 */
    public static final ExtensionEventSpec<AgentBeforeSettleEvent, BoundaryResult>
        AGENT_BEFORE_SETTLE = new ExtensionEventSpec<>("agent_before_settle");

    // ── provider 请求面（docs/17）──

    /**
     * pi {@code "before_provider_request"}（types.ts:1604）——结果型：
     * {@code BeforeProviderRequestEventResult = unknown}（:1422）对位为 {@link Object}，
     * 处理器返回非 null ⇒ 替换当前载荷。
     */
    public static final ExtensionEventSpec<BeforeProviderRequestEvent, Object>
        BEFORE_PROVIDER_REQUEST = new ExtensionEventSpec<>("before_provider_request");

    /** pi {@code "before_provider_headers"}（types.ts:1607）——原位改写，返回值忽略。 */
    public static final ExtensionEventSpec<BeforeProviderHeadersEvent, Void>
        BEFORE_PROVIDER_HEADERS = new ExtensionEventSpec<>("before_provider_headers");

    /** pi {@code "after_provider_response"}（types.ts:1608）。 */
    public static final ExtensionEventSpec<AfterProviderResponseEvent, Void>
        AFTER_PROVIDER_RESPONSE = new ExtensionEventSpec<>("after_provider_response");

    /** pi {@code "provider_stream_event"}（types.ts:1609）。 */
    public static final ExtensionEventSpec<ProviderStreamEvent, Void>
        PROVIDER_STREAM_EVENT = new ExtensionEventSpec<>("provider_stream_event");

    private final String name;

    private ExtensionEventSpec(String name) {
        this.name = name;
    }

    /** pi 的事件线名（{@code ExtensionEvent["type"]}）。 */
    public String name() {
        return name;
    }
}

package com.pijava.coding.agent.extension.bridge;

import java.util.Locale;

import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentSettledEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageUpdateEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionCompactEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionCompactFailedEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionInfoChangedEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ThinkingLevelSelectEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionUpdateEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.TurnStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.AGENT_END;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.AGENT_SETTLED;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.AGENT_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.MESSAGE_END;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.MESSAGE_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.MESSAGE_UPDATE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_COMPACT;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_COMPACT_FAILED;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_INFO_CHANGED;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.THINKING_LEVEL_SELECT;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.TOOL_EXECUTION_END;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.TOOL_EXECUTION_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.TOOL_EXECUTION_UPDATE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.TURN_START;

/**
 * 宿主事件族桥（docs/14 §3.5）—— pi {@code _emitExtensionEvent}
 * （agent-session.ts:1284-1362）的 java 对位：把 {@link AgentSessionEvent} 的 14 个
 * 变体直译成扩展事件喂给 {@link ExtensionEventBus}。
 *
 * <p>全部观察语义：逐处理器 try/catch ⇒ {@code emitError} ⇒ 继续。无订阅者快走
 * （pi 多处触发点先查 {@code hasHandlers} 同构 —— {@code message_update} 每 token
 * 一帧，快照投影不能白做）。</p>
 *
 * <p>桥内状态（pi 的会话层字段对位）：{@code turnIndex}（pi {@code _turnIndex}：
 * {@code agent_start} 归 0、{@code turn_end} 递增 —— boundary 派发臂挂 B249，但递增
 * 语义本批复制）；{@code previousLevel}（pi 的 {@code thinking_level_select} 载荷
 * 需要，宿主事件只有新值 ⇒ 桥内跟踪，种子＝装配时当前等级）。</p>
 *
 * <p>{@code fromExtension} 恒 {@code false}（窄差 D6：java 无扩展触发压缩的路径）。</p>
 *
 * <p><b>线程假设</b>：{@code turnIndex}/{@code previousLevel} 是普通字段 —— 宿主事件
 * 发射在实践中按事件类型单线程（引擎车道线程 / setter 调用线程），pi 更是天然单线程。
 * 若未来出现多线程并发发射同族事件，这两个计数器需要同步化（评审 M6 登记）。</p>
 */
public final class SessionEventBridge {

    private final ExtensionEventBus bus;
    private final ExtensionContext ctx;
    private int turnIndex;
    private ModelThinkingLevel previousLevel;

    /**
     * @param bus       扩展事件总线
     * @param ctx       传给处理器的共享上下文
     * @param seedLevel previousLevel 的种子（装配时会话的当前思考等级）
     */
    public SessionEventBridge(ExtensionEventBus bus, ExtensionContext ctx,
                              ModelThinkingLevel seedLevel) {
        this.bus = bus;
        this.ctx = ctx;
        this.previousLevel = seedLevel;
    }

    /** 会话事件入口（{@code AgentSession.subscribe(...)} 的消费者形状）。 */
    public void onEvent(AgentSessionEvent event) {
        switch (event) {
            case AgentSessionEvent.AgentStart ignored -> {
                turnIndex = 0; // pi :1286
                fire(AGENT_START, new AgentStartEvent());
            }
            case AgentSessionEvent.TurnStart ignored ->
                fire(TURN_START, new TurnStartEvent(turnIndex, System.currentTimeMillis()));
            case AgentSessionEvent.TurnEnd ignored -> turnIndex++; // pi :1301；boundary 臂挂 B249
            case AgentSessionEvent.AgentEnd e ->
                fire(AGENT_END, new AgentEndEvent(e.messages()));
            case AgentSessionEvent.AgentSettled e ->
                fire(AGENT_SETTLED, new AgentSettledEvent(e.aborted()));
            case AgentSessionEvent.MessageStart e ->
                fire(MESSAGE_START, new MessageStartEvent(e.message()));
            case AgentSessionEvent.MessageUpdate e -> messageUpdate(e);
            case AgentSessionEvent.MessageEnd e ->
                fire(MESSAGE_END, new MessageEndEvent(e.message())); // 替换臂挂 B249
            case AgentSessionEvent.ToolExecutionStart e ->
                fire(TOOL_EXECUTION_START,
                    new ToolExecutionStartEvent(e.toolCallId(), e.toolName(), e.args()));
            case AgentSessionEvent.ToolExecutionUpdate e ->
                fire(TOOL_EXECUTION_UPDATE, new ToolExecutionUpdateEvent(
                    e.toolCallId(), e.toolName(), e.args(), e.partialResult()));
            case AgentSessionEvent.ToolExecutionEnd e ->
                fire(TOOL_EXECUTION_END, new ToolExecutionEndEvent(
                    e.toolCallId(), e.toolName(), e.result(), e.isError(), e.durationMs()));
            case AgentSessionEvent.ThinkingLevelChanged e -> {
                fire(THINKING_LEVEL_SELECT,
                    new ThinkingLevelSelectEvent(e.level(), previousLevel));
                previousLevel = e.level(); // 无订阅者也要跟踪（后续订阅的 previous 才正确）
            }
            case AgentSessionEvent.SessionInfoChanged e ->
                fire(SESSION_INFO_CHANGED, new SessionInfoChangedEvent(e.name()));
            case AgentSessionEvent.CompactionEnd e -> compaction(e);
            default -> {
                // 其余 10 变体无对应扩展事件（docs/14 §3.5 翻译表末行），不映射。
            }
        }
    }

    /** {@code message_update}：每 token 一帧，无订阅者必须零投影快走。 */
    private void messageUpdate(AgentSessionEvent.MessageUpdate e) {
        if (!bus.hasHandlers(MESSAGE_UPDATE)) {
            return;
        }
        var partial = e.streamEvent().partial();
        if (partial == null) {
            return; // UsageInfo 允许无快照（StreamEvent.UsageInfo 文档）
        }
        fire(MESSAGE_UPDATE, new MessageUpdateEvent(
            Message.AssistantMessage.fromPartial(partial), e.streamEvent()));
    }

    /** pi :2858/:3188 与 :1054-1056：成功 ⇒ session_compact；失败/中止 ⇒ session_compact_failed。 */
    private void compaction(AgentSessionEvent.CompactionEnd e) {
        var reason = e.reason().name().toLowerCase(Locale.ROOT);
        if (!e.aborted() && e.errorMessage() == null) {
            // 窄差 D4：compactionEntry 载荷缺席（CompactionResult 无条目本体，挂 B249）。
            fire(SESSION_COMPACT, new SessionCompactEvent(reason, e.willRetry(), false));
        } else {
            fire(SESSION_COMPACT_FAILED, new SessionCompactFailedEvent(
                reason, e.errorMessage(), e.aborted(), e.willRetry(), false));
        }
    }

    /** 观察型发射：无订阅者快走；逐处理器 try/catch ⇒ emitError ⇒ 继续（pi 的 catch 捕获一切）。 */
    private <E extends ExtensionEvent> void fire(ExtensionEventSpec<E, Void> spec, E event) {
        if (!bus.hasHandlers(spec)) {
            return;
        }
        for (var h : bus.snapshot(spec)) {
            try {
                h.handle(event, ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
    }
}

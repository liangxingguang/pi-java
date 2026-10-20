package com.pijava.coding.agent.extension.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.extension.ExtensionError;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentSettledEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.MessageUpdateEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionCompactEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionCompactFailedEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ThinkingLevelSelectEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolExecutionEndEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.TurnStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 §7.5：{@link SessionEventBridge} —— 14 事件逐个「宿主进 ⇒ 扩展出」对拍，
 * 外加 turnIndex 复位/递增、previousLevel 跟踪、CompactionEnd 两支分发、
 * message_update 载荷与快走。
 */
class SessionEventBridgeTest {

    private final ExtensionEventBus bus = new ExtensionEventBus();
    private final List<ExtensionError> errors = new ArrayList<>();
    private final Map<String, List<ExtensionEvent>> fired = new LinkedHashMap<>();
    private final SessionEventBridge bridge =
        new SessionEventBridge(bus, null, new ModelThinkingLevel.Off());

    private SessionEventBridgeTest() {
        bus.onError(errors::add);
    }

    private <E extends ExtensionEvent, R> void record(ExtensionEventSpec<E, R> spec) {
        bus.on("ext", spec, (event, ctx) -> {
            fired.computeIfAbsent(spec.name(), k -> new ArrayList<>()).add(event);
            return null;
        });
    }

    @SuppressWarnings("unchecked") // 测试按线名取回，类型由驱动侧夹具保证
    private <E extends ExtensionEvent> E only(String name) {
        var list = fired.get(name);
        assertThat(list).as(name + " 恰好一发").hasSize(1);
        return (E) list.get(0);
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)),
            Instant.now());
    }

    // ═══ 14 事件逐个对拍 ═══

    @Test
    void agentStartFiresAgentStartEvent() {
        record(ExtensionEventSpec.AGENT_START);
        bridge.onEvent(new AgentSessionEvent.AgentStart());
        only("agent_start");
    }

    @Test
    void turnStartCarriesIndexAndTimestamp() {
        record(ExtensionEventSpec.TURN_START);
        bridge.onEvent(new AgentSessionEvent.AgentStart());
        bridge.onEvent(new AgentSessionEvent.TurnStart());
        var e = (TurnStartEvent) only("turn_start");
        assertThat(e.turnIndex()).isZero();
        assertThat(e.timestamp()).isPositive();
    }

    @Test
    void agentEndCarriesMessages() {
        record(ExtensionEventSpec.AGENT_END);
        var messages = List.<Message>of(user("u1"));
        bridge.onEvent(new AgentSessionEvent.AgentEnd(messages, false));
        var e = (ExtensionEvent.AgentEndEvent) only("agent_end");
        assertThat(e.messages()).isEqualTo(messages);
    }

    @Test
    void agentSettledCarriesAbortedFlag() {
        record(ExtensionEventSpec.AGENT_SETTLED);
        bridge.onEvent(new AgentSessionEvent.AgentSettled(true));
        assertThat(((AgentSettledEvent) only("agent_settled")).aborted()).isTrue();
    }

    @Test
    void messageStartCarriesTheMessage() {
        record(ExtensionEventSpec.MESSAGE_START);
        var message = user("hello");
        bridge.onEvent(new AgentSessionEvent.MessageStart(message));
        assertThat(((ExtensionEvent.MessageStartEvent) only("message_start")).message())
            .isSameAs(message);
    }

    /** docs/15 裁决①：message_end 改由 sink 适配器发射，宿主桥不再火（防双发）。 */
    @Test
    void messageEndIsNotFiredByTheHostBridge() {
        record(ExtensionEventSpec.MESSAGE_END);
        bridge.onEvent(new AgentSessionEvent.MessageEnd(user("hello")));
        assertThat(fired).isEmpty();
    }

    /** V1 落锤：message 从 StreamEvent partial 投影；assistantMessageEvent 原样带。 */
    @Test
    void messageUpdateProjectsPartialAndCarriesTheStreamEvent() {
        record(ExtensionEventSpec.MESSAGE_UPDATE);
        var partial = new AssistantMessage("id1",
            List.of(new ContentBlock.TextContent("hi")), null, null);
        var streamEvent = new StreamEvent.TextDelta(0, "hi", partial);

        bridge.onEvent(new AgentSessionEvent.MessageUpdate(streamEvent));

        var e = (MessageUpdateEvent) only("message_update");
        assertThat(e.assistantMessageEvent()).isSameAs(streamEvent);
        assertThat(e.message()).isInstanceOf(Message.AssistantMessage.class);
        assertThat(((ContentBlock.TextContent) e.message().content().get(0)).text())
            .isEqualTo("hi");
    }

    /** 无快照的帧（UsageInfo 允许 partial==null）⇒ 不发射。 */
    @Test
    void messageUpdateWithoutPartialIsDropped() {
        record(ExtensionEventSpec.MESSAGE_UPDATE);
        bridge.onEvent(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.UsageInfo(1, 2, null)));
        assertThat(fired).isEmpty();
    }

    @Test
    void toolExecutionTrioTranslatesFields() {
        record(ExtensionEventSpec.TOOL_EXECUTION_START);
        record(ExtensionEventSpec.TOOL_EXECUTION_UPDATE);
        record(ExtensionEventSpec.TOOL_EXECUTION_END);
        var args = Map.<String, Object>of("cmd", "ls");

        bridge.onEvent(new AgentSessionEvent.ToolExecutionStart("tc1", "bash", args));
        bridge.onEvent(new AgentSessionEvent.ToolExecutionUpdate("tc1", "bash", args, "out"));
        bridge.onEvent(new AgentSessionEvent.ToolExecutionEnd("tc1", "bash", "done", false, 42L));

        var start = (ExtensionEvent.ToolExecutionStartEvent) only("tool_execution_start");
        assertThat(start.toolCallId()).isEqualTo("tc1");
        assertThat(start.toolName()).isEqualTo("bash");
        assertThat(start.args()).isEqualTo(args);
        var update = (ExtensionEvent.ToolExecutionUpdateEvent) only("tool_execution_update");
        assertThat(update.partialResult()).isEqualTo("out");
        var end = (ToolExecutionEndEvent) only("tool_execution_end");
        assertThat(end.result()).isEqualTo("done");
        assertThat(end.isError()).isFalse();
        assertThat(end.durationMs()).isEqualTo(42L);
    }

    @Test
    void thinkingLevelSelectTracksPreviousLevel() {
        record(ExtensionEventSpec.THINKING_LEVEL_SELECT);
        var high = new ModelThinkingLevel.Enabled(new ThinkingLevel.High());
        var low = new ModelThinkingLevel.Enabled(new ThinkingLevel.Low());

        bridge.onEvent(new AgentSessionEvent.ThinkingLevelChanged(high));
        bridge.onEvent(new AgentSessionEvent.ThinkingLevelChanged(low));

        var events = fired.get("thinking_level_select");
        assertThat(events).hasSize(2);
        var first = (ThinkingLevelSelectEvent) events.get(0);
        assertThat(first.level()).isEqualTo(high);
        assertThat(first.previousLevel()).as("种子＝装配时等级")
            .isEqualTo(new ModelThinkingLevel.Off());
        var second = (ThinkingLevelSelectEvent) events.get(1);
        assertThat(second.previousLevel()).isEqualTo(high);
    }

    /** 无订阅者时等级跟踪不丢（后续订阅看到的 previous 才正确）。 */
    @Test
    void previousLevelIsTrackedEvenWithoutSubscribers() {
        var high = new ModelThinkingLevel.Enabled(new ThinkingLevel.High());
        bridge.onEvent(new AgentSessionEvent.ThinkingLevelChanged(high)); // 无订阅
        record(ExtensionEventSpec.THINKING_LEVEL_SELECT);
        var low = new ModelThinkingLevel.Enabled(new ThinkingLevel.Low());

        bridge.onEvent(new AgentSessionEvent.ThinkingLevelChanged(low));

        assertThat(((ThinkingLevelSelectEvent) only("thinking_level_select")).previousLevel())
            .isEqualTo(high);
    }

    @Test
    void sessionInfoChangedCarriesName() {
        record(ExtensionEventSpec.SESSION_INFO_CHANGED);
        bridge.onEvent(new AgentSessionEvent.SessionInfoChanged("my-session"));
        assertThat(((ExtensionEvent.SessionInfoChangedEvent) only("session_info_changed"))
            .name()).isEqualTo("my-session");
    }

    /** CompactionEnd 成功臂：!aborted && errorMessage==null ⇒ session_compact。 */
    @Test
    void successfulCompactionEndFiresSessionCompact() {
        record(ExtensionEventSpec.SESSION_COMPACT);
        var result = new CompactionResult("sum", "kept-1", 1000, null, null, null);

        bridge.onEvent(new AgentSessionEvent.CompactionEnd(
            AgentSessionEvent.CompactionReason.MANUAL, result, false, false, null, null));

        var e = (SessionCompactEvent) only("session_compact");
        assertThat(e.reason()).isEqualTo("manual");
        assertThat(e.willRetry()).isFalse();
        assertThat(e.fromExtension()).isFalse();
    }

    /** CompactionEnd 失败臂（errorMessage 非空）与中止臂（aborted）⇒ session_compact_failed。 */
    @Test
    void failedOrAbortedCompactionEndFiresSessionCompactFailed() {
        record(ExtensionEventSpec.SESSION_COMPACT_FAILED);

        bridge.onEvent(new AgentSessionEvent.CompactionEnd(
            AgentSessionEvent.CompactionReason.THRESHOLD, null, false, true, "boom", null));
        bridge.onEvent(new AgentSessionEvent.CompactionEnd(
            AgentSessionEvent.CompactionReason.OVERFLOW, null, true, false, null, null));

        var events = fired.get("session_compact_failed");
        assertThat(events).hasSize(2);
        var failed = (SessionCompactFailedEvent) events.get(0);
        assertThat(failed.reason()).isEqualTo("threshold");
        assertThat(failed.errorMessage()).isEqualTo("boom");
        assertThat(failed.aborted()).isFalse();
        assertThat(failed.willRetry()).isTrue();
        assertThat(failed.fromExtension()).isFalse();
        var aborted = (SessionCompactFailedEvent) events.get(1);
        assertThat(aborted.reason()).isEqualTo("overflow");
        assertThat(aborted.aborted()).isTrue();
        assertThat(aborted.errorMessage()).isNull();
    }

    // ═══ turnIndex（pi _turnIndex：agent_start 归 0、turn_end 递增）═══

    @Test
    void turnIndexResetsOnAgentStartAndIncrementsOnTurnEnd() {
        record(ExtensionEventSpec.TURN_START);

        bridge.onEvent(new AgentSessionEvent.AgentStart());
        bridge.onEvent(new AgentSessionEvent.TurnStart());   // 0
        bridge.onEvent(new AgentSessionEvent.TurnEnd(null, List.of()));
        bridge.onEvent(new AgentSessionEvent.TurnStart());   // 1
        bridge.onEvent(new AgentSessionEvent.AgentStart());
        bridge.onEvent(new AgentSessionEvent.TurnStart());   // 0（复位）

        var indices = fired.get("turn_start").stream()
            .map(e -> ((TurnStartEvent) e).turnIndex()).toList();
        assertThat(indices).containsExactly(0, 1, 0);
    }

    /** 观察族逐处理器捕获：一个抛错 ⇒ 上报＋另一个照跑。 */
    @Test
    void aThrowingObserverIsReportedAndTheNextOneStillRuns() {
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        bus.on("ext-bad", ExtensionEventSpec.AGENT_START, (event, ctx) -> {
            throw new IllegalStateException("obs boom");
        });
        bus.on("ext-good", ExtensionEventSpec.AGENT_START, (event, ctx) -> {
            ran.set(true);
            return null;
        });

        bridge.onEvent(new AgentSessionEvent.AgentStart());

        assertThat(ran).isTrue();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).extensionName()).isEqualTo("ext-bad");
        assertThat(errors.get(0).event()).isEqualTo("agent_start");
    }

    /** 无订阅者快走：14 事件全驱动一遍，零发射零错误。 */
    @Test
    void noSubscribersIsAFastNoOp() {
        bridge.onEvent(new AgentSessionEvent.AgentStart());
        bridge.onEvent(new AgentSessionEvent.TurnStart());
        bridge.onEvent(new AgentSessionEvent.MessageUpdate(
            new StreamEvent.TextDelta(0, "x",
                new AssistantMessage("id", List.of(), null, null))));
        bridge.onEvent(new AgentSessionEvent.CompactionEnd(
            AgentSessionEvent.CompactionReason.MANUAL, null, false, false, null, null));

        assertThat(fired).isEmpty();
        assertThat(errors).isEmpty();
    }
}

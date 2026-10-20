package com.pijava.coding.agent.extension.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.HarnessConfig;
import com.pijava.agent.harness.LaneState;
import com.pijava.agent.hook.CompactionContext;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.hook.SuppliedCompaction;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.extension.ExtensionError;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionResults.BoundaryResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.MessageEndEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeCompactResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBoundaryDraft;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.AGENT_BEFORE_SETTLE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.MESSAGE_END;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_COMPACT;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.TURN_END;

/**
 * docs/15 T4：{@code message_end} 替换适配器（pi runner.ts:1144-1181）、
 * {@code session_before_compact} 适配器（agent-session.ts:2792-2811）、boundary
 * 两适配器（:846-896, :1893-1919＋runner.ts:1029-1078）的逐字情形。
 */
class Batch15BridgeAdaptersTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    // ── message_end 适配器 ────────────────────────────────────

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.AssistantMessage assistant(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static String textOf(Message message) {
        return ((ContentBlock.TextContent) message.content().get(0)).text();
    }

    @Test
    void messageEndChainsReplacements() {
        var bus = new ExtensionEventBus();
        bus.on("h1", MESSAGE_END, (e, c) -> e.message() instanceof Message.AssistantMessage
            ? new MessageEndEventResult(assistant("first")) : null);
        bus.on("h2", MESSAGE_END, (e, c) -> new MessageEndEventResult(
            assistant(textOf(e.message()) + "-second"))); // 链式：h2 看到 h1 的产物
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        var replaced = hooks.fireMessageEnd("default",
            new com.pijava.agent.hook.MessageEndContext("default", "r1", assistant("orig")));
        assertThat(textOf(replaced)).isEqualTo("first-second");
    }

    @Test
    void messageEndRoleMismatchIsReportedAndSkipped() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        bus.on("bad", MESSAGE_END, (e, c) -> new MessageEndEventResult(user("wrong role")));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        var replaced = hooks.fireMessageEnd("default",
            new com.pijava.agent.hook.MessageEndContext("default", "r1", assistant("orig")));
        assertThat(replaced).isNull(); // 替换被跳过
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message())
            .isEqualTo("message_end handlers must return a message with the same role"); // pi 逐字
    }

    @Test
    void messageEndErrorToleranceAndAbstain() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        bus.on("bad", MESSAGE_END, (e, c) -> {
            throw new IllegalStateException("boom");
        });
        bus.on("good", MESSAGE_END, (e, c) -> new MessageEndEventResult(assistant("fixed")));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        var ctx = new com.pijava.agent.hook.MessageEndContext("default", "r1", assistant("o"));
        assertThat(textOf(hooks.fireMessageEnd("default", ctx))).isEqualTo("fixed");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).extensionName()).isEqualTo("bad");

        // 全程无发言 ⇒ null（sink 用原消息）；无订阅者同样 null。
        var quiet = new HookSystem(new LaneState());
        EngineHookBridge.install(quiet, "default", new ExtensionEventBus(), null);
        assertThat(quiet.fireMessageEnd("default", ctx)).isNull();
    }

    // ── before_compaction 适配器 ──────────────────────────────

    private static CompactionContext compactionCtx(List<Entry> branch) {
        var plan = new CompactionService.Plan(List.of(), 0, 1,
            List.of(user("old")), List.of(), false, "prev-summary", "keep-1");
        return new CompactionContext("default", branch, 4321, plan, "manual", false,
            "custom-instr", null, new CompactionSettings(true, 16384, 1000));
    }

    @Test
    void beforeCompactionPayloadMapsPlanAndCancelShortCircuits() {
        var bus = new ExtensionEventBus();
        var seen = new ArrayList<String>();
        var branch = List.<Entry>of(
            new Entry.Message("e1", 0, null, null, user("old"), null));
        bus.on("h1", SESSION_BEFORE_COMPACT, (e, c) -> {
            var p = e.preparation();
            seen.add(p.firstKeptEntryId() + "|" + p.previousSummary() + "|"
                + p.tokensBefore() + "|" + e.reason() + "|" + e.customInstructions()
                + "|" + e.branchEntries().size());
            return new SessionBeforeCompactResult(true, null);
        });
        bus.on("h2", SESSION_BEFORE_COMPACT, (e, c) -> {
            throw new AssertionError("cancel 短路，后续处理器不跑"); // pi :1100-1104
        });
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        var plan = hooks.fireBeforeCompaction("default", compactionCtx(branch));
        assertThat(plan.cancel()).isTrue();
        assertThat(seen).containsExactly("keep-1|prev-summary|4321|manual|custom-instr|1");
    }

    @Test
    void beforeCompactionSuppliedLastSpeakerWins() {
        var bus = new ExtensionEventBus();
        bus.on("h1", SESSION_BEFORE_COMPACT, (e, c) -> new SessionBeforeCompactResult(null,
            new SuppliedCompaction("sum-1", "keep-1", 100, null, null)));
        bus.on("h2", SESSION_BEFORE_COMPACT, (e, c) -> new SessionBeforeCompactResult(null,
            new SuppliedCompaction("sum-2", "keep-2", 200, null, Map.of("k", "v"))));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        var plan = hooks.fireBeforeCompaction("default", compactionCtx(List.of()));
        assertThat(plan.supplied().summary()).isEqualTo("sum-2");
        assertThat(plan.supplied().details()).containsEntry("k", "v");
    }

    // ── boundary 适配器（真 harness 驱动）─────────────────────

    private static List<StreamEvent> reply(String text) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return List.of(new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private AgentHarness harness(List<List<StreamEvent>> scripted) {
        var it = scripted.iterator();
        return AgentHarness.create(HarnessConfig.builder()
            .streamFn((model, context, options) -> StreamIterator.from(it.next()))
            .model(MODEL).build());
    }

    @Test
    void turnEndBoundaryCommitsDraftsAndCarriesPayload() {
        var h = harness(List.of(reply("ok")));
        var bus = new ExtensionEventBus();
        var commits = new ArrayList<Entry>();
        var seen = new ArrayList<String>();
        bus.on("ext", TURN_END, (e, c) -> {
            seen.add(e.turnIndex() + "|" + e.outcome() + "|"
                + (e.messageEntryId() != null) + "|" + e.toolResultEntryIds().size()
                + "|" + e.context().canContinue());
            return new BoundaryResult(List.of(
                new SessionBoundaryDraft.Custom("mark", Map.of("k", 1)),
                new SessionBoundaryDraft.CustomMessage("note", null, true, null),
                new SessionBoundaryDraft.ContextEdit(
                    e.messageEntryId(), null), // 剔除刚落定的助手消息（投影级）
                new SessionBoundaryDraft.Compaction("sum", null, null, null)), false);
        });
        var turnIndex = new BoundaryBridge.TurnIndex();
        EngineHookBridge.installBoundary(h.hookSystem(), h, "default", bus, null,
            commits::add, () -> false, turnIndex);

        h.prompt("hello");

        assertThat(seen).containsExactly("0|completed|true|0|false");
        assertThat(commits).hasSize(4);
        assertThat(commits.get(0)).isInstanceOf(Entry.Custom.class);
        assertThat(commits.get(1)).isInstanceOf(Entry.CustomMessage.class);
        assertThat(commits.get(2)).isInstanceOf(Entry.ContextEdit.class);
        var compaction = (Entry.Compaction) commits.get(3);
        assertThat(compaction.fromHook()).isTrue();
        assertThat(compaction.summary()).isEqualTo("sum");
        assertThat(compaction.retainedTail()).isEmpty(); // 自留压缩（firstKeptEntryId null）
    }

    @Test
    void turnEndContinueForcesAnotherRequest() {
        var streamCalls = new AtomicInteger();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("ok")))
            .withStopReason(StopReason.STOP);
        var h = AgentHarness.create(HarnessConfig.builder()
            .streamFn((model, context, options) -> {
                streamCalls.incrementAndGet();
                return StreamIterator.from(reply("ok"));
            }).model(MODEL).build());
        var bus = new ExtensionEventBus();
        var boundaryCalls = new AtomicInteger();
        bus.on("ext", TURN_END, (e, c) -> {
            if (boundaryCalls.incrementAndGet() == 1) {
                h.followUp("default", "again"); // pi：boundary 处理器入队驱动 continue
                return new BoundaryResult(null, true);
            }
            return null; // 第二轮弃权，否则无限续跑（pi 同形）
        });
        EngineHookBridge.installBoundary(h.hookSystem(), h, "default", bus, null,
            entry -> { }, () -> false, new BoundaryBridge.TurnIndex());

        h.prompt("hello");
        // continue=true 且 canContinue（有排队）⇒ CONTINUE ⇒ 恰再来一次请求。
        assertThat(streamCalls.get()).isEqualTo(2);
    }

    @Test
    void turnEndInvalidContinuationIsReportedAndAbstains() {
        var streamCalls = new AtomicInteger();
        var h = AgentHarness.create(HarnessConfig.builder()
            .streamFn((model, context, options) -> {
                streamCalls.incrementAndGet();
                return StreamIterator.from(reply("ok"));
            }).model(MODEL).build());
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        bus.on("ext", TURN_END, (e, c) -> new BoundaryResult(null, true)); // 不排队、尾是助手
        EngineHookBridge.installBoundary(h.hookSystem(), h, "default", bus, null,
            entry -> { }, () -> false, new BoundaryBridge.TurnIndex());

        h.prompt("hello");
        assertThat(streamCalls.get()).isEqualTo(1); // 弃权 ⇒ 正常收口
        assertThat(errors).anyMatch(e -> e.message()
            .equals("turn_end requested continuation without runnable model context"));
        assertThat(errors).allMatch(e -> e.extensionName().equals("<boundary>"));
    }

    @Test
    void turnEndUnresolvedEntryIdSkipsDispatch() {
        var h = harness(List.of());
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        var fired = new AtomicInteger();
        bus.on("ext", TURN_END, (e, c) -> {
            fired.incrementAndGet();
            return null;
        });
        var boundary = new BoundaryBridge(h, "default", bus, null, entry -> { },
            () -> false, new BoundaryBridge.TurnIndex());
        // 空 transcript：entryId 必然解析不到（pi :857-865）。
        var decision = boundary.turnEnd(new com.pijava.agent.hook.ShouldStopAfterTurnContext(
            "default", "r1", assistant("ghost"), List.of()));

        assertThat(decision).isNull();
        assertThat(fired.get()).isZero();
        assertThat(errors).anyMatch(e -> e.message()
            .equals("turn_end could not resolve the persisted assistant entry ID"));
    }

    @Test
    void beforeSettleContinueRunsAnotherPassAndAbstainSettles() {
        var streamCalls = new AtomicInteger();
        var h = AgentHarness.create(HarnessConfig.builder()
            .streamFn((model, context, options) -> {
                streamCalls.incrementAndGet();
                return StreamIterator.from(reply("ok"));
            }).model(MODEL).build());
        var bus = new ExtensionEventBus();
        var settleCalls = new AtomicInteger();
        bus.on("ext", AGENT_BEFORE_SETTLE, (e, c) -> {
            if (settleCalls.incrementAndGet() == 1) {
                h.followUp("default", "again");
                return new BoundaryResult(null, true);
            }
            return null;
        });
        EngineHookBridge.installBoundary(h.hookSystem(), h, "default", bus, null,
            entry -> { }, () -> false, new BoundaryBridge.TurnIndex());

        h.prompt("hello");
        assertThat(streamCalls.get()).isEqualTo(2);
        assertThat(settleCalls.get()).isEqualTo(2);

        // 无订阅者 ⇒ 钩子弃权（引擎按无桥行为收口）。
        var h2 = harness(List.of(reply("x")));
        var quietBoundary = new BoundaryBridge(h2, "default", new ExtensionEventBus(),
            null, entry -> { }, () -> false, new BoundaryBridge.TurnIndex());
        assertThat(quietBoundary.beforeSettle(
            new com.pijava.agent.hook.BeforeSettleContext("default", "r1"))).isNull();
    }
}

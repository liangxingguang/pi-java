package com.pijava.coding.agent.extension.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.harness.LaneState;
import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.harness.PiToolRunner;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.ExtensionError;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ContextEventResult;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolCallEventResult;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolResultEventResult;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 §7.2–7.4：{@link EngineHookBridge} 三适配器 —— pi runner.ts:1183-1359 的
 * 逐字情形（系统消息过滤/restore 语义/引用相等快照/丢头系统上报/block 短路/
 * input 原地可变链/structuredContent 丢弃规则/逐处理器捕获）。
 */
class EngineHookBridgeTest {

    private static final ToolContext CTX = new ToolContext(
        System.getProperty("java.io.tmpdir"), Map.of(),
        new DefaultShellExecutor(), new DefaultFileSystem());
    private static final PiLoop.Sink NO_EMIT = event -> {};

    // ── 消息夹具 ─────────────────────────────────────────────

    private static Message.SystemMessage system(String text) {
        return new Message.SystemMessage(text, Instant.now(), Map.of(), List.of(), List.of());
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)),
            Instant.now());
    }

    private static Message.AssistantMessage assistant(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static String textOf(Message message) {
        return ((ContentBlock.TextContent) message.content().get(0)).text();
    }

    // ═══════════════════════════════════════════════════════════
    // ContextAdapter（§7.2）
    // ═══════════════════════════════════════════════════════════

    /** ① 阶段一系统消息过滤：SystemMessage 不出现在 event.messages。 */
    @Test
    void stageOneHidesSystemMessagesFromHandlers() {
        var bus = new ExtensionEventBus();
        var seen = new AtomicReference<List<Message>>();
        bus.on("ext", ExtensionEventSpec.CONTEXT, (event, ctx) -> {
            seen.set(List.copyOf(event.messages()));
            return null;
        });
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        hooks.fireTransformContext("default",
            List.of(system("s1"), user("u1"), assistant("a1")));

        assertThat(seen.get()).hasSize(2);
        assertThat(seen.get()).noneMatch(m -> m instanceof Message.SystemMessage);
    }

    /** ② 会话被改 ⇒ 头系统重放＋中间系统消息丢（pi restoreSystemMessages）。 */
    @Test
    void changedConversationGetsReplayedHeadAndDropsMidConversationSystem() {
        var bus = new ExtensionEventBus();
        var user1 = user("u1");
        bus.on("ext", ExtensionEventSpec.CONTEXT,
            (event, ctx) -> new ContextEventResult(List.of(user1)));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        var transformed = hooks.fireTransformContext("default",
            List.of(system("s1"), user1, system("s2"), user("u2")));

        // 头系统＝getCurrentSystemMessage 重放（s1 与 s2 的内容按 \n\n 合并），
        // 中间系统消息不再独立存在；改动后的可见列表跟在后面。
        assertThat(transformed).hasSize(2);
        assertThat(transformed.get(0)).isInstanceOf(Message.SystemMessage.class);
        assertThat(textOf(transformed.get(0))).isEqualTo("s1\n\ns2");
        assertThat(transformed.get(1)).isSameAs(user1);
    }

    /** ③ 会话未改 ⇒ current 原样保留（含中间系统消息，pi 的 sameMessages 分支）。 */
    @Test
    void unchangedConversationKeepsEveryMessageInPlace() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.CONTEXT, (event, ctx) -> null);
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var original = List.<Message>of(system("s1"), user("u1"), system("s2"), user("u2"));

        var transformed = hooks.fireTransformContext("default", original);

        assertThat(transformed).containsExactlyElementsOf(original);
    }

    /** ④ 原地改列表被检出（== 快照）：不返回值也算改。 */
    @Test
    void inPlaceMutationIsDetectedByTheReferenceSnapshot() {
        var bus = new ExtensionEventBus();
        var injected = user("injected");
        bus.on("ext", ExtensionEventSpec.CONTEXT, (event, ctx) -> {
            event.messages().add(0, injected);
            return null;
        });
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var user1 = user("u1");
        var user2 = user("u2");

        var transformed = hooks.fireTransformContext("default",
            List.of(system("s1"), user1, user2));

        assertThat(transformed).hasSize(4);
        assertThat(transformed.get(0)).isInstanceOf(Message.SystemMessage.class);
        assertThat(transformed.subList(1, 4)).containsExactly(injected, user1, user2);
    }

    /** ⑤ 阶段二弄丢头系统 ⇒ emitError 报告（文本逐字）**且仍采用**。 */
    @Test
    void stageTwoLosingTheLeadingSystemMessageIsReportedButAdopted() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        var user1 = user("u1");
        bus.on("ext-cws", ExtensionEventSpec.CONTEXT_WITH_SYSTEM,
            (event, ctx) -> new ContextEventResult(List.of(user1)));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        var transformed = hooks.fireTransformContext("default",
            List.of(system("s1"), user1));

        assertThat(transformed).containsExactly(user1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message())
            .isEqualTo(EngineHookBridge.LEADING_SYSTEM_REMOVED);
        assertThat(errors.get(0).event()).isEqualTo("context_with_system");
        assertThat(errors.get(0).extensionName()).isEqualTo("ext-cws");
    }

    /** ⑥ 处理器抛错 ⇒ 上报＋后续处理器照跑（逐处理器捕获）。 */
    @Test
    void aThrowingContextHandlerIsReportedAndLaterHandlersStillRun() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        var ran = new AtomicBoolean();
        bus.on("ext-bad", ExtensionEventSpec.CONTEXT, (event, ctx) -> {
            throw new IllegalStateException("ctx boom");
        });
        bus.on("ext-good", ExtensionEventSpec.CONTEXT, (event, ctx) -> {
            ran.set(true);
            return null;
        });
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        hooks.fireTransformContext("default", List.of(system("s1"), user("u1")));

        assertThat(ran).isTrue();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).extensionName()).isEqualTo("ext-bad");
        assertThat(errors.get(0).event()).isEqualTo("context");
        assertThat(errors.get(0).message()).isEqualTo("ctx boom");
    }

    // ═══════════════════════════════════════════════════════════
    // ToolCallAdapter（§7.3，harness 集成）
    // ═══════════════════════════════════════════════════════════

    /** 记录 prepareArguments 入参、可标执行与否的工具。 */
    private static final class CapturingTool implements AgentTool<String, Void> {
        private final AtomicBoolean executed = new AtomicBoolean();
        private final AtomicReference<Map<String, Object>> rawArgs = new AtomicReference<>();

        @Override public String name() { return "cap"; }
        @Override public String label() { return "cap"; }
        @Override public String description() { return "capturing tool"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
        @Override public String prepareArguments(Map<String, Object> raw) {
            rawArgs.set(raw);
            return "prepared";
        }
        @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
            executed.set(true);
            return ToolResult.success("done");
        }
    }

    private static PiLoop.ToolOutcome runBoth(PiToolRunner runner, PiLoop.ToolCall call) {
        var preparation = runner.prepare(call);
        return preparation instanceof PiLoop.ImmediateOutcome immediate
            ? immediate.outcome()
            : runner.execute((PiLoop.Prepared) preparation, NO_EMIT);
    }

    private static PiToolRunner runnerWith(AgentTool<?, ?> tool, HookSystem hooks) {
        var registry = new ToolRegistry(null);
        registry.register(tool);
        return new PiToolRunner("default", registry, hooks, CTX, null, null);
    }

    /** block ⇒ 工具不执行、错误结果带 reason、terminate 透传。 */
    @Test
    void blockSkipsExecutionAndCarriesReasonAndTerminate() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.TOOL_CALL,
            (event, ctx) -> new ToolCallEventResult(true, "no way", true));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var tool = new CapturingTool();

        var outcome = runBoth(runnerWith(tool, hooks),
            new PiLoop.ToolCall("tc1", "cap", Map.of(), false));

        assertThat(tool.executed).isFalse();
        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.terminate()).isTrue();
        assertThat(outcome.message().content().toString()).contains("no way");
    }

    /** block 短路后续处理器（pi emitToolCall 的 return result）。 */
    @Test
    void blockShortCircuitsLaterHandlers() {
        var bus = new ExtensionEventBus();
        var laterRan = new AtomicBoolean();
        bus.on("ext-a", ExtensionEventSpec.TOOL_CALL,
            (event, ctx) -> new ToolCallEventResult(true, "stop", null));
        bus.on("ext-b", ExtensionEventSpec.TOOL_CALL, (event, ctx) -> {
            laterRan.set(true);
            return null;
        });
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);

        runBoth(runnerWith(new CapturingTool(), hooks),
            new PiLoop.ToolCall("tc1", "cap", Map.of(), false));

        assertThat(laterRan).isFalse();
    }

    /** input 原地改 ⇒ 后续处理器看到、执行拿到改后参数（pi 的原地可变链）。 */
    @Test
    void inPlaceInputMutationFlowsToLaterHandlersAndExecution() {
        var bus = new ExtensionEventBus();
        var seenBySecond = new AtomicReference<Map<String, Object>>();
        bus.on("ext-a", ExtensionEventSpec.TOOL_CALL, (event, ctx) -> {
            event.input().put("x", 1);
            return null;
        });
        bus.on("ext-b", ExtensionEventSpec.TOOL_CALL, (event, ctx) -> {
            seenBySecond.set(Map.copyOf(event.input()));
            return null;
        });
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var tool = new CapturingTool();

        var outcome = runBoth(runnerWith(tool, hooks),
            new PiLoop.ToolCall("tc1", "cap", Map.of(), false));

        assertThat(outcome.isError()).isFalse();
        assertThat(seenBySecond.get()).isEqualTo(Map.of("x", 1));
        assertThat(tool.rawArgs.get()).as("执行拿到改写后的参数").isEqualTo(Map.of("x", 1));
    }

    /** 处理器抛错 ⇒ 工具错误结果（裁决③ 的 pi 链：传播 ⇒ prepare 的 catch 转换）。 */
    @Test
    void aThrowingToolCallHandlerBecomesTheToolErrorResult() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.TOOL_CALL, (event, ctx) -> {
            throw new IllegalStateException("ext boom");
        });
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var tool = new CapturingTool();

        var outcome = runBoth(runnerWith(tool, hooks),
            new PiLoop.ToolCall("tc1", "cap", Map.of(), false));

        assertThat(tool.executed).isFalse();
        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().content().toString()).contains("ext boom");
    }

    // ═══════════════════════════════════════════════════════════
    // ToolResultAdapter（§7.4）
    // ═══════════════════════════════════════════════════════════

    /** 结果带 structuredContent（+可选 isError）的工具。 */
    private static AgentTool<String, Void> structuredTool(
            String name, boolean isError, Object structured, AtomicReference<ToolResult<?>> holder) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "structured tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return "prepared"; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                var result = new ToolResult<Void>(
                    List.of(new ContentBlock.TextContent("payload")),
                    null, null, false, List.of(), isError, structured);
                holder.set(result);
                return result;
            }
        };
    }

    /** content 换而 structured 未同换 ⇒ structured 被丢（pi runner.ts:1194-1197）。 */
    @Test
    void replacingContentWithoutStructuredDropsStructuredContent() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.TOOL_RESULT, (event, ctx) ->
            new ToolResultEventResult(
                List.of(new ContentBlock.TextContent("rewritten")), null, null, null, null));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var holder = new AtomicReference<ToolResult<?>>();

        var outcome = runBoth(runnerWith(
            structuredTool("t", false, Map.of("rows", 3), holder), hooks),
            new PiLoop.ToolCall("tc1", "t", Map.of(), false));

        assertThat(outcome.message().content().toString()).contains("rewritten");
        assertThat(outcome.result().structuredContent()).isNull();
    }

    /** details-only 补丁 ⇒ isError/structuredContent 保留（§2.3 修真的桥层夹具）。 */
    @Test
    void detailsOnlyResultPatchPreservesIsErrorAndStructuredContent() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.TOOL_RESULT, (event, ctx) ->
            new ToolResultEventResult(null, Map.of("patched", true), null, null, null));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var holder = new AtomicReference<ToolResult<?>>();
        var structured = Map.of("rows", 3);

        var outcome = runBoth(runnerWith(structuredTool("t", true, structured, holder), hooks),
            new PiLoop.ToolCall("tc1", "t", Map.of(), false));

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.result().structuredContent()).isEqualTo(structured);
        assertThat(outcome.result().details()).isEqualTo(Map.of("patched", true));
    }

    /** isError 翻转（成功 ⇒ 错误）。 */
    @Test
    void toolResultHandlerCanFlipIsError() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.TOOL_RESULT, (event, ctx) ->
            new ToolResultEventResult(null, null, null, true, null));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var holder = new AtomicReference<ToolResult<?>>();

        var outcome = runBoth(runnerWith(structuredTool("t", false, null, holder), hooks),
            new PiLoop.ToolCall("tc1", "t", Map.of(), false));

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().isError()).isTrue();
    }

    /** 全程无修改 ⇒ 原结果同一引用（pi 的 modified=false ⇒ undefined）。 */
    @Test
    void noModificationKeepsTheOriginalResultInstance() {
        var bus = new ExtensionEventBus();
        bus.on("ext", ExtensionEventSpec.TOOL_RESULT, (event, ctx) -> null);
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var holder = new AtomicReference<ToolResult<?>>();

        var outcome = runBoth(runnerWith(
            structuredTool("t", false, Map.of("k", 1), holder), hooks),
            new PiLoop.ToolCall("tc1", "t", Map.of(), false));

        assertThat(outcome.result()).isSameAs(holder.get());
    }

    /** 处理器抛错 ⇒ 上报＋后续处理器照跑（逐处理器捕获，错误归属正确）。 */
    @Test
    void aThrowingToolResultHandlerIsReportedAndLaterHandlersStillApply() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        bus.on("ext-bad", ExtensionEventSpec.TOOL_RESULT, (event, ctx) -> {
            throw new IllegalStateException("tr boom");
        });
        bus.on("ext-good", ExtensionEventSpec.TOOL_RESULT, (event, ctx) ->
            new ToolResultEventResult(
                List.of(new ContentBlock.TextContent("patched")), null, null, null, null));
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var holder = new AtomicReference<ToolResult<?>>();

        var outcome = runBoth(runnerWith(structuredTool("t", false, null, holder), hooks),
            new PiLoop.ToolCall("tc1", "t", Map.of(), false));

        assertThat(outcome.message().content().toString()).contains("patched");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).extensionName()).isEqualTo("ext-bad");
        assertThat(errors.get(0).event()).isEqualTo("tool_result");
        assertThat(errors.get(0).message()).isEqualTo("tr boom");
    }

    /** 空订阅快走：未订阅时引擎钩子零开销、结果原样。 */
    @Test
    void noSubscriptionsIsAFastNoOp() {
        var bus = new ExtensionEventBus();
        var hooks = new HookSystem(new LaneState());
        EngineHookBridge.install(hooks, "default", bus, null);
        var holder = new AtomicReference<ToolResult<?>>();

        var outcome = runBoth(runnerWith(structuredTool("t", false, null, holder), hooks),
            new PiLoop.ToolCall("tc1", "t", Map.of(), false));

        assertThat(outcome.result()).isSameAs(holder.get());
        var original = List.<Message>of(system("s1"), user("u1"));
        assertThat(hooks.fireTransformContext("default", original)).isEqualTo(original);
    }
}

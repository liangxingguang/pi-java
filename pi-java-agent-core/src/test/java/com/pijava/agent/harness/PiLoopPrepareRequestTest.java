package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.pijava.ai.api.StreamIterator;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #34：{@code prepareRequest} 写钩子，逐条镜像 pi {@code agent-loop.ts:219-239}
 * （@ {@code ea448f454}）与 {@code types.ts:159-190}：
 *
 * <ul>
 *   <li>在<b>每一次</b> provider 请求之前调用（含第一次），此时 pending 已 append（{@code :216-218} 注释）</li>
 *   <li>调用点在 transformContext 之前（{@code :219} vs {@code :390}）</li>
 *   <li>{@code context} 整体替换（{@code currentContext = u.context ?? currentContext}）</li>
 *   <li>model/thinking 写回 config ⇒ 对本 run 后续请求生效，不跨 run（pi 的 config 是 run 局部）</li>
 *   <li>{@code "off"} ⇒ 请求侧 {@code reasoning: undefined}（密封类型天然编码）</li>
 *   <li>{@code void}（java 的 {@code null}）⇒ 一切不变</li>
 *   <li>HookSystem 链式：后钩看到前钩的替换结果；全弃权 ⇒ null；抛异常弃权不炸循环</li>
 * </ul>
 */
class PiLoopPrepareRequestTest {

    // ── 剧本（与 PiLoopFinishTurnTest 同装置） ────────────────────────

    private final List<List<Message>> requests = new ArrayList<>();
    private final List<ModelId<?>> requestModels = new ArrayList<>();
    private final List<StreamOptions> requestOptions = new ArrayList<>();

    private StreamFn scripted(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (model, context, options) -> {
            requests.add(List.copyOf(context.messages()));
            requestModels.add(model);
            requestOptions.add(options);
            var script = scripts.get(index.getAndIncrement());
            return new StreamIterator() {
                private int i;

                @Override public boolean hasNext() { return i < script.size(); }
                @Override public StreamEvent next() { return script.get(i++); }
                @Override public void close() { }
            };
        };
    }

    private static List<StreamEvent> textTurn(String text) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private static List<StreamEvent> toolTurn(String callId, String name) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(callId, name, Map.of())))
            .withStopReason(StopReason.TOOL_USE);
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.ToolCallEnd(0, callId, name, Map.of(), done),
            new StreamEvent.StreamDone(StopReason.TOOL_USE, null, done));
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static final PiLoop.ToolRunner OK_TOOLS = PiLoop.ToolRunner.always(call ->
        new PiLoop.ToolOutcome(
            new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                List.of(new ContentBlock.TextContent("ok")), false),
            ToolResult.success("ok"), false));

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static Context context() {
        return new Context(null, new ArrayList<>(), List.of());
    }

    private static PiLoop.Config config(StreamFn streamFn,
                                        Supplier<List<Message>> steering,
                                        PiLoop.ContextTransform transform,
                                        PiLoop.PrepareRequestHook prepareRequest) {
        return new PiLoop.Config(
            MODEL,
            ModelThinkingLevel.off(),
            ThinkingLevelMap.empty(),
            ToolExecution.defaultMode(),
            OK_TOOLS,
            streamFn,
            null,
            steering,
            null,
            transform,
            prepareRequest,
            null,
            null,
            null,
            null);
    }

    // ── ① 每轮请求前都调（含第一轮） ─────────────────────────────────

    @Test
    void firesBeforeEveryRequestIncludingTheFirst() {
        var calls = new AtomicInteger();
        var config = config(scripted(List.of(toolTurn("tc1", "echo"), textTurn("done"))),
            null, null, request -> {
                calls.incrementAndGet();
                return null;
            });

        PiLoop.run(List.of(user("go")), context(), config, event -> { });

        assertThat(requests).hasSize(2);
        assertThat(calls.get()).as("两轮剧本 ⇒ 恰 2 次（types.ts:186-190「every…including the first」）")
            .isEqualTo(2);
    }

    // ── ② 调用时机：pending 已 append 且在 transformContext 之前 ──────

    @Test
    void seesPendingAlreadyAppendedAndRunsBeforeTransformContext() {
        var order = new ArrayList<String>();
        var seenAtHook = new ArrayList<List<Message>>();
        var polls = new AtomicInteger();
        Supplier<List<Message>> steering = () ->
            polls.getAndIncrement() == 0 ? List.of(user("STEER")) : List.of();
        var config = config(scripted(List.of(textTurn("done"))),
            steering,
            messages -> {
                order.add("transform_context");
                return messages;
            },
            request -> {
                order.add("prepare_request");
                seenAtHook.add(List.copyOf(request.context().messages()));
                return null;
            });

        PiLoop.run(List.of(user("hi")), context(), config, event -> { });

        assertThat(seenAtHook).hasSize(1);
        assertThat(seenAtHook.get(0))
            .as("pending 已 append 进 context（pi :216-218 的注释承诺）：prompt 与 steering 都在")
            .hasSize(2)
            .allMatch(m -> m instanceof Message.UserMessage);
        assertThat(order).as("prepareRequest（:219）先于 transformContext（:390）")
            .containsExactly("prepare_request", "transform_context");
    }

    // ── ③ context 整体替换：请求用的是新 context ─────────────────────

    @Test
    void contextReplacementIsUsedByTheRequest() {
        var config = config(scripted(List.of(textTurn("done"))),
            null, null, request -> new PiLoop.RequestUpdate(
                new Context(null, new ArrayList<>(List.of(user("REPLACED"))), List.of()),
                null, null));

        PiLoop.run(List.of(user("hi")), context(), config, event -> { });

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0))
            .as("currentContext = u.context ?? currentContext —— 整体替换，不含原 prompt")
            .hasSize(1);
        assertThat(((Message.UserMessage) requests.get(0).get(0)).content().toString())
            .contains("REPLACED");
    }

    // ── ④ model/thinking 替换对本 run 后续轮生效；Off ⇒ reasoning 为空 ──

    @Test
    void modelAndThinkingReplacementHoldsForTheRestOfTheRun() {
        var calls = new AtomicInteger();
        var config = config(scripted(List.of(toolTurn("tc1", "echo"), textTurn("done"))),
            null, null, request -> calls.incrementAndGet() == 1
                ? new PiLoop.RequestUpdate(null, ModelId.of("faux", "switched-model"),
                    ModelThinkingLevel.of(new ThinkingLevel.High()))
                : null);

        PiLoop.run(List.of(user("go")), context(), config, event -> { });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(requestModels)
            .as("钩子只发言一次；第一轮请求已用替换值（prepareRequest 在请求**之前**调），"
                + "第二轮弃权仍保持替换值 ⇒ 写回 config 对本 run 后续请求生效（pi 的 config 是 run 局部）")
            .containsExactly(ModelId.of("faux", "switched-model"),
                ModelId.of("faux", "switched-model"));
        assertThat(requestOptions.get(1).reasoning())
            .as("Enabled 级别 ⇒ 请求侧 reasoning 携带该级别")
            .contains(new ThinkingLevel.High());
    }

    @Test
    void offThinkingMeansNoReasoningOnTheWire() {
        var config = config(scripted(List.of(textTurn("done"))),
            null, null, request -> new PiLoop.RequestUpdate(null, null, ModelThinkingLevel.off()));

        PiLoop.run(List.of(user("go")), context(), config, event -> { });

        assertThat(requestOptions.get(0).reasoning())
            .as("pi 的 reasoning: undefined —— \"off\" 在请求侧就是「不传」")
            .isEmpty();
    }

    // ── ⑤ null 返回 ⇒ 一切不变 ────────────────────────────────────────

    @Test
    void nullReturnChangesNothing() {
        var config = config(scripted(List.of(toolTurn("tc1", "echo"), textTurn("done"))),
            null, null, request -> null);

        PiLoop.run(List.of(user("go")), context(), config, event -> { });

        assertThat(requestModels).containsExactly(MODEL, MODEL);
        assertThat(requests.get(0)).anyMatch(m -> m instanceof Message.UserMessage);
    }

    // ── ⑥ HookSystem 链式合并 ─────────────────────────────────────────

    private static StreamFn simpleStream(List<ModelId<?>> seenModels) {
        return (model, context, options) -> {
            seenModels.add(model);
            var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason(StopReason.STOP);
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "ok", partial),
                new StreamEvent.StreamDone(StopReason.STOP, null, partial)));
        };
    }

    private static AgentHarness harness(StreamFn streamFn) {
        return AgentHarness.create(new HarnessConfig(
            streamFn, MODEL, ModelThinkingLevel.off(), "",
            java.util.Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    @Test
    void chainedHooksSeeEachOthersReplacements() {
        var seenModels = new ArrayList<ModelId<?>>();
        var h = harness(simpleStream(seenModels));
        var hook2Saw = new ArrayList<ModelId<?>>();
        h.hookSystem().onPrepareRequest("default", request ->
            new PiLoop.RequestUpdate(null, ModelId.of("faux", "hook1-model"), null));
        h.hookSystem().onPrepareRequest("default", request -> {
            hook2Saw.add(request.model());
            return null;
        });

        h.prompt("go");

        assertThat(hook2Saw)
            .as("后钩看到前钩的替换结果（HookSystem 链式合并）")
            .containsExactly(ModelId.of("faux", "hook1-model"));
        assertThat(seenModels)
            .as("合并结果真正落到请求上")
            .containsExactly(ModelId.of("faux", "hook1-model"));
    }

    @Test
    void allAbstainMeansNoChange() {
        var seenModels = new ArrayList<ModelId<?>>();
        var h = harness(simpleStream(seenModels));
        h.hookSystem().onPrepareRequest("default", request -> null);
        h.hookSystem().onPrepareRequest("default", request -> null);

        h.prompt("go");

        assertThat(seenModels).as("全弃权 ⇒ null ⇒ 一切不变").containsExactly(MODEL);
    }

    @Test
    void throwingHookAbstainsWithoutBreakingTheLoop() {
        var seenModels = new ArrayList<ModelId<?>>();
        var h = harness(simpleStream(seenModels));
        h.hookSystem().onPrepareRequest("default", request -> {
            throw new RuntimeException("boom");
        });
        h.hookSystem().onPrepareRequest("default", request ->
            new PiLoop.RequestUpdate(null, ModelId.of("faux", "survivor-model"), null));

        h.prompt("go");

        assertThat(seenModels)
            .as("抛异常的钩记账并弃权（fireFinishTurn 同纪律），后钩照常生效")
            .containsExactly(ModelId.of("faux", "survivor-model"));
    }
}

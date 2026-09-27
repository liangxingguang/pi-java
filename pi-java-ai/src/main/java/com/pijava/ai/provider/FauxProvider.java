package com.pijava.ai.provider;

import java.util.List;
import java.util.Set;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.stream.StreamEvent;

/**
 * A programmable fake provider for testing.
 *
 * <p>Allows tests to preset a list of {@link StreamEvent} values that will
 * be replayed in order when any chat API method is called. Supports text
 * responses, tool calls, errors, and configurable inter-event delay.</p>
 */
public final class FauxProvider implements Provider {

    private final String name;
    private final List<List<StreamEvent>> responses;
    private final long delayMs;
    private final AtomicInteger nextCall = new AtomicInteger();

    /**
     * Create a provider that replays the given events on every call.
     *
     * @param name    provider name
     * @param events  the event sequence to replay
     * @param delayMs delay between events in milliseconds
     */
    public FauxProvider(String name, List<StreamEvent> events, long delayMs) {
        this(name, List.of(events), delayMs, false);
    }

    private FauxProvider(String name, List<List<StreamEvent>> responses,
                         long delayMs, boolean multiResponse) {
        this.name = name;
        this.responses = List.copyOf(responses);
        this.delayMs = delayMs;
    }

    /**
     * Create a provider that returns a different event sequence per model
     * call (e.g. tool call first, then a final text reply). This models the
     * multi-turn tool loop without hanging on a repeated tool call.
     */
    public static FauxProvider sequence(
            String name, List<List<StreamEvent>> responses) {
        return new FauxProvider(name, responses, 0, true);
    }

    List<StreamEvent> nextResponse() {
        int index = Math.min(nextCall.getAndIncrement(), responses.size() - 1);
        return responses.get(index);
    }

    /**
     * Convenience: create a FauxProvider that returns the given text.
     * Produces a full event sequence: Start → TextStart → TextDelta → TextEnd → StreamDone.
     */
    public static FauxProvider text(String text) {
        var msg = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason("stop");
        var partial0 = AssistantMessage.empty();
        var partial1 = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("")));
        return new FauxProvider("faux", List.of(
                new StreamEvent.Start(partial0),
                new StreamEvent.TextStart(0, partial1),
                new StreamEvent.TextDelta(0, text, msg.withStopReason(null)),
                new StreamEvent.TextEnd(0, text, msg.withStopReason(null)),
                StreamEvent.StreamDone.settle("stop", msg)
        ), 0);
    }

    /**
     * Convenience: create a FauxProvider that simulates a tool call.
     * Produces: Start → ToolCallStart → ToolCallDelta → ToolCallEnd → StreamDone.
     */
    public static FauxProvider toolCall(String toolName, java.util.Map<String, Object> args) {
        var callId = "faux_call_1";
        var block = new ContentBlock.ToolUseContent(callId, toolName, args);
        var finalMsg = AssistantMessage.empty()
                .withContent(List.of(block))
                .withStopReason("toolUse");
        return new FauxProvider("faux-tool", List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                // 包⑥：桩的起点块与生产同形（带 id/name）—— 改核心而不改桩会让
                // 夹具把人脑里的模型当契约。
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()
                        .withContent(List.of(new ContentBlock.ToolUseContent(
                            callId, toolName, java.util.Map.of())))),
                new StreamEvent.ToolCallEnd(0, callId, toolName, args, finalMsg.withStopReason(null)),
                StreamEvent.StreamDone.settle("toolUse", finalMsg)
        ), 0);
    }

    /**
     * Convenience: create a FauxProvider that returns an error.
     * Produces: Start → StreamError.
     *
     * <p>C 批次（{@code docs/55}）：三条终局都经 {@code settle} 工厂落定。
     * ⚠️ 这条尤其要紧 —— {@code FauxChatApi} 沿用的是生产那条身份挂载缝，
     * 但**出口缝只补身份与计量、不造文本**（{@code docs/55 §11-2}）⇒ 桩若不落定，
     * 一切经 faux 驱动的夹具就仍在旧形状上跑（错误文本只在 {@code Throwable} 上）。</p>
     */
    public static FauxProvider error(String message) {
        return new FauxProvider("faux-error", List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                StreamEvent.StreamError.settle("error",
                        new RuntimeException(message), AssistantMessage.empty())
        ), 0);
    }

    @Override
    public String name() { return name; }

    @Override
    public String displayName() { return "Faux (" + name + ")"; }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class);
    }

    @Override
    @SuppressWarnings("unchecked")  // safe: apiType equality check guarantees T is the expected API type
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        if (apiType.equals(ChatApi.class)) {
            return (T) new FauxChatApi(this, delayMs);
        }
        throw new IllegalArgumentException("Unsupported API type: " + apiType);
    }

    @Override
    public ModelCatalog builtinModels() {
        return ModelCatalog.empty();
    }

    // ── FauxChatApi ───────────────────────────────────────────

    /**
     * 夹具的 {@code ChatApi} —— 包⑪（docs/38，台账 A16）**改为继承
     * {@link AbstractChatApi}**，即走**生产同一条**身份挂载缝。
     *
     * <p>此前它直接实现 {@code ChatApi}、**绕过**了那个唯一的挂载点 ⇒ 一切经 faux
     * 驱动的夹具都在**另一个形状**上跑（消息不带 {@code api}/{@code provider}/
     * {@code model}/{@code timestamp}）。包⑩ 的 B48（RPC 线在带 timestamp 的消息上
     * **抛**）就是这么藏住的。</p>
     *
     * <p>而且要照 pi：pi 的 faux <b>挂得比 pi-java 还真</b> ——
     * {@code cloneMessage} 写 {@code api}/{@code provider}/{@code model}/
     * {@code timestamp}/{@code usage} 五项（{@code ai/src/providers/faux.ts:281-291}），
     * 默认 {@code api="faux"}（{@code :23}）。⇒ <b>pi-java 的 faux 才是异类</b>。</p>
     *
     * <p>继承之后，{@code stream()} 的「先订阅后发射」竞态、{@code streamBlocking}、
     * {@code send} 三条全由基类提供（{@code send} 经 {@code fromPartial} 产出全字段
     * 终局消息）—— 这里只需实现协议名与事件重放。</p>
     */
    private static final class FauxChatApi extends AbstractChatApi {

        private final FauxProvider provider;
        private final long delayMs;

        FauxChatApi(FauxProvider provider, long delayMs) {
            this.provider = provider;
            this.delayMs = delayMs;
        }

        /**
         * 测试替身**不参与**请求侧选项解析（包 A-10，{@code docs/57 §6 R2}）。
         *
         * <p>pi 的 faux 也不过 {@code buildBaseOptions}；更重要的是，本替身存在的意义是
         * 重放**给定的**事件序列 —— 让它的请求被悄悄改写会让「夹具输入 = 断言前提」
         * 这条链少一环。生产车道那半边由 `ai` 的 wire 夹具守。</p>
         */
        @Override
        protected boolean resolvesRequestOptions() {
            return false;
        }

        @Override
        public String apiName() {
            // pi 的 faux 默认 api 字面量（providers/faux.ts:23）。
            return "faux";
        }

        @Override
        protected void streamInternal(StreamRequest request,
                SubmissionPublisher<StreamEvent> publisher) {
            for (var event : provider.nextResponse()) {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        // 基类按「异常 ⇒ closeExceptionally」处理；中断也走那条路。
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while replaying", e);
                    }
                }
                publisher.submit(event);
            }
        }
    }
}

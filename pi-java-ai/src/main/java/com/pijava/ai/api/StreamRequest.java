package com.pijava.ai.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * A streaming chat request sent to an LLM provider.
 *
 * <p><b>形状</b>：{@code systemPrompt} / {@code messages} / {@code tools} 是 pi 的
 * {@link ModelInfo}（兼容输入），它们在**构造时**被 {@link ContextNormalizer} 折进
 * {@link TranscriptContext} —— 与 pi 的 {@code normalizeContext} 同一时点
 * （{@code ai/src/utils/transcript.ts:30}，在公开 stream 入口调用）。车道只看得见
 * {@link #transcript()}：{@code systemPrompt()} / {@code tools()} 访问器**不存在**，
 * 镜像 pi 的 branded {@code TranscriptContext}（{@code types.ts:628-634}「a raw
 * Context cannot reach provider code by accident」）。</p>
 *
 * <p>⚠️ 与 pi 的形状差异：pi 在**公开入口**做一次归一、provider 收到的是
 * {@code TranscriptContext}；java 的 {@code StreamApi.stream(StreamRequest, ApiOptions)}
 * 一身两角（公开入口 ＋ 车道钩子），故把归一收口在**本记录的兼容构造器**里
 * （{@code docs/49 §5.1}）。</p>
 *
 * <p>Carries the target model's **full** {@link ModelInfo}, not just its {@link ModelId} —
 * pi's request builders receive the whole {@code Model<TApi>} and read things off it
 * ({@code compat}, {@code input}, {@code thinkingLevelMap}); a request that carried only the
 * id could not replay thinking blocks correctly, because the per-model flags were unavailable
 * (docs/31 §8.34.4 决策 5).</p>
 *
 * @param model        the target model, with its metadata
 * @param transcript   the normalized ordered transcript —— 系统提示与工具声明都在它的
 *                     系统消息里（pi 的 {@code TranscriptContext}）
 * @param maxTokens    maximum output tokens (-1 for provider default)
 * @param temperature  sampling temperature (-1 for provider default)
 * @param extra        provider-specific parameters
 * @param reasoning    pi {@code SimpleStreamOptions.reasoning} —— <b>未翻译</b>的思考级别；
 *                     空 ≙ pi 的 {@code undefined}（不开思考）。<b>翻译归车道</b>：
 *                     它要读 {@code model.compat} 与 {@code model.thinkingLevelMap}
 *                     （{@code anthropic-messages.ts:858-904}），两者都在 {@code model} 上
 */
public record StreamRequest(
    ModelInfo model,
    TranscriptContext transcript,
    int maxTokens,
    double temperature,
    Map<String, Object> extra,
    Optional<ThinkingLevel> reasoning
) {
    /** Compact constructor that defensively copies the transcript and extra map. */
    public StreamRequest {
        transcript = transcript == null ? new TranscriptContext(List.of()) : transcript;
        extra = Map.copyOf(extra);
        if (reasoning.isEmpty()) {
            reasoning = Optional.empty();
        }
    }

    /**
     * Legacy-shape constructor: pi's public entry parameters
     * ({@code Context.systemPrompt} / {@code Context.messages} / {@code Context.tools}).
     *
     * <p>这三个值在本构造器里经 {@link ContextNormalizer#normalize} 折成 transcript ——
     * 调用点（宿主、evals、conformance、测试）因此**零改签**。</p>
     */
    public StreamRequest(
        ModelInfo model,
        String systemPrompt,
        List<Message> messages,
        List<ToolDefinition> tools,
        int maxTokens,
        double temperature,
        Map<String, Object> extra,
        Optional<ThinkingLevel> reasoning
    ) {
        this(model, ContextNormalizer.normalize(systemPrompt, messages, tools),
            maxTokens, temperature, extra, reasoning);
    }

    /**
     * Convenience constructor without a thinking level (≙ pi 的 {@code reasoning: undefined}).
     *
     * <p>保留此 7 参形态使既有构造点零改签。</p>
     */
    public StreamRequest(
        ModelInfo model,
        String systemPrompt,
        List<Message> messages,
        List<ToolDefinition> tools,
        int maxTokens,
        double temperature,
        Map<String, Object> extra
    ) {
        this(model, systemPrompt, messages, tools, maxTokens, temperature, extra,
            Optional.empty());
    }

    /**
     * Convenience constructor taking just a model id.
     *
     * <p>Synthesizes {@link ModelInfo#minimal(ModelId)}. Callers that know the model's metadata
     * should pass the real {@link ModelInfo} — a synthesized one declares no capabilities and
     * no compat flags, so per-model replay rules fall back to their defaults.</p>
     */
    public StreamRequest(
        ModelId<?> model,
        String systemPrompt,
        List<Message> messages,
        List<ToolDefinition> tools,
        int maxTokens,
        double temperature,
        Map<String, Object> extra
    ) {
        this(ModelInfo.minimal(model), systemPrompt, messages, tools,
            maxTokens, temperature, extra, Optional.empty());
    }

    /**
     * The target model's id.
     *
     * <p>Most call sites only need the id (to name the model on the wire, or to stamp message
     * identity) — this saves them a {@code model().id()} hop.</p>
     */
    public ModelId<?> modelId() {
        return model == null ? null : model.id();
    }

    /**
     * The session messages (a pure alias for {@link #transcript()}'s messages).
     *
     * <p>保留它是为了让「把 {@code request.messages()} 当成消息表」的既有读取点只需改来源、
     * 不必改形状；它**不**是第二个真相 —— 前导系统消息也在里面
     * （pi 的 {@code transformMessages(context.messages)} 看到的正是这张表）。</p>
     */
    public List<Message> messages() {
        return transcript.messages();
    }

    /** Create a simple request with defaults. */
    public static StreamRequest of(ModelId<?> model, List<Message> messages) {
        return new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
    }

    /**
     * 换一个输出上限的副本（包 A-10）。
     *
     * <p>消费方是 {@link SimpleOptions#resolveRequest}：pi 的夹取发生在车道的
     * {@code streamSimple} 里，java 对应的是 {@code AbstractChatApi.stream} ——
     * 那里把请求换成解析后的副本，车道就零改签地拿到 {@code maxTokens}。</p>
     */
    public StreamRequest withMaxTokens(int newMaxTokens) {
        return new StreamRequest(model, transcript, newMaxTokens, temperature, extra, reasoning);
    }
}

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
 * <p>Carries the target model's **full** {@link ModelInfo}, not just its {@link ModelId} —
 * pi's request builders receive the whole {@code Model<TApi>} and read things off it
 * ({@code compat}, {@code input}, {@code thinkingLevelMap}); a request that carried only the
 * id could not replay thinking blocks correctly, because the per-model flags were unavailable
 * (docs/31 §8.34.4 决策 5).</p>
 *
 * @param model        the target model, with its metadata
 * @param systemPrompt system instruction ({@code null} = none). Carried separately from
 *                     {@code messages} because pi's {@code Message} union has no system
 *                     role; every provider maps this to its own system field
 * @param messages     conversation history
 * @param tools        tool definitions (may be empty)
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
    String systemPrompt,
    List<Message> messages,
    List<ToolDefinition> tools,
    int maxTokens,
    double temperature,
    Map<String, Object> extra,
    Optional<ThinkingLevel> reasoning
) {
    /** Compact constructor that defensively copies the messages, tools, and extra maps. */
    public StreamRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        extra = Map.copyOf(extra);
        if (reasoning == null) {
            reasoning = Optional.empty();
        }
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

    /** Create a simple request with defaults. */
    public static StreamRequest of(ModelId<?> model, List<Message> messages) {
        return new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
    }
}

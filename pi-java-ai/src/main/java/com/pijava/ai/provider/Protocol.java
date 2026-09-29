package com.pijava.ai.provider;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Protocol family — corresponds to a ChatApi adapter implementation.
 *
 * <p>Naming aligns with pi's KnownApi: pi uses kebab-case wire names
 * (e.g. "openai-completions", "anthropic-messages"), we use the enum
 * constant name and derive the wire name via {@link #wireName()}.</p>
 *
 * <p>This is a pure constant closed set → enum (CLAUDE.md convention).</p>
 */
public enum Protocol {
    /** pi: "openai-completions" → OpenAICompletionsApi (Phase 1) */
    OPENAI_COMPLETIONS,
    /** pi: "anthropic-messages" → AnthropicMessagesApi (Phase 1) */
    ANTHROPIC_MESSAGES,
    /** pi: "google-generative-ai" → GoogleGenerativeAiApi (Phase 1) */
    GOOGLE_GENERATIVE_AI,
    /** pi: "mistral-conversations" → MistralConversationsApi (Phase 1) */
    MISTRAL_CONVERSATIONS,
    /** pi: "openai-responses" → OpenAIResponsesApi (Phase 6) */
    OPENAI_RESPONSES,
    /** pi: "azure-openai-responses" → AzureOpenAIResponsesApi (Phase 6) */
    AZURE_OPENAI_RESPONSES,
    /** pi: "pi-messages" → PiMessagesApi (Phase 6) */
    PI_MESSAGES;

    /**
     * Wire name used in {@code ApiOptions.extra} and JSON, e.g.
     * {@code openai-completions}.
     *
     * @return kebab-case protocol name
     */
    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * {@link #wireName} 的逆（包 A-02）：线格名 → 枚举。此前同一行
     * {@code valueOf(toUpperCase.replace('-','_'))} 在 {@code ConfigurableProvider.resolveProtocol}
     * 与 {@code ModelsJsonConfig.buildProvider} 各写一遍，per-model {@code api} 起第三处 ⇒ 收口。
     *
     * <p>⚠️ 未知值**响亮抛**（与本仓其它闭集键同口径）：静默回落会让模型走错车道，
     * 而那种偏离没有任何其它症状。</p>
     *
     * @param wire kebab-case 协议名（如 {@code "anthropic-messages"}）
     * @return 对应枚举
     * @throws IllegalArgumentException 取值不在闭集内（含 {@code null}）
     */
    public static Protocol fromWire(String wire) {
        try {
            return valueOf(wire.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown api: " + wire, e);
        }
    }
}

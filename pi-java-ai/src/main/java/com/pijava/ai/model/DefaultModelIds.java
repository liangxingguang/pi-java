package com.pijava.ai.model;

import java.util.Map;
import java.util.Optional;

/**
 * 裸 provider 引用（{@code "anthropic"}、{@code null} 模式）解析时的默认模型 id —— pi
 * {@code coding-agent/src/core/model-resolver.ts:16-58} 的 {@code defaultModelPerProvider}
 * 表中、本仓已注册且有 npm 生成分片的 17 provider 子集（ollama 在 pi 无条目）。
 *
 * <p><b>为什么不能直接取目录首条</b>：分片按 models.dev 的序排列，首条不保证是默认模型
 * （google 分片首条是 {@code deep-research-max-preview-04-2026}；openrouter 首条是
 * {@code anthropic/claude-fable-5}）。pi 用这张常量表独立指定默认；批 3a 只移植该表做
 * <b>防回归切片</b>，glob/alias/诊断等 resolver 主体是批 3b（B137）。</p>
 *
 * <p>每个值都已在对应分片里验证存在（{@code DefaultModelIdsTest} 循环钉住，防换数据时
 * 静默默认回退首条）。</p>
 */
public final class DefaultModelIds {

    private static final Map<String, String> BY_PROVIDER = Map.ofEntries(
        Map.entry("anthropic", "claude-opus-4-8"),
        Map.entry("openai", "gpt-5.5"),
        Map.entry("google", "gemini-3.1-pro-preview"),
        Map.entry("deepseek", "deepseek-v4-pro"),
        Map.entry("mistral", "devstral-medium-latest"),
        Map.entry("xai", "grok-4.7"),
        Map.entry("minimax", "MiniMax-M2.7"),
        Map.entry("minimax-cn", "MiniMax-M2.7"),
        Map.entry("moonshotai", "kimi-k2.6"),
        Map.entry("moonshotai-cn", "kimi-k2.6"),
        Map.entry("zai", "glm-5.3"),
        Map.entry("zai-coding-cn", "glm-5.3"),
        Map.entry("qwen-token-plan-cn", "qwen3.7-max"),
        Map.entry("xiaomi", "mimo-v2.5-pro"),
        Map.entry("xiaomi-token-plan-cn", "mimo-v2.5-pro"),
        Map.entry("ant-ling", "Ring-2.6-1T"),
        Map.entry("openrouter", "moonshotai/kimi-k2.6"));

    private DefaultModelIds() {}

    /** pi {@code defaultModelPerProvider}[provider]；表外 provider（ollama 等）为空。 */
    public static Optional<String> forProvider(String provider) {
        return provider == null ? Optional.empty() : Optional.ofNullable(BY_PROVIDER.get(provider));
    }
}

package com.pijava.ai.model;

import java.util.Map;

import com.pijava.ai.provider.builtin.ProviderCatalog;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 3a T6：pi {@code defaultModelPerProvider}（model-resolver.ts:16-58）的 17 provider
 * 防回归切片。分片首条不保证是默认模型（google 首条是 deep-research 预览模型），
 * 故裸 provider 引用必须走常量表；换数据时默认 id 缺席要在本测试变红，而不是静默回退首条。
 */
class DefaultModelIdsTest {

    @Test
    void everyDefaultIdExistsInItsShard() {
        Map<String, String> expected = Map.ofEntries(
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
        assertThat(DefaultModelIds.forProvider("ollama")).as("ollama has no pi default").isEmpty();
        // 表内容与 pi 逐字一致（17 键）。
        assertThat(expected).hasSize(17);
        for (var e : expected.entrySet()) {
            assertThat(DefaultModelIds.forProvider(e.getKey())).contains(e.getValue());
        }
    }

    @Test
    void resolverPicksTableDefaultsInsteadOfFirstEntries() {
        var resolver = new DefaultModelResolver(ProviderCatalog.allModels());

        // null 模式：兜底 provider google ⇒ 默认表，而不是分片首条 deep-research 预览。
        assertThat(resolver.resolve(null).provider()).isEqualTo("google");
        assertThat(resolver.resolve(null).modelName()).isEqualTo("gemini-3.1-pro-preview");

        // 裸 provider 名支。
        assertThat(resolver.resolve("anthropic").modelName()).isEqualTo("claude-opus-4-8");
        assertThat(resolver.resolve("google").modelName()).isEqualTo("gemini-3.1-pro-preview");
        assertThat(resolver.resolve("openrouter").modelName()).isEqualTo("moonshotai/kimi-k2.6");

        // 表外 provider（ollama）仍取首条。
        assertThat(resolver.resolve("ollama"))
            .isEqualTo(ModelId.of("ollama", "llama3.2"));

        // 裸 "provider/" 支同样走默认表。
        assertThat(resolver.resolve("anthropic/").modelName()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void capabilityResolutionStillPrefersTheDefaultWhenItSatisfiesRequired() {
        var resolver = new DefaultModelResolver(ProviderCatalog.allModels());
        // 能力版解析（preferred provider）：默认 id 满足 TEXT ⇒ 它胜出，而非首条。
        var id = resolver.resolve(java.util.Set.of(ModelCapability.TEXT),
            java.util.Optional.of("google"));
        assertThat(id).isEqualTo(ModelId.of("google", "gemini-3.1-pro-preview"));
    }
}

package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

/**
 * L5 W6b（docs/25 §7；pi {@code main.ts:476-490}＋{@code resolveCliModel}）：宿主接线——
 * 裸未知 {@code --model} 报错而非兜底自定义模型；同 id 跨 provider 时由
 * {@code hasConfiguredAuth} 破平；{@code --api-key} 让可用性恒 false；Java 独有的
 * 裸 provider／{@code provider/} 便利臂仍先于解析核。
 */
class CliModelWiringTest {

    private static ModelInfo info(String provider, String id) {
        return new ModelInfo(ModelId.of(provider, id), id, Set.of(ModelCapability.TEXT),
            128_000, 8192, true, PricingInfo.UNKNOWN);
    }

    private static final List<ModelInfo> MODELS = List.of(
        info("anthropic", "claude-sonnet-4-5"),
        info("openai", "gpt-4o"),
        info("openrouter", "anthropic/claude-opus-5.5"),
        info("vercel-ai-gateway", "anthropic/claude-opus-5.5"));

    /** 最小目录（pi 测试夹具同形）。 */
    private static final com.pijava.ai.catalog.ModelCatalog CATALOG =
        new com.pijava.ai.catalog.ModelCatalog() {
            @Override
            public List<ModelInfo> listModels() {
                return MODELS;
            }

            @Override
            public java.util.Optional<ModelInfo> find(ModelId<?> id) {
                return MODELS.stream().filter(m -> m.id().equals(id)).findFirst();
            }

            @Override
            public List<ModelInfo> search(String query) {
                return List.of();
            }
        };

    private static final DefaultModelResolver RESOLVER = new DefaultModelResolver(CATALOG);

    @Test
    void bareUnknownModelIsAnError() {
        // 2026-11-05 裁决：不再是「默认 provider 下的自定义模型」。
        assertThatThrownBy(() -> RESOLVER.resolve("ghost-model", "google", p -> false, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Model \"ghost-model\" not found. "
                + "Use --list-models to see available models.");
    }

    @Test
    void usableProviderBreaksTheTie() {
        var resolved = RESOLVER.resolve("anthropic/claude-opus-5.5", null,
            "vercel-ai-gateway"::equals, false);

        assertThat(resolved).isEqualTo(ModelId.of("vercel-ai-gateway", "anthropic/claude-opus-5.5"));
    }

    @Test
    void noUsableProviderIsAmbiguous() {
        assertThatThrownBy(() -> RESOLVER.resolve("anthropic/claude-opus-5.5", null,
                p -> false, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("Model \"anthropic/claude-opus-5.5\" is ambiguous "
                + "across providers: openrouter/anthropic/claude-opus-5.5, "
                + "vercel-ai-gateway/anthropic/claude-opus-5.5. ");
    }

    @Test
    void inlineApiKeyIgnoresStoredCredentials() {
        assertThatThrownBy(() -> {
            var inlineApiKey = true;
            java.util.function.Predicate<String> usable = p -> !inlineApiKey
                && "vercel-ai-gateway".equals(p);
            RESOLVER.resolve("anthropic/claude-opus-5.5", null, usable, inlineApiKey);
        })
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--api-key does not choose a provider.");
    }

    @Test
    void explicitProviderStillAllowsCustomIds() {
        var resolved = RESOLVER.resolve("openai/ghost-model", null, p -> false, false);

        assertThat(resolved).isEqualTo(ModelId.of("openai", "ghost-model"));
    }

    @Test
    void bareProviderConvenienceArmStillWins() {
        // Java 独有支路（pi 无）：裸 provider 名取该 provider 的默认模型/首条。
        assertThat(RESOLVER.resolve("anthropic", null, p -> false, false).provider())
            .isEqualTo("anthropic");
        assertThat(RESOLVER.resolve("openai/", null, p -> false, false).provider())
            .isEqualTo("openai");
    }

    /**
     * 显式 {@code --model} 解析失败 ⇒ {@link SessionResolutionExit}（干净的
     * {@code Error: <msg>} ＋ exit 1），不是裸 {@code IllegalStateException} 冒栈
     * （pi main.ts:894-934 的 diagnostics→reportDiagnostics→process.exit 链）。
     */
    @Test
    void explicitModelFailureIsACleanExitNotAStackTrace() {
        // 裸未知 id（无 provider 提示）⇒ pi 报 not-found；有 --provider 提示时
        // 走 D227 自定义 id 臂、不报错。
        var args = ArgsParser.parse(new String[] {"--model", "ghost-model", "--no-session"});

        assertThatThrownBy(() -> CliModelWiring.resolveInitialModel(args,
            SettingsManager.withStorage(new InMemorySettingsStorage()), RESOLVER,
            "ghost-model", null, null, null, null))
            .isInstanceOf(SessionResolutionExit.class)
            .hasMessage("Model \"ghost-model\" not found. "
                + "Use --list-models to see available models.")
            .satisfies(e -> {
                var exit = (SessionResolutionExit) e;
                assertThat(exit.code()).isEqualTo(1);
                assertThat(exit.errorPrefix()).isTrue();
            });
    }
}

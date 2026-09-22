package com.pijava.coding.agent.core;

import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.auth.Credentials;
import com.pijava.ai.auth.RecordedCredential;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Assembly helpers for providers and the harness {@link StreamFn}
 * (Phase 3 design §9.5 "新增 API 清单").
 */
public final class DefaultProviders {

    private static final String DEFAULT_PROVIDER = "google";

    private DefaultProviders() {}

    /**
     * Register the 16 built-in providers, any ServiceLoader-discovered
     * third-party {@code ProviderFactory} implementations, and the user's
     * {@code models.json} custom providers (which override builtins on
     * name collision). A malformed models.json warns and continues —
     * aligned with pi's diagnostics behavior.
     */
    public static ProviderRegistry defaultProviders() {
        var registry = ProviderRegistry.create();
        registry.loadBuiltinProviders();
        registry.discoverFromServiceLoader();
        registerModelsJsonProviders(registry);
        return registry;
    }

    private static void registerModelsJsonProviders(ProviderRegistry registry) {
        try {
            for (var provider : com.pijava.ai.provider.ModelsJsonConfig.loadDefault().buildProviders()) {
                registry.register(provider);
            }
        } catch (RuntimeException e) {
            System.err.println("Warning: failed to load models.json providers: "
                + e.getMessage());
        }
    }

    /**
     * Resolve the effective provider name: CLI {@code --provider} &gt; settings
     * {@code defaultProvider} &gt; the built-in default ("google").
     */
    public static String resolveProviderName(Args args, String defaultProvider) {
        if (args.provider() != null && !args.provider().isBlank()) {
            return args.provider();
        }
        if (defaultProvider != null && !defaultProvider.isBlank()) {
            return defaultProvider;
        }
        return DEFAULT_PROVIDER;
    }

    /**
     * Build a {@link StreamFn} that routes **each request by the model's own
     * provider** ({@code model.provider()}), using the CLI API key, the
     * settings key, or the environment/file credential store.
     *
     * <p>与 pi 同形：{@code compat.ts:262}/{@code :287} 的
     * {@code resolveApiProvider(model.api)} —— 派发键是**模型**，凭据也跟模型
     * （{@code compat.ts:225-231} {@code getEnvApiKey(model.provider, ...)}）。
     * 会话级的 {@code defaultProvider} 自此只决定**起手模型**（
     * {@code AgentSession} 的 {@code models.resolve}）与**未注册 provider 的回退**
     * （见下），不再决定每个请求的适配器 —— 此前它把适配器闭包死，导致切到别的
     * provider 的模型仍用旧适配器发请求（生产事故见 {@code docs/31 §8.31}）。</p>
     *
     * <p>回退（裁决 ①，{@code docs/31 §8.31.7}）：模型的 provider 不在注册表里时
     * （目录里的自定义 id、或未实现协议的 provider），回退到会话 provider 并往
     * stderr 留一行警告 —— 不新增硬失败。baseUrl/apiKey 的解析链原样保留（CLI
     * {@code --base-url} &gt; settings 默认 &gt; 凭据存储），只是按模型 provider 取名。</p>
     */
    public static StreamFn streamFnFor(Args args, String defaultProvider,
                                       ProviderRegistry providers, Settings settings) {
        var fallbackName = resolveProviderName(args, defaultProvider);
        return (model, context, options) -> {
            var provider = providers.get(model.provider()).orElseGet(() -> {
                System.err.println("[provider] unknown model provider \"" + model.provider()
                    + "\"; falling back to \"" + fallbackName + "\"");
                return providers.get(fallbackName).orElseThrow(
                    () -> new IllegalStateException("Unknown provider: " + fallbackName));
            });
            return streamBlocking(provider, model, context, options,
                apiOptions(args, model.provider(), settings, Credentials::resolveCredential));
        };
    }

    private static StreamIterator streamBlocking(
            Provider provider,
            ModelId<?> model,
            com.pijava.agent.harness.Context context,
            com.pijava.agent.harness.StreamOptions options,
            ApiOptions apiOptions) {
        var api = provider.createApi(ChatApi.class, apiOptions);
        // 系统提示与工具定义都来自 Context（pi 的 Context）；它们不再走消息列表或 options。
        //
        // 模型**元数据**（而非只有 id）必须随请求走：pi 的请求构建器拿到整个 Model<TApi>，
        // 从上面读 compat / input / thinkingLevelMap（docs/31 §8.34.4 决策 5）。此前只投
        // ModelId ⇒ 目录里的 per-model 开关到不了适配器。目录里查不到时退化为
        // ModelInfo.minimal（compat 缺席 ≡ pi 的 `?? false`，安全方向）。
        //
        // ⚠️ 包H5：`extra["thinking.budgetTokens"]` 那条<b>休眠</b>通道已删 —— 它此前
        // 从未被写入过（门恒假），且 pi 的 reasoning 是**未翻译的级别**、由车道自己翻译
        // （anthropic-messages.ts:858-904）。翻译所需的 compat/thinkingLevelMap 就在
        // modelInfo 上，故随 StreamRequest 一起走。
        var modelInfo = provider.builtinModels().find(model)
                .orElseGet(() -> ModelInfo.minimal(model));
        var request = new com.pijava.ai.api.StreamRequest(
            modelInfo, context.systemPrompt(), context.messages(),
            ToolRegistry.definitionsOf(context.tools()),
            options.maxTokens().orElse(-1),
            options.temperature().orElse(-1),
            java.util.Map.of(),
            options.reasoning());
        return api.streamBlocking(request, apiOptions);
    }

    /**
     * Resolve {@link ApiOptions} for a provider: baseUrl/apiKey priority is
     * CLI flag &gt; settings default &gt; credential resolver (null = none).
     */
    /**
     * 解析 {@link ApiOptions}：baseUrl/apiKey 的优先序为 CLI 旗标 &gt; settings 默认
     * &gt; 凭证解析器（{@code null} = 无）。
     *
     * <p>包 A0 步7（{@code docs/43 D5/D6}）：凭证解析器返回的不再是裸字符串而是
     * {@link RecordedCredential} —— 形态（{@code API_KEY} ／ {@code BEARER} ／ {@code OAUTH}）
     * 随值一起装进 {@link ApiOptions}，车道才可能把它放对头。CLI/settings 直给的 key
     * 恒为 {@code API_KEY}（那两层没有「token」这个概念）。</p>
     */
    static ApiOptions apiOptions(Args args, String providerName, Settings settings,
                                 Function<String, Optional<RecordedCredential>> credentialResolver) {
        var baseUrl = firstNonBlank(args.baseUrl(), settings == null ? null : settings.defaultBaseUrl);
        var apiKey = firstNonBlank(args.apiKey(), settings == null ? null : settings.defaultApiKey);
        var kind = AuthKind.API_KEY;
        if (apiKey == null && credentialResolver != null) {
            var credential = credentialResolver.apply(providerName).orElse(null);
            if (credential != null) {
                apiKey = credential.value();
                kind = credential.kind();
            }
        }
        return new ApiOptions(baseUrl == null ? "" : baseUrl,
            apiKey == null ? "" : apiKey,
            java.time.Duration.ofSeconds(120), 2, Map.of(), kind);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}

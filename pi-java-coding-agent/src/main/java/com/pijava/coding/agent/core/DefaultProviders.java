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
     * Register the 18 built-in providers, any ServiceLoader-discovered
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
            registerModelsJsonProviders(registry,
                com.pijava.ai.provider.ModelsJsonConfig.loadDefault());
        } catch (RuntimeException e) {
            System.err.println("Warning: failed to load models.json providers: "
                + e.getMessage());
        }
    }

    /** D-P1：用显式加载的 models.json 注册（夹具用同一代码路径，docs/65 §3 Step 4）。 */
    static void registerModelsJsonProviders(ProviderRegistry registry,
                                            com.pijava.ai.provider.ModelsJsonConfig config) {
        for (String id : config.providerIds()) {
            var def = config.provider(id);
            var existing = registry.get(id);
            if (existing.isPresent()) {
                // 命中内置 provider id ⇒ 合并 catalog 后包装注册（R4：不改内置单例）。
                var merged = com.pijava.ai.provider.ModelJsonMerge.merge(
                    id, existing.get().builtinModels().listModels(), def);
                registry.register(new com.pijava.ai.provider.OverrideProvider(
                    existing.get(), com.pijava.ai.catalog.BuiltinCatalog.of(merged)));
            } else {
                // 未命中 ⇒ 独立 provider（原行为）。
                registry.register(
                    com.pijava.ai.provider.ModelsJsonConfig.buildProviderEntry(id, def));
            }
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
                                       ProviderRegistry providers, Settings settings,
                                       String sessionId) {
        var fallbackName = resolveProviderName(args, defaultProvider);
        return (model, context, options) -> {
            var provider = providers.get(model.provider()).orElseGet(() -> {
                System.err.println("[provider] unknown model provider \"" + model.provider()
                    + "\"; falling back to \"" + fallbackName + "\"");
                return providers.get(fallbackName).orElseThrow(
                    () -> new IllegalStateException("Unknown provider: " + fallbackName));
            });
            return streamBlocking(provider, model, context, options,
                apiOptions(args, model.provider(), settings, Credentials::resolveCredential,
                    requestExtra(options, sessionId)));
        };
    }

    /**
     * D-P1：把 per-model {@code baseUrl} 与 {@code headers} 投影到请求选项
     * （pi {@code Model.baseUrl/headers} 在车道构造期生效）。baseUrl 缺席 ⇒
     * 保留 CLI/settings 原值；headers 经 {@code extra["headers"]} 交给 SDK client builder。
     */
    private static ApiOptions withPerModelOverrides(ApiOptions options, ModelInfo modelInfo) {
        String baseUrl = modelInfo.baseUrl() != null ? modelInfo.baseUrl() : options.baseUrl();
        var extra = new java.util.LinkedHashMap<String, Object>(options.extra());
        if (modelInfo.headers() != null && !modelInfo.headers().isEmpty()) {
            extra.put("headers", modelInfo.headers());
        }
        return new ApiOptions(baseUrl, options.apiKey(), options.timeout(),
            options.maxRetries(), extra, options.authKind());
    }

    /**
     * 无 sessionId 的重载（包 B103 之前的签名）：等价于会话 id 缺席 ⇒ 不注入亲和通道。
     * 保留给存量调用与夹具。
     */
    public static StreamFn streamFnFor(Args args, String defaultProvider,
                                       ProviderRegistry providers, Settings settings) {
        return streamFnFor(args, defaultProvider, providers, settings, null);
    }

    /**
     * 包 A-01：把 {@link com.pijava.agent.harness.StreamOptions#cacheRetention()} 放进
     * {@code ApiOptions.extra} 的字符串键。
     *
     * <p>车道是**每请求新建**的（{@code provider.createApi(...)} 在 {@code streamBlocking} 里），
     * 所以这里逐请求传值不会与其它请求串味；读取点是
     * {@code AnthropicMessagesApi.retentionOf}（与 {@code ResponsesOptions} 同形）。
     * ⚠️ 键缺席 ≙ pi 的 {@code undefined} ⇒ 车道回落到 {@code PI_CACHE_RETENTION} 与
     * {@code "short"}，<b>不要</b>在这里塞默认值，否则会把环境变量静默屏蔽。</p>
     *
     * <p>包可见是为了夹具（{@code DefaultProvidersTest}）—— 这是「宿主 → 车道」这条
     * 通道在宿主侧的唯一一环，变异掉它（恒返回空 map）应当恰有夹具变红。</p>
     */
    static Map<String, Object> requestExtra(
            com.pijava.agent.harness.StreamOptions options, String sessionId) {
        var extra = new java.util.LinkedHashMap<String, Object>();
        options.cacheRetention().ifPresent(r -> extra.put("cacheRetention", r.wireName()));
        // ⚠️ blank/null 不塞：键缺席 ≙ pi undefined（同 cacheRetention 口径），别塞默认值。
        if (sessionId != null && !sessionId.isBlank()) {
            extra.put("sessionId", sessionId);
        }
        return extra;
    }

    /** 包 B103 之前的夹具形态：只看 cacheRetention（等价于 sessionId 缺席）。 */
    static Map<String, Object> cacheExtra(
            com.pijava.agent.harness.StreamOptions options) {
        return requestExtra(options, null);
    }

    /**
     * 包 A-02（docs/59 §4.6）：{@link ModelInfo#api()} → {@code extra["protocol"]} ——
     * pi {@code compat.ts:262} 的 {@code resolveApiProvider(model.api)} 在本仓的落点
     * （{@code ConfigurableProvider.resolveProtocol} 是读点，会按 provider 的
     * supportedProtocols 校验，不支持则响亮抛）。
     *
     * <p>⚠️ 三态口径与 {@link #cacheExtra} 一致：api 缺席 ⇒ <b>不注入</b>（provider
     * 默认协议照旧），不要在这里塞默认值；extra 已有显式 {@code protocol} ⇒ 不覆盖
     * （显式赢）。包可见是为了夹具。</p>
     */
    static ApiOptions withModelProtocol(ApiOptions options, ModelInfo modelInfo) {
        var api = modelInfo == null ? null : modelInfo.api();
        if (api == null || options.extra().containsKey("protocol")) {
            return options;
        }
        var extra = new java.util.LinkedHashMap<>(options.extra());
        extra.put("protocol", api);
        // 六参构造：authKind 保真（B138 的同族教训）。
        return new ApiOptions(options.baseUrl(), options.apiKey(), options.timeout(),
            options.maxRetries(), extra, options.authKind());
    }

    private static StreamIterator streamBlocking(
            Provider provider,
            ModelId<?> model,
            com.pijava.agent.harness.Context context,
            com.pijava.agent.harness.StreamOptions options,
            ApiOptions apiOptions) {
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
        // D-P1（docs/65）：per-model baseUrl/headers（三源合并的产物）在请求面生效。
        var effectiveOptions = withPerModelOverrides(apiOptions, modelInfo);
        // 包 A-02（docs/59 §4.6）：车道跟着 modelInfo.api 走（pi compat.ts:262）。
        // ⚠️ 必须**先**查 modelInfo 再 createApi —— 派发键在 modelInfo 上，此前 createApi
        // 用的是不含 protocol 的 apiOptions，多协议 provider（openrouter）恒走默认车道。
        var api = provider.createApi(ChatApi.class, withModelProtocol(effectiveOptions, modelInfo));
        var request = new com.pijava.ai.api.StreamRequest(
            modelInfo, context.systemPrompt(), context.messages(),
            ToolRegistry.definitionsOf(context.tools()),
            options.maxTokens().orElse(-1),
            options.temperature().orElse(-1),
            java.util.Map.of(),
            options.reasoning());
        return api.streamBlocking(request, effectiveOptions);
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
        return apiOptions(args, providerName, settings, credentialResolver, Map.of());
    }

    /**
     * 四参形态 ＋ 逐请求的 extra（包 A-01 起）。四参重载保持原签名，使既有调用点零改签。
     *
     * <p>⚠️ {@code extra} 是**与 {@code StreamRequest.extra} 不同的**那条通道：后者在本仓的
     * 生产路径上恒为空（{@code docs/54 §3 F1} 的登记 B107）。</p>
     */
    static ApiOptions apiOptions(Args args, String providerName, Settings settings,
                                 Function<String, Optional<RecordedCredential>> credentialResolver,
                                 Map<String, Object> extra) {
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
        // A-14（G4/G5）：provider 重试设置在 Settings POJO 上按 pi 的 ?? 链现读
        // （settings-manager.ts:966-972）：maxRetries 缺席 ⇒ 0；
        // maxRetryDelayMs ?? 60000 经 extra 过桥；timeoutMs 明给则替换 120s 默认。
        var providerRetry = settings != null && settings.retry != null
            ? settings.retry.provider() : null;
        int providerMaxRetries = providerRetry != null && providerRetry.maxRetries() != null
            ? providerRetry.maxRetries() : 0;
        long maxRetryDelayMs = providerRetry != null && providerRetry.maxRetryDelayMs() != null
            ? providerRetry.maxRetryDelayMs() : 60_000L;
        var mergedExtra = new java.util.LinkedHashMap<String, Object>();
        if (extra != null) {
            mergedExtra.putAll(extra);
        }
        mergedExtra.put("maxRetryDelayMs", maxRetryDelayMs);
        var timeout = providerRetry != null && providerRetry.timeoutMs() != null
            ? java.time.Duration.ofMillis(providerRetry.timeoutMs())
            : java.time.Duration.ofSeconds(120);
        return new ApiOptions(baseUrl == null ? "" : baseUrl,
            apiKey == null ? "" : apiKey,
            timeout, providerMaxRetries, Map.copyOf(mergedExtra), kind);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}

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
        return defaultProviders(null);
    }

    /**
     * {@link #defaultProviders()} ＋ 给内置 provider 包上 pi.dev 远程目录
     * （pi {@code model-runtime.ts:183-190}：radius 之外一律 {@code withRemoteCatalog}）。
     *
     * <p>顺序：内置 → ServiceLoader → models.json 覆盖（**内层**）→ 远程包装
     * （**外层**）。包反了会让远端目录与用户配置互相错位覆盖。</p>
     *
     * @param catalogBaseUrl 远程目录根；null/空 ⇒ {@code https://pi.dev}
     */
    public static ProviderRegistry defaultProviders(String catalogBaseUrl) {
        var registry = ProviderRegistry.create();
        registry.loadBuiltinProviders();
        registry.discoverFromServiceLoader();
        registerModelsJsonProviders(registry);
        wrapRemoteCatalogs(registry, catalogBaseUrl);
        return registry;
    }

    /**
     * 不包远程目录的内置 provider。pi 排除 radius；本仓的等价项是图片 provider ——
     * pi 的图片目录在独立的 {@code images-models.ts}，不对应
     * {@code /api/models/providers/<id>}。
     *
     * <p>第三方 ServiceLoader provider 与 models.json 自定义 provider 也不包：
     * pi 的 {@code withRemoteCatalog} 只套在生成的内置目录上。</p>
     */
    private static final java.util.Set<String> REMOTE_CATALOG_EXCLUDED =
        java.util.Set.of("openrouter-images");

    private static void wrapRemoteCatalogs(ProviderRegistry registry, String catalogBaseUrl) {
        var builtinIds = com.pijava.ai.provider.builtin.ProviderCatalog.all().stream()
            .map(com.pijava.ai.provider.Provider::name)
            .collect(java.util.stream.Collectors.toSet());
        // 整批共用一个 HttpClient（否则一次刷新会拉起 N 个选择器线程）。
        var http = RemoteCatalogProvider.defaultHttpClient();
        var generatedAt = java.util.Optional.of(
            com.pijava.ai.provider.builtin.ModelData.generatedAt());
        for (var provider : registry.listAll()) {
            if (!builtinIds.contains(provider.name())
                    || REMOTE_CATALOG_EXCLUDED.contains(provider.name())) {
                continue;
            }
            registry.register(RemoteCatalogProvider.wrap(
                provider, catalogBaseUrl, generatedAt, http));
        }
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

    /** D-P1：用显式加载的 models.json 注册（夹具用同一代码路径，原 docs/65 §3 Step 4）。 */
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
     * provider 的模型仍用旧适配器发请求（生产事故见 {@code 原 docs/31 §8.31}）。</p>
     *
     * <p>回退（裁决 ①，{@code 原 docs/31 §8.31.7}）：模型的 provider 不在注册表里时
     * （目录里的自定义 id、或未实现协议的 provider），回退到会话 provider 并往
     * stderr 留一行警告 —— 不新增硬失败。baseUrl/apiKey 的解析链原样保留（CLI
     * {@code --base-url} &gt; settings 默认 &gt; 凭据存储），只是按模型 provider 取名。</p>
     */
    public static StreamFn streamFnFor(Args args, String defaultProvider,
                                       ProviderRegistry providers, Settings settings,
                                       String sessionId) {
        return streamFnFor(args, defaultProvider, providers, settings, sessionId, null);
    }

    /**
     * docs/17（B248）：带 provider 请求面桥的完整形态——pi sdk.ts 的四闭包
     * （{@code onPayload}/{@code onResponse}/{@code onProviderStreamEvent} 经 extra
     * 三键下车道、{@code transformHeaders} 在 createApi 前缝消费）对位。
     * {@code bridge} 为 {@code null} ⇒ 零注入（旧行为）。
     */
    public static StreamFn streamFnFor(Args args, String defaultProvider,
                                       ProviderRegistry providers, Settings settings,
                                       String sessionId,
                                       com.pijava.coding.agent.extension.bridge.ProviderEventBridge bridge) {
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
                    requestExtra(options, sessionId)), bridge);
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
     * 包 A-02（原 docs/59 §4.6）：{@link ModelInfo#api()} → {@code extra["protocol"]} ——
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

    /**
     * 批 13（docs/24 T7，pi cache-warmer.ts:332-338 对位）：暖重放专用 {@link StreamFn}——
     * <ol>
     *   <li>{@code maxRetries} 强制 0（pi 展开覆盖 {@code {...options, maxRetries: 0}}；
     *       java 的 maxRetries 在 {@link #apiOptions} 从 settings 现读，需强制通道）；</li>
     *   <li>跳过 {@code ProviderHooks} 三钩（pi 的 onPayload/onResponse/onProviderStreamEvent
     *       是 Agent 级回调，{@code models.streamSimple} 直调不过）；</li>
     *   <li>保留 transformHeaders（pi 暖请求 options 含 buildRequestOptions 烘焙的头变换
     *       闭包 ⇒ before_provider_headers 钩与头合并照走）。</li>
     * </ol>
     */
    public static StreamFn warmStreamFnFor(Args args, String defaultProvider,
                                           ProviderRegistry providers, Settings settings,
                                           String sessionId,
                                           com.pijava.coding.agent.extension.bridge.ProviderEventBridge bridge) {
        var fallbackName = resolveProviderName(args, defaultProvider);
        return (model, context, options) -> {
            var provider = providers.get(model.provider()).orElseGet(() -> {
                System.err.println("[provider] unknown model provider \"" + model.provider()
                    + "\"; falling back to \"" + fallbackName + "\"");
                return providers.get(fallbackName).orElseThrow(
                    () -> new IllegalStateException("Unknown provider: " + fallbackName));
            });
            var resolved = apiOptions(args, model.provider(), settings,
                Credentials::resolveCredential, requestExtra(options, sessionId));
            // ①：六参重建，maxRetries 钉 0（authKind 保真，B138 同族教训）。
            resolved = new ApiOptions(resolved.baseUrl(), resolved.apiKey(),
                resolved.timeout(), 0, resolved.extra(), resolved.authKind());
            return streamBlocking(provider, model, context, options, resolved, bridge, true);
        };
    }

    private static StreamIterator streamBlocking(
            Provider provider,
            ModelId<?> model,
            com.pijava.agent.harness.Context context,
            com.pijava.agent.harness.StreamOptions options,
            ApiOptions apiOptions,
            com.pijava.coding.agent.extension.bridge.ProviderEventBridge bridge) {
        return streamBlocking(provider, model, context, options, apiOptions, bridge, false);
    }

    private static StreamIterator streamBlocking(
            Provider provider,
            ModelId<?> model,
            com.pijava.agent.harness.Context context,
            com.pijava.agent.harness.StreamOptions options,
            ApiOptions apiOptions,
            com.pijava.coding.agent.extension.bridge.ProviderEventBridge bridge,
            boolean warm) {
        // 系统提示与工具定义都来自 Context（pi 的 Context）；它们不再走消息列表或 options。
        //
        // 模型**元数据**（而非只有 id）必须随请求走：pi 的请求构建器拿到整个 Model<TApi>，
        // 从上面读 compat / input / thinkingLevelMap（原 docs/31 §8.34.4 决策 5）。此前只投
        // ModelId ⇒ 目录里的 per-model 开关到不了适配器。目录里查不到时退化为
        // ModelInfo.minimal（compat 缺席 ≡ pi 的 `?? false`，安全方向）。
        //
        // ⚠️ 包H5：`extra["thinking.budgetTokens"]` 那条<b>休眠</b>通道已删 —— 它此前
        // 从未被写入过（门恒假），且 pi 的 reasoning 是**未翻译的级别**、由车道自己翻译
        // （anthropic-messages.ts:858-904）。翻译所需的 compat/thinkingLevelMap 就在
        // modelInfo 上，故随 StreamRequest 一起走。
        // ⚠️ 读 getModels()（当前生效目录）而不是 builtinModels()：远程目录包装
        // （RemoteCatalogProvider）把 overlay 放在 getModels() 上，读 builtinModels()
        // 会让远端补进来的模型解析不到元数据。
        var modelInfo = provider.getModels().stream()
                .filter(candidate -> candidate.id().equals(model))
                .findFirst()
                .orElseGet(() -> ModelInfo.minimal(model));
        // D-P1（原 docs/65）：per-model baseUrl/headers（三源合并的产物）在请求面生效。
        var effectiveOptions = withPerModelOverrides(apiOptions, modelInfo);
        if (bridge != null) {
            // docs/17（B248，pi models.ts:866-871 对位）：合并头集过 transformHeaders
            // 写回 extra["headers"]（钩本体不入 extra——pi :870 剥离键对位）；
            // 随后三钩入 extra，车道构造期经 ProviderHooks 读取。
            // 批 13（T7）：暖路径只过头变换、跳过三钩（pi streamSimple 直调不过
            // Agent 级回调，cache-warmer.ts:332-338）。
            effectiveOptions = bridge.applyHeaderTransform(effectiveOptions);
            if (!warm) {
                effectiveOptions = bridge.withRequestHooks(effectiveOptions);
            }
        }
        // 包 A-02（原 docs/59 §4.6）：车道跟着 modelInfo.api 走（pi compat.ts:262）。
        // ⚠️ 必须**先**查 modelInfo 再 createApi —— 派发键在 modelInfo 上，此前 createApi
        // 用的是不含 protocol 的 apiOptions，多协议 provider（openrouter）恒走默认车道。
        var api = provider.createApi(ChatApi.class, withModelProtocol(effectiveOptions, modelInfo));
        var request = new com.pijava.ai.api.StreamRequest(
            modelInfo, context.systemPrompt(), context.messages(),
            ToolRegistry.definitionsOf(context.tools()),
            options.maxTokens().orElse(-1),
            options.temperature().orElse(-1),
            options.reasoning(),
            // B128：settings.thinkingBudgets 经三层通道到这里（null ≙ undefined）。
            options.thinkingBudgets());
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
     * <p>包 A0 步7（{@code 原 docs/43 D5/D6}）：凭证解析器返回的不再是裸字符串而是
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
     * <p>per-request 选项的唯一通道：{@code StreamRequest} 不再携带 extra
     * （B107/docs/52 删除恒空通道）——呼叫数据在 record 组件上，其余选项在此。</p>
     */
    static ApiOptions apiOptions(Args args, String providerName, Settings settings,
                                 Function<String, Optional<RecordedCredential>> credentialResolver,
                                 Map<String, Object> extra) {
        var baseUrl = firstNonBlank(args.baseUrl(), settings == null ? null : settings.defaultBaseUrl);
        var apiKey = firstNonBlank(args.apiKey(), settings == null ? null : settings.defaultApiKey);
        var kind = AuthKind.API_KEY;
        RecordedCredential credential = null;
        if (apiKey == null && credentialResolver != null) {
            credential = credentialResolver.apply(providerName).orElse(null);
            if (credential != null) {
                apiKey = credential.value();
                kind = credential.kind();
            }
        }
        // A-14（G4/G5）：provider 重试设置在 Settings POJO 上按 pi 的 ?? 链现读
        // （settings-manager.ts:966-972）：maxRetries 缺席 ⇒ 0；
        // maxRetryDelayMs ?? 60000 经 extra 过桥；timeoutMs 明给则替换
        // httpIdleTimeoutMs 有效值（批 11 T4，缺席 300s）。
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
        // #98：federation 的 provider env（五变量）经 extra 过桥到车道（与 headers/
        // cacheRetention 同通道；读点 AnthropicFederationSupport.federationEnvOf）。
        // 三态口径同既有约定：键缺席 ≙ pi 的 undefined，非 federation 不塞。
        if (credential != null && credential.kind() == AuthKind.FEDERATION) {
            mergedExtra.put("federationEnv", credential.env());
        }
        // 批 11 T4（pi sdk.ts:344-354 链序）：provider retry timeoutMs ??
        // httpIdleTimeoutMs 有效值（0 ⇒ 2147483647；缺席 ⇒ 300_000）。java 原 120s
        // 自造兜底删除。
        var timeout = providerRetry != null && providerRetry.timeoutMs() != null
            ? java.time.Duration.ofMillis(providerRetry.timeoutMs())
            : effectiveHttpIdleTimeout(settings);
        return new ApiOptions(baseUrl == null ? "" : baseUrl,
            apiKey == null ? "" : apiKey,
            timeout, providerMaxRetries, Map.copyOf(mergedExtra), kind);
    }

    /** pi sdk.ts:345-346：0/"disabled" ⇒ 2147483647；缺席 ⇒ 300s（非法值随访问器抛）。 */
    private static java.time.Duration effectiveHttpIdleTimeout(Settings settings) {
        long ms = TimeoutSettings.parseTimeoutSetting(
            settings == null ? null : settings.httpIdleTimeoutMs, "httpIdleTimeoutMs")
            .orElse(TimeoutSettings.DEFAULT_HTTP_IDLE_TIMEOUT_MS);
        return java.time.Duration.ofMillis(ms == 0 ? Integer.MAX_VALUE : ms);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}

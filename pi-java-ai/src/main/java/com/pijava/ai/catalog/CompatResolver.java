package com.pijava.ai.catalog;

import java.util.Locale;
import java.util.Optional;

/**
 * pi 的五份 per-api compat 解析函数在 java 上的**合一**实现 ——
 * {@code detectCompat}/{@code getCompat}（{@code api/openai-completions.ts:1583}/{@code :1685}）、
 * {@code getCompat}（{@code api/openai-responses.ts:68}）、
 * {@code getAnthropicCompat}（{@code api/anthropic-messages.ts:206}）、
 * mistral 的直读（{@code api/mistral-conversations.ts:130}）。
 *
 * <p><b>为什么能合一</b>：pi 有五个接口是因为各车道的<b>字段集</b>不同；java 把五者并成了
 * {@link ModelCompat} 一个类型（{@code docs/53 §2 P8}），而逐字段核对的结果是
 * <b>除 {@code supportsMidConvoSystemMessages} 外每个字段只被一条车道读</b>，
 * 而那一个在四条车道上的缺省<b>同为 {@code false}</b> ⇒ 合一安全。</p>
 *
 * <p><b>形状</b>：pi 是「车道的请求构建函数在入口处调一次 {@code getCompat(model)}，
 * 拿到全字段确定的形状」；本类就是那个调用的落点。每个方法只负责<b>本车道定义的</b>字段，
 * 其余组件原样透传（可能仍是 {@code null}）—— 而那是安全的，因为没有别的车道会读它。</p>
 *
 * <p>⚠️ <b>解析点只能在请求期</b>：pi 的 {@code detectCompat} 读 {@code model.baseUrl}，
 * 而 {@link ModelInfo} <b>不带</b> baseUrl（provider 在 {@link com.pijava.ai.model.ModelId} 上）
 * ⇒ baseUrl 只能由车道给。⚠️ 由此产生一处**刻意的形状偏差**：pi 的探测只认
 * {@code model.baseUrl}、<b>不</b>吃请求期覆盖，而本仓的 {@code baseUrl} 是
 * {@code ApiOptions.baseUrl()} 覆盖后的<b>有效值</b>（{@code docs/53 §9 R2}）。对内置模型
 * 两者同值、不可观察；只有「models.json 的 baseUrl」与「请求期 --base-url」并存时才可见
 * （登记为 {@code docs/32 B100}）。</p>
 *
 * <p>⚠️ <b>没有 {@code bedrock} / {@code google} / {@code pi-messages} 的方法</b>：
 * pi 的 {@code BedrockCompat} 只有一个 java 不携带的字段，google 与 pi-messages 连 compat
 * 接口都没有 ⇒ 那几条车道的读点不需要解析（它们读的字段全是二态常量）。</p>
 *
 * @see MaxTokensField
 */
public final class CompatResolver {

    private CompatResolver() {
    }

    /**
     * openai-completions 车道：pi {@code detectCompat:1583-1679} ＋ {@code getCompat:1685-1721}。
     *
     * <p>本车道是 pi 唯一有真探测的一条（十四个 provider/baseUrl 谓词）。java 携带的四个
     * 被探测字段全部落在这里；其余（如 {@code thinkingFormat}/{@code supportsReasoningEffort}）
     * 归 A-09 等包（{@code docs/53 §4.4}）。</p>
     *
     * @param model   目标模型，{@code null} 表示「无模型上下文」（本仓的
     *                {@code StreamRequest.model} 允许为空）—— 此时探测仍按 baseUrl 走，
     *                覆盖源退化为 {@link ModelCompat#NONE}
     * @param baseUrl 车道的有效 base URL（{@code null} ≙ 无）
     * @return 探测与覆盖合一后的 compat（本车道的读点全部非空）
     */
    public static ModelCompat forCompletions(ModelInfo model, String baseUrl) {
        var compat = base(model);
        var url = baseUrl == null ? "" : baseUrl;
        var provider = provider(model);
        var modelName = model == null || model.id() == null ? "" : model.id().modelName();

        // pi detectCompat:1587-1601 —— 十个布尔量，逐个照抄（含 `provider === "xai"` 那类
        // 以 provider 名义命中的分支，本仓的 ProviderCatalog 里确实有这些 provider）。
        var isZai = provider.equals("zai") || provider.equals("zai-coding-cn")
            || url.contains("api.z.ai") || url.contains("open.bigmodel.cn");
        var isTogether = provider.equals("together")
            || url.contains("api.together.ai") || url.contains("api.together.xyz");
        var isMoonshot = provider.equals("moonshotai") || provider.equals("moonshotai-cn")
            || url.contains("api.moonshot.");
        var isOpenRouter = provider.equals("openrouter") || url.contains("openrouter.ai");
        var isCloudflareWorkersAi = provider.equals("cloudflare-workers-ai")
            || url.contains("api.cloudflare.com");
        var isCloudflareAiGateway = provider.equals("cloudflare-ai-gateway")
            || url.contains("gateway.ai.cloudflare.com");
        var isNvidia = provider.equals("nvidia") || url.contains("integrate.api.nvidia.com");
        var isAntLing = provider.equals("ant-ling") || url.contains("api.ant-ling.com");
        var isCerebras = provider.equals("cerebras") || url.contains("cerebras.ai");
        var isDeepSeek = provider.equals("deepseek")
            || url.toLowerCase(Locale.ROOT).contains("deepseek.com");

        var isNonStandard = isNvidia || isCerebras || provider.equals("xai")
            || url.contains("api.x.ai") || isTogether || url.contains("chutes.ai")
            || isDeepSeek || isZai || isMoonshot || provider.equals("opencode")
            || url.contains("opencode.ai") || isCloudflareWorkersAi || isCloudflareAiGateway
            || isAntLing;
        var useMaxTokens = url.contains("chutes.ai") || isDeepSeek || isMoonshot
            || isCloudflareAiGateway || isTogether || isNvidia || isAntLing || isZai;
        // detectCompat:1630 —— 判据**同时**看 provider 与模型 id 前缀（OpenRouter 上只有
        // anthropic/* 与 openai/* 走 developer 角色）。
        var isOpenRouterDeveloperRoleModel = isOpenRouter
            && (modelName.startsWith("anthropic/") || modelName.startsWith("openai/"));

        return resolved(compat,
            isDeepSeek,
            Boolean.FALSE,
            Boolean.FALSE,
            null,
            null,
            null,
            useMaxTokens ? MaxTokensField.MAX_TOKENS : MaxTokensField.MAX_COMPLETION_TOKENS,
            !isNonStandard,
            isOpenRouterDeveloperRoleModel || (!isNonStandard && !isOpenRouter),
            // ⚠️ completions 车道的两个 cache 门（`cacheControlFormat` /
            // `supportsLongCacheRetention` 的 completions 面）不在包 A-01 范围 —— 它们
            // 只在 OpenRouter ＋ `anthropic/*` 上生效，而本仓没有 OpenRouter chat 车道
            // ⇒ **A-02 之前不可达**（docs/54 §1.2、docs/32 B105 的登记）。
            null,
            null,
            null,
            // 包 A-10：两个预算字段的**探测值**照 pi 的 `detectCompat:1662-1663` 原样
            // —— `supportsThinkingTokenBudget: false` / `thinkingTokenBudgetField:
            // undefined`（pi 的注释明写「not set on the generated catalog」）⇒
            // 内置目录一个都不标，只有 models.json 显式覆盖可达。
            null,
            Boolean.FALSE,
            // 包 A-10 第 6 步：`supportsMaxOutputTokens` 只被 **Responses** 车道读
            // （pi `openai-responses.ts:79`；azure 那份副本连读点都没有）⇒ 本车道不定义它
            // ⇒ 原样透传模型的显式取值（本车道的任何读点都不会碰它）。
            null);
    }

    /**
     * anthropic-messages 车道：pi {@code getAnthropicCompat:206-219}。
     *
     * <p>⚠️ 本车道<b>没有 URL 探测</b>：那条 {@code isOpenRouter} 只影响
     * {@code sendSessionAffinityHeaders}/{@code sessionAffinityFormat}，两个 java 都不携带
     * ⇒ 本方法只做「两个 mid-convo 标志的 {@code ?? false}」。</p>
     *
     * <p>包 A-01 另加两个 cache 相关的 {@code ?? true}（{@code :212-213}）：两者 pi 的缺省
     * 都是真，且**只**被本车道读。</p>
     */
    public static ModelCompat forAnthropic(ModelInfo model) {
        return resolved(base(model), null, Boolean.FALSE, null, Boolean.FALSE,
            null, null, null, null, null, null,
            Boolean.TRUE, Boolean.TRUE, null, null, null);
    }

    /**
     * Responses 车道（openai-responses / azure-openai-responses / openai-codex-responses）：
     * pi {@code openai-responses.ts:68-81}。
     *
     * <p>⚠️ {@code supportsStrictMode} 的缺省<b>随车道相反</b>（responses {@code false}、
     * azure {@code true}，{@code docs/50 §12} 的 B88 记录）⇒ 由 {@code strictModeDefault}
     * 形参给，而不是写死在这里。</p>
     */
    public static ModelCompat forResponses(ModelInfo model, boolean strictModeDefault) {
        return resolved(base(model), null, Boolean.FALSE, null, null,
            Boolean.FALSE, Boolean.FALSE, null, null, null, strictModeDefault,
            null, null, null, null,
            // 包 A-10 第 6 步：pi `openai-responses.ts:79` 的 `?? true`（types.ts:773-774
            // 的注释：「某些 Codex 协议网关会拒绝 max_output_tokens」）。
            // ⚠️ azure 车道共用本方法 ⇒ 它拿到的也是这一份，但**它不读这个组件**
            // （pi 的 azure 副本连门都没有）⇒ 该值在 azure 上不可观察。
            Boolean.TRUE);
    }

    /**
     * mistral-conversations 车道：pi <b>没有</b>解析函数，直接读 partial
     * （{@code mistral-conversations.ts:130} 的 {@code model.compat?.supportsMidConvoSystemMessages}）
     * ⇒ 等价于 {@code ?? false}。这里补成显式的解析，使四条车道的读点形状一致。
     */
    public static ModelCompat forMistral(ModelInfo model) {
        return resolved(base(model), null, Boolean.FALSE, null, null,
            null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * anthropic 车道的**缓存断点**：pi {@code getCacheControl}（{@code anthropic-messages.ts:69-83}）
     * 与 {@code resolveCacheRetention}（{@code :60-67}）的合一。
     *
     * <pre>
     * retention = 选项 ?? (PI_CACHE_RETENTION === "long" ? "long" : "short")
     * none       → 无 cacheControl（Optional.empty）
     * long       → ttl = supportsLongCacheRetention ? "1h" : 无
     * short      → 无 ttl
     * </pre>
     *
     * <p>⚠️ <b>两处判据都不做「宽松化」</b>：① {@code PI_CACHE_RETENTION} 与 pi 的
     * {@code === "long"} 逐字对应 —— <b>只认字面 {@code "long"}</b>，大小写敏感、不 trim，
     * {@code "long "} / {@code "Long"} / {@code "1h"} 一律落到 {@code short}（实测 P4b）；
     * ② {@code supportsLongCacheRetention} 的缺省是 <b>{@code true}</b>（pi 的 {@code ?? true}）
     * —— 与 {@code NONE} 的「用户没写 compat」是两回事，别把缺省写成假。</p>
     *
     * @param model        目标模型（{@code null} 时按缺省走：断点照发、无 ttl）
     * @param requested    请求期选项（{@code Optional.empty()} ≙ pi 的 {@code undefined}）
     * @param envRetention {@code PI_CACHE_RETENTION} 的**原始**取值（{@code null} ≙ 未设）
     * @return 断点规格；{@link Optional#empty()} ≙ pi 的 {@code cacheRetention:"none"}
     */
    public static Optional<CacheBreakpointSpec> anthropicCacheControl(
            ModelInfo model,
            Optional<CacheRetention> requested,
            String envRetention) {
        var retention = requested.orElseGet(
            () -> "long".equals(envRetention) ? CacheRetention.LONG : CacheRetention.SHORT);
        if (retention == CacheRetention.NONE) {
            return Optional.empty();
        }
        var compat = forAnthropic(model);
        var oneHour = retention == CacheRetention.LONG
            && Boolean.TRUE.equals(compat.supportsLongCacheRetention());
        return Optional.of(new CacheBreakpointSpec(oneHour));
    }

    // ── 内部 ────────────────────────────────────────────────────

    private static ModelCompat base(ModelInfo model) {
        return model == null || model.compat() == null ? ModelCompat.NONE : model.compat();
    }

    private static String provider(ModelInfo model) {
        return model == null || model.id() == null ? "" : model.id().provider();
    }

    /**
     * 把「本车道的探测结果」与模型上已有的覆盖合起来 —— pi 的
     * {@code explicit ?? detected}（{@code getCompat:1685-1721} 的每一行）。
     *
     * @param detected 本车道的探测值；{@code null} 表示<b>本车道不定义这个字段</b>
     *                 （pi 的相应接口里没有它）⇒ 原样透传模型的覆盖
     */
    private static ModelCompat resolved(ModelCompat c,
                                        Boolean reasoningContentRequired,
                                        Boolean midConvoSystemMessages,
                                        Boolean midConvoToolAdditions,
                                        Boolean midConvoToolChanges,
                                        Boolean additionalTools,
                                        Boolean toolSearch,
                                        MaxTokensField maxTokensField,
                                        Boolean store,
                                        Boolean developerRole,
                                        Boolean strictMode,
                                        Boolean longCacheRetention,
                                        Boolean cacheControlOnTools,
                                        ThinkingTokenBudgetField thinkingTokenBudgetField,
                                        Boolean supportsThinkingTokenBudget,
                                        Boolean supportsMaxOutputTokens) {
        return new ModelCompat(
            c.allowEmptySignature(),
            pick(c.requiresReasoningContentOnAssistantMessages(), reasoningContentRequired),
            c.supportsFinishReason(),
            c.forceAdaptiveThinking(),
            pick(c.supportsMidConvoSystemMessages(), midConvoSystemMessages),
            pick(c.supportsMidConvoToolAdditions(), midConvoToolAdditions),
            pick(c.supportsMidConvoToolChanges(), midConvoToolChanges),
            pick(c.supportsAdditionalTools(), additionalTools),
            pick(c.supportsToolSearch(), toolSearch),
            c.supportsTemperature(),
            c.maxTokensField() != null ? c.maxTokensField() : maxTokensField,
            pick(c.supportsStore(), store),
            pick(c.supportsDeveloperRole(), developerRole),
            pick(c.supportsStrictMode(), strictMode),
            pick(c.supportsLongCacheRetention(), longCacheRetention),
            pick(c.supportsCacheControlOnTools(), cacheControlOnTools),
            c.thinkingTokenBudgetField() != null
                ? c.thinkingTokenBudgetField() : thinkingTokenBudgetField,
            pick(c.supportsThinkingTokenBudget(), supportsThinkingTokenBudget),
            pick(c.supportsMaxOutputTokens(), supportsMaxOutputTokens));
    }

    private static Boolean pick(Boolean explicit, Boolean detected) {
        return explicit != null ? explicit : detected;
    }
}

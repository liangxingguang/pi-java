package com.pijava.ai.catalog;

import java.util.Locale;

/**
 * pi 的五份 per-api compat 解析函数在 java 上的**合一**实现 ——
 * {@code detectCompat}/{@code getCompat}（{@code api/openai-completions.ts:1583}/{@code :1685}）、
 * {@code getCompat}（{@code api/openai-responses.ts:68}）、
 * {@code getAnthropicCompat}（{@code api/anthropic-messages.ts:208}）、
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
     * openai-completions 车道：pi {@code detectCompat:1583-1670} ＋ {@code getCompat:1685-1721}。
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
        // detectCompat:1633 —— 判据**同时**看 provider 与模型 id 前缀（OpenRouter 上只有
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
            null);
    }

    /**
     * anthropic-messages 车道：pi {@code getAnthropicCompat:208-219}。
     *
     * <p>⚠️ 本车道<b>没有 URL 探测</b>：那条 {@code isOpenRouter} 只影响
     * {@code sendSessionAffinityHeaders}/{@code sessionAffinityFormat}，两个 java 都不携带
     * ⇒ 本方法只做「两个 mid-convo 标志的 {@code ?? false}」。</p>
     */
    public static ModelCompat forAnthropic(ModelInfo model) {
        return resolved(base(model), null, Boolean.FALSE, null, Boolean.FALSE,
            null, null, null, null, null, null);
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
            Boolean.FALSE, Boolean.FALSE, null, null, null, strictModeDefault);
    }

    /**
     * mistral-conversations 车道：pi <b>没有</b>解析函数，直接读 partial
     * （{@code mistral-conversations.ts:130} 的 {@code model.compat?.supportsMidConvoSystemMessages}）
     * ⇒ 等价于 {@code ?? false}。这里补成显式的解析，使四条车道的读点形状一致。
     */
    public static ModelCompat forMistral(ModelInfo model) {
        return resolved(base(model), null, Boolean.FALSE, null, null,
            null, null, null, null, null, null);
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
                                        Boolean strictMode) {
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
            pick(c.supportsStrictMode(), strictMode));
    }

    private static Boolean pick(Boolean explicit, Boolean detected) {
        return explicit != null ? explicit : detected;
    }
}

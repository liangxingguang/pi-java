package com.pijava.ai.catalog;

import java.util.List;
import java.util.Map;

import com.pijava.ai.model.PricingInfo;

/**
 * pi 的**生成期**目录 compat 规则（{@code scripts/generate-models.ts}）里，与内置目录
 * **代码推导**相关的那几条 —— 逐字照抄谓词，而不是把生成出来的 JSON 值硬编进目录。
 *
 * <p><b>为什么要照抄谓词</b>（包 A7 的裁决 {@code 原 docs/53 §9 R3}）：pi 的目录数据
 * （{@code src/providers/data/*.json}）**不在仓库里**（gitignore，由生成器从 models.dev 抓取后
 * 算出），但它的 compat 里有相当一部分**不是远端数据、而是代码**：下面这四条全是
 * {@code (provider, modelId)} 的纯字符串函数。照抄谓词 ⇒ models.dev 漂移时 java 的行为
 * **只随 pi 的代码变**，与 pi 的变更点一一对应；硬编值则会静默陈旧（{@code deepseek-v4-flash}
 * 那次改名就是教训，{@code 原 docs/53 §3 F5}）。</p>
 *
 * <p>⚠️ <b>大小写与匹配方式必须逐个照抄</b>，pi 三处谓词并不统一：
 * {@code supportsAnthropicMidConvoSystemMessages} 用**正则**且**不**小写化；
 * {@code isAnthropicAdaptiveThinkingModel} 用 {@code includes} 且**不**小写化；
 * {@code isAnthropicTemperatureUnsupportedModel} 用 {@code includes} 且**先小写化**。
 * 把三者统一成一种写法会**扩大或缩小**命中集（例如把正则改成 {@code includes} 会让
 * {@code claude-opus-4-80} 这类 id 误命中）。</p>
 *
 * <p>⚠️ 本类只承载 java 真正**携带**的标志（{@link ModelCompat} 的组件）；pi 生成器给这些模型
 * 写的其余键（{@code supportsStrictTools}、{@code allowedFallbackModels}…）按
 * {@code 原 docs/53 §4.4} 的归属表留给各自的包。</p>
 */
public final class CatalogCompatRules {

    private CatalogCompatRules() {
    }

    /**
     * Anthropic 车道的目录标注 —— pi {@code getAnthropicMessagesCompat:1142-1170}
     * （只看与 java 携带字段有关的三个分支）。
     *
     * <p>⚠️ 中间两条都对 {@code api === "anthropic-messages"} 有门（{@code generate-models.ts}
     * 的调用点 {@code :1039}/{@code :1043}），而本方法<b>只被 anthropic 目录调用</b>
     * ⇒ 那道门由调用方承担。</p>
     *
     * @param provider pi 的 provider 名（中间那条要求它恰是 {@code "anthropic"}）
     * @param modelId  pi 的模型 id（**原样**，谓词自己决定要不要小写化）
     * @return 该模型应标注的 compat；无命中时返回 {@link ModelCompat#NONE}
     */
    public static ModelCompat anthropic(String provider, String modelId) {
        var id = modelId == null ? "" : modelId;
        var midConvo = "anthropic".equals(provider) && supportsMidConvoSystemMessages(id);
        var partial = new ModelCompat(
            false,
            null,
            true,
            isAdaptiveThinkingModel(id),
            // :1152-1154 —— provider 是 anthropic 时**两个一起给**（tool changes 随 system messages）。
            midConvo ? Boolean.TRUE : null,
            null,
            midConvo ? Boolean.TRUE : null,
            null,
            null,
            !isTemperatureUnsupportedModel(id),
            null, null, null, null);
        // 原 docs/66：pi generate-models.ts:826-828 对全部 anthropic-messages 模型无条件
        // merge supportsStrictTools:true（与 id 无关）。
        // B98（docs/51）：新锚点 data/anthropic.json 里 claude-fable-5 是唯一带
        // allowedFallbackModels 的模型 —— 服务端拒绝回退到 opus-4-8/opus-5（beta
        // server-side-fallback-2026-07-01）；成本是本地元数据，逐字照抄。
        List<AllowedFallbackModel> fallbacks =
            "anthropic".equals(provider) && "claude-fable-5".equals(id)
                ? List.of(
                    new AllowedFallbackModel("anthropic", "claude-opus-4-8",
                        new PricingInfo(5, 25, 0.5, 6.25, List.of())),
                    new AllowedFallbackModel("anthropic", "claude-opus-5",
                        new PricingInfo(5, 25, 0.5, 6.25, List.of())))
                : null;
        return withStrictTools(partial, "anthropic".equals(provider), fallbacks);
    }

    /** Copy a compat, overriding {@code supportsStrictTools} and the fallback list. */
    private static ModelCompat withStrictTools(ModelCompat c, boolean supportsStrictTools,
                                               List<AllowedFallbackModel> fallbacks) {
        return new ModelCompat(
            c.allowEmptySignature(), c.requiresReasoningContentOnAssistantMessages(),
            c.supportsFinishReason(), c.forceAdaptiveThinking(),
            c.supportsMidConvoSystemMessages(), c.supportsMidConvoToolAdditions(),
            c.supportsMidConvoToolChanges(), c.supportsAdditionalTools(),
            c.supportsToolSearch(), c.supportsTemperature(), c.maxTokensField(),
            c.supportsStore(), c.supportsDeveloperRole(), c.supportsStrictMode(),
            c.supportsLongCacheRetention(), c.supportsCacheControlOnTools(),
            c.thinkingTokenBudgetField(), c.supportsThinkingTokenBudget(),
            c.supportsMaxOutputTokens(), c.thinkingFormat(), c.chatTemplateKwargs(),
            c.chatTemplateArgs(), c.supportsReasoningEffort(), c.cacheControlFormat(),
            c.openRouterRouting(), c.sendSessionAffinityHeaders(),
            c.sessionAffinityFormat(), supportsStrictTools,
            c.supportsOpenAIGrammarTools(),
            // 包 09：逐组件重建的地方**必须**带上新字段，否则会静默丢成缺省
            // （旧 29 参调用点现在落到便捷构造上）。
            c.supportsEagerToolInputStreaming(), c.supportsExplicitPromptCacheMode(),
            // B98：逐组件重建，八字段原样透传（fallback 是 anthropic 专属也照传，
            // 本方法只被 anthropic 目录调用）。
            c.requiresToolResultName(), c.requiresAssistantAfterToolResult(),
            c.requiresThinkingAsText(), c.supportsUsageInStreaming(),
            c.zaiToolStream(), c.vllmPriority(), c.vercelGatewayRouting(),
            fallbacks != null ? fallbacks : c.allowedFallbackModels());
    }

    /**
     * completions 车道的目录标注 —— pi {@code applyOpenAICompletionsTranscriptMetadata:875-900}
     * ＋ 包 A-09 的 per-provider {@code thinkingFormat} 常量
     * （{@code generate-models.ts:1416/:2426/:2476/:2533/:2784/:2874}）。
     *
     * <p>⚠️ mid-convo 那条规则**逐 id 写死**：{@code provider === "deepseek" && model.id ===
     * "deepseek-v4-pro"} 走 {@code isTextOnly}（只得 {@code supportsMidConvoSystemMessages}），
     * 而同族的 {@code deepseek-flash} **不在**名单里 —— pi 自己的测试把这一点钉成了断言
     * （{@code test/providers.test.ts} 的 {@code supported}/{@code unsupported} 两份列表，
     * 锚点 26/26 绿）。</p>
     *
     * <p>{@code thinkingFormat} 的两类来源（{@code 原 docs/58 §2.5(b)}）：<b>探测给不出的</b>
     * （moonshotai／xiaomi／qwen-token-plan —— 探测会把它们误判成 {@code openai}）是
     * **必要标注**；探测已给同值的（zai／deepseek／ant-ling）是**冗余照抄** —— pi 的目录
     * 照写 ⇒ 这里也照写。⚠️ together／baseten／opencode／fireworks 的常量 java 不携带
     * 那些 provider ⇒ 只落代码路径、不落目录（{@code 原 docs/58 §9} 登记）。</p>
     *
     * <p>其余端点属性（{@code maxTokensField}／{@code supportsStore}／
     * {@code supportsDeveloperRole}）**不在这里标注**：它们在 pi 的目录里是**探测的差量**，
     * 而 {@link CompatResolver#forCompletions} 在请求期会算出同一个值。⚠️ 例外：
     * {@code requiresReasoningContentOnAssistantMessages} 对 xiaomi 全族由目录标注
     * （探测的 {@code isDeepSeek} 不认 xiaomi）——B129 已落（{@code docs/50}）。</p>
     */
    public static ModelCompat completions(String provider, String modelId) {
        var isNativeDeepSeekPro =
            "deepseek".equals(provider) && "deepseek-v4-pro".equals(modelId);
        var format = thinkingFormatOf(provider);
        if (format == null && !isNativeDeepSeekPro) {
            return ModelCompat.NONE;
        }
        // ⚠️ zai 的 supportsReasoningEffort 在 pi 是**数据驱动**的（generate-models.ts:1396
        //    `thinkingLevelMap !== undefined`）—— java 的内置条目今天没有级别表 ⇒ 不标
        //    （null），由探测（isZai ⇒ false）兜底 ⇒ 与 pi 的有效值 false 等价；若日后
        //    补上级别表，判据应改成 `!thinkingLevelMap.isEmpty()`（原 docs/58 §9 登记）。
        //    qwen-token-plan 的 true 是 pi 的明文常量（generate-models.ts:2537，冗余照抄）。
        var supportsEffort = "qwen-token-plan-cn".equals(provider) ? Boolean.TRUE : null;
        // B129：xiaomi 全族目录都标 requiresReasoningContentOnAssistantMessages=true
        // （新锚点 providers/data/xiaomi*.json 实测；探测给不出——isDeepSeek 只认
        // provider "deepseek" 与 deepseek.com）。前缀同时覆盖 ams/sgp 两个 token-plan。
        var requiresReasoningContent =
            "xiaomi".equals(provider) || provider.startsWith("xiaomi-token-plan")
                ? Boolean.TRUE : null;
        // B98（docs/51）：zaiToolStream —— 新锚点 providers/data/zai.json 与
        // zai-coding-cn.json 全部 11 条模型都标 true（与具体 id 无关），探测给 false。
        var zaiToolStream = ("zai".equals(provider) || "zai-coding-cn".equals(provider))
            ? Boolean.TRUE : null;
        return new ModelCompat(
            false,
            requiresReasoningContent,
            true,
            false,
            // :875-900 —— deepseek-v4-pro 是 java 携带集里唯一拿 mid-convo 标志的 id。
            isNativeDeepSeekPro ? Boolean.TRUE : null,
            null, null, null, null,
            true,
            null, null, null, null,
            null, null, null, null, null,
            format, Map.of(), Map.of(), supportsEffort,
            // 24-31：cacheControl/routing/affinity 缺席；strictTools 恒 false（与
            // 旧 29 参便捷构造的委托同形）；grammar/eager/explicit 缺席。
            null, null, null, null, false, null, null, null,
            // 32-39 B98：toolResult name/插桥/thinking-as-text/usage 走探测缺省，
            // vllm/vercel 显式-only；fallback 是 anthropic 专属，本车道恒 null。
            null, null, null, null, zaiToolStream, null, null, null);
    }

    /**
     * openai-responses 车道的目录标注 —— pi {@code applyStrictToolCompatMetadata}
     * （{@code generate-models.ts:821-825}）：provider 为 openai 的 responses 模型
     * 无条件写 {@code supportsStrictMode:true}。api（responses）门由调用方承担；
     * 非 openai provider 返回 {@link ModelCompat#NONE}。
     *
     * <p>原 docs/69（pi {@code applyOpenAIGrammarToolCompatMetadata:831-854}）：gpt-5+
     * （{@code /^gpt-(\d+)/} 组 ≥5）再置 {@code supportsOpenAIGrammarTools:true}。</p>
     */
    public static ModelCompat openaiResponses(String provider, String modelId) {
        if (!"openai".equals(provider)) {
            return ModelCompat.NONE;
        }
        var strict = new ModelCompat(false, null, true, false,
            null, null, null, null, null, true,
            null, null, null, Boolean.TRUE);
        return withGrammarTools(strict, isGpt5OrLater(modelId) ? Boolean.TRUE : null);
    }

    /** Copy a compat, overriding only {@code supportsOpenAIGrammarTools}. */
    private static ModelCompat withGrammarTools(ModelCompat c,
                                                 Boolean supportsOpenAIGrammarTools) {
        return new ModelCompat(
            c.allowEmptySignature(), c.requiresReasoningContentOnAssistantMessages(),
            c.supportsFinishReason(), c.forceAdaptiveThinking(),
            c.supportsMidConvoSystemMessages(), c.supportsMidConvoToolAdditions(),
            c.supportsMidConvoToolChanges(), c.supportsAdditionalTools(),
            c.supportsToolSearch(), c.supportsTemperature(), c.maxTokensField(),
            c.supportsStore(), c.supportsDeveloperRole(), c.supportsStrictMode(),
            c.supportsLongCacheRetention(), c.supportsCacheControlOnTools(),
            c.thinkingTokenBudgetField(), c.supportsThinkingTokenBudget(),
            c.supportsMaxOutputTokens(), c.thinkingFormat(), c.chatTemplateKwargs(),
            c.chatTemplateArgs(), c.supportsReasoningEffort(), c.cacheControlFormat(),
            c.openRouterRouting(), c.sendSessionAffinityHeaders(),
            c.sessionAffinityFormat(), c.supportsStrictTools(),
            supportsOpenAIGrammarTools,
            c.supportsEagerToolInputStreaming(), c.supportsExplicitPromptCacheMode(),
            // B98：八字段原样透传。
            c.requiresToolResultName(), c.requiresAssistantAfterToolResult(),
            c.requiresThinkingAsText(), c.supportsUsageInStreaming(),
            c.zaiToolStream(), c.vllmPriority(), c.vercelGatewayRouting(),
            c.allowedFallbackModels());
    }

    /** pi {@code generate-models.ts:834} 谓词：{@code /^gpt-(\d+)/} 组 ≥5。 */
    private static boolean isGpt5OrLater(String modelId) {
        var matcher = java.util.regex.Pattern.compile("^gpt-(\\d+)")
            .matcher(modelId == null ? "" : modelId);
        return matcher.find() && Integer.parseInt(matcher.group(1)) >= 5;
    }

    /**
     * openai-responses 车道的 xai 目录标注 —— pi {@code XAI_RESPONSES_COMPAT}
     * （{@code generate-models.ts:472-474}）＋ 应用点 {@code :2053}：<b>全部</b> xai
     * responses 模型**无条件**得 {@code supportsLongCacheRetention:false}（与 id 无关）。
     * 非 xai provider 返回 {@link ModelCompat#NONE}。
     *
     * <p>动机：xAI 的 prompt cache 不走 OpenAI 的 {@code prompt_cache_retention:"24h"}
     * 长缓存语义（{@code openai-responses.ts:101}）⇒ 该位为假后，responses 车道在
     * {@code cacheRetention=="long"} 时**不发**那个键。java 侧读点
     * （{@code ResponsesMessageConverter.applyCacheRetention} 的 {@code longOk}）
     * 已就位 ⇒ 本规则一落，行为即刻对齐。</p>
     */
    public static ModelCompat xaiResponses(String provider) {
        if (!"xai".equals(provider)) {
            return ModelCompat.NONE;
        }
        // 23 参便捷构造，位置 15（supportsLongCacheRetention）显式 false；其余保持缺席
        // （探测/车道缺省由 CompatResolver.forResponses 补 —— 那里这一位传的是 null）。
        return new ModelCompat(false, null, true, false, null, null, null, null, null, true,
            null, null, null, null, Boolean.FALSE, null, null, null, null, null,
            Map.of(), Map.of(), null);
    }

    /**
     * pi {@code generate-models.ts} 的 per-provider {@code thinkingFormat} 常量表 ——
     * 只列 java **携带**的 provider（{@code 原 docs/58 §2.5(b)}）。
     */
    private static ThinkingFormat thinkingFormatOf(String provider) {
        if (provider == null) {
            return null;
        }
        return switch (provider) {
            case "zai", "zai-coding-cn" -> ThinkingFormat.ZAI;
            case "moonshotai", "moonshotai-cn", "xiaomi", "xiaomi-token-plan-cn" ->
                ThinkingFormat.DEEPSEEK;
            case "qwen-token-plan-cn" -> ThinkingFormat.QWEN;
            case "deepseek" -> ThinkingFormat.DEEPSEEK;
            case "ant-ling" -> ThinkingFormat.ANT_LING;
            // 包 A-02：pi 生成器给 openrouter 的**全部** completions 条目写
            // thinkingFormat:"openrouter"（生成数据实测；探测给同值 ⇒ 冗余照抄，
            // 与 zai/deepseek/ant-ling 同口径）。A-09 时 java 不携带该 provider，
            // 常量表没有这一臂（原 docs/58 §9 的「不携带 provider 常量」登记，B132 家族）。
            case "openrouter" -> ThinkingFormat.OPENROUTER;
            default -> null;
        };
    }

    // ── 照抄的谓词（generate-models.ts:578/585/604）──────────────

    /**
     * pi {@code supportsAnthropicMidConvoSystemMessages:578-583} —— 正则、**不**小写化。
     *
     * <pre>{@code /^claude-opus-(?:4[.-]8|5)(?:-\d{8})?$/  ||  /^claude-(?:fable|mythos)-5(?:[.-]1)?(?:-\d{8})?$/}</pre>
     */
    private static boolean supportsMidConvoSystemMessages(String modelId) {
        return modelId.matches("^claude-opus-(?:4[.-]8|5)(?:-\\d{8})?$")
            || modelId.matches("^claude-(?:fable|mythos)-5(?:[.-]1)?(?:-\\d{8})?$");
    }

    /**
     * pi {@code isAnthropicAdaptiveThinkingModel:585-602} —— {@code includes} 串、**不**小写化。
     */
    private static boolean isAdaptiveThinkingModel(String modelId) {
        return modelId.contains("opus-4-6") || modelId.contains("opus-4.6")
            || modelId.contains("opus-4-7") || modelId.contains("opus-4.7")
            || modelId.contains("opus-4-8") || modelId.contains("opus-4.8")
            || modelId.contains("opus-5") || modelId.contains("opus.5")
            || modelId.contains("sonnet-4-6") || modelId.contains("sonnet-4.6")
            || modelId.contains("sonnet-5") || modelId.contains("sonnet.5")
            || modelId.contains("fable-5") || modelId.contains("mythos-5");
    }

    /**
     * pi {@code isAnthropicTemperatureUnsupportedModel:604-614} —— {@code includes} 串、**先**小写化。
     */
    private static boolean isTemperatureUnsupportedModel(String modelId) {
        var id = modelId.toLowerCase(java.util.Locale.ROOT);
        return id.contains("opus-4-7") || id.contains("opus-4.7")
            || id.contains("opus-4-8") || id.contains("opus-4.8")
            || id.contains("opus-5") || id.contains("opus.5");
    }
}

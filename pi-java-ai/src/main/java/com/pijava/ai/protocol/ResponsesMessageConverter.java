package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.Tool;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * OpenAI Responses 协议的消息/工具转换与请求构建。
 *
 * <p>对齐 pi {@code openai-responses-shared.ts} 的 {@code convertResponsesMessages}
 * / {@code convertResponsesTools}，供 {@code OpenAIResponsesApi} 与
 * {@code AzureOpenAIResponsesApi} 共享。产出与 OpenAI Completions 路径一致的
 * {@link StreamRequest} 语义：system/user 消息映射为 {@code input_message}，
 * assistant 消息映射为 {@code message}（含 tool call 独立 item），工具结果映射为
 * {@code function_call_output}。</p>
 */
final class ResponsesMessageConverter {

    /** OpenAI Responses rejects max_output_tokens below 16. */
    static final int MIN_OUTPUT_TOKENS = 16;

    /**
     * **只有这条车道**理会 {@code compat.supportsMaxOutputTokens} 这道门（包 A-10 第 6 步）。
     *
     * <p>pi 的两条 Responses 副本在这一点上**不对称**：{@code openai-responses.ts:321} 写
     * {@code if (options?.maxTokens && compat.supportsMaxOutputTokens)}，而
     * {@code azure-openai-responses.ts:309} 的对应行**光秃秃**（{@code if (options?.maxTokens)}）
     * —— azure 那份 compat 副本里连这个键都不存在。本仓两条车道共用本类 ⇒ 那份不对称只能落在
     * **车道名**上：门在这条车道上生效，在别的车道名上恒开（azure 侧显式写
     * {@code supportsMaxOutputTokens: false} 也无效，与 pi 一致）。
     *
     * <p>⚠️ 这个串必须与 {@code OpenAIResponsesApi.apiName()} 逐字一致（那里就是本键的命名处）。
     * 若将来两条车道拆成两个转换器，这道门要跟着走 —— {@code 原 docs/57 §11} 与探针 M5
     * 是这条约束的登记处。</p>
     */
    private static final String MAX_OUTPUT_TOKENS_GATED_LANE = "openai-responses";

    private ResponsesMessageConverter() {}

    /**
     * 构建 Responses 流式请求参数。
     *
     * @param modelName 覆盖 model 字段（Azure 传部署名）
     * @param apiName   本车道的 api 名（{@code "openai-responses"} / {@code "azure-openai-responses"}），
     *                  交给共享预通道做同模型判定，**同时**决定 {@code max_output_tokens} 的
     *                  {@code supportsMaxOutputTokens} 门是否生效（包 A-10 第 6 步，
     *                  {@link #MAX_OUTPUT_TOKENS_GATED_LANE}）；**必须由调用方传**，因为两条车道
     *                  共用本类而这两件事在 pi 侧都随车道变（pi 侧同样是两条独立构建器分别调
     *                  transformMessages）
     * @param compat    本请求**解析后**的 compat（{@link CompatResolver#forResponses}）——
     *                  ⚠️ 车道缺省（{@code supportsStrictMode} 的 {@code false}／{@code true}）
     *                  由**调用方**在解析时喂进去（pi 的两车道 compat 缺省相反：
     *                  {@code openai-responses.ts:74} 是 {@code ?? false}，
     *                  {@code azure-openai-responses.ts:296} 是 {@code ?? true}）；本类只消费
     */
    static ResponseCreateParams buildParams(StreamRequest request, ResponsesOptions ropts,
                                            String modelName, String apiName,
                                            ModelCompat compat) {
        return buildParams(request, ropts, modelName, apiName, compat,
            java.util.Map.of(), false, java.util.Map.of());
    }

    /**
     * 原 docs/69 + 包 B103：grammar 能力表（custom tool 出站/回放）与逐请求会话亲和头。
     *
     * @param omitUnsupportedFields B240（pi {@code openai-responses.ts:329}）：Sign in with
     *                              ChatGPT 订阅 token 为真 ⇒ 裁 {@code prompt_cache_retention}、
     *                              {@code prompt_cache_options}、{@code max_output_tokens}、
     *                              {@code temperature}（{@code prompt_cache_key} 不裁）。
     *                              只可能由 openai 车道传 true（azure 副本无此门）。
     */
    static ResponseCreateParams buildParams(StreamRequest request, ResponsesOptions ropts,
                                            String modelName, String apiName,
                                            ModelCompat compat,
                                            java.util.Map<String, String> grammarProperties,
                                            boolean omitUnsupportedFields,
                                            java.util.Map<String, String> affinityHeaders) {
        // pi openai-responses.ts:119 / azure-openai-responses.ts:77 —— 车道入口先
        // resolveTranscript，之后再构建请求。
        var transcript = Transcripts.resolveTranscript(request.transcript(), compat);
        // 包 A3（原 docs/51 §4.4）：Responses 的**两个独立锚定机制**（命中其一即可）——
        // `additional_tools` 优先，缺席时退回合成的 tool_search 对（pi :180、:185-208）。
        boolean supportsAdditionalTools = Boolean.TRUE.equals(compat.supportsAdditionalTools());
        boolean supportsToolSearch = Boolean.TRUE.equals(compat.supportsToolSearch());
        boolean supportsStrictMode = Boolean.TRUE.equals(compat.supportsStrictMode());
        var transcriptTools = Transcripts.resolveTranscriptTools(transcript.messages(),
                supportsAdditionalTools || supportsToolSearch);

        // B222（pi openai-responses-shared.ts:214-215）：前导/中途系统指令的角色——
        // 推理模型且 compat 未显式关掉 developer ⇒ "developer"，否则 "system"。
        // 缺省 true（!== false）；注意 completions 车道是相反缺省（显式 true 才行）。
        boolean instructionDeveloper = request.model() != null
            && request.model().capabilities().contains(ModelCapability.THINKING)
            && !Boolean.FALSE.equals(compat.supportsDeveloperRole());
        var builder = ResponseCreateParams.builder()
            .model(modelName)
            .store(false)
            .input(ResponseCreateParams.Input.ofResponse(
                ResponsesMessageItems.convertMessages(request, transcript, apiName, transcriptTools,
                    supportsAdditionalTools, supportsToolSearch, supportsStrictMode,
                    grammarProperties, instructionDeveloper)));

        var tools = new ArrayList<Tool>();
        for (var td : transcriptTools.requestTools()) {
            tools.add(ResponseToolWire.responseTool(td, supportsStrictMode,
                grammarProperties, false));
        }
        if (!tools.isEmpty()) {
            builder.tools(tools);
        }

        // B240（pi :340-341）：订阅 token 的 omit 门叠在既有两条件之外，顺序按 pi。
        if (request.maxTokens() > 0 && sendsMaxOutputTokens(apiName, compat)
                && !omitUnsupportedFields) {
            builder.maxOutputTokens(Math.max(request.maxTokens(), MIN_OUTPUT_TOKENS));
        }
        // B240（pi :344-345）：temperature 同被裁。
        if (request.temperature() >= 0 && !omitUnsupportedFields) {
            builder.temperature(request.temperature());
        }
        if (ropts.serviceTier() != null) {
            builder.serviceTier(ResponseCreateParams.ServiceTier.of(ropts.serviceTier()));
        }

        // 包 09（B142）：pi `openai-responses.ts:343-362` 的整块。级别取 `:230` 的
        // streamSimple 包装 —— 先夹取（未翻译的 SimpleStreamOptions.reasoning）；
        // `extra["reasoningEffort"]` 是 pi `stream()` 直调语义的**显式覆盖通道**
        // （生产路径不经它，见 ResponsesOptions 的 javadoc）。
        var reasoningSupported = request.model().capabilities()
            .contains(ModelCapability.THINKING);
        var level = ropts.reasoningEffort() != null
            ? Optional.of(ropts.reasoningEffort())
            : SimpleOptions.clampedReasoningEffort(request.model(), request.reasoning());
        if (reasoningSupported) {
            if (level.isPresent() || ropts.reasoningSummary() != null) {
                // pi `:346-347`：目录映射值优先，缺席回落级别自身的字面量（**不塌缩**）。
                var mapped = request.model().thinkingLevelMap()
                    .mapped(ModelThinkingLevel.of(level.orElse(new ThinkingLevel.Medium())));
                var fallbackLiteral = level.map(ResponsesMessageConverter::wireLiteral)
                    .orElse("medium");                       // pi `:348` 的 `: "medium"`
                var rb = Reasoning.builder()
                    .effort(ReasoningEffort.of(mapped.orElse(fallbackLiteral)))
                    .summary(Reasoning.Summary.of(
                        ropts.reasoningSummary() != null ? ropts.reasoningSummary() : "auto"));
                builder.reasoning(rb.build());
                // pi openai-responses.ts:352 —— 仅此分支请求加密推理内容（xAI :359 排除）。
                builder.include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT));
            } else if (!"github-copilot".equals(request.model().id().provider())
                    && !request.model().thinkingLevelMap()
                        .explicitlyUnsupported(ModelThinkingLevel.off())) {
                // pi `:355-359` 的 off 支：`thinkingLevelMap?.off ?? "none"`。
                var offEffort = request.model().thinkingLevelMap()
                    .mapped(ModelThinkingLevel.off()).orElse("none");
                builder.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.of(offEffort)).build());
            }
        }

        applyCacheRetention(builder, ropts, compat, omitUnsupportedFields);
        // 包 A-10：模型级采样参数（pi `openai-responses.ts:362-365`，**body 的最后一个变更**）。
        // B240：pi 的 Object.assign 在 omit 门**之后**且自身无门 —— models.json 模型级
        // samplingParams 即便回写 temperature 等也照发（pi 的事实行为，刻意不「改进」）。
        // 批 3a（#91）：级别与上面 reasoning 块同源 —— extra 显式通道优先，否则循环级
        // clampedReasoningEffort（request.reasoning），皆缺席 ⇒ off（pi `options?.reasoning ?? "off"`）。
        var samplingLevel = ropts.reasoningEffort() != null
            ? ModelThinkingLevel.of(ropts.reasoningEffort())
            : level.map(ModelThinkingLevel::of).orElse(ModelThinkingLevel.off());
        SamplingParamsWriter.applyToResponses(builder, request.model(),
            samplingLevel, ropts.samplingParams());
        // 包 B103：会话亲和头（pi openai-responses.ts:258-267）。
        affinityHeaders.forEach(builder::putAdditionalHeader);
        return builder.build();
    }

    // ── Message conversion ─────────────────────────────────────────────

    /**
     * 本车道是否发 {@code max_output_tokens}（包 A-10 第 6 步）。
     *
     * <p>门只在 {@link #MAX_OUTPUT_TOKENS_GATED_LANE} 上生效 —— 这是 pi 两条副本的不对称
     * （见该常量的 javadoc）。⚠️ 缺省是**开**：{@code null}（模型没写 compat）与
     * {@code true} 行为相同。</p>
     */
    private static boolean sendsMaxOutputTokens(String apiName, ModelCompat compat) {
        return !MAX_OUTPUT_TOKENS_GATED_LANE.equals(apiName)
            || Boolean.TRUE.equals(compat.supportsMaxOutputTokens());
    }

    // ── Options ────────────────────────────────────────────────────────

    /**
     * 级别 → 线格字面量，**一一对应**。pi 的 {@code reasoningEffort} 类型是
     * {@code "minimal"|"low"|"medium"|"high"|"xhigh"|"max"}（{@code openai-responses.ts:104}），
     * 逐字发。包 09（B142）：此前 {@code xhigh}/{@code max} 被塌成 {@code "high"}
     * （包 H5 的权宜）—— 支持 xhigh 的模型上发的是**错值**，现按 pi 去掉。
     */
    private static String wireLiteral(ThinkingLevel level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case ThinkingLevel.Minimal() -> "minimal";
            case ThinkingLevel.Low() -> "low";
            case ThinkingLevel.Medium() -> "medium";
            case ThinkingLevel.High() -> "high";
            case ThinkingLevel.XHigh() -> "xhigh";
            case ThinkingLevel.Max() -> "max";
        };
    }

    /**
     * pi {@code openai-responses.ts:315-317} 的三个键 ＋ {@code :83-99} 的两个工厂。
     *
     * <pre>{@code
     * prompt_cache_key:       cacheRetention === "none" ? undefined : clampOpenAIPromptCacheKey(sessionId),
     * prompt_cache_retention: omit ? undefined
     *                         : long && supportsLongCacheRetention && !supportsExplicitPromptCacheMode ? "24h" : undefined,
     * prompt_cache_options:   omit ? undefined
     *                         : !supportsExplicitPromptCacheMode ? undefined
     *                         : none  ? { mode: "explicit" }
     *                         : long && supportsLongCacheRetention ? { ttl: "30m" }
     *                         : undefined,
     * }</pre>
     *
     * <p>包 09（B104）：此前只有 {@code prompt_cache_key} 与**无条件**的 {@code 24h}，
     * 且 {@code prompt_cache_options} 整个键从不出现。B240：{@code omit}（ChatGPT 订阅
     * token）只裁 retention/options 两支，{@code prompt_cache_key} 照发（pi {@code :334}）。</p>
     */
    private static void applyCacheRetention(ResponseCreateParams.Builder builder,
                                            ResponsesOptions ropts, ModelCompat compat,
                                            boolean omitUnsupportedFields) {
        var retention = ropts.cacheRetention();
        // pi :73 的 `?? true`：只有显式 false 才关掉 long 那一支。
        var longOk = !Boolean.FALSE.equals(compat == null ? null : compat.supportsLongCacheRetention());
        // pi :78 的 `?? false`：只有显式 true 才走显式模式。
        var explicit = Boolean.TRUE.equals(compat == null ? null : compat.supportsExplicitPromptCacheMode());

        // B240（pi :334）：prompt_cache_key **无 omit 门**，订阅 token 照发。
        if (retention != ResponsesOptions.CacheRetention.NONE && ropts.sessionId() != null) {
            builder.promptCacheKey(clampCacheKey(ropts.sessionId()));
        }
        // B240（pi :335-336）：retention 与 options 两支整体在门后，订阅 token 整键不发。
        if (omitUnsupportedFields) {
            return;
        }
        if (retention == ResponsesOptions.CacheRetention.LONG && longOk && !explicit) {
            builder.promptCacheRetention(ResponseCreateParams.PromptCacheRetention.of("24h"));
        }
        if (explicit) {
            var options = ResponseCreateParams.PromptCacheOptions.builder();
            if (retention == ResponsesOptions.CacheRetention.NONE) {
                options.mode(ResponseCreateParams.PromptCacheOptions.Mode.EXPLICIT);
                builder.promptCacheOptions(options.build());
            } else if (retention == ResponsesOptions.CacheRetention.LONG && longOk) {
                options.ttl(ResponseCreateParams.PromptCacheOptions.Ttl._30M);
                builder.promptCacheOptions(options.build());
            }
            // SHORT ⇒ 两条支都不命中 ⇒ pi 返回 undefined ⇒ 整个键缺席。
        }
    }

    /** pi: clampOpenAIPromptCacheKey —— 按码点截前 64（包 B103，委托 {@link PromptCacheKeys}）。 */
    private static String clampCacheKey(String key) {
        return PromptCacheKeys.clamp(key);
    }
}

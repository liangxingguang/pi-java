package com.pijava.ai.protocol;

import java.util.Optional;
import java.util.concurrent.SubmissionPublisher;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.core.JsonValue;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.AllowedFallbackModel;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.http.ProviderRetry;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * Anthropic Messages API adapter using the official {@code anthropic-java} SDK.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}. Handles text, thinking, and tool-call blocks.</p>
 *
 * <p>Phase 6: added {@code (ApiOptions, String apiKeyEnvVar)} constructor and
 * {@code baseUrl} override support for Anthropic-compatible providers (MiniMax etc.).</p>
 *
 * <p><b>B20</b>（{@code 原 docs/31 §8.35.14}）：{@code message_delta.stop_reason} 经
 * {@code mapStopReason} 映射（pi {@code anthropic-messages.ts:1464-1493} 逐字移植），
 * 收尾按 pi 的判序走 {@code pending → error → done} 三分支（{@code :779-804}）。
 * 修复前车道**不看** wire 上的 stop reason，一律发 {@code "end_turn"} ⇒ 「被 max_tokens
 * 截断」对宿主层的 {@code length} 门不可见、「refusal / sensitive」不报错。</p>
 *
 * <p>⚠️ pi 收尾的第一段是 {@code options.signal?.aborted}（{@code :779-781}），在 pi-java
 * 的车道层**结构上不可达**：{@link StreamRequest} 不带信号，中止由宿主层
 * {@code PiLoopRunner.markAborted} 承担。此处**不**把信号塞进请求（§8.35.14 第三节③）。</p>
 */
public final class AnthropicMessagesApi extends AbstractChatApi {

    @Override
    public String apiName() {
        return "anthropic-messages";
    }

    private final AnthropicClient client;

    /**
     * Create an adapter for the given options.
     *
     * @param options API options (apiKey or {@code ANTHROPIC_API_KEY} required)
     */
    public AnthropicMessagesApi(ApiOptions options) {
        this(options, "ANTHROPIC_API_KEY");
    }

    /**
     * Create an adapter for the given options, resolving the API key from an env var.
     *
     * @param options     API options (apiKey or env var required)
     * @param apiKeyEnvVar the environment variable holding the API key
     */
    public AnthropicMessagesApi(ApiOptions options, String apiKeyEnvVar) {
        // 包 A0 步7（原 docs/43 D5/D7）：按**凭证种类**分派，对应 pi
        // api/anthropic-messages.ts:906-989 的三分支（github-copilot / OAuth / 默认）。
        var auth = resolveAuth(options, apiKeyEnvVar);
        // pi :906-908 的判据是**值**（`apiKey.includes("sk-ant-oat")`）——CLI 直给／文件凭证
        // 里的 oat 值同样走 OAuth 形态；AuthKind.OAUTH（来自 ANTHROPIC_OAUTH_TOKEN）是
        // pi-java 的显式载体。两者都认。
        boolean oauth = auth.kind() == AuthKind.OAUTH
            || auth.value().contains(OAUTH_TOKEN_MARKER);
        boolean bearer = oauth || auth.kind() == AuthKind.BEARER;
        var builder = AnthropicOkHttpClient.builder();
        if (bearer) {
            builder.authToken(auth.value());
            if (oauth) {
                // pi :951-970 的 OAuth 分支：两枚身份头（accept ／
                // anthropic-dangerous-direct-browser-access 是浏览器场景产物，不移植）。
                builder.putHeader("user-agent",
                    "claude-cli/" + ClaudeCodeNames.CLAUDE_CODE_VERSION);
                builder.putHeader("x-app", "cli");
            }
        } else {
            builder.apiKey(auth.value());
        }
        if (options.baseUrl() != null && !options.baseUrl().isBlank()) {
            builder.baseUrl(options.baseUrl());
            this.baseUrl = options.baseUrl();
        } else {
            this.baseUrl = null;
        }
        // A-14（G1）：SDK 内置重试关到 0，初始请求由 ProviderRetry 独占。
        builder.maxRetries(0);
        // D-P1：models.json 合并来的 default headers（原 docs/65）。
        putExtraHeaders(options, builder::putHeader);
        // B99 裁决③：把有效 header 的<b>名集合</b>留给 buildParams —— 显式配置
        // anthropic-beta 时自动 beta 列表整组跳过（pi getBetaFeatures :1087-1103）。
        this.effectiveHeaderNames = AnthropicStreamSupport.extraHeaderNames(options);
        this.client = builder.build();
        this.providerRetry = ProviderRetry.optionsOf(options);
        this.cacheRetention = AnthropicStreamSupport.retentionOf(options);
        // B103（docs/52）：pi :625 的 cacheSessionId 原料（none 门在 streamInternal
        // 按解析后的保留期过）；Bearer（OAuth/copilot）分支 pi 不挂亲和头（:992-1031）。
        this.bearer = bearer;
        this.oauth = oauth;
        this.sessionId = AnthropicStreamSupport.sessionIdOf(options);
    }

    /**
     * pi {@code anthropic-messages.ts:978-980} 的 OAuth 值标记。
     */
    private static final String OAUTH_TOKEN_MARKER = "sk-ant-oat";

    /**
     * pi 的缓存保留期环境变量（{@code anthropic-messages.ts:64}）。
     *
     * <p>取值判据是**严格相等**（{@code === "long"}），不做 trim／小写化 ——
     * 那一份严格性在 {@link CompatResolver#anthropicCacheControl} 里，本类只负责读原文。</p>
     */
    private static final String CACHE_RETENTION_ENV = "PI_CACHE_RETENTION";

    /**
     * 包 A-01：请求期的 {@code cacheRetention} 选项（pi {@code StreamOptions.cacheRetention}）。
     *
     * <p>在**构造期**读一次 —— 车道是每请求新建的（{@code DefaultProviders.streamBlocking}
     * 里的 {@code provider.createApi(...)}），所以这个时点与 pi 的「每次 stream 调用读一次」
     * 等价（{@code 原 docs/54 §3 F1}）。取值经 {@code ApiOptions.extra} 的字符串键过桥，
     * 与 {@code ResponsesOptions}/{@code AzureOptions} 同形。</p>
     */
    private final Optional<CacheRetention> cacheRetention;

    /** B103：Bearer（OAuth/copilot）分支不挂会话亲和头（pi :992-1031）。 */
    private final boolean bearer;

    /**
     * B81：本车道是否 Claude Code 伪装（pi {@code isOAuthToken}）。为真时出站加系统前缀、
     * 工具名 CC 映射、身份 beta 对，入站把工具名 demap 回本仓真名。
     */
    private final boolean oauth;

    /** B103：有效 baseUrl（openrouter 探测用；空白 ⇒ null）。 */
    private final String baseUrl;

    /** B99 裁决③：models.json 合并后有效 header 的名集合（仅名，不传值）。 */
    private final java.util.Set<String> effectiveHeaderNames;

    /** B103：原始 sessionId（亲和头原料；none 门在请求期过）。 */
    private final String sessionId;

    /** A-14：初始请求获取的重试选项。 */
    private final ProviderRetry.Options providerRetry;

    @Override
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        var isToolBlock = new boolean[]{false};
        var isThinkingBlock = new boolean[]{false};
        var pendingToolName = new String[]{""};
        var pendingToolId = new String[]{""};
        var stop = new AnthropicStreamSupport.StopState();
        var usageState = new AnthropicUsageState(request.model());
        // B148：服务端回退元数据（fallback 成本归属）＋ input_transformations 收集。
        var requestCompat = request.model() == null ? null
            : CompatResolver.forAnthropic(request.model(), baseUrl);
        var fallbacks = requestCompat == null ? java.util.List.<AllowedFallbackModel>of()
            : requestCompat.allowedFallbackModels();
        var responseExtras = new AnthropicResponseExtras();
        // B81（pi :578）：入站工具名 demap 的「当前工具」，整轮一次。必须走
        // resolveTranscript —— compat 不支中途系统消息时会折叠，影响当前工具集。
        var currentTools = oauth && requestCompat != null
            ? com.pijava.ai.api.Transcripts.getCurrentTools(
                com.pijava.ai.api.Transcripts.resolveTranscript(
                    request.transcript(), requestCompat).messages())
            : java.util.List.<com.pijava.ai.api.ToolDefinition>of();
        try {
            var params = buildParams(request, affinitySessionId(), baseUrl,
                effectiveHeaderNames, oauth);
            publisher.submit(builder.emitStart());

            // A-14（R7）：只包初始请求获取；每次重试是全新 SDK 请求。
            StreamResponse<RawMessageStreamEvent> sr = ProviderRetry.retry(
                    () -> client.messages().createStreaming(params),
                    ProviderRetry::ofAnthropic, providerRetry);
            try (sr) {
                sr.stream().forEach(raw -> {
                    StreamEvent se = mapEvent(raw, request, builder, isToolBlock,
                            isThinkingBlock, pendingToolName, pendingToolId, stop,
                            usageState, fallbacks, responseExtras, currentTools);
                    if (se != null) {
                        if (se instanceof StreamEvent.StreamError) {
                            // pi 在这一层是 throw（未知 stop reason、SDK 异常都会抛穿整条流），
                            // 收尾的分支**根本不会跑**。pi-java 的 mapEvent 把异常转成事件，
                            // 故在此记账 —— 收尾据此不再补第二个终局事件（§8.35.14 第三节①）。
                            stop.errored = true;
                        }
                        publisher.submit(se);
                    }
                });
            }
            AnthropicStreamSupport.submitTail(builder, publisher, stop, responseExtras);
        } catch (Exception e) {
            publisher.submit(builder.emitError(StopReason.ERROR, e));
        }
    }

    private StreamEvent mapEvent(RawMessageStreamEvent event,
                                  StreamRequest request,
                                  StreamPartialBuilder builder,
                                  boolean[] isToolBlock,
                                  boolean[] isThinkingBlock,
                                  String[] pendingToolName,
                                  String[] pendingToolId,
                                  AnthropicStreamSupport.StopState stop,
                                  AnthropicUsageState usageState,
                                  java.util.List<AllowedFallbackModel> fallbacks,
                                  AnthropicResponseExtras responseExtras,
                                  java.util.List<com.pijava.ai.api.ToolDefinition> currentTools) {
        try {
            if (event.isMessageStart()) {
                var startMessage = event.asMessageStart().message();
                // B148（pi :666-690）：responseModel/fallback 计价/input_transformations
                // 三件流内触点收敛在 AnthropicResponseExtras；定价切换必须在本次
                // onMessageStart（首次计价）之前。
                responseExtras.noteStart(builder, request, fallbacks, startMessage);
                usageState.usePricing(responseExtras.servingPricing());
                // pi `:615-625`：**首帧保留** —— 五字段无条件 `|| 0` 写入，注释逐字
                // "This ensures we have input token counts even if the stream is aborted
                // early"。pi 在此**不 push 任何事件**（它没有 usage 事件，usage 是
                // output 对象上的字段）⇒ 本处用 noteUsage 而非 emitUsage，**帧数不变**。
                var u = startMessage.usage();
                Long oneHour = u._cacheCreation().asKnown()
                        .flatMap(c -> c._ephemeral1hInputTokens().asKnown())
                        .orElse(null);
                usageState.onMessageStart(
                        u._inputTokens().asKnown().orElse(null),
                        u._outputTokens().asKnown().orElse(null),
                        u._cacheReadInputTokens().asKnown().orElse(null),
                        u._cacheCreationInputTokens().asKnown().orElse(null),
                        oneHour);
                builder.noteUsage(usageState.usage());
                return null;
            }
            if (event.isContentBlockStart()) {
                var block = event.asContentBlockStart().contentBlock();
                // B148（pi :690-694，探针 AnthropicFallbackBlockProbeTest 已 settle
                // 访问面）：SDK 联合无 "fallback" typed 变体，经 _json() 逃生槽判别。
                // 无已有内容 ⇒ continue（块不入 content、不发事件）；已有内容 ⇒ 抛错，
                // 被本方法 catch 收成 StreamError（errorMessage 逐字，已流块保留）。
                if (AnthropicResponseExtras.isFallbackBlock(block)) {
                    if (builder.hasContentBlocks()) {
                        throw new IllegalStateException(
                            "Anthropic performed an unsupported mid-output model fallback");
                    }
                    return null;
                }
                if (block.isToolUse()) {
                    var tu = block.toolUse().orElseThrow();
                    isToolBlock[0] = true;
                    isThinkingBlock[0] = false;
                    // B20：这里原来还置一个 `toolCallSeen` 标志，收尾据此二选一
                    // （`tool_use` / `end_turn`）。pi 不看工具块、只看
                    // `message_delta.stop_reason` ⇒ 该标志随本包一并删除。
                    // B81（pi :725-730）：OAuth 车道模型回的是 CC 规范名（Read…），
                    // 在当前工具集内大小写不敏感 demap 回本仓真名；不命中原样透传。
                    pendingToolName[0] = oauth
                        ? ClaudeCodeNames.fromWire(tu.name(), currentTools) : tu.name();
                    pendingToolId[0] = tu.id();
                    // 包⑥：起点即带身份 —— pi 在此刻块已带 id/name 入 content
                    // （anthropic-messages.ts:648-660），两个值上一行刚取到。
                    return builder.emitToolCallStart(pendingToolId[0], pendingToolName[0]);
                }
                if (block.isRedactedThinking()) {
                    // B7（原 docs/31 §8.33）：pi 把 redacted 映射成 thinking 块 ——
                    // 文本固定 "[Reasoning redacted]"、thinkingSignature = data、redacted: true
                    // （anthropic-messages.ts:638-647），且同样「先入 content、后 push 事件」。
                    // 落到 text 分支会留下一个空 TextContent，那个空块会被原样发给 Anthropic。
                    isToolBlock[0] = false;
                    isThinkingBlock[0] = true;
                    // pi 在 :642 是 `thinkingSignature: event.content_block.data` 直取 ——
                    // TS 类型谎报 required，缺字段会拼出字面量 "undefined"。此处**故意不复刻**，
                    // 用非抛异常的 _data()（与下面的 signature 同一口径）。
                    return builder.emitThinkingStart("[Reasoning redacted]",
                            block.redactedThinking().orElseThrow()._data().asString().orElse(""),
                            true);
                }
                if (block.isThinking()) {
                    // 签名必须**容忍缺失**（P2，原 docs/31 §8.31）：Anthropic 的 thinking 块其
                    // signature 由后续 signature_delta 补，relay/兼容端点为非 Anthropic 模型
                    // 合成思考时更可能整个流都不给。SDK 的严格访问器 signature() 会抛
                    // AnthropicInvalidDataException("`signature` is not set") 打死整轮 run；
                    // pi 在同一位置是 `event.content_block.signature ?? ""`
                    // （anthropic-messages.ts:633）。_signature() 是非抛异常面：
                    // 字段缺失即 JsonMissing ⇒ asString() 为空 ⇒ 取空串。
                    //
                    // B6/B9：初始**文本**同签名一道随首个 ThinkingStart.partial 投影
                    // （pi :630-637 是先建好带初值的块、再 push 事件）。此前只补了签名，
                    // 且是在 snapshot() 之后补的 —— 既丢了文本，签名也进不了首个 partial。
                    isToolBlock[0] = false;
                    isThinkingBlock[0] = true;
                    var tb = block.thinking().orElseThrow();
                    return builder.emitThinkingStart(
                            tb._thinking().asString().orElse(""),
                            tb._signature().asString().orElse(""),
                            false);
                }
                isToolBlock[0] = false;
                isThinkingBlock[0] = false;
                return builder.emitTextStart();
            }
            if (event.isContentBlockDelta()) {
                var delta = event.asContentBlockDelta().delta();
                if (delta.isText()) {
                    return builder.emitTextDelta(delta.asText().text());
                }
                if (delta.isInputJson()) {
                    return builder.emitToolCallDelta(pendingToolId[0],
                            delta.asInputJson().partialJson());
                }
                if (delta.isThinking()) {
                    return builder.emitThinkingDelta(delta.asThinking().thinking());
                }
                if (delta.isSignature()) {
                    // 同上的容忍规则（P2，原 docs/31 §8.31）：缺字段 ⇒ 空串，不抛。
                    // pi 的 `block.thinkingSignature += event.delta.signature`（anthropic-messages.ts:773-779）
                    // 在 JS 里会把 undefined 拼成字面量 "undefined" —— 那是 pi 的事故
                    // （TS 类型谎报 required），这里**故意不复制**；真 Anthropic 的
                    // signature_delta 恒带该字段，该分支不可达。
                    // 批1/#23：pi 对 signature_delta **只改块、不 push 事件**；返回 null ⇒
                    // 本事件不 submit（mapEvent 链对 null 有既有处理），签名随下一帧 partial 可见。
                    builder.appendThinkingSignature(
                            delta.asSignature()._signature().asString().orElse(""));
                    return null;
                }
                return null;
            }
            if (event.isContentBlockStop()) {
                if (isToolBlock[0]) {
                    return builder.emitToolCallEnd(
                            pendingToolId[0], pendingToolName[0]);
                }
                if (isThinkingBlock[0]) {
                    return builder.emitThinkingEnd();
                }
                return builder.emitTextEnd();
            }
            if (event.isMessageDelta()) {
                // B148（pi :815-817）：input_transformations 在事件**顶层**（不在 delta 里），
                // 是数组即整体替换 start 的列表（末值胜）。
                var deltaEvent = event.asMessageDelta();
                var deltaTransformations =
                    deltaEvent._additionalProperties().get("input_transformations");
                if (deltaTransformations != null) {
                    responseExtras.replaceTransformations(deltaTransformations);
                }
                var delta = deltaEvent.delta();
                // pi `:738-744`：`if (event.delta.stop_reason)` 是 JS 真值判断 ⇒
                // 键缺失、JSON null、空串三种都按「本事件没观测到 stop reason」处理。
                var rawStopReason = delta._stopReason();
                if (!rawStopReason.isMissing() && !rawStopReason.isNull()) {
                    var raw = rawStopReason.asKnown()
                            .map(com.anthropic.models.messages.StopReason::asString).orElse("");
                    if (!raw.isEmpty()) {
                        // pi `:744`：原值先落消息（⑨/D5），映射结果再落 stopReason。
                        builder.noteRawStopReason(raw);
                        var mapped = AnthropicStopReasons.map(
                            raw, AnthropicStopReasons.refusalExplanation(delta));
                        stop.reason = mapped.reason();
                        if (mapped.errorMessage() != null) {
                            stop.errorMessage = mapped.errorMessage();
                        }
                    }
                }
                // pi `:763-787`：四字段**各自 `!= null`** 才覆盖（注释逐字 "Only update
                // usage fields if present (not null). Preserves input_tokens from
                // message_start when proxies omit it in message_delta."）⇒ `0` 是合法值。
                // `reasoning` 走 `output_tokens_details.thinking_tokens`（与四字段不同层）；
                // `cacheWrite1h` **不在覆盖列表里**。`totalTokens` 重算与计价在
                // `if (event.usage)` **块外**，无条件执行（在 AnthropicUsageState 里）。
                var deltaUsage = event.asMessageDelta().usage();
                usageState.onMessageDelta(
                        deltaUsage.inputTokens().orElse(null),
                        deltaUsage._outputTokens().asKnown().orElse(null),
                        deltaUsage.cacheReadInputTokens().orElse(null),
                        deltaUsage.cacheCreationInputTokens().orElse(null),
                        deltaUsage.outputTokensDetails()
                                .flatMap(d -> d._thinkingTokens().asKnown()).orElse(null));
                return builder.emitUsage(usageState.usage());
            }
            if (event.isMessageStop()) {
                return null; // StreamDone emitted in streamInternal finally
            }
        } catch (Exception e) {
            return builder.emitError(StopReason.ERROR, e);
        }
        return null;
    }


    /** 直调夹具形态（反射调用）：无亲和头、无显式 beta 覆写、非 OAuth 身份。 */
    private MessageCreateParams buildParams(StreamRequest request) {
        return buildParams(request, null, null, java.util.Set.of(), false);
    }

    private MessageCreateParams buildParams(StreamRequest request, String affinitySessionId,
                                             String baseUrl,
                                             java.util.Set<String> headerNames,
                                             boolean oauth) {
        // 包 A-01：断点规格在这里解析一次（三源的合并：请求期选项 ?? 环境变量 ?? "short"），
        // 随后由 AnthropicRequestBuilder 翻成 SDK 形状并挂到三处落点上。
        return AnthropicRequestBuilder.buildParams(request,
            CompatResolver.anthropicCacheControl(request.model(), cacheRetention,
                System.getenv(CACHE_RETENTION_ENV)),
            affinitySessionId, baseUrl, headerNames, oauth);
    }

    /**
     * B110（pi {@code anthropic-messages.ts:581}）：唯一真生产者 —— compat flag
     * 为真时盖 {@code options.effort ?? "high"}（{@link AnthropicThinking#activeEffort}），
     * 与 managed 请求体的队尾 active marker <b>同一个函数</b>。门只认
     * {@code supportsMidConvoEffort}，不认 {@code forceAdaptiveThinking}。
     */
    @Override
    protected String providerThinkingLevel(StreamRequest request) {
        if (request.model() == null) {
            return null;
        }
        var compat = CompatResolver.forAnthropic(request.model(), baseUrl);
        if (!Boolean.TRUE.equals(compat.supportsMidConvoEffort())) {
            return null;
        }
        return AnthropicThinking.activeEffort(request.model(), request.reasoning());
    }

    /**
     * B103：过 {@code cacheRetention === "none"} 门（pi :625 的 cacheSessionId）后的
     * 亲和头原料；Bearer 分支恒 null（pi :992-1031）。
     */
    private String affinitySessionId() {
        if (bearer) {
            return null;
        }
        var resolved = CompatResolver.resolveCacheRetention(cacheRetention,
            System.getenv(CACHE_RETENTION_ENV));
        return resolved == CacheRetention.NONE ? null : sessionId;
    }

}

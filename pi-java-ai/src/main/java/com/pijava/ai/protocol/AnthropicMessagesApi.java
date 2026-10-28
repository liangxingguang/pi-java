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
import com.pijava.ai.api.ProviderHooks;
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
        // #98（pi :612-613/:933-934）：federation 车道在 resolveAuth **之前**分派 ——
        // 它没有 key，落进 resolveAuth 只会抛「No API key」（pi 在 federation 在场时
        // 同样跳过 assertRequestAuth）。门照 pi getAnthropicFederation（:346-370）：
        // 仅真 anthropic 车道（host 路径：Credentials 只对 provider=="anthropic" 产出
        // FEDERATION；直调路径：apiKeyEnvVar 判别 —— MiniMax/openrouter 等兼容
        // provider 各自传自己的变量）、无任何 request auth（pi hasRequestAuth :316-322：
        // apiKey 或 authorization/x-api-key/cf-aig-authorization 头）、三必需变量齐。
        var federationEnv = AnthropicFederationSupport.federationEnvOf(options);
        boolean federation = federationEnv != null
            && (options.apiKey() == null || options.apiKey().isBlank())
            && !AnthropicFederationSupport.hasAuthHeader(options)
            && (options.authKind() == AuthKind.FEDERATION
                || "ANTHROPIC_API_KEY".equals(apiKeyEnvVar));
        boolean bearer;
        boolean oauth;
        if (federation) {
            // federation 的头配方与 key 车道同（pi :1065 ＋ :1036-1046）⇒ bearer=false：
            // 会话亲和头照常挂（Bearer/OAuth 分支 pi 才不挂，:992-1031）。
            this.client = AnthropicFederationSupport.client(options, federationEnv);
            bearer = false;
            oauth = false;
        } else {
            // 包 A0 步7（原 docs/43 D5/D7）：按**凭证种类**分派，对应 pi
            // api/anthropic-messages.ts:906-989 的三分支（github-copilot / OAuth / 默认）。
            var auth = resolveAuth(options, apiKeyEnvVar);
            // pi :906-908 的判据是**值**（`apiKey.includes("sk-ant-oat")`）——CLI 直给／文件凭证
            // 里的 oat 值同样走 OAuth 形态；AuthKind.OAUTH（来自 ANTHROPIC_OAUTH_TOKEN）是
            // pi-java 的显式载体。两者都认。
            oauth = auth.kind() == AuthKind.OAUTH
                || auth.value().contains(OAUTH_TOKEN_MARKER);
            bearer = oauth || auth.kind() == AuthKind.BEARER;
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
            }
            // A-14（G1）：SDK 内置重试关到 0，初始请求由 ProviderRetry 独占。
            builder.maxRetries(0);
            // D-P1：models.json 合并来的 default headers（原 docs/65）。
            putExtraHeaders(options, builder::putHeader);
            this.client = builder.build();
        }
        this.baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
            ? options.baseUrl() : null;
        this.bearer = bearer;
        this.oauth = oauth;
        this.eventMapper = new AnthropicEventMapper(oauth);
        // B99 裁决③：把有效 header 的<b>名集合</b>留给 buildParams —— 显式配置
        // anthropic-beta 时自动 beta 列表整组跳过（pi getBetaFeatures :1087-1103）。
        this.effectiveHeaderNames = AnthropicStreamSupport.extraHeaderNames(options);
        this.providerRetry = ProviderRetry.optionsOf(options);
        this.cacheRetention = AnthropicStreamSupport.retentionOf(options);
        // B103（docs/52）：pi :625 的 cacheSessionId 原料（none 门在 streamInternal
        // 按解析后的保留期过）；Bearer（OAuth/copilot）分支 pi 不挂亲和头（:992-1031）。
        this.sessionId = AnthropicStreamSupport.sessionIdOf(options);
        // docs/17（B248）：pi 请求面三钩（anthropic-messages.ts:640/657/664）。
        this.payloadHook = ProviderHooks.payloadHookOf(options);
        this.responseHook = ProviderHooks.responseHookOf(options);
        this.streamEventHook = ProviderHooks.streamEventHookOf(options);
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

    /** SDK 流事件映射（结构债拆分——实现逐字迁往 {@link AnthropicEventMapper}）。 */
    private final AnthropicEventMapper eventMapper;

    /** B103：有效 baseUrl（openrouter 探测用；空白 ⇒ null）。 */
    private final String baseUrl;

    /** B99 裁决③：models.json 合并后有效 header 的名集合（仅名，不传值）。 */
    private final java.util.Set<String> effectiveHeaderNames;

    /** B103：原始 sessionId（亲和头原料；none 门在请求期过）。 */
    private final String sessionId;

    /** A-14：初始请求获取的重试选项。 */
    private final ProviderRetry.Options providerRetry;

    /** docs/17（B248）：pi {@code onPayload}（anthropic-messages.ts:640）——null ≙ undefined。 */
    private final ProviderHooks.PayloadHook payloadHook;

    /** docs/17（B248）：pi {@code onResponse}（:657）——null ≙ undefined。 */
    private final ProviderHooks.ResponseHook responseHook;

    /** docs/17（B248）：pi {@code onProviderStreamEvent}（:664）——null ≙ undefined。 */
    private final ProviderHooks.StreamEventHook streamEventHook;

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
            // docs/17（pi :639-643 逐字）：载荷构建后、HTTP 前；非 null 返回 ⇒ 替换。
            if (payloadHook != null) {
                var next = payloadHook.onPayload(params, request.model());
                if (next != null) {
                    params = ProviderHooks.requirePayloadType(next, MessageCreateParams.class);
                }
            }
            publisher.submit(builder.emitStart());

            // A-14（R7）：只包初始请求获取；每次重试是全新 SDK 请求。
            // docs/17（pi :652-657）：withRawResponse 拿到 status/headers（pi .asResponse()
            // 对位）；成功获取后、流消费前 fire onResponse——重试耗尽在 retry 内抛 ⇒ 不发。
            final var finalParams = params; // 替换臂后 lambda 捕获需要 effectively final
            var rawResponse = ProviderRetry.retry(
                    () -> client.withRawResponse().messages().createStreaming(finalParams),
                    ProviderRetry::ofAnthropic, providerRetry);
            if (responseHook != null) {
                responseHook.onResponse(rawResponse.statusCode(),
                    ProviderHooks.flattenHeaders(rawResponse.headers().names(),
                        rawResponse.headers()::values),
                    request.model());
            }
            StreamResponse<RawMessageStreamEvent> sr = rawResponse.parse();
            try (sr) {
                sr.stream().forEach(raw -> {
                    // docs/17（pi :664）：原始事件、归一化前。
                    if (streamEventHook != null) {
                        streamEventHook.onEvent(raw, request.model());
                    }
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

    /**
     * 事件映射（实现逐字迁往 {@link AnthropicEventMapper}，结构债拆分）。
     * 名字与签名不许动 —— {@code AnthropicMessagesApiThinkingSignatureTest} 反射取它。
     */
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
        return eventMapper.mapEvent(event, request, builder, isToolBlock, isThinkingBlock,
            pendingToolName, pendingToolId, stop, usageState, fallbacks, responseExtras,
            currentTools);
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

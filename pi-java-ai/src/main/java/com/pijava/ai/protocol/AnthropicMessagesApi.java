package com.pijava.ai.protocol;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.SubmissionPublisher;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;

import com.anthropic.backends.AnthropicBackend;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.http.ProviderRetry;
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

    /** pi 累加器的 stop reason 初值（{@code anthropic-messages.ts:526}），非 pi 词汇表取值。 */
    private static final String PENDING = "pending";

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
                builder.putHeader("user-agent", "claude-cli/" + CLAUDE_CODE_VERSION);
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
        this.providerRetry = ProviderRetry.optionsOf(options);
        this.cacheRetention = retentionOf(options);
    }

    /**
     * 从 {@code ApiOptions.extra} 读 {@code cacheRetention}。
     *
     * <p>两种来源都认：直接塞 {@link CacheRetention} 值（java 内代码走这条），或塞线格字符串
     * （{@code models.json}／CLI 直给走这条）。与 {@code ResponsesOptions} 读
     * {@code reasoningEffort} 的两分支同形。⚠️ 非法字符串经
     * {@link CacheRetention#parse} 落成 <b>缺席</b>（不是 {@code SHORT}），
     * 好让 {@code PI_CACHE_RETENTION} 仍能生效。</p>
     */
    private static Optional<CacheRetention> retentionOf(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object raw = extra == null ? null : extra.get("cacheRetention");
        if (raw instanceof CacheRetention retention) {
            return Optional.of(retention);
        }
        return CacheRetention.parse(raw == null ? null : raw.toString());
    }

    /**
     * pi {@code anthropic-messages.ts:906-908} 的 OAuth 值标记；
     * 版本常量取 {@code :87} 的硬编码值 —— <b>照抄同值</b>，不读运行时版本。
     */
    private static final String OAUTH_TOKEN_MARKER = "sk-ant-oat";

    /** pi {@code anthropic-messages.ts:87} 的硬编码常量。 */
    private static final String CLAUDE_CODE_VERSION = "2.1.251";

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
        var stop = new StopState();
        var usageState = new AnthropicUsageState(request.model());
        try {
            var params = buildParams(request);
            publisher.submit(builder.emitStart());

            // A-14（R7）：只包初始请求获取；每次重试是全新 SDK 请求。
            StreamResponse<RawMessageStreamEvent> sr = ProviderRetry.retry(
                    () -> client.messages().createStreaming(params),
                    ProviderRetry::ofAnthropic, providerRetry);
            try (sr) {
                sr.stream().forEach(raw -> {
                    StreamEvent se = mapEvent(raw, builder, isToolBlock,
                            isThinkingBlock, pendingToolName, pendingToolId, stop, usageState);
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
            submitTail(builder, publisher, stop);
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
    }

    /**
     * 收尾三段判（pi {@code anthropic-messages.ts:779-804} 的四段去掉首段 abort）。
     *
     * <pre>
     * signal aborted        → throw "Request was aborted"                        // 车道层不可达
     * stopReason == pending → throw "Anthropic stream ended without a stop reason"
     * stopReason == aborted/error → throw (errorMessage || "An unknown error occurred")
     * 其余                  → push {type:"done", reason: stopReason}; stream.end()
     * </pre>
     *
     * <p>⚠️ 与 pi 的差别不在判序、在 **throw 的去处**：pi 抛进自己的 {@code catch}，
     * 那里发 {@code {type:"error"}} 并结束流 ⇒ 一条流**只有一个**终局事件。pi-java 的
     * 等价物是 {@code emitError}，故前三个分支**必须**在这里返回 —— 修复前是无条件
     * {@code emitDone}，物理错误轮次在通道上是「先 error 后 done」两条。</p>
     */
    private static void submitTail(StreamPartialBuilder builder,
                                   SubmissionPublisher<StreamEvent> publisher,
                                   StopState stop) {
        if (stop.errored) {
            return; // 循环内已发过 error：pi 在那一层 throw，收尾不跑
        }
        if (PENDING.equals(stop.reason)) {
            publisher.submit(builder.emitError("error",
                    new IllegalStateException("Anthropic stream ended without a stop reason")));
            return;
        }
        if ("error".equals(stop.reason)) {
            publisher.submit(builder.emitError("error", new IllegalStateException(
                    stop.errorMessage != null ? stop.errorMessage : "An unknown error occurred")));
            return;
        }
        publisher.submit(builder.emitDone(stop.reason));
    }

    /**
     * 一条流的 stop reason 状态 —— pi 累加器里的 {@code output.stopReason} 与
     * {@code output.errorMessage} 两个字段（{@code anthropic-messages.ts:526} 起）。
     *
     * <p>初值 {@code "pending"} 是 pi 的哨兵值：收尾据此区分「流看完了但**一个** stop reason
     * 都没观测到」与「正常结束」。修复前 pi-java 用局部变量兜底成 {@code "end_turn"}，
     * 把前者伪装成后者（§8.35.14 第三节②）。</p>
     *
     * <p>{@code errored} 是 pi-java 侧新增的（pi 靠 throw 逃逸循环，不需要这个位）：
     * 见 {@link #submitTail} 的说明。</p>
     */
    private static final class StopState {
        private String reason = PENDING;
        private String errorMessage;
        private boolean errored;
    }

    private StreamEvent mapEvent(RawMessageStreamEvent event,
                                  StreamPartialBuilder builder,
                                  boolean[] isToolBlock,
                                  boolean[] isThinkingBlock,
                                  String[] pendingToolName,
                                  String[] pendingToolId,
                                  StopState stop,
                                  AnthropicUsageState usageState) {
        try {
            if (event.isMessageStart()) {
                // pi `:615-625`：**首帧保留** —— 五字段无条件 `|| 0` 写入，注释逐字
                // "This ensures we have input token counts even if the stream is aborted
                // early"。pi 在此**不 push 任何事件**（它没有 usage 事件，usage 是
                // output 对象上的字段）⇒ 本处用 noteUsage 而非 emitUsage，**帧数不变**。
                var u = event.asMessageStart().message().usage();
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
                if (block.isToolUse()) {
                    var tu = block.toolUse().orElseThrow();
                    isToolBlock[0] = true;
                    isThinkingBlock[0] = false;
                    // B20：这里原来还置一个 `toolCallSeen` 标志，收尾据此二选一
                    // （`tool_use` / `end_turn`）。pi 不看工具块、只看
                    // `message_delta.stop_reason` ⇒ 该标志随本包一并删除。
                    pendingToolName[0] = tu.name();
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
                    // pi 的 `block.thinkingSignature += event.delta.signature`（anthropic-messages.ts:705）
                    // 在 JS 里会把 undefined 拼成字面量 "undefined" —— 那是 pi 的事故
                    // （TS 类型谎报 required），这里**故意不复制**；真 Anthropic 的
                    // signature_delta 恒带该字段，该分支不可达。
                    return builder.emitThinkingSignature(
                            delta.asSignature()._signature().asString().orElse(""));
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
                var delta = event.asMessageDelta().delta();
                // pi `:738-744`：`if (event.delta.stop_reason)` 是 JS 真值判断 ⇒
                // 键缺失、JSON null、空串三种都按「本事件没观测到 stop reason」处理。
                var rawStopReason = delta._stopReason();
                if (!rawStopReason.isMissing() && !rawStopReason.isNull()) {
                    var raw = rawStopReason.asKnown().map(StopReason::asString).orElse("");
                    if (!raw.isEmpty()) {
                        // pi `:744`：原值先落消息（⑨/D5），映射结果再落 stopReason。
                        builder.noteRawStopReason(raw);
                        var mapped = mapStopReason(raw, refusalExplanation(delta));
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
            return builder.emitError("error", e);
        }
        return null;
    }

    /**
     * pi {@code anthropic-messages.ts:1464-1493} {@code mapStopReason} 的逐字移植。
     *
     * <p>⚠️ **一处**刻意偏差：返回值是记录而不是 pi 的对象字面量
     * （{@code {stopReason, errorMessage?}}）—— 同形，只是 Java 需要显式类型。</p>
     *
     * <p>（原第二条偏差「pi 返回 {@code "toolUse"} 而 pi-java 落 {@code "tool_use"}」已随
     * B109 消失：归一化词表现在与 pi 同字面量，见 {@code 原 docs/56}。注意区分三个同名字面量
     * —— 上面 {@code case} 的标号是**线格原值**，一直写作 {@code "tool_use"}，本包不动它。）</p>
     *
     * <p>未知取值**抛** {@code IllegalStateException}，与 pi 的 {@code default: throw} 一致；
     * 它由 {@code mapEvent} 的 catch 转成 {@code StreamError}（文案相同），随后收尾不再补事件。</p>
     *
     * @param raw          wire 上的原始取值（{@code StopReason.asString()}；SDK 1.15.0 的
     *                     {@code StopReason.Known} 不含 {@code sensitive} 一类新值，未知值会被
     *                     {@code known()} 抛掉，只能读原始字符串）
     * @param explanation  {@code stop_details.explanation}，无则 null
     */
    private static MappedStopReason mapStopReason(String raw, String explanation) {
        return switch (raw) {
            case "end_turn" -> new MappedStopReason("stop", null);
            case "max_tokens" -> new MappedStopReason("length", null);
            case "tool_use" -> new MappedStopReason("toolUse", null);
            case "refusal" -> new MappedStopReason("error",
                    explanation != null && !explanation.isEmpty()
                            ? explanation
                            : "The model refused to complete the request");
            case "pause_turn" -> new MappedStopReason("stop", null); // 重发即可，stop 足够
            case "stop_sequence" -> new MappedStopReason("stop", null); // 未供 stop 序列，不该出现
            case "sensitive" -> new MappedStopReason("error", "Provider stopped with: sensitive");
            default -> throw new IllegalStateException("Unhandled stop reason: " + raw);
        };
    }

    /** pi {@code mapStopReason} 的返回形状 {@code { stopReason, errorMessage? }}。 */
    private record MappedStopReason(String reason, String errorMessage) {}

    /**
     * {@code message_delta.delta.stop_details.explanation}（pi {@code :1475} 的
     * {@code stopDetails?.explanation}）。
     *
     * <p>走非抛异常面 {@code _explanation()}（P2 处理 {@code signature} 的同一口径）：
     * 字段缺失或类型不符都退化成「没有说明」，由调用方落 pi 的默认文案。</p>
     */
    private static String refusalExplanation(RawMessageDeltaEvent.Delta delta) {
        return delta._stopDetails().asKnown()
                .flatMap(details -> details._explanation().asString())
                .orElse(null);
    }

    private MessageCreateParams buildParams(StreamRequest request) {
        // 包 A-01：断点规格在这里解析一次（三源的合并：请求期选项 ?? 环境变量 ?? "short"），
        // 随后由 AnthropicRequestBuilder 翻成 SDK 形状并挂到三处落点上。
        return AnthropicRequestBuilder.buildParams(request,
            CompatResolver.anthropicCacheControl(request.model(), cacheRetention,
                System.getenv(CACHE_RETENTION_ENV)));
    }

}

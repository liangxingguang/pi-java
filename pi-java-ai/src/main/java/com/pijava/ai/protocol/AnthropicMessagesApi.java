package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
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
 * <p><b>B20</b>（{@code docs/31 §8.35.14}）：{@code message_delta.stop_reason} 经
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
        var apiKey = resolveApiKey(options, apiKeyEnvVar);
        var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
        if (options.baseUrl() != null && !options.baseUrl().isBlank()) {
            builder.baseUrl(options.baseUrl());
        }
        this.client = builder.build();
    }

    @Override
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        var isToolBlock = new boolean[]{false};
        var isThinkingBlock = new boolean[]{false};
        var pendingToolName = new String[]{""};
        var pendingToolId = new String[]{""};
        var stop = new StopState();
        try {
            var params = buildParams(request);
            publisher.submit(builder.emitStart());

            try (StreamResponse<RawMessageStreamEvent> sr =
                         client.messages().createStreaming(params)) {
                sr.stream().forEach(raw -> {
                    StreamEvent se = mapEvent(raw, builder, isToolBlock,
                            isThinkingBlock, pendingToolName, pendingToolId, stop);
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
                                  StopState stop) {
        try {
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
                    return builder.emitToolCallStart();
                }
                if (block.isRedactedThinking()) {
                    // B7（docs/31 §8.33）：pi 把 redacted 映射成 thinking 块 ——
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
                    // 签名必须**容忍缺失**（P2，docs/31 §8.31）：Anthropic 的 thinking 块其
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
                    // 同上的容忍规则（P2，docs/31 §8.31）：缺字段 ⇒ 空串，不抛。
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
                        var mapped = mapStopReason(raw, refusalExplanation(delta));
                        stop.reason = mapped.reason();
                        if (mapped.errorMessage() != null) {
                            stop.errorMessage = mapped.errorMessage();
                        }
                    }
                }
                var usage = event.asMessageDelta().usage();
                return builder.emitUsage(
                        usage.inputTokens().orElse(0L),
                        usage.outputTokens());
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
     * <p>⚠️ 两处**刻意偏差**，都关乎词汇表而非语义：</p>
     * <ol>
     *   <li>pi 返回 {@code "toolUse"}（camelCase），pi-java 的词汇表是 {@code "tool_use"}
     *       （{@code StreamEvent.StreamDone} 的 javadoc、{@code PiMessagesApi:244-246}
     *       在 pi 消息通道上做的是同一次翻译）⇒ 此处落 {@code "tool_use"}。</li>
     *   <li>返回值是记录而不是 pi 的对象字面量（{@code {stopReason, errorMessage?}}）——
     *       同形，只是 Java 需要显式类型。</li>
     * </ol>
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
            case "tool_use" -> new MappedStopReason("tool_use", null);
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
        // pi anthropic-messages.ts:1029 —— 共享预通道跑在**适配器之外**，
        // 在消息进入落线逻辑之前决定哪些块活下来（docs/31 §8.34.4 决策 1）。
        var messages = TransformMessages.apply(
                request.messages(), request.modelId(), apiName());
        // pi anthropic-messages.ts:193 `model.compat?.allowEmptySignature ?? false` ——
        // 经 StreamRequest 带到 :1047 的形参、再落到 :1304 的唯一行为点（决策 5 投送）。
        // 缺席与 false 同义（pi 的 `?? false` 是二态，不是三态）。
        var allowEmptySignature = request.model() != null
                && request.model().compat().allowEmptySignature();
        var builder = MessageCreateParams.builder()
                .model(request.modelId().modelName())
                .maxTokens(request.maxTokens() > 0 ? request.maxTokens() : 4096L);

        // 系统提示是请求上的独立字段（pi anthropic-messages.ts:1074 读 context.systemPrompt），
        // 不在消息列表里 —— pi 的 Message 没有 system 角色。
        var systemText = request.systemPrompt();
        if (systemText != null && !systemText.isEmpty()) {
            builder.system(systemText);
        }

        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);

            // Anthropic requires tool_result blocks inside a user message
            // (pi anthropic-messages.ts maps toolResult -> role "user" and
            // merges consecutive tool results into one user message).
            if (msg instanceof Message.ToolResultMessage) {
                var resultBlocks = new ArrayList<ContentBlockParam>();
                int j = i;
                while (j < messages.size()
                        && messages.get(j) instanceof Message.ToolResultMessage tool) {
                    resultBlocks.add(toToolResultBlock(tool));
                    j++;
                }
                i = j - 1;
                builder.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(resultBlocks))
                    .build());
                continue;
            }

            var blockParams = toBlockParams(msg, allowEmptySignature);
            if (blockParams.isEmpty()) continue;

            var role = msg instanceof Message.UserMessage
                    ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
            builder.addMessage(MessageParam.builder()
                    .role(role)
                    .content(MessageParam.Content.ofBlockParams(blockParams))
                    .build());
        }

        for (var td : request.tools()) {
            var inputSchema = Tool.InputSchema.builder()
                    .putAllAdditionalProperties(toJsonValues(td.inputSchema()))
                    .build();
            var toolBuilder = Tool.builder()
                    .name(td.name())
                    .inputSchema(inputSchema);
            if (td.description() != null && !td.description().isBlank()) {
                toolBuilder.description(td.description());
            }
            builder.addTool(ToolUnion.ofTool(toolBuilder.build()));
        }

        if (request.temperature() >= 0) {
            builder.temperature(request.temperature());
        }

        // pi alignment (anthropic-messages.ts:1047-1051): budget-based
        // extended thinking, threaded through StreamRequest.extra by the
        // harness StreamFn. The SDK builder leaves `type` as JsonMissing,
        // so it must be set explicitly or the API ignores the config.
        var budget = request.extra().get("thinking.budgetTokens");
        if (budget instanceof Number n && n.longValue() > 0) {
            builder.thinking(com.anthropic.models.messages.ThinkingConfigParam.ofEnabled(
                    com.anthropic.models.messages.ThinkingConfigEnabled.builder()
                            .budgetTokens(n.longValue())
                            .type(com.anthropic.core.JsonValue.from("enabled"))
                            .build()));
        }

        return builder.build();
    }

    private List<ContentBlockParam> toBlockParams(Message msg, boolean allowEmptySignature) {
        var result = new ArrayList<ContentBlockParam>();
        for (var block : msg.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi 两条车道都按 trim 判空丢弃文本块：assistant 车道 `:1282`
                // （`if (block.text.trim().length === 0) continue;`）、user 车道
                // `:1262-1268` 的 filteredBlocks + `:1269 continue`（另有字符串形态内容
                // 的 `:1241-1246`）。pi-java 的 toBlockParams 两条车道共用 ⇒ 一处即够。
                // 整个消息的块被清空后不再落线，由调用点 `blockParams.isEmpty()` 承担，
                // 对应 pi 的 `:1269`/`:1331` 两处 continue。
                if (tc.text() == null || tc.text().trim().isEmpty()) {
                    continue;
                }
                result.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(tc.text()).build()));
            } else if (block instanceof ContentBlock.ThinkingContent th) {
                appendThinkingBlock(result, th, allowEmptySignature);
            } else if (block instanceof ContentBlock.ToolUseContent tu) {
                var input = ToolUseBlockParam.Input.builder()
                        .putAllAdditionalProperties(toJsonValues(tu.arguments()))
                        .build();
                result.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(tu.id())
                        .name(tu.name())
                        .input(input)
                        .build()));
            }
            // 其余变体（ImageContent / UrlImageContent / DiffContent）此处**静默丢弃**
            // —— pi 在 user 车道把图片映射成 `{type:"image",source:{...}}`
            // （`anthropic-messages.ts:1250-1260`），pi-java 的 Anthropic 车道缺这条。
            // 已登记为 B16，本包不动（行为变更须先过设计）。
        }
        return result;
    }

    /**
     * 落线：一块 thinking 变成什么线格（pi {@code anthropic-messages.ts:1287-1321}，逐分支对照）。
     *
     * <pre>
     * block.redacted                  → {type:"redacted_thinking", data: signature}   // :1289-1294
     * hasSignature = !!sig &amp;&amp; trim 非空                                                  // :1296
     * text trim 为空 且 无签名          → 丢弃                                             // :1298
     * 无签名 → allowEmptySignature ? {type:"thinking",thinking,signature:""} : {type:"text",text}  // :1300-1312
     * 有签名                          → {type:"thinking",thinking,signature}           // :1313-1319
     * </pre>
     *
     * <p>⚠️ 此处**不再判同模型/异模型**：那个决定已由闸（{@code TransformMessages}）做完，
     * 能走到这里的 thinking 恒是同模型的（异模型的在闸里已降级成 TextContent 或被丢弃）。
     * pi 在同一位置也不判身份 —— 判身份的是 {@code transformMessages} 那一层。</p>
     *
     * <p>⚠️ 与 pi 的**一处刻意偏差**：pi `:1316` 落线的是**未 trim** 的
     * {@code thinkingSignature}（它只在 `:1296` 的判空里 trim 过）。pi-java 落 trim 后的值
     * （沿用包①之前 `:318` 的写法）。差别只在签名首尾带空白时可见，而真 Anthropic 的
     * 签名是无空白 base64；两处判空语义一致，故本包**不改**这一处（§8.34.6-2）。</p>
     */
    private void appendThinkingBlock(List<ContentBlockParam> result,
                                     ContentBlock.ThinkingContent th,
                                     boolean allowEmptySignature) {
        if (th.redacted()) {
            result.add(ContentBlockParam.ofRedactedThinking(
                    RedactedThinkingBlockParam.builder().data(th.signature()).build()));
            return;
        }
        var signature = th.signature() == null ? "" : th.signature().trim();
        var text = th.text() == null ? "" : th.text();
        var hasSignature = !signature.isEmpty();
        if (text.trim().isEmpty() && !hasSignature) {
            return;
        }
        if (!hasSignature) {
            result.add(allowEmptySignature
                    ? ContentBlockParam.ofThinking(
                        com.anthropic.models.messages.ThinkingBlockParam.builder()
                                .thinking(text)
                                .signature("")
                                .build())
                    : ContentBlockParam.ofText(
                        TextBlockParam.builder().text(text).build()));
            return;
        }
        result.add(ContentBlockParam.ofThinking(
                com.anthropic.models.messages.ThinkingBlockParam.builder()
                        .thinking(text)
                        .signature(signature)
                        .build()));
    }

    private static ContentBlockParam toToolResultBlock(Message.ToolResultMessage tool) {
        var resultContent = ToolResultBlockParam.Content.ofBlocks(
                toTextBlocks(tool.content()));
        var toolResult = ToolResultBlockParam.builder()
                .toolUseId(tool.toolUseId())
                .content(resultContent)
                .isError(tool.isError())
                .build();
        return ContentBlockParam.ofToolResult(toolResult);
    }

    private static List<ToolResultBlockParam.Content.Block> toTextBlocks(List<ContentBlock> blocks) {
        var result = new ArrayList<ToolResultBlockParam.Content.Block>();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) {
                result.add(ToolResultBlockParam.Content.Block.ofText(
                        TextBlockParam.builder().text(tc.text()).build()));
            }
        }
        return result;
    }

    private static Map<String, com.anthropic.core.JsonValue> toJsonValues(
            Map<String, Object> schema) {
        var out = new java.util.LinkedHashMap<String, com.anthropic.core.JsonValue>();
        schema.forEach((key, value) -> out.put(key, com.anthropic.core.JsonValue.from(value)));
        return out;
    }

    private String extractText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) sb.append(tc.text());
        }
        return sb.toString();
    }
}

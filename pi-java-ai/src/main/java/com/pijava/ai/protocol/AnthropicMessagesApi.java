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
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
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
        var toolCallSeen = new boolean[]{false};
        var pendingToolName = new String[]{""};
        var pendingToolId = new String[]{""};
        try {
            var params = buildParams(request);
            publisher.submit(builder.emitStart());

            try (StreamResponse<RawMessageStreamEvent> sr =
                         client.messages().createStreaming(params)) {
                sr.stream().forEach(raw -> {
                    StreamEvent se = mapEvent(raw, builder, isToolBlock,
                            isThinkingBlock, toolCallSeen, pendingToolName, pendingToolId);
                    if (se != null) publisher.submit(se);
                });
            }
            publisher.submit(builder.emitDone(toolCallSeen[0] ? "tool_use" : "end_turn"));
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
    }

    private StreamEvent mapEvent(RawMessageStreamEvent event,
                                  StreamPartialBuilder builder,
                                  boolean[] isToolBlock,
                                  boolean[] isThinkingBlock,
                                  boolean[] toolCallSeen,
                                  String[] pendingToolName,
                                  String[] pendingToolId) {
        try {
            if (event.isContentBlockStart()) {
                var block = event.asContentBlockStart().contentBlock();
                if (block.isToolUse()) {
                    var tu = block.toolUse().orElseThrow();
                    isToolBlock[0] = true;
                    isThinkingBlock[0] = false;
                    toolCallSeen[0] = true;
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

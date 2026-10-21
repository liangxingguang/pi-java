package com.pijava.ai.protocol;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.http.PiHttpClient;
import com.pijava.ai.message.AssistantMessageDiagnostic;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.utils.ProviderErrors;

/**
 * pi-messages 协议适配器。
 *
 * <p>pi 自有的 wire 协议：单次 POST {@code {baseUrl}/messages}，body
 * {@code { model, context, options }}，响应为 SSE 流（{@code data:} 行 + 空行
 * 分隔，{@code [DONE]} 忽略）。每个事件与 pi-java {@code StreamEvent} 近乎 1:1。
 * 流必须以 {@code done} 或 {@code error} 终止；否则抛「stream ended without a
 * terminal event」（对齐 pi {@code pi-messages.ts:404-411}）。</p>
 */
public final class PiMessagesApi extends AbstractChatApi {

    @Override
    public String apiName() {
        return "pi-messages";
    }

    /**
     * ⚠️ 本车道**不参与**请求侧选项解析（{@code 原 docs/57 §6 R2}）。
     *
     * <p>pi 的 pi-messages 车道**不过** {@code buildBaseOptions}
     * （{@code pi-messages.ts:431-443} 直接 {@code {...options, reasoning, toolChoice, debug}}）
     * ⇒ 它的 envelope 里 {@code maxTokens} 恒为 {@code undefined}（缺席）。
     * java 这边 {@link #buildBody} 的 {@code request.maxTokens() > 0} 门在解析前恒假
     * —— 若不给这道豁免，本车道会**凭空多发**一个 {@code maxTokens} 字段，
     * 那是对齐的**反向**破坏。</p>
     */
    @Override
    protected boolean resolvesRequestOptions() {
        return false;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PiHttpClient client;
    private final String baseUrl;
    private final String apiKey;
    /** docs/17（裁决⑦）：extra["headers"] 原始 map（null 值＝删除）；缺席为 null。 */
    private final Map<?, ?> optionHeaders;

    /** docs/17（B248）：pi {@code onPayload}（pi-messages.ts:387）——null ≙ undefined。 */
    private final ProviderHooks.PayloadHook payloadHook;

    /** docs/17（B248）：pi {@code onResponse}（:404）——null ≙ undefined。 */
    private final ProviderHooks.ResponseHook responseHook;

    /** docs/17（B248）：pi {@code onProviderStreamEvent}（:415）——null ≙ undefined。 */
    private final ProviderHooks.StreamEventHook streamEventHook;

    /**
     * Create an adapter, resolving the API key from an env var.
     *
     * @param options     API options (apiKey or env var required; baseUrl required)
     * @param apiKeyEnvVar the environment variable holding the API key
     */
    public PiMessagesApi(ApiOptions options, String apiKeyEnvVar) {
        this.apiKey = resolveApiKey(options, apiKeyEnvVar);
        this.baseUrl = resolveBaseUrl(options);
        this.client = PiHttpClient.builder().userAgent("pi-java/dev").build();
        Object rawHeaders = options.extra() == null ? null : options.extra().get("headers");
        this.optionHeaders = rawHeaders instanceof Map<?, ?> map ? map : null;
        // docs/17（B248）：pi 请求面三钩——pi-messages 车道在 pi **不豁免**
        // （pi-messages.ts:387/:404/:415 全有，与 R2 的 resolvesRequestOptions 豁免是两层事）。
        this.payloadHook = ProviderHooks.payloadHookOf(options);
        this.responseHook = ProviderHooks.responseHookOf(options);
        this.streamEventHook = ProviderHooks.streamEventHookOf(options);
    }

    @Override
    protected void streamInternal(StreamRequest request,
                                  SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        var toolIds = new HashMap<Integer, String>();
        var toolJson = new HashMap<Integer, String>();
        boolean sawTerminal = false;
        try {
            publisher.submit(builder.emitStart());
            // docs/17（pi :386-391 逐字）：载荷＝envelope ObjectNode；非 null 返回 ⇒ 替换后再序列化。
            ObjectNode envelope = buildBody(request);
            if (payloadHook != null) {
                var next = payloadHook.onPayload(envelope, request.model());
                if (next != null) {
                    envelope = ProviderHooks.requirePayloadType(next, ObjectNode.class);
                }
            }
            String body = JSON.writeValueAsString(envelope);
            // docs/17（裁决⑦，pi :394-399）：手写头打底＋extra["headers"] 覆盖/null 删除
            // （pi providerHeadersToRecord 逐字：名小写规范化、null＝删、调用方值压默认）——
            // 本车道此前不读 extra["headers"]（存量缺口）。
            var headers = new java.util.LinkedHashMap<String, String>();
            headers.put("authorization", "Bearer " + apiKey);
            headers.put("accept", "text/event-stream");
            if (optionHeaders != null) {
                optionHeaders.forEach((k, v) -> {
                    if (k == null) {
                        return;
                    }
                    var key = k.toString().toLowerCase(java.util.Locale.ROOT);
                    if (v == null) {
                        headers.remove(key);
                    } else {
                        headers.put(key, v.toString());
                    }
                });
            }
            // docs/17（pi :404）：终态响应观察（在 !ok 判定前——含错误状态）。
            var responseObserver = responseHook == null ? null
                : (com.pijava.ai.http.SseResponseObserver) (status, responseHeaders) ->
                    responseHook.onResponse(status,
                        ProviderHooks.flattenHeaders(responseHeaders.map()), request.model());
            Iterator<PiHttpClient.ServerSentEvent> sseEvents =
                client.postSse(baseUrl + "/messages", body, headers, null, null,
                    responseObserver);

            while (sseEvents.hasNext()) {
                var sse = sseEvents.next();
                String data = sse.data();
                if (data == null || data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                PiMessagesEvent event = JSON.readValue(data, PiMessagesEvent.class);
                // docs/17（pi :415）：反序列化后、归一化（switch）前。
                if (streamEventHook != null) {
                    streamEventHook.onEvent(event, request.model());
                }
                switch (event) {
                    case PiMessagesEvent.Start start -> {
                        // 已由 emitStart 发出，wire start 无对应事件
                    }
                    case PiMessagesEvent.TextStart textStart ->
                        publisher.submit(builder.emitTextStart());
                    case PiMessagesEvent.TextDelta d ->
                        publisher.submit(builder.emitTextDelta(d.delta()));
                    case PiMessagesEvent.TextEnd textEnd ->
                        publisher.submit(builder.emitTextEnd());
                    case PiMessagesEvent.ThinkingStart thinkingStart ->
                        publisher.submit(builder.emitThinkingStart());
                    case PiMessagesEvent.ThinkingDelta d ->
                        publisher.submit(builder.emitThinkingDelta(d.delta()));
                    case PiMessagesEvent.ThinkingEnd thinkingEnd -> {
                        // P4（原 docs/31 §8.33）：pi 在 thinking_end 上把 contentSignature/redacted
                        // 装配回块 —— `Object.assign(entry, {thinkingSignature: event.contentSignature,
                        // redacted: event.redacted})`（pi-messages.ts:236-240，事件形状见 :60-64）。
                        // 这四个字段在 PiMessagesEvent:43 早已声明，此前**从未被填过** ⇒
                        // 签名在这条车道上恒空，B7/B8 的忠实度到不了 web/harness 车道。
                        builder.applyThinkingSignature(
                            thinkingEnd.contentSignature(), thinkingEnd.redacted());
                        publisher.submit(builder.emitThinkingEnd());
                    }
                    case PiMessagesEvent.ToolCallStart s -> {
                        toolIds.put(s.contentIndex(), s.id());
                        // 包⑥（§4-C）：PiMessagesEvent.ToolCallStart:46 的 toolName
                        // 字段此前**从未被这个调用点取用** ⇒ 该车道的起点身份为空。
                        publisher.submit(builder.emitToolCallStart(s.id(), s.toolName()));
                    }
                    case PiMessagesEvent.ToolCallDelta d -> {
                        toolJson.put(d.contentIndex(),
                            toolJson.getOrDefault(d.contentIndex(), "") + d.delta());
                        publisher.submit(builder.emitToolCallDelta(
                            toolIds.get(d.contentIndex()), d.delta()));
                    }
                    case PiMessagesEvent.ToolCallEnd e -> {
                        var tc = e.toolCall();
                        feedToolCallTail(builder, publisher, toolJson,
                            e.contentIndex(), tc);
                        // Batch F（原 docs/67）：toolCall.thoughtSignature 随块透传。
                        publisher.submit(
                            builder.emitToolCallEnd(tc.id(), tc.name(), tc.thoughtSignature()));
                        toolJson.remove(e.contentIndex());
                        toolIds.remove(e.contentIndex());
                    }
                    case PiMessagesEvent.Done done -> {
                        sawTerminal = true;
                        if (done.usage() != null) {
                            publisher.submit(builder.emitUsage(
                                (long) done.usage().input(), (long) done.usage().output()));
                        }
                        // B110（pi pi-messages.ts:180-217）：终局帧的 providerThinkingLevel
                        // 在 emitDone **之前**进快照 ⇒ 终局消息携带（缺席 ⇒ null ⇒ 不出键）。
                        if (done.providerThinkingLevel() != null) {
                            builder.noteProviderThinkingLevel(done.providerThinkingLevel());
                        }
                        // B148（pi :173-180）：网关重写摘要在终局落成诊断（done/error 两支都附）。
                        appendRewriteDiagnostic(builder, done.rewrite());
                        // B109：pi 消息通道发的归一化停因与本枚举同词表（camelCase）。
                        // 这是 JSON 解码边界（pi TS 类型擦除、零校验）⇒ fromWireOrNull
                        // 容错：未知值落 null（比较全不命中），不走 JsonlCodec 的 tool_use 垫片。
                        publisher.submit(builder.emitDone(StopReason.fromWireOrNull(done.reason())));
                        return;
                    }
                    case PiMessagesEvent.Error err -> {
                        sawTerminal = true;
                        if (err.providerThinkingLevel() != null) {
                            builder.noteProviderThinkingLevel(err.providerThinkingLevel());
                        }
                        appendRewriteDiagnostic(builder, err.rewrite());
                        // 解码边界容错：未知/缺失 ⇒ null ⇒ settle 补缺 ERROR（同 pi 零校验）。
                        publisher.submit(builder.emitError(
                            StopReason.fromWireOrNull(err.reason()),
                            new RuntimeException(
                                err.errorMessage() == null ? "pi-messages error" : err.errorMessage())));
                        return;
                    }
                }
            }
            if (!sawTerminal) {
                publisher.submit(builder.emitError(StopReason.ERROR,
                    new RuntimeException("stream ended without a terminal event")));
            }
        } catch (Exception e) {
            // B224：HTTP 错误响应按 pi pi-messages.ts:124-131 成形
            //（"N Phrase: message ?? body (code)"）；非 HTTP 故障（建连失败/中止/
            // body 构造）原样发，保留异常身份。SSE 流内 error 事件在 switch 里已透传，
            // 不走这里（与 pi createErrorEvent 只包 PiMessagesResponseError 同界）。
            if (e instanceof com.pijava.ai.http.PiHttpException http && http.statusCode() > 0) {
                String message = ProviderErrors.formatPiMessagesError(
                    http.statusCode(), http.body());
                // B148（pi pi-messages.ts:336-341）：HTTP 故障附
                // pi_messages_response_failure 诊断（信封半，见 failureDiagnostic）。
                // SSE 流内 error 事件不附（switch 分支）；中止态不附（车道层不可达）。
                builder.appendDiagnostic(failureDiagnostic(request, baseUrl, http, message));
                publisher.submit(builder.emitError(StopReason.ERROR,
                    message.equals(e.getMessage()) ? e : new IllegalStateException(message, e)));
            } else {
                publisher.submit(builder.emitError(StopReason.ERROR, e));
            }
        }
    }

    /**
     * B148：pi {@code createPiMessagesResponseError} 的 details 信封
     * （pi-messages.ts:133-152）＋ {@code createAssistantMessageDiagnostic}
     * （:336-341）。
     *
     * <p>details 逐字：{@code version:1, provider, model, url, status, statusText,
     * error|body（二选一）, timestampMs}。错误体可解析为 {@code {error:对象}} ⇒
     * 挂原始 error 节点；否则挂截断裸 body（8192 + U+2026）。error 信息里
     * name 恒为字面量 {@code PiMessagesResponseError}（Java 无同名异常），
     * code 取 error.code 文本值，stack 取底层异常栈串。</p>
     */
    private static AssistantMessageDiagnostic failureDiagnostic(
            StreamRequest request, String baseUrl,
            com.pijava.ai.http.PiHttpException http, String formattedMessage) {
        long now = System.currentTimeMillis();
        var details = JSON.createObjectNode();
        details.put("version", 1);
        details.put("provider", request.modelId().provider());
        details.put("model", request.modelId().modelName());
        details.put("url", baseUrl + "/messages");
        details.put("status", http.statusCode());
        details.put("statusText", ProviderErrors.reasonPhrase(http.statusCode()));
        JsonNode errorNode = ProviderErrors.parsePiMessagesErrorNode(http.body());
        JsonNode codeNode = null;
        if (errorNode != null) {
            details.set("error", errorNode);
            var rawCode = errorNode.get("code");
            if (rawCode != null && rawCode.isTextual() && !rawCode.asText().isEmpty()) {
                codeNode = rawCode;
            }
        } else {
            details.put("body", ProviderErrors.truncateDiagnosticString(http.body()));
        }
        details.put("timestampMs", now);
        var errorInfo = new AssistantMessageDiagnostic.DiagnosticErrorInfo(
            "PiMessagesResponseError", formattedMessage, stackOf(http), codeNode);
        return new AssistantMessageDiagnostic(
            "pi_messages_response_failure", now, errorInfo, details);
    }

    /**
     * B148（pi :165-180 {@code appendRewriteDiagnostic}）：非空 rewrite 摘要落成
     * {@code pi_messages_rewrite} 诊断，details 是 impact 的逐字段拷贝（done/error 两支同法）。
     */
    private static void appendRewriteDiagnostic(StreamPartialBuilder builder,
                                                 RewriteImpact rewrite) {
        if (rewrite == null) {
            return;
        }
        var details = JSON.createObjectNode();
        details.put("policyId", rewrite.policyId());
        details.put("policyVersion", rewrite.policyVersion());
        details.put("changed", rewrite.changed());
        details.put("tokenCountChange", rewrite.tokenCountChange());
        details.put("messageCountChange", rewrite.messageCountChange());
        details.put("systemPromptChanged", rewrite.systemPromptChanged());
        builder.appendDiagnostic(new AssistantMessageDiagnostic(
            "pi_messages_rewrite", System.currentTimeMillis(), null, details));
    }

    /** 异常栈串（pi 的 {@code error.stack}；JDK PrintWriter 重放）。 */
    private static String stackOf(Throwable throwable) {
        var writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    // ── 请求构建 ─────────────────────────────────────────────────────────

    private static String resolveBaseUrl(ApiOptions options) {
        String raw = options.baseUrl() == null ? "" : options.baseUrl().trim();
        String base = raw.replaceAll("/+$", "");
        if (base.isEmpty()) {
            throw new IllegalStateException("PiMessagesApi requires baseUrl");
        }
        return base;
    }

    /**
     * docs/17：序列化前移到调用点（{@code onPayload} 钩要拿 envelope 本体）——
     * 本方法只构建，返回 {@link ObjectNode}。
     */
    private static ObjectNode buildBody(StreamRequest request) {
        try {
            // pi api/pi-messages.ts:374-390 —— body 是 `{model, context, options}`，而
            // `context` **就是** TranscriptContext：只有 `messages`，系统提示与工具声明都在
            // 消息数组里（`9e05370b2` 起形参从 `Context` 改成 `TranscriptContext`）。
            // ⚠️ 旧实现发的是 `{systemPrompt?, messages, tools?}`（＝重构**前**的 `Context`）
            // —— 包 A2 的 R2 把它对齐（原 docs/49 §4.2 F2）。本车道不折叠、不切头：对端自己读
            // 数组里的系统消息。
            var context = JSON.createObjectNode();
            var messages = context.putArray("messages");
            for (var msg : request.transcript().messages()) {
                messages.add(toWireMessage(msg));
            }
            var options = JSON.createObjectNode();
            if (request.temperature() >= 0) {
                options.put("temperature", request.temperature());
            }
            if (request.maxTokens() > 0) {
                options.put("maxTokens", request.maxTokens());
            }
            var body = JSON.createObjectNode();
            body.put("model", request.modelId().modelName());
            body.set("context", context);
            body.set("options", options);
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build pi-messages body", e);
        }
    }

    private static ObjectNode toWireMessage(Message msg) {
        if (msg instanceof Message.UserMessage u) {
            return wireMessage("user", u.content());
        }
        if (msg instanceof Message.AssistantMessage a) {
            return wireMessage("assistant", a.content());
        }
        if (msg instanceof Message.ToolResultMessage t) {
            var node = wireMessage("toolResult", t.content());
            node.put("toolCallId", t.toolUseId());
            node.put("toolName", t.toolName());
            return node;
        }
        if (msg instanceof Message.SystemMessage s) {
            var node = wireMessage("system", s.content());
            // 缺席纪律同 SessionJson 的 A7 规则：pi 写的是 `...(x ? {x} : {})`，
            // 空集合在线上没有对应键，而 Jackson 会把它们照样写出来 ⇒ 主动省略。
            if (s.timestamp() != null) {
                node.put("timestamp", s.timestamp().toEpochMilli());
            }
            if (!s.sections().isEmpty()) {
                // 逐项写，不走 valueToTree：值为 null 的段是 pi 的「删掉这一段」
                // （types.ts:501），而 valueToTree 的 null 处理**取决于映射器的包含策略**
                // —— 本类今天的默认策略保留 null，但把这件事绑在映射器的默认值上是脆的
                // （SessionJson 的映射器带 NON_NULL，同样的写法在那里会把删除项丢掉）。
                var sectionsNode = JSON.createObjectNode();
                for (var entry : s.sections().entrySet()) {
                    if (entry.getValue() == null) {
                        sectionsNode.putNull(entry.getKey());
                    } else {
                        sectionsNode.put(entry.getKey(), entry.getValue());
                    }
                }
                node.set("sections", sectionsNode);
            }
            if (!s.toolsAdded().isEmpty()) {
                node.set("toolsAdded", toolDeclarations(s.toolsAdded()));
            }
            if (!s.toolsRemoved().isEmpty()) {
                node.set("toolsRemoved", JSON.valueToTree(s.toolsRemoved()));
            }
            return node;
        }
        throw new IllegalArgumentException("Unknown message type: " + msg);
    }

    /**
     * pi 的 ai 层 {@code Tool} 形状（{@code types.ts:600-605}）：**三个**字段
     * {@code {name, description, parameters}}。
     *
     * <p>投影本身在 {@link Transcripts#toToolDeclaration} —— 包 B87② 之前这里是**第一个**
     * 按 pi 形状写它的地方，而 {@code SessionJson} 的系统消息落线仍写
     * {@link ToolDefinition} 全形（{@code docs/05} B87② 登记的形状未定问题）。两处现在
     * 共用同一份投影，线上字节因此一致。</p>
     */
    private static ArrayNode toolDeclarations(List<ToolDefinition> tools) {
        var arr = JSON.createArrayNode();
        for (var td : tools) {
            var declaration = Transcripts.toToolDeclaration(td);
            // 逐项写：constrainedSampling 缺席（null）时**不发键** —— 本 mapper 是默认
            // ALWAYS 包含，直接 valueToTree 会写出 null 值（与 pi 的 defined-only 不一致）。
            var node = arr.addObject();
            node.put("name", declaration.name());
            if (declaration.description() != null) {
                node.put("description", declaration.description());
            }
            node.set("parameters", JSON.valueToTree(declaration.parameters()));
            if (declaration.constrainedSampling() != null) {
                node.set("constrainedSampling",
                    JSON.valueToTree(declaration.constrainedSampling()));
            }
        }
        return arr;
    }

    private static ObjectNode wireMessage(String role, List<ContentBlock> blocks) {
        var node = JSON.createObjectNode();
        node.put("role", role);
        node.set("content", toWireContent(blocks));
        return node;
    }

    private static ArrayNode toWireContent(List<ContentBlock> blocks) {
        var arr = JSON.createArrayNode();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) {
                arr.addObject().put("type", "text").put("text", tc.text());
            } else if (block instanceof ContentBlock.ThinkingContent th) {
                arr.addObject().put("type", "thinking").put("thinking", th.text());
            } else if (block instanceof ContentBlock.ToolUseContent tool) {
                arr.addObject().put("type", "toolCall")
                    .put("id", tool.id()).put("name", tool.name())
                    .set("arguments", JSON.valueToTree(tool.arguments()));
            } else if (block instanceof ContentBlock.ImageContent img) {
                arr.addObject().put("type", "image")
                    .put("mediaType", img.mediaType())
                    .put("data", img.data());
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                arr.addObject().put("type", "image_url").put("url", url.url());
            }
        }
        return arr;
    }

    // ── 事件映射辅助 ─────────────────────────────────────────────────────

    /** toolcall_end 的 toolCall.arguments 是权威终值；补喂剩余 delta。 */
    private static void feedToolCallTail(StreamPartialBuilder builder,
                                         SubmissionPublisher<StreamEvent> publisher,
                                         Map<Integer, String> toolJson, int index,
                                         PiToolCall tc) {
        String full;
        try {
            full = JSON.writeValueAsString(tc.arguments());
        } catch (Exception e) {
            full = "{}";
        }
        String acc = toolJson.getOrDefault(index, "");
        if (full.startsWith(acc) && full.length() > acc.length()) {
            publisher.submit(builder.emitToolCallDelta(tc.id(), full.substring(acc.length())));
        }
    }
}

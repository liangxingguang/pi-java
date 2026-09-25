package com.pijava.ai.protocol;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.http.PiHttpClient;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PiHttpClient client;
    private final String baseUrl;
    private final String apiKey;

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
            String body = buildBody(request);
            var headers = Map.of(
                "Authorization", "Bearer " + apiKey,
                "Accept", "text/event-stream");
            Iterator<PiHttpClient.ServerSentEvent> sseEvents =
                client.postSse(baseUrl + "/messages", body, headers);

            while (sseEvents.hasNext()) {
                var sse = sseEvents.next();
                String data = sse.data();
                if (data == null || data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                PiMessagesEvent event = JSON.readValue(data, PiMessagesEvent.class);
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
                        // P4（docs/31 §8.33）：pi 在 thinking_end 上把 contentSignature/redacted
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
                        publisher.submit(builder.emitToolCallEnd(tc.id(), tc.name()));
                        toolJson.remove(e.contentIndex());
                        toolIds.remove(e.contentIndex());
                    }
                    case PiMessagesEvent.Done done -> {
                        sawTerminal = true;
                        if (done.usage() != null) {
                            publisher.submit(builder.emitUsage(
                                (long) done.usage().input(), (long) done.usage().output()));
                        }
                        publisher.submit(builder.emitDone(mapDoneReason(done.reason())));
                        return;
                    }
                    case PiMessagesEvent.Error err -> {
                        sawTerminal = true;
                        publisher.submit(builder.emitError(
                            err.reason() == null ? "error" : err.reason(),
                            new RuntimeException(
                                err.errorMessage() == null ? "pi-messages error" : err.errorMessage())));
                        return;
                    }
                }
            }
            if (!sawTerminal) {
                publisher.submit(builder.emitError("error",
                    new RuntimeException("stream ended without a terminal event")));
            }
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
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

    private static String buildBody(StreamRequest request) {
        try {
            // pi api/pi-messages.ts:374-390 —— body 是 `{model, context, options}`，而
            // `context` **就是** TranscriptContext：只有 `messages`，系统提示与工具声明都在
            // 消息数组里（`9e05370b2` 起形参从 `Context` 改成 `TranscriptContext`）。
            // ⚠️ 旧实现发的是 `{systemPrompt?, messages, tools?}`（＝重构**前**的 `Context`）
            // —— 包 A2 的 R2 把它对齐（docs/49 §4.2 F2）。本车道不折叠、不切头：对端自己读
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
            return JSON.writeValueAsString(body);
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
                node.set("sections", JSON.valueToTree(s.sections()));
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
     * {@link ToolDefinition} 全形（{@code docs/32} B87② 登记的形状未定问题）。两处现在
     * 共用同一份投影，线上字节因此一致。</p>
     */
    private static ArrayNode toolDeclarations(List<ToolDefinition> tools) {
        var arr = JSON.createArrayNode();
        for (var td : tools) {
            arr.add(JSON.valueToTree(Transcripts.toToolDeclaration(td)));
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

    /** pi 的 "toolUse" → pi-java 的 "tool_use"；其余原样。 */
    private static String mapDoneReason(String reason) {
        return "toolUse".equals(reason) ? "tool_use" : reason;
    }

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

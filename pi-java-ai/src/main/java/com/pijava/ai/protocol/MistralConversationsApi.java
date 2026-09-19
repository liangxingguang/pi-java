package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.http.PiHttpClient;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.stream.ToolCallBuilder;

/**
 * Mistral Chat Completions API adapter using raw HTTP + SSE.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}. Mistral has no official Java SDK; uses
 * {@link PiHttpClient} for JSON requests and SSE parsing.</p>
 *
 * <h3>B20：stop reason 从线格读、收尾按 pi 严格判定（docs/31 §8.35.14）</h3>
 *
 * <p>pi 的收尾（{@code mistral-conversations.ts:150-161}）是四段判：abort →
 * {@code pending} → {@code error} → done。本车道此前**一段都没有**，而且读点位置也是错的：
 * 取值在 {@code processSseData} 里读，却排在那道 {@code if (delta == null) return} **之后**
 * ——「只有 finish_reason、没有 delta」的终帧取值被整块丢掉；读到的取值又只翻
 * {@code tool_calls}→{@code tool_use} 一种，其余原样发出去（{@code model_length} 发成
 * {@code "model_length"}、{@code error} 发成 {@code done("error")}），局部变量还兜底成
 * {@code "stop"} 把「什么都没看到」伪装成「正常收尾」。</p>
 *
 * <p>两处与 pi 的取舍已写在各读点旁，汇总：</p>
 * <ul>
 *   <li><b>abort 检查（pi {@code :150}）不可达</b> —— {@code StreamRequest} 没有 signal，
 *       中止由宿主 {@code PiLoopRunner.markAborted} 在流外处理（§8.35.14 第三节 ③）。</li>
 *   <li><b>读点排在 delta 处理之后</b>（pi 是之前）—— 只此一处刻意偏差，理由见读点注释；
 *       pi 的 {@code rawStopReason}（{@code :614}）随第 ⑨ 包（D5）落地，写在同一个读点旁。</li>
 * </ul>
 */
public final class MistralConversationsApi extends AbstractChatApi {

    @Override
    public String apiName() {
        return "mistral-conversations";
    }

    private static final String DEFAULT_BASE_URL = "https://api.mistral.ai/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * pi 累加器的 stop reason 初值（{@code mistral-conversations.ts:222}），
     * 非 pi 词汇表取值；收尾拿它当「一个 finish reason 都没观测到」的哨兵。
     */
    private static final String PENDING = "pending";

    private final PiHttpClient http;
    private final String apiKey;
    private final String baseUrl;

    /**
     * Create an adapter for the given options.
     *
     * @param options API options (apiKey or {@code MISTRAL_API_KEY} required)
     */
    public MistralConversationsApi(ApiOptions options) {
        this.apiKey = resolveApiKey(options);
        this.baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
                ? options.baseUrl() : DEFAULT_BASE_URL;
        this.http = PiHttpClient.builder()
                .userAgent("pi-java/dev")
                .build();
    }

    // ── Internals ─────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        try {
            String jsonBody = buildRequestBody(request);
            var headers = Map.of(
                    "Authorization", "Bearer " + apiKey,
                    "Accept", "text/event-stream");

            publisher.submit(builder.emitStart());

            Iterator<PiHttpClient.ServerSentEvent> sseEvents =
                    http.postSse(baseUrl + "/chat/completions", jsonBody, headers);

            var toolCallBuilders = new HashMap<String, ToolCallBuilder>();
            var textStarted = new boolean[]{false};
            var stop = new StopState();

            while (sseEvents.hasNext()) {
                var sse = sseEvents.next();
                if ("[DONE]".equals(sse.data())) {
                    // pi 的解析器把 `[DONE]` 当作**迭代结束**（mistral-conversations.ts:494
                    // 的哨兵 + `:458` 的 `return`）⇒ 收尾判定在循环**之外**统一跑。
                    // 此前这里提前 `emitDone` + `return`，整段收尾判定被跳过。
                    break;
                }
                processSseData(sse.data(), publisher, builder,
                        toolCallBuilders, textStarted, stop);
            }
            if (textStarted[0]) publisher.submit(builder.emitTextEnd());
            // ⚠️ pi 的 abort 检查（:150）在车道层**结构上不可达** —— `StreamRequest` 没有
            // signal，中止由宿主 `PiLoopRunner.markAborted` 在流外处理（§8.35.14 第三节 ③）。
            if (PENDING.equals(stop.reason)) {
                // 严格收尾（pi :154-155）：一个 finish reason 都没观测到 ⇒ 绝不当成正常结束。
                throw new IllegalStateException("Mistral stream ended without a finish reason");
            }
            // pi :157 还比了 `"aborted"`，那个值只由上面的 abort 检查写入 ⇒ 不可达。
            if ("error".equals(stop.reason)) {
                // pi :158：文案取 `errorMessage`（**映射里**给的），缺失才兜底。
                throw new IllegalStateException(stop.errorMessage != null
                        ? stop.errorMessage : "An unknown error occurred");
            }
            publisher.submit(builder.emitDone(stop.reason));
        } catch (Exception e) {
            // pi 的 catch（:166-171）只发一条 error 就结束 ⇒ 一条流**只有一个**终局事件。
            publisher.submit(builder.emitError("error", e));
        }
    }

    /** pi 的 {@code output.stopReason} + {@code output.errorMessage} 两件套。 */
    private static final class StopState {
        private String reason = PENDING;
        private String errorMessage;
    }

    /**
     * 线格取值 → pi 的 {@code StopReason}（pi {@code mistral-conversations.ts:926-941}）。
     *
     * <p>⚠️ 与 Google 车道的**形状差异**：Mistral 把文案**做进映射**（{@code error} 与
     * {@code default} 两支都自带 {@code Provider stopped with: …}），而 Google 的映射只返回
     * 裸 {@code "error"}、文案由收尾处用 {@code rawStopReason} 拼。两处都照 pi 写，别「统一」。</p>
     *
     * <p>⚠️ 与 completions / Google 又不同的一处：本车道的未知取值 pi **不抛**（落 error 事件），
     * 与 completions 同向、与 Anthropic / Google 反向。</p>
     */
    private static MappedStopReason mapChatStopReason(String reason) {
        if (reason == null) {
            // pi 的 `if (reason === null) return {stopReason:"stop"}`（`:927`）。调用点的
            // 真值守卫已挡掉 null 与空串 ⇒ 流路径上不可达，留着是为了与 pi 逐行同形。
            return new MappedStopReason("stop", null);
        }
        return switch (reason) {
            case "stop" -> new MappedStopReason("stop", null);
            case "length", "model_length" -> new MappedStopReason("length", null);
            case "tool_calls" -> new MappedStopReason("tool_use", null);
            case "error" -> new MappedStopReason("error", "Provider stopped with: error");
            default -> new MappedStopReason("error", "Provider stopped with: " + reason);
        };
    }

    /** 映射结果：pi 的 {@code { stopReason, errorMessage? }}。 */
    private record MappedStopReason(String reason, String errorMessage) {}

    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    private void processSseData(String data,
                                  SubmissionPublisher<StreamEvent> publisher,
                                  StreamPartialBuilder builder,
                                  Map<String, ToolCallBuilder> toolBuilders,
                                  boolean[] textStarted,
                                  StopState stop) {
        try {
            var json = MAPPER.readValue(data, Map.class);
            var choices = (List<Map<String, Object>>) json.get("choices");
            if (choices == null || choices.isEmpty()) return;

            var choice = choices.get(0);
            // delta 缺失不再整段早退：终帧「只有 finish_reason、没有 delta」是合法形状，
            // 原先那道 `if (delta == null) return;` 会把它的取值整块丢掉。
            var delta = (Map<String, Object>) choice.get("delta");
            if (delta != null) {
                // Text delta
                var content = (String) delta.get("content");
                if (content != null && !content.isEmpty()) {
                    if (!textStarted[0]) {
                        publisher.submit(builder.emitTextStart());
                        textStarted[0] = true;
                    }
                    publisher.submit(builder.emitTextDelta(content));
                }

                // Tool call delta
                var toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                if (toolCalls != null) {
                    for (var tc : toolCalls) {
                        var index = String.valueOf(tc.getOrDefault("index", "0"));
                        var tcId = (String) tc.get("id");
                        var function = (Map<String, Object>) tc.get("function");
                        if (function == null) continue;

                        var name = (String) function.get("name");
                        var args = (String) function.get("arguments");

                        var toolBuilder = toolBuilders.computeIfAbsent(index,
                                k -> new ToolCallBuilder());

                        if (tcId != null && name != null && !toolBuilder.isStarted()) {
                            toolBuilder.start(tcId, name);
                            // 包⑥：起点即带身份（上行的 null 判定已保证两者非空）。
                            publisher.submit(builder.emitToolCallStart(tcId, name));
                        }
                        if (args != null) {
                            toolBuilder.append(args);
                            publisher.submit(builder.emitToolCallDelta(
                                    toolBuilder.id(), args));
                        }
                    }
                }
            }

            // Finish reason —— 放在 delta 处理**之后**（pi 是之前，`mistral-conversations.ts:613`）：
            // 下面那步「tool_use 补发 ToolCallEnd」要看工具块是否已完整，而工具块是上面刚折进来
            // 的 ⇒ 顺序反了会把同帧的 tool_call 终帧漏掉。**刻意偏差**，只此一处。
            var reason = (String) choice.get("finish_reason");
            if (reason != null && !reason.isEmpty()) {
                // pi `:614`：原值先落消息（⑨/D5），映射结果再落 stop.reason。
                builder.noteRawStopReason(reason);
                var mapped = mapChatStopReason(reason);
                stop.reason = mapped.reason();
                stop.errorMessage = mapped.errorMessage();
                if ("tool_use".equals(stop.reason)) {
                    for (var tb : toolBuilders.values()) {
                        if (tb.isComplete()) {
                            publisher.submit(builder.emitToolCallEnd(
                                    tb.id(), tb.name()));
                        }
                    }
                }
            }

            // Usage
            var usage = (Map<String, Object>) json.get("usage");
            if (usage != null) {
                long promptTokens = ((Number) usage.getOrDefault("prompt_tokens", 0)).longValue();
                long completionTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).longValue();
                publisher.submit(builder.emitUsage(promptTokens, completionTokens));
            }

        } catch (JsonProcessingException e) {
            // Skip unparseable data lines
        }
    }

    private String buildRequestBody(StreamRequest request) throws JsonProcessingException {
        var body = new HashMap<String, Object>();
        body.put("model", request.modelId().modelName());
        body.put("stream", true);
        body.put("messages", toMistralMessages(request));

        if (!request.tools().isEmpty()) {
            body.put("tools", toMistralTools(request.tools()));
        }
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() >= 0) {
            body.put("temperature", request.temperature());
        }

        return MAPPER.writeValueAsString(body);
    }

    private List<Map<String, Object>> toMistralMessages(StreamRequest request) {
        var messages = new ArrayList<Map<String, Object>>();
        // 系统提示是请求上的独立字段（pi mistral-conversations.ts:523 读 context.systemPrompt），
        // 不在消息列表里；Mistral 用一条 role=system 的消息承载它。
        var systemPrompt = request.systemPrompt();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            var system = new HashMap<String, Object>();
            system.put("role", "system");
            system.put("content", systemPrompt);
            messages.add(system);
        }
        // 共享预通道先于本车道的映射跑（pi mistral-conversations.ts:139 在消息转换前调
        // transformMessages）—— 本车道的 extractText 只收 TextContent，
        // 不过闸则跨模型重放的 thinking 文本无声消失。
        TransformMessages.apply(request.messages(), request.modelId(), apiName())
            .stream().<Map<String, Object>>map(msg -> {
            var m = new HashMap<String, Object>();
            switch (msg) {
                case Message.UserMessage(var content) -> {
                    m.put("role", "user");
                    m.put("content", extractText(content));
                }
                case Message.AssistantMessage a -> {
                    m.put("role", "assistant");
                    m.put("content", extractText(a.content()));
                }
                case Message.ToolResultMessage t -> {
                    // 具名模式而非组件解构：details/usage/addedToolNames 是结构化载荷，
                    // provider 投影只读 toolUseId + content（pi 的适配器同样不读它们）
                    m.put("role", "tool");
                    m.put("tool_call_id", t.toolUseId());
                    m.put("content", extractText(t.content()));
                }
            }
            return m;
        }).forEach(messages::add);
        return messages;
    }

    private List<Map<String, Object>> toMistralTools(List<ToolDefinition> definitions) {
        return definitions.stream().<Map<String, Object>>map(def -> {
            var tool = new HashMap<String, Object>();
            tool.put("type", "function");
            var function = new HashMap<String, Object>();
            function.put("name", def.name());
            function.put("description", def.description());
            function.put("parameters", def.inputSchema());
            tool.put("function", function);
            return tool;
        }).toList();
    }

    private String extractText(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(c -> c instanceof ContentBlock.TextContent)
                .map(c -> ((ContentBlock.TextContent) c).text())
                .reduce("", String::concat);
    }

    private static String resolveApiKey(ApiOptions options) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        var env = System.getenv("MISTRAL_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException(
                "No Mistral API key found. Set MISTRAL_API_KEY or pass apiKey in ApiOptions.");
    }

}

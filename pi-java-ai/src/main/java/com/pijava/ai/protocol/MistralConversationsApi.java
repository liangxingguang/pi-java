package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.http.PiHttpClient;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.stream.ToolCallBuilder;
import com.pijava.ai.utils.SanitizeUnicode;

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
                        toolCallBuilders, textStarted, stop, request.model());
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
                                  StopState stop,
                                  ModelInfo model) {
        try {
            var json = MAPPER.readValue(data, Map.class);

            // Usage —— 位置照 pi：usage 应用（{@code :596-611}）在「choices 空帧早退」
            // （{@code :613} 的 `if (!choice) continue`）**之前**。两个后果：
            // ① Mistral 真会把 usage 放在终局 {@code choices: []} 帧上 —— 早退在前会
            //    整帧丢掉（修复前的形状，M5fix 探针实测恰红这一条）；
            // ② 同帧既有内容又有 usage 时，UsageInfo 排在本帧 delta **之前** —— 与 pi
            //    的 partial 可见性同向（pi 先写 output.usage，后 push 的 delta 带着它）。
            //    UsageInfo 本身是 pi-java 自有的投影（pi 无 usage 事件）。
            var usage = (Map<String, Object>) json.get("usage");
            if (usage != null) {
                publisher.submit(builder.emitUsage(MistralUsage.parse(usage, model)));
            }

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
                    // pi mistral-conversations.ts:626 —— 流式文本增量净化（本车道唯一的响应面落点）。
                    var textDelta = SanitizeUnicode.surrogates(content);
                    if (!textStarted[0]) {
                        publisher.submit(builder.emitTextStart());
                        textStarted[0] = true;
                    }
                    publisher.submit(builder.emitTextDelta(textDelta));
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
            system.put("content", SanitizeUnicode.surrogates(systemPrompt)); // pi :789
            messages.add(system);
        }
        // 共享预通道先于本车道的映射跑（pi mistral-conversations.ts:139 在消息转换前调
        // transformMessages）—— 本车道的 extractText 只收 TextContent，
        // 不过闸则跨模型重放的 thinking 文本无声消失。
        // pi :517 —— 车道的图片能力位来自同一个 model.input.includes("image")。
        boolean supportsImages = request.model().supportsImageInput();
        TransformMessages.apply(request.messages(), request.modelId(), apiName(), request.model(),
                MistralToolCallIds.create())
            .stream().<Map<String, Object>>map(msg -> {
            var m = new HashMap<String, Object>();
            switch (msg) {
                case Message.UserMessage(var content) -> {
                    var userMessage = userMessage(content, supportsImages);
                    if (userMessage != null) {
                        messages.add(userMessage);
                    }
                    return null; // 已自行落线（pi :805/:811/:814 三条分支各有去向）
                }
                case Message.AssistantMessage a -> {
                    m.put("role", "assistant");
                    // pi :822 —— 逐块净化后再拼（不是拼完再净化）。
                    m.put("content", extractSanitizedText(a.content()));
                }
                case Message.ToolResultMessage t -> {
                    // 具名模式而非组件解构：details/usage/addedToolNames 是结构化载荷，
                    // provider 投影只读 toolUseId + content（pi 的适配器同样不读它们）
                    m.put("role", "tool");
                    m.put("tool_call_id", t.toolUseId());
                    // pi :870 —— name 恒带（java 旧实现不发；docs/44 待裁决③ 整块照抄的顺带项）。
                    m.put("name", t.toolName());
                    m.put("content", toolContent(t, supportsImages));
                }
            }
            return m;
        }).filter(java.util.Objects::nonNull).forEach(messages::add);
        return messages;
    }

    // ── 图片（包 H2，docs/44 步4）──────────────────────────────────────

    /**
     * user 消息落线 —— pi {@code mistral-conversations.ts:792-815}。无图片 ⇒ **串形态**（pi-java 的
     * 既有形状；pi 的串分支在 pi-java 结构上不可达，见 {@code docs/44 §6}）；有图片 ⇒ 块数组。
     *
     * @return 该落的消息；{@code null} ＝ pi 的 {@code :814 continue}（整条消息不落线）
     */
    private Map<String, Object> userMessage(List<ContentBlock> content, boolean supportsImages) {
        boolean hadImages = content.stream().anyMatch(MistralConversationsApi::isImageBlock);
        if (!hadImages) {
            var m = new LinkedHashMap<String, Object>();
            m.put("role", "user");
            // pi :795/:802 —— java 的 user 恒为串形态 ⇒ 对齐 pi 的串分支（整串净化）。
            m.put("content", SanitizeUnicode.surrogates(extractText(content)));
            return m;
        }
        var chunks = new ArrayList<Map<String, Object>>();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent tc) {
                chunks.add(textChunk(SanitizeUnicode.surrogates(tc.text())));
            } else if (block instanceof ContentBlock.ImageContent img) {
                chunks.add(imageChunk("data:" + img.mediaType() + ";base64," + img.data()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                // java 扩展（pi 无此类型）：线格本名就是 image_url ⇒ 按它下发（docs/44 D4）。
                chunks.add(imageChunk(url.url()));
            }
        }
        if (!chunks.isEmpty()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("role", "user");
            m.put("content", chunks);
            return m;
        }
        if (hadImages && !supportsImages) {
            // pi :809-811 —— ⚠️ 共享闸已先把图片换成文本块 ⇒ 此分支在两侧都不可达
            // （同 completions 车道的收集门，docs/44 §9）。照抄保留。
            var m = new LinkedHashMap<String, Object>();
            m.put("role", "user");
            m.put("content", "(image omitted: model does not support images)");
            return m;
        }
        return null; // pi :814 —— `continue`
    }

    /**
     * 工具结果的 content 块数组 —— pi {@code :851-874}：文本块在前（{@link #buildToolResultText}），
     * 图片块按 {@code supportsImages} 追加。
     */
    private List<Map<String, Object>> toolContent(Message.ToolResultMessage tool,
                                                  boolean supportsImages) {
        // pi :852-854 —— **逐 part 净化再 join("\n")**（与 Anthropic 的 toolResult 口径相反）。
        var text = tool.content().stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(b -> SanitizeUnicode.surrogates(((ContentBlock.TextContent) b).text()))
                .collect(java.util.stream.Collectors.joining("\n"));
        boolean hasImages = tool.content().stream().anyMatch(MistralConversationsApi::isImageBlock);
        var chunks = new ArrayList<Map<String, Object>>();
        chunks.add(textChunk(
                buildToolResultText(text, hasImages, supportsImages, tool.isError())));
        if (supportsImages) {
            for (var block : tool.content()) {
                if (block instanceof ContentBlock.ImageContent img) {
                    chunks.add(imageChunk("data:" + img.mediaType() + ";base64," + img.data()));
                } else if (block instanceof ContentBlock.UrlImageContent url) {
                    chunks.add(imageChunk(url.url()));
                }
            }
        }
        return chunks;
    }

    /**
     * pi {@code mistral-conversations.ts:877-897} 的 {@code buildToolResultText} —— **整块逐行照抄**
     * （{@code docs/44} 待裁决 ③）：错误前缀 ＋ trim ＋ 不支持时的图片省略后缀 ＋ 三个占位串。
     * ⚠️ 本函数是 java 侧三处**非图片**行为变更的来源：{@code "[tool error] "} 前缀、文本 trim、
     * 空结果的 {@code "(no tool output)"}（旧实现发空串且完全不看 {@code isError}）。
     */
    private static String buildToolResultText(String text, boolean hasImages,
                                              boolean supportsImages, boolean isError) {
        var trimmed = text.trim();
        var errorPrefix = isError ? "[tool error] " : "";
        if (!trimmed.isEmpty()) {
            var imageSuffix = hasImages && !supportsImages
                    ? "\n[tool image omitted: model does not support images]" : "";
            return errorPrefix + trimmed + imageSuffix;
        }
        if (hasImages) {
            if (supportsImages) {
                return isError ? "[tool error] (see attached image)" : "(see attached image)";
            }
            return isError
                    ? "[tool error] (image omitted: model does not support images)"
                    : "(image omitted: model does not support images)";
        }
        return isError ? "[tool error] (no tool output)" : "(no tool output)";
    }

    /** 一个内容块。⚠️ pi 的线格键名是 {@code imageUrl}，序列化时映射成 {@code image_url}（{@code :416}）。 */
    private static Map<String, Object> chunk(String type, String key, String value) {
        var chunk = new LinkedHashMap<String, Object>();
        chunk.put("type", type);
        chunk.put(key, value);
        return chunk;
    }

    private static Map<String, Object> textChunk(String text) {
        return chunk("text", "text", text);
    }

    private static Map<String, Object> imageChunk(String url) {
        return chunk("image_url", "image_url", url);
    }

    /** pi 的图片判据是 {@code type === "image"}；java 的 URL 图片同等对待（docs/44 D4）。 */
    private static boolean isImageBlock(ContentBlock block) {
        return block instanceof ContentBlock.ImageContent
                || block instanceof ContentBlock.UrlImageContent;
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

    /**
     * pi {@code mistral-conversations.ts:822/:853} 的逐块口径：**先净化每块再拼**
     * （不是拼完再净化 —— 跨块边界的孤高+孤低在 pi 会被各自删掉，拼完再净化则会让它们
     * 配对成活 emoji）。拼接方式沿用本车道既有的 {@code concat}（无分隔符），
     * 与 pi 的 {@code join("\n")} 的差别是本车道的既有偏差，不在本包范围内。
     */
    private String extractSanitizedText(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(c -> c instanceof ContentBlock.TextContent)
                .map(c -> SanitizeUnicode.surrogates(((ContentBlock.TextContent) c).text()))
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

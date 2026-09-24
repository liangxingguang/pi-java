package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;

import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.model.CostCalculator;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * Google Gemini API adapter using the official {@code google-genai} SDK.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots.
 * Google returns complete function-call arguments in a single response
 * (no delta aggregation needed).</p>
 *
 * <h3>B20：stop reason 从线格读、收尾按 pi 严格判定（docs/31 §8.35.14）</h3>
 *
 * <p>pi 的收尾（{@code google-generative-ai.ts:264-278}）是四段判：abort →
 * {@code pending} → {@code error} → done。本车道此前**一段都没有**，而且读点本身是错的：
 * 读的是 {@code response} 级访问器，实测（google-genai 1.15.0 源码
 * {@code GenerateContentResponse:352-364}）它在候选没有 finishReason 时自己**造**一个
 * {@code FINISH_REASON_UNSPECIFIED} 返回、**永不返回 null** ⇒ 「没观测到」与
 * 「线格真的发了 {@code FINISH_REASON_UNSPECIFIED}」不可区分（旧代码的 {@code != null}
 * 兜底是死代码）；读到之后又只 {@code toLowerCase()} 就当作取值发出去 —— 线格词表
 * （{@code STOP}/{@code MAX_TOKENS}/{@code SAFETY}…）于是被当成 pi 词表用了。</p>
 *
 * <p>三处与 pi 的取舍已写在各读点旁，汇总：</p>
 * <ul>
 *   <li><b>abort 检查（pi {@code :264}）不可达</b> —— {@code StreamRequest} 没有 signal，
 *       中止由宿主 {@code PiLoopRunner.markAborted} 在流外处理（§8.35.14 第三节 ③）。</li>
 *   <li><b>映射按原始字符串</b>（pi {@code mapStopReason} 收的是 SDK 枚举）—— 实测
 *       google-genai 的 {@code FinishReason.knownEnum()} 对未知值与 SDK 词表缺的
 *       {@code NO_IMAGE} **都**静默吞成 {@code FINISH_REASON_UNSPECIFIED}（两者不可区分，
 *       且都不抛 —— 与 openai-java 的 {@code known()} 抛异常相反），只有 {@code toString()}
 *       保留线格原值。</li>
 *   <li><b>{@code promptFeedback.blockReason} 的自查是 pi-java 扩展</b>（pi 无此分支），
 *       保留不动；它已符合「先 error 后不发 done」。</li>
 * </ul>
 */
public final class GoogleGenerativeAiApi extends AbstractChatApi {

    @Override
    public String apiName() {
        return "google-generative-ai";
    }

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    /**
     * pi 累加器的 stop reason 初值（{@code google-generative-ai.ts:75}），
     * 非 pi 词汇表取值；收尾拿它当「一个 finish reason 都没观测到」的哨兵。
     */
    private static final String PENDING = "pending";

    private final Client client;

    /**
     * Create an adapter for the given options.
     *
     * @param options API options (apiKey or {@code GEMINI_API_KEY} required)
     */
    public GoogleGenerativeAiApi(ApiOptions options) {
        var apiKey = resolveApiKey(options);
        var baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
                ? options.baseUrl() : DEFAULT_BASE_URL;
        this.client = Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().baseUrl(baseUrl).build())
                .build();
    }

    // ── Internals ─────────────────────────────────────────────────

    @Override
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        // pi 累加器初值（google-generative-ai.ts:75）—— **非** pi 词汇表取值，收尾拿它
        // 当「一个 finish reason 都没观测到」的哨兵（`:268`）。
        String stopReason = PENDING;
        // pi 的 output.rawStopReason（`:217`）：映射**前**的线格原值，收尾用它拼错误文案
        // （`:272-273`）。第 ⑨ 包（D5）把它升成消息字段：观测到 finish reason 时下面
        // `builder.noteRawStopReason(...)` 落消息，本局部量留作**同一处**的文案来源
        // （与 pi 一样是「一个量、两个去处」，别只留一个）。
        String rawStopReason = null;
        boolean toolCallSeen = false;
        try {
            // 共享预通道先于本车道的映射跑（pi google-shared.ts:138 在 contents 转换前调
            // transformMessages）—— 跨模型重放的 thinking 块在此降级为文本，
            // 转换器的 ThinkingContent 分支（丢块）才不会把它整段吞掉。
            var contents = GoogleMessageConverter.toContents(TransformMessages.apply(
                request.messages(), request.modelId(), apiName(), request.model(),
                GoogleToolCallIds.create()),
                request.modelId(), request.model());
            var config = buildConfig(request);

            publisher.submit(builder.emitStart());

            boolean textStarted = false;
            boolean thinkingStarted = false;
            try (ResponseStream<GenerateContentResponse> stream =
                         client.models.generateContentStream(
                                 request.modelId().modelName(), contents, config)) {

                for (var response : stream) {
                    // Safety filter check
                    if (response.promptFeedback().isPresent()) {
                        var fb = response.promptFeedback().get();
                        if (fb.blockReason().isPresent()) {
                            publisher.submit(builder.emitError("error",
                                    new IllegalStateException(
                                            "Content blocked by Google safety filter: "
                                                    + fb.blockReason().get())));
                            return;
                        }
                    }

                    // Usage metadata —— pi google-generative-ai.ts:231-250 的逐条移植
                    // （包 H1 步 5，docs/42 §2.1 P12/P13）。
                    if (response.usageMetadata().isPresent()) {
                        var usage = response.usageMetadata().get();
                        long cached = usage.cachedContentTokenCount().orElse(0);
                        // ⚠️ pi 的减法**没有** Math.max(0, …) 钳位（:233-234；vertex 同），
                        // 与 OpenAI 两条车道相反 ⇒ 越界 cached 产出负 input 是 pi 行为。
                        // docs/42 裁决 D「照抄」，夹具 negativeInputIsNotClampedAwayAsPiDoes
                        // 钉着；补钳位会红那条，且须重开裁决 D。
                        long input = usage.promptTokenCount().orElse(0) - cached;
                        long thoughts = usage.thoughtsTokenCount().orElse(0);
                        // P12 双写：thoughts 既折进 output（candidates + thoughts）
                        // 又单设 reasoning —— 一个量、两个去处。
                        long output = usage.candidatesTokenCount().orElse(0) + thoughts;
                        // totalTokens 直取 totalTokenCount（P3：本车道属「直取」派）
                        var u = new Usage(input, output, cached, 0,
                                null, (double) thoughts,
                                usage.totalTokenCount().orElse(0), Usage.Cost.zero());
                        var model = request.model();
                        publisher.submit(builder.emitUsage(model == null
                                ? u
                                : u.withCost(CostCalculator.calculateCost(model.pricing(), u))));
                    }

                    // Process candidates
                    if (response.candidates().isEmpty()) continue;
                    for (var candidate : response.candidates().get()) {
                        // content/parts 缺失**不再** `continue` —— pi 的形状是
                        // `if (candidate?.content?.parts) { … }`（`google-generative-ai.ts:104`），
                        // 而且 finish reason 的读点在**候选体末尾**（`:216`）、必须在同一个
                        // 候选体里跑到（工具块与 finishReason 常在**同一帧**，见下面那步），
                        // `continue` 会把它整块跳过。
                        var maybeParts = candidate.content().flatMap(c -> c.parts());
                        if (maybeParts.isPresent()) {
                            for (var part : maybeParts.get()) {
                                // Thinking block — Google's thought() is a boolean flag
                                // indicating the text content represents model thinking
                                if (part.thought().isPresent() && part.thought().get()
                                        && part.text().isPresent()) {
                                    String thought = part.text().get();
                                    if (!thinkingStarted) {
                                        publisher.submit(builder.emitThinkingStart());
                                        thinkingStarted = true;
                                    }
                                    publisher.submit(builder.emitThinkingDelta(thought));
                                    continue;
                                }

                                // Text
                                if (part.text().isPresent()) {
                                    String text = part.text().get();
                                    if (!textStarted) {
                                        publisher.submit(builder.emitTextStart());
                                        textStarted = true;
                                    }
                                    publisher.submit(builder.emitTextDelta(text));
                                }

                                // Function call (Google returns complete args — no delta)
                                if (part.functionCall().isPresent()) {
                                    FunctionCall fc = part.functionCall().get();
                                    String id = fc.id().orElse(
                                            fc.name().orElse("unknown") + "_"
                                                    + System.currentTimeMillis());
                                    String name = fc.name().orElse("");
                                    Map<String, Object> args = fc.args().orElse(Map.of());

                                    toolCallSeen = true;
                                    // 包⑥：起点即带身份（Google 一次给全，无 delta）。
                                    publisher.submit(builder.emitToolCallStart(id, name));
                                    publisher.submit(builder.emitToolCallDelta(id, ""));
                                    publisher.submit(builder.emitToolCallEnd(id, name));
                                }
                            }
                        }

                        // Finish reason —— 读**候选级** `Optional`，不是 response 级访问器。
                        // 实测（google-genai 1.15.0 源码 `GenerateContentResponse:352-364`）：
                        // response 级访问器在候选**没有** finishReason 时自己**造**一个
                        // `FINISH_REASON_UNSPECIFIED` 返回、**永不返回 null** ⇒ 拿它读，
                        // 「没观测到」与「线格真的发了 FINISH_REASON_UNSPECIFIED」不可区分
                        // （旧代码正是拿它读的，`!= null` 那个兜底是死代码）。
                        // 候选级 `Optional` 才是 pi 的 `if (candidate?.finishReason)` 真值测试的
                        // 同形物；`toString()` 给线格原值（`FinishReason:106-108` 即
                        // `return this.value`），空串按真值测试算「没观测到」。
                        var finish = candidate.finishReason();
                        if (finish.isPresent() && !finish.get().toString().isEmpty()) {
                            rawStopReason = finish.get().toString();
                            // pi `:217-218`：原值先落消息（⑨/D5），映射结果再落 stopReason。
                            builder.noteRawStopReason(rawStopReason);
                            stopReason = mapStopReason(rawStopReason);
                            if (toolCallSeen && "stop".equals(stopReason)) {
                                // pi :219-220 —— Google 的 STOP 同时表示「正常收尾」与
                                // 「调工具收尾」，已有工具块时补成 toolUse。
                                stopReason = "tool_use";
                            }
                        }
                    }
                }
            }
            if (thinkingStarted) publisher.submit(builder.emitThinkingEnd());
            if (textStarted) publisher.submit(builder.emitTextEnd());
            // ⚠️ pi 的 abort 检查（:264）在车道层**结构上不可达** —— `StreamRequest` 没有
            // signal，中止由宿主 `PiLoopRunner.markAborted` 在流外处理（§8.35.14 第三节 ③）。
            if (PENDING.equals(stopReason)) {
                // 严格收尾（pi :268-269）：一个 finish reason 都没观测到 ⇒ **绝不**当成
                // 正常结束。这是本车道此前最大的缺口 —— `null` 兜底成 "stop" 把
                // 「什么都没看到」伪装成「正常收尾」。
                throw new IllegalStateException("Google stream ended without a finish reason");
            }
            // pi 此处还比了 `"aborted"`（:271），那个值只由上面的 abort 检查写入 ⇒
            // 在 pi-java 结构上不可达，故只比 `"error"`。
            if ("error".equals(stopReason)) {
                // pi :271-277：文案由 `rawStopReason` 拼，缺失才兜底。
                throw new IllegalStateException(rawStopReason != null
                        ? "Provider stopped with: " + rawStopReason
                        : "An unknown error occurred");
            }
            publisher.submit(builder.emitDone(stopReason));
        } catch (Exception e) {
            // pi 的 catch（:285-290）只发一条 error 就 `stream.end()` ⇒ 一条流**只有一个**
            // 终局事件；上面的 throw 落在这里，`emitDone` 不会被发出去。
            publisher.submit(builder.emitError("error", e));
        }
    }

    /**
     * 线格原值 → pi 的 {@code StopReason}（pi {@code google-shared.ts:379-411}）。
     *
     * <p>pi 收的是 SDK 枚举、用 exhaustive switch + {@code default: throw}；本车道收的是
     * 原始字符串（理由见 {@code streamInternal} 的读点注释），故把 17 个取值**显式列出**、
     * 其余 throw —— 与 pi 的 switch 逐值等价：枚举之外的线格值在 pi 侧同样落到
     * {@code default}。</p>
     *
     * <p>⚠️ 那 15 个错误取值 pi **不给文案**（返回裸 {@code "error"}），文案由收尾处用
     * {@code rawStopReason} 拼成 {@code Provider stopped with: X} —— 两处分写，别合并。</p>
     */
    private static String mapStopReason(String reason) {
        return switch (reason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "SAFETY",
                 "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION",
                 "IMAGE_OTHER", "RECITATION", "FINISH_REASON_UNSPECIFIED", "OTHER",
                 "LANGUAGE", "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL",
                 "NO_IMAGE" -> "error";
            default -> throw new IllegalStateException("Unhandled stop reason: " + reason);
        };
    }

    private GenerateContentConfig buildConfig(StreamRequest request) {
        var builder = GenerateContentConfig.builder();

        // System instruction —— 请求上的独立字段（pi google-generative-ai.ts:380 读
        // context.systemPrompt），不在消息列表里。
        var systemText = request.systemPrompt();
        if (systemText != null && !systemText.isEmpty()) {
            builder.systemInstruction(
                    // pi google-generative-ai.ts:393 —— systemInstruction 净化（整串）。
                    Content.fromParts(Part.fromText(SanitizeUnicode.surrogates(systemText))));
        }

        if (request.maxTokens() > 0) {
            builder.maxOutputTokens(request.maxTokens());
        }
        if (request.temperature() >= 0) {
            builder.temperature((float) request.temperature());
        }

        // Tools
        if (!request.tools().isEmpty()) {
            builder.tools(List.of(Tool.builder()
                    .functionDeclarations(GoogleMessageConverter.functions(request.tools()))
                    .build()));
        }

        return builder.build();
    }

    private static String resolveApiKey(ApiOptions options) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        var env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException(
                "No Gemini API key found. Set GEMINI_API_KEY or pass apiKey in ApiOptions.");
    }

}

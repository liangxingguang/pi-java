package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.MaxTokensField;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ThinkingTokenBudgetField;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.thinking.ThinkingBudgets;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * 「消息 → OpenAI-completions 线格」的转换 —— 从 {@link OpenAICompletionsApi} 抽出的请求面
 * （包 H2 收尾的拆文件提交；{@code OpenAICompletionsApi} 已 729 行，超 500 行上限）。
 *
 * <p><b>纯函数、零状态</b>：只依赖入参，不碰 client/流 —— 那些留在车道类里。
 * 入口是 {@link #buildParams}（车道与若干夹具都从它观测出参）。</p>
 *
 * <p>⚠️ 本车道的两处**刻意不对称**都有 pi 行号背书（{@code docs/44 D3}）：user 有图分支
 * **不过滤**空文本块；toolResult 的图片**移出** tool 消息、补一条合成 user 消息且**连续合并**。</p>
 *
 * @see OpenAICompletionsApi
 */
final class OpenAICompletionsMessageConverter {

    private OpenAICompletionsMessageConverter() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Wire names accepted as a thinking block's **signature** when replaying it back
     * (pi {@code openai-completions.ts:278}). The signature is what the collect side stamped
     * on the block — i.e. the field the provider actually sent — so replay sends the text
     * back under that same name instead of guessing (see {@link #addAssistantMessage}).
     *
     * <p>⚠️ Deliberately a **second array** with a different order from the collect side's
     * probe list ({@code OpenAICompletionsApi.REASONING_PROBE_FIELDS}): pi's two lists are in
     * different orders ({@code :597} probes {@code reasoning_content} first, {@code :278} lists
     * {@code reasoning} first). Here the order cannot matter (it is an {@code includes} test),
     * but the pair exists for two different questions and must not be collapsed.</p>
     */
    private static final List<String> REASONING_SIGNATURE_FIELDS =
        List.of("reasoning", "reasoning_content", "reasoning_text");

    /**
     * 三参便捷形态（包 A-02 之前的规范入口，全部存量夹具零改签）：retention 取
     * {@link CacheRetention#SHORT} —— 对非 openrouter-anthropic 模型线格**零影响**
     * （断点族被 cacheControlFormat 门关死；prompt_cache_retention 只在 LONG 出现）。
     */
    static ChatCompletionCreateParams buildParams(StreamRequest request, String apiName,
                                                  String baseUrl) {
        return buildParams(request, apiName, baseUrl, CacheRetention.SHORT);
    }

    /**
     * Build the wire request.
     *
     * @param request the stream request
     * @param apiName the lane's name, used by the shared pre-pass
     *                ({@link TransformMessages}) to decide what to downgrade
     * @param baseUrl the adapter's **effective** base URL; replay needs it because the
     *                {@code deepseek}-family relay detection reads it (pi
     *                {@code detectCompat:1592} classifies from {@code model.baseUrl})
     * @param cacheRetention 三源合并后的保留期（包 A-02；pi {@code buildParams} 的同名形参，
     *                {@code :349-357}）—— 断点族与 {@code prompt_cache_retention} 共用
     * @return the request body
     */
    static ChatCompletionCreateParams buildParams(StreamRequest request, String apiName,
                                                  String baseUrl, CacheRetention cacheRetention) {
        // 包 A7：车道的 compat 在这里**解析一次**（pi `getCompat(model)` 的落点，
        // `openai-completions.ts:1685`），下面的读点全部改读它 —— 探测用的是车道传进来的
        // **有效** baseUrl（与 pi 的 `model.baseUrl` 有一处刻意的形状偏差，docs/53 §9 R2）。
        var compat = CompatResolver.forCompletions(request.model(), baseUrl);
        // pi :1225 —— 指令消息的角色。非推理模型即使端点支持 developer 也一律 system。
        var instructionRoleIsDeveloper = request.model() != null
            && request.model().capabilities().contains(ModelCapability.THINKING)
            && Boolean.TRUE.equals(compat.supportsDeveloperRole());
        var builder = ChatCompletionCreateParams.builder()
                .model(request.modelId().modelName());
        // 包 A-02（pi :858-859 的「建完再变异」）：消息与工具先收集进局部表，缓存断点
        // 后处理（CompletionsCacheControl.apply 原位替换）之后再整体入 builder ——
        // SDK 参数对象不可变，倒序 walk 又要求「最后一条挂得上的」在建完前不可知。
        var wire = new ArrayList<ChatCompletionMessageParam>();

        var transcript = Transcripts.resolveTranscript(request.transcript(), compat);
        // 系统文本来自**前导系统消息**（pi :1249 的 `i === 0` 支 → `getSystemMessageText`）。
        // pi 在消息循环里**就地**把它转成 instruction 消息；折叠后头必然在下标 0，
        // 故这里先发它、循环里再跳过它 —— 同一线格顺序。
        var initialSystemMessage = Transcripts.getInitialSystemMessage(transcript.messages());
        var systemText = initialSystemMessage == null
            ? "" : MessageTexts.getSystemMessageText(initialSystemMessage);
        if (!systemText.isEmpty()) {
            // pi :1251 —— instruction/system 文本净化。
            wire.add(instructionMessage(SanitizeUnicode.surrogates(systemText),
                instructionRoleIsDeveloper));
        }

        // 共享预通道必须先于本车道的映射跑（pi openai-completions.ts:1212 在
        // convertMessages 之前调 transformMessages）：跨模型重放的带签名 thinking 块
        // 在此降级为文本，本车道才看得见那段文本。
        var messages = TransformMessages.apply(transcript.messages(), request.modelId(), apiName,
                request.model(), CompletionsToolCallIds.create());
        // 包 A3（R7）：`requireOnlyLeadingSystemMessage` 已删 —— 中途系统消息现在有落线支（见下）。

        // 包 A3（docs/51 §4.4）：**两个标志都要**（pi :1223 的同式 —— 后者注释明写
        // *Requires* 前者），且判据是 `=== true` ⇒ 缺席与 false 同义。
        var transcriptTools = Transcripts.resolveTranscriptTools(transcript.messages(),
            Boolean.TRUE.equals(compat.supportsMidConvoSystemMessages())
                && Boolean.TRUE.equals(compat.supportsMidConvoToolAdditions()));

        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (msg instanceof Message.SystemMessage system) {
                // 包 A3（pi :1238-1252）：前导系统消息已在上面落成 instruction 消息，
                // 这里只处理**中途**的 —— 它们可能顺带带来工具增量。
                if (i == 0) {
                    continue;
                }
                // ⚠️ Kimi 形状：`{role:"system", tools:[…]}` 先于文本落线，**一条系统消息
                // 可能产出两条线上消息**。pi 的 TS 侧靠 `as unknown as` 塞进 SDK 类型；
                // java 侧走 SDK 的未知键直通（原始 JSON 反序列化），通路与逐字节往返
                // 证据见 SdkJsonEscapeHatchTest。只在 `anchorsAdditions` 为真时发 ——
                // 否则那些工具已经在请求级 tools 字段里了。
                if (transcriptTools.anchorsAdditions() && !system.toolsAdded().isEmpty()) {
                    wire.add(kimiToolSystemMessage(
                        system.toolsAdded().stream().map(OpenAICompletionsMessageConverter::toTool)
                            .toList()));
                }
                // pi :1249 —— 非前导走**分段差分更新**渲染（不是完整提示）。
                // ⚠️ 角色与前面那条**同源**：pi 的 `instructionRole` 一处算出、两处用
                // （`:1225` 定义，`:1253` 与 `:1249` 都用它）。
                var update = MessageTexts.renderSystemMessageUpdate(system);
                if (!update.isEmpty()) {
                    wire.add(instructionMessage(SanitizeUnicode.surrogates(update),
                        instructionRoleIsDeveloper));
                }
                continue;
            }
            if (msg instanceof Message.UserMessage user) {
                addUserMessage(wire, user);
            } else if (msg instanceof Message.AssistantMessage assistant) {
                addAssistantMessage(wire, assistant, request.model(), compat);
            } else if (msg instanceof Message.ToolResultMessage) {
                // pi :1398-1455 —— **连续的** toolResult 合成一组：各自落一条 tool 消息，
                // 但图片**合并收集**进**同一条**合成 user 消息（不是一条结果配一条）。
                var imageParts = new ArrayList<ChatCompletionContentPart>();
                int j = i;
                while (j < messages.size()
                        && messages.get(j) instanceof Message.ToolResultMessage tool) {
                    wire.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                        .toolCallId(tool.toolUseId())
                        // pi :1416 —— 净化的是**选中之后**的串（含两个占位串）。
                        .content(SanitizeUnicode.surrogates(toolResultText(tool.content())))
                        .build()));
                    // pi :1424 —— 图片收集**另有**一道能力门（与共享闸冗余，pi 两处都写）。
                    // ⚠️ 这道门在 pi 与 pi-java **两侧都不可观察**：共享闸（TransformMessages）
                    // 已按同一个 model 把非视觉模型的图片换成了文本块 ⇒ 这里永远收不到图片。
                    // 照抄保留（pi 也保留），但**没有任何夹具能钉住它** —— 不是夹具没牙，
                    // 是这一行没有出参（docs/44 §9 的变异探针 4 实测：去掉它零红）。
                    if (request.model().supportsImageInput()) {
                        collectImageParts(tool.content(), imageParts);
                    }
                    j++;
                }
                i = j - 1;
                if (!imageParts.isEmpty()) {
                    // pi :1448-1456 —— 合成的 user 消息（文案逐字）。
                    wire.add(ChatCompletionMessageParam.ofUser(syntheticToolImageMessage(imageParts)));
                }
            }
        }

        // Pass tools so the model emits structured tool_calls instead of
        // writing fake XML tool invocations into the text stream (which also
        // avoids garbled interleaving in the rendered bubble).
        var wireTools = new ArrayList<ChatCompletionTool>();
        for (var td : transcriptTools.requestTools()) {
            wireTools.add(toTool(td));
        }

        // 包 A-02（B105；pi :814 取值、:858-859 落点）：anthropic 形状缓存断点的后处理
        // —— cacheControlFormat 解析成 "anthropic" 且 retention != none 才有断点；
        // 三落点次序（指令消息→工具末项→倒序会话消息）在 CompletionsCacheControl 里照抄。
        CompletionsCacheControl.cacheControlOf(compat, cacheRetention)
            .ifPresent(cc -> CompletionsCacheControl.apply(wire, wireTools, cc));
        wire.forEach(builder::addMessage);
        wireTools.forEach(builder::addTool);

        // pi :825 —— prompt_cache_retention 的门**不含** cacheControlFormat（与断点族独立）。
        // prompt_cache_key（:819-823）不移植：两个合取支都终结在
        // clampOpenAIPromptCacheKey(options?.sessionId)，java 无该通道的生产者（B134；
        // pi 在同样条件下也发不出这个键——clamp(undefined) → undefined）。
        if (cacheRetention == CacheRetention.LONG
                && Boolean.TRUE.equals(compat.supportsLongCacheRetention())) {
            builder.putAdditionalBodyProperty("prompt_cache_retention", JsonValue.from("24h"));
        }

        // Ask for usage in the stream so the token counter/status bar updates.
        builder.streamOptions(ChatCompletionStreamOptions.builder()
            .includeUsage(true)
            .build());

        // pi :832-834 —— 支持就**显式**关掉服务端留存（注意值是 `false`，不是把开关原样发出去）。
        if (Boolean.TRUE.equals(compat.supportsStore())) {
            builder.store(false);
        }
        // pi :836-841 —— 输出上限的**字段名**随端点（非标准端点用 `max_tokens`）。
        // 包 A7 之前这里恒发 `max_completion_tokens` ⇒ deepseek 一类的内置模型线格与 pi 不同。
        if (request.maxTokens() > 0) {
            if (compat.maxTokensField() == MaxTokensField.MAX_TOKENS) {
                builder.maxTokens(request.maxTokens());
            } else {
                builder.maxCompletionTokens(request.maxTokens());
            }
        }
        if (request.temperature() >= 0) builder.temperature(request.temperature());
        // pi :870-871 —— 字段名与预算在形态链条**之前**一次算好、两处消费：链条的
        // `$var: thinking.budget`（包 A-09）与顶层预算字段（包 A-10）同源同值
        // （两侧算法各自零变化，只是上提成一次调用）。
        var budgetField = SimpleOptions.thinkingTokenBudgetField(compat);
        var ceiling = SimpleOptions.maxTokensOrDefault(request.model(), request.maxTokens());
        var thinkingBudget = SimpleOptions.clampedThinkingBudget(request.model(), compat,
            request.reasoning(), ThinkingBudgets.DEFAULT, ceiling);
        // 包 A-09：思考开关的形状（pi :873-970）。
        ThinkingFormatWriter.apply(builder, request, compat, thinkingBudget);
        // 包 A-10：顶层思考预算字段（pi :972-978）。⚠️ 它在形态链条**之外**
        // （注释：同一台服务器可能同时服务 zai/qwen/chat-template 模型），
        // 且落点**先于** samplingParams（pi 的 :976 早于 :997）。
        writeThinkingTokenBudget(builder, budgetField, thinkingBudget);
        // 包 A-02（pi :980-982）：OpenRouter 路由偏好 → body 的 `provider` 键。
        // ⚠️ 读 **raw model.compat**（不是 resolved）—— pi 的读点写的就是
        // `model.compat?.openRouterRouting`；探测/合并面（:1657/:1704）的 `{}` **无读者**
        // ⇒ 这里同样只认显式值：null 不发键、空表发 `provider:{}`（JS 真值语义，
        // 二者线格可区分；docs/59 R6，ModelCompat 的 compact 构造器为此不归一）。
        // ⚠️ 经树（A-09 R11）：SDK mapper 的 NON_NULL inclusion 会静默丢 null 键 ——
        // routing 的值可空（sort.partition，types.ts:884）；本类的 JSON 是无 inclusion
        // 的普通 mapper（与 ThinkingFormatWriter.PLAIN_JSON 同物，就地复用不跨类借）。
        var routing = request.model() == null ? null : request.model().compat().openRouterRouting();
        if (routing != null) {
            builder.putAdditionalBodyProperty("provider", JsonValue.from(JSON.valueToTree(routing)));
        }
        // 包 A-10：模型级采样参数（pi `:996-999`，**body 的最后一个变更** ⇒ 同名键压过具名字段）。
        SamplingParamsWriter.applyToCompletions(builder, request.model());

        return builder.build();
    }

    /**
     * pi {@code openai-completions.ts:976-978}：字段名与预算都在才写。
     * 包 A-09 起取值上提到 {@link #buildParams}（pi 的 {@code :870-871} 同样是先算后用，
     * 形态链条的 {@code $var: thinking.budget} 消费同一个值）—— 取值零变化。
     */
    private static void writeThinkingTokenBudget(ChatCompletionCreateParams.Builder builder,
                                                 Optional<ThinkingTokenBudgetField> field,
                                                 OptionalInt budget) {
        if (field.isEmpty() || budget.isEmpty()) {
            return;
        }
        builder.putAdditionalBodyProperty(field.get().wireName(),
            JsonValue.from(budget.getAsInt()));
    }

    /**
     * pi :1253（前导）与 {@code :1249}（中途更新）共用的一步：按 {@code instructionRole}
     * 造一条指令消息。⚠️ 角色在 pi 里**一处算出、两处用**（{@code :1225} 定义）
     * ⇒ 本仓也必须同源，否则「前导是 developer、中途是 system」这种半截形状会静默出现。
     *
     * <p>包 A-02 起返回参数对象而不是直写 builder —— 缓存断点的倒序 walk 要求消息
     * 先收集完（pi 也是先建 {@code messages} 数组、{@code :858} 再变异）。</p>
     */
    private static ChatCompletionMessageParam instructionMessage(String text,
                                                                 boolean asDeveloperRole) {
        return asDeveloperRole
            ? ChatCompletionMessageParam.ofDeveloper(ChatCompletionDeveloperMessageParam.builder()
                .content(text).build())
            : ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder()
                .content(text).build());
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, JsonValue>();
        schema.forEach((key, value) -> out.put(key, JsonValue.from(value)));
        return out;
    }

    /** pi {@code convertTools} 的 function-tool 支（本仓不支持 grammar 工具，登记 L-A）。 */
    private static ChatCompletionTool toTool(ToolDefinition td) {
        return ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .type(JsonValue.from("function"))
                .function(FunctionDefinition.builder()
                    .name(td.name())
                    .description(td.description())
                    .parameters(FunctionParameters.builder()
                        .putAllAdditionalProperties(toJsonValues(td.inputSchema()))
                        .build())
                    .build())
                .build());
    }

    /**
     * pi {@code openai-completions.ts:1240-1246} 的 **Kimi 形状** ——
     * {@code {role:"system", tools:[…]}}，一条系统消息可以在文本之外**多**产出一条线上消息。
     *
     * <p>⚠️ 这个形状在 openai-java 4.42.0 里**没有类型化对应物**：{@code ChatCompletionMessageParam}
     * 的六个变体没有一个带 {@code tools}，{@code ChatCompletionSystemMessageParam} 只有
     * content/name，且没有 beta 的 chat-completions 命名空间。这里走 SDK 的**未知键直通**
     * （原始 JSON 反序列化 ⇒ 序列化时原样写出），通路与逐字节往返证据（含「省略 content
     * 时线上也不出现 content」）见 {@code SdkJsonEscapeHatchTest}（{@code docs/51 §12.4}）。</p>
     */
    private static ChatCompletionMessageParam kimiToolSystemMessage(List<ChatCompletionTool> tools) {
        var mapper = com.openai.core.ObjectMappers.jsonMapper();
        var node = mapper.createObjectNode();
        node.put("role", "system");
        node.set("tools", mapper.valueToTree(tools));
        try {
            return mapper.treeToValue(node, ChatCompletionMessageParam.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot build the Kimi tool system message", e);
        }
    }

    /**
     * Serializes an assistant message including its tool calls and its reasoning.
     *
     * <p>pi applies **two independent** reasoning rules here, and this method ports both:</p>
     * <ol>
     *   <li><b>Signature-driven replay</b> ({@code :1310-1318}), with **no provider gate**: the
     *       thinking block's signature is the wire name the provider sent that text under, so it
     *       is also the name to send it back under. Only signatures in
     *       {@link #REASONING_SIGNATURE_FIELDS} qualify; anything else (an Anthropic signature,
     *       say) is dropped rather than sent as an unknown parameter. Multiple blocks are joined
     *       with a single {@code "\n"}.</li>
     *   <li><b>Empty-string backfill</b> ({@code :1356-1362}): relay houses in the deepseek family
     *       reject assistant history that lacks {@code reasoning_content}, so one is added as
     *       {@code ""}. Guarded by the compat flag <i>and</i> {@code model.reasoning}, and skipped
     *       when rule (i) already set that exact key.</li>
     * </ol>
     *
     * @param wire     the wire-message list under construction (包 A-02：缓存断点的倒序
     *                 walk 要求先收集后变异，pi 的 {@code messages} 数组同形)
     * @param assistant the assistant message to serialize
     * @param model    the request's target model — its capabilities give pi's
     *                 {@code model.reasoning}
     * @param compat   the request's **resolved** compat ({@link CompatResolver}) — its
     *                 {@code requiresReasoningContentOnAssistantMessages} drives rule (ii)
     */
    private static void addAssistantMessage(
            List<ChatCompletionMessageParam> wire,
            Message.AssistantMessage assistant, ModelInfo model, ModelCompat compat) {
        var text = new StringBuilder();
        var reasoning = new ArrayList<String>();
        String signature = null;
        var toolCalls = new ArrayList<ChatCompletionMessageToolCall>();
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi :1295 —— assistant 文本**逐块**净化后再 join("")（不是拼完再净化：
                // 跨块边界的孤高+孤低在 pi 会被各自删除，拼完再净化则会成对复活）。
                text.append(SanitizeUnicode.surrogates(tc.text()));
            } else if (block instanceof ContentBlock.ThinkingContent thinking) {
                // pi :1289 —— 纯空白块不算推理：既不进连接，也不参与签名的选取。
                if (thinking.text().trim().isEmpty()) {
                    continue;
                }
                // 签名取**第一个非空块**的（pi :1313 取 nonEmptyThinkingBlocks[0]），
                // 不是取第一个已知签名的 —— 块序在这里是有意义的。
                if (signature == null) {
                    signature = thinking.signature();
                }
                reasoning.add(thinking.text());
            } else if (block instanceof ContentBlock.ToolUseContent toolUse) {
                toolCalls.add(ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id(toolUse.id())
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(toolUse.name())
                            .arguments(toArgumentsJson(toolUse.arguments()))
                            .build())
                        .build()));
            }
        }
        var ab = ChatCompletionAssistantMessageParam.builder();
        if (!text.isEmpty()) {
            ab.content(text.toString());
        }
        if (!toolCalls.isEmpty()) {
            ab.toolCalls(toolCalls);
        }

        // 规则 (i)：签名即线格名，原样发回。pi 在这条路径上还有一层 reasoning_details
        // 分支（preservedReasoningDetails，OpenAI 加密推理详情）；pi-java 不解析该结构
        // ⇒ 那个 if 恒真，故不移植。
        boolean reasoningContentSent = false;
        if (signature != null && REASONING_SIGNATURE_FIELDS.contains(signature)) {
            ab.putAdditionalProperty(signature, JsonValue.from(String.join("\n", reasoning)));
            reasoningContentSent = "reasoning_content".equals(signature);
        }

        // 规则 (ii)：deepseek 一类 relay 的助手历史必带 reasoning_content，缺了就补空串。
        // ⚠️ 门是「reasoning_content 这个键还没被写」而不是「什么都没写」—— 签名是
        // `reasoning` 时 pi 会**两个字段都发**（reasoning 有内容、reasoning_content 空串）。
        // ⚠️ 第二个合取项 model.reasoning 是**同一条**门（pi :1357 写在一行里）：非推理模型
        // 即使挂在 deepseek 上也不补，否则等于给普通对话凭空塞一个推理字段。
        // ⚠️ 包 A7：第一个合取项现在来自**解析后**的 compat（探测已收进 CompatResolver，
        // 那段内联的 deepseek 判据随之删除）。
        if (!reasoningContentSent
                && Boolean.TRUE.equals(compat.requiresReasoningContentOnAssistantMessages())
                && model.capabilities().contains(ModelCapability.THINKING)) {
            ab.putAdditionalProperty("reasoning_content", JsonValue.from(""));
        }

        // pi :1365-1372 —— 既无内容又无工具调用的助手消息整条丢掉（有 provider 不接受
        // 空助手消息）。⚠️ reasoning **不算内容**：只带 thinking 的消息就是要丢的那种。
        if (!text.isEmpty() || !toolCalls.isEmpty()) {
            wire.add(ChatCompletionMessageParam.ofAssistant(ab.build()));
        }
    }

    private static String toArgumentsJson(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String extractText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) sb.append(tc.text());
        }
        return sb.toString();
    }

    // ── 图片（包 H2，docs/44 步3）──────────────────────────────────────

    /**
     * user 消息落线 —— pi {@code openai-completions.ts:1255-1277}。
     *
     * <p>无图片 ⇒ **串形态**（pi-java 的既有形状，见 {@code docs/44 §6} 的登记：pi 的串分支
     * 在 pi-java 结构上不可达 —— {@code UserMessage.content} 恒为列表）；有图片 ⇒ **数组形态**
     * {@code [{type:"text"},{type:"image_url",image_url:{url:"data:<mime>;base64,<data>"}}]}。</p>
     *
     * <p>⚠️ 有图分支**不过滤**空文本块（pi {@code :1267} 只判 {@code content.length === 0}）
     * —— 与 Anthropic 的 user 分支（过滤）**刻意不同**，别顺手统一（{@code docs/44 D3}）。</p>
     */
    private static void addUserMessage(List<ChatCompletionMessageParam> wire,
                                       Message.UserMessage user) {
        boolean hasImages = user.content().stream().anyMatch(OpenAICompletionsMessageConverter::isImageBlock);
        if (!hasImages) {
            var text = extractText(user.content());
            // pi :1257 —— user 串形态：整串净化。
            if (!text.isEmpty()) {
                wire.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                    .content(SanitizeUnicode.surrogates(text)).build()));
            }
            return;
        }
        var parts = new ArrayList<ChatCompletionContentPart>();
        for (var block : user.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi :1261 —— 有图分支：**逐项**净化。
                parts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                        .text(SanitizeUnicode.surrogates(tc.text())).build()));
            } else if (block instanceof ContentBlock.ImageContent img) {
                parts.add(imageUrlPart("data:" + img.mediaType() + ";base64," + img.data()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                // java 扩展（pi 无此类型）：image_url 本来就收 URL ⇒ 按线格本名下发（docs/44 D4）。
                parts.add(imageUrlPart(url.url()));
            }
        }
        if (parts.isEmpty()) return; // pi :1267 —— `content.length === 0 ⇒ continue`
        wire.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
                .build()));
    }

    /**
     * 工具结果的 tool 消息正文 —— pi {@code :1405-1412}：
     * 各文本块 {@code join("\n")} 后净化；空串时按「有图 ／ 无图」落两个占位串之一。
     *
     * <p>⚠️ 顺带的行为变更：旧实现是**无分隔符拼接**、且空串原样发（没有 {@code "(no tool output)"}）。
     * 两处都随本步与 pi 对齐。</p>
     */
    private static String toolResultText(List<ContentBlock> content) {
        var text = content.stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(b -> ((ContentBlock.TextContent) b).text())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!text.isEmpty()) return text;
        return content.stream().anyMatch(OpenAICompletionsMessageConverter::isImageBlock)
                ? "(see attached image)" : "(no tool output)";
    }

    /** 收集图片块（能力门在调用点，pi {@code :1424}）。 */
    private static void collectImageParts(List<ContentBlock> content,
                                          List<ChatCompletionContentPart> out) {
        for (var block : content) {
            if (block instanceof ContentBlock.ImageContent img) {
                out.add(imageUrlPart("data:" + img.mediaType() + ";base64," + img.data()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                out.add(imageUrlPart(url.url()));
            }
        }
    }

    /**
     * pi {@code :1448-1456} 的合成 user 消息：一句固定文案 ＋ 收集到的图片块。
     * 文案是纯 ASCII 字面量，pi 也不净化。
     */
    private static ChatCompletionUserMessageParam syntheticToolImageMessage(
            List<ChatCompletionContentPart> imageParts) {
        var parts = new ArrayList<ChatCompletionContentPart>(imageParts.size() + 1);
        parts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                .text("Attached image(s) from tool result:").build()));
        parts.addAll(imageParts);
        return ChatCompletionUserMessageParam.builder()
                .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
                .build();
    }

    private static ChatCompletionContentPart imageUrlPart(String url) {
        return ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(url).build())
                .build());
    }

    /** pi 的图片判据是 {@code type === "image"}；java 的 URL 图片同等对待（docs/44 D4）。 */
    private static boolean isImageBlock(ContentBlock block) {
        return block instanceof ContentBlock.ImageContent
                || block instanceof ContentBlock.UrlImageContent;
    }
}

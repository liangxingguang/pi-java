package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.catalog.CacheBreakpointSpec;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.utils.SanitizeUnicode;

/** Builds the Anthropic Messages request after shared message transformation. */
final class AnthropicRequestBuilder {

    private static final String INTERLEAVED_THINKING_BETA =
            "interleaved-thinking-2025-05-14";

    /** pi {@code anthropic-messages.ts:186} —— 原生工具增删的 beta 头。 */
    private static final String MID_CONVERSATION_TOOL_CHANGES_BETA =
            "mid-conversation-tool-changes-2026-07-01";

    /**
     * pi {@code anthropic-messages.ts:195-200} 的 {@code DEFERRED_TOOL_PLACEHOLDER}。
     *
     * <p>只要请求里**任何**工具有 {@code defer_loading}，Anthropic 就会加隐藏的提示脚手架；
     * 从第一个请求起就声明这个占位符能把那段脚手架留在缓存前缀里，于是第一个真正的
     * 迟到工具不会击穿缓存（pi 注释：实测不这么做会**全量 miss**）。
     * 它永不被激活，模型也看不见。</p>
     */
    private static final String DEFERRED_TOOL_PLACEHOLDER_NAME = "__pi_deferred_placeholder__";

    private AnthropicRequestBuilder() {
    }

    static MessageCreateParams buildParams(StreamRequest request,
                                           Optional<CacheBreakpointSpec> cacheSpec) {
        // 包 A7：车道的 compat 在这里**解析一次**（pi `getAnthropicCompat(model)` 的落点，
        // `anthropic-messages.ts:206`），其余读点全部改读它 —— 不再有第二处读 `model.compat()`。
        var compat = CompatResolver.forAnthropic(request.model());
        // 包 A-01：断点规格（pi `:1041` 的 `getCacheControl` 返回值）在这里翻成 SDK 形状，
        // 一次算好、三处落点共用 —— pi 也是同一个 `cacheControl` 对象挂三处。
        var cacheControl = cacheSpec.map(AnthropicRequestBuilder::toCacheControl).orElse(null);
        // pi `:1114`：工具那一处多一道门。⚠️ 这道门**只**管工具（`system` 与消息照挂）。
        var toolCacheControl = Boolean.TRUE.equals(compat.supportsCacheControlOnTools())
            ? cacheControl : null;
        var transcript = Transcripts.resolveTranscript(request.transcript(), compat);
        var messages = TransformMessages.apply(
                transcript.messages(), request.modelId(), "anthropic-messages", request.model(),
                AnthropicToolCallIds.create());
        var allowEmptySignature = compat.allowEmptySignature();
        var thinking = AnthropicThinking.resolve(
                request.model(),
                compat,
                request.reasoning(),
                request.maxTokens() > 0
                    ? java.util.OptionalInt.of(request.maxTokens())
                    : java.util.OptionalInt.empty());
        var builder = MessageCreateParams.builder()
                .model(request.modelId().modelName())
                .maxTokens(thinking.maxTokens().isPresent()
                    ? thinking.maxTokens().getAsInt()
                    : (request.maxTokens() > 0 ? request.maxTokens() : 4096L));
        thinking.thinking().ifPresent(builder::thinking);
        thinking.outputConfig().ifPresent(builder::outputConfig);

        // 系统文本来自**前导系统消息**（pi :1043-1044），随后把它从会话数组里**切掉**
        // （pi :1044 的 `transformedMessages.slice(1)`）—— 否则它会作为普通消息落线
        // （旧实现就是那样：msg 不是 UserMessage ⇒ 走 ASSISTANT 分支，静默错发）。
        //
        // 包 A-01（同时结案 **B89**）：pi 的顶层 `system` **恒为块数组**
        // （`params.system = [{type:"text", text, …}]`，`:1077-1097`），且 `cacheRetention`
        // 为 `none` 时**也是块**——只是块上没有 `cache_control`（探针 P18 实测）。
        // 故这里是**无条件**的形状修正，与缓存无关。pi 的 OAuth 双块形状（`:1077-1090`）
        // 属 A-15，本仓仍只发一块。
        var initialSystemMessage = Transcripts.getInitialSystemMessage(transcript.messages());
        var systemText = initialSystemMessage == null
                ? "" : MessageTexts.getSystemMessageText(initialSystemMessage);
        if (!systemText.isEmpty()) {
            var systemBlock = TextBlockParam.builder().text(SanitizeUnicode.surrogates(systemText));
            if (cacheControl != null) {
                systemBlock.cacheControl(cacheControl);
            }
            builder.systemOfTextBlockParams(List.of(systemBlock.build()));
        }

        // ⚠️ 切头用的是**变换后**的表（pi 同上），但「有没有头」按**变换前**判定 ——
        // pi :1042-1044 正是这么写的，而第二遍（孤儿合成）不会移动下标 0 的系统消息。
        var conversation = initialSystemMessage == null
                ? messages : messages.subList(1, messages.size());

        var initialTools = initialSystemMessage == null
                ? List.<ToolDefinition>of() : initialSystemMessage.toolsAdded();
        // pi :1050-1055 的**四条件门**。四条都不能少：块只按名引用（表达不了重定义）、
        // Anthropic 拒绝「全部 deferred」的工具表（必须有活跃工具做锚）、
        // 以及那两个 compat 标志（后者注释明写 *Requires* 前者）。
        boolean nativeToolChanges = Boolean.TRUE.equals(compat.supportsMidConvoSystemMessages())
                && Boolean.TRUE.equals(compat.supportsMidConvoToolChanges())
                && !initialTools.isEmpty()
                && !Transcripts.hasToolRedefinitions(transcript.messages());

        var betas = new ArrayList<String>();
        if (request.model() != null
                && request.model().capabilities().contains(com.pijava.ai.model.ModelCapability.THINKING)
                && request.reasoning().isPresent()
                && !compat.forceAdaptiveThinking()) {
            betas.add(INTERLEAVED_THINKING_BETA);
        }
        if (nativeToolChanges) {
            betas.add(MID_CONVERSATION_TOOL_CHANGES_BETA);
        }
        if (!betas.isEmpty()) {
            builder.putAdditionalBodyProperty("betas", JsonValue.from(betas));
        }

        addMessages(builder, conversation, allowEmptySignature, nativeToolChanges, cacheControl);
        addTools(builder, transcript, initialTools, nativeToolChanges, toolCacheControl);

        // pi :1104-1110 —— temperature 是**四重合取**：{@code temperature !== undefined} ＋
        // {@code !thinkingEnabled} ＋ {@code supportsMidConvoEffort !== true} ＋
        // {@code compat.supportsTemperature}。本仓缺第三项（`supportsMidConvoEffort` 的主体行为
        // `block_binding` 在钉住的 SDK 上写不出来，docs/53 §10 B99），前两项就是下面的两个条件。
        if (request.temperature() >= 0 && request.reasoning().isEmpty()
                && compat.supportsTemperature()) {
            builder.temperature(request.temperature());
        }
        return builder.build();
    }

    /**
     * 工具表（pi {@code :1114-1148} 的两支）。
     *
     * <p>原生支的形状是 pi 注释写死的那句「请求级列表**只增不减**」：初始工具保持活跃、
     * 缓存断点挂最后一个上；此后每个声明都是 deferred，只靠 {@code tool_addition} 块浮出；
     * 被删的工具**仍然声明**，由 {@code tool_removal} 撤回。于是这段缓存前缀跨工具变更完整。</p>
     *
     * <p>⚠️ 本仓暂无 {@code cache_control}（A-01），故 pi 的 {@code toolCacheControl}
     * 与「缓存断点允许落在 tool_addition/tool_removal 上」两条无从落（{@code docs/51 §4.4 ⑥}）。</p>
     * <p>包 A-01：断点挂在本支**最后一个工具**上（pi {@code :1489} 的
     * {@code index === tools.length - 1}）。⚠️ 原生支挂的是 <b>{@code initialTools} 的末项</b>
     * —— 迟到工具与占位符**都不挂**（pi 对它们另调一次不带 {@code cacheControl} 的
     * {@code convertTools}），这样缓存前缀跨工具变更完整（探针 P7/P10 实测）。</p>
     */
    private static void addTools(MessageCreateParams.Builder builder,
                                 com.pijava.ai.api.TranscriptContext transcript,
                                 List<ToolDefinition> initialTools, boolean nativeToolChanges,
                                 CacheControlEphemeral toolCacheControl) {
        if (!nativeToolChanges) {
            var tools = Transcripts.getCurrentTools(transcript.messages());
            for (int i = 0; i < tools.size(); i++) {
                builder.addTool(ToolUnion.ofTool(
                    toAnthropicTool(tools.get(i), false, atLast(i, tools.size(), toolCacheControl))));
            }
            return;
        }
        for (int i = 0; i < initialTools.size(); i++) {
            builder.addTool(ToolUnion.ofTool(
                toAnthropicTool(initialTools.get(i), false,
                    atLast(i, initialTools.size(), toolCacheControl))));
        }
        builder.addTool(ToolUnion.ofTool(deferredToolPlaceholder()));
        var initialNames = new HashSet<String>();
        for (var td : initialTools) {
            initialNames.add(td.name());
        }
        for (var td : Transcripts.getDeclaredTools(transcript.messages())) {
            if (initialNames.contains(td.name())) {
                continue;
            }
            builder.addTool(ToolUnion.ofTool(toAnthropicTool(td, true, null)));
        }
    }

    /** pi {@code :1489}：只有末项拿断点。 */
    private static CacheControlEphemeral atLast(int index, int size, CacheControlEphemeral cc) {
        return index == size - 1 ? cc : null;
    }

    /**
     * 工具定义 → Anthropic 的 {@code Tool}；{@code deferLoading} ＝ pi 的
     * {@code defer_loading: true}；{@code cacheControl} 非空时挂断点。
     */
    private static Tool toAnthropicTool(ToolDefinition td, boolean deferLoading,
                                        CacheControlEphemeral cacheControl) {
        var inputSchema = Tool.InputSchema.builder()
                .putAllAdditionalProperties(AnthropicMessageConverter.toJsonValues(td.inputSchema()))
                .build();
        var toolBuilder = Tool.builder()
                .name(td.name())
                .inputSchema(inputSchema);
        if (td.description() != null && !td.description().isBlank()) {
            toolBuilder.description(td.description());
        }
        if (deferLoading) {
            toolBuilder.deferLoading(true);
        }
        if (cacheControl != null) {
            toolBuilder.cacheControl(cacheControl);
        }
        return toolBuilder.build();
    }

    /** pi 的 {@code DEFERRED_TOOL_PLACEHOLDER} 的三个 schema 键，**保序**（type/properties/required）。 */
    private static Tool deferredToolPlaceholder() {
        var schema = new LinkedHashMap<String, JsonValue>();
        schema.put("type", JsonValue.from("object"));
        schema.put("properties", JsonValue.from(Map.of()));
        schema.put("required", JsonValue.from(List.of()));
        return Tool.builder()
                .name(DEFERRED_TOOL_PLACEHOLDER_NAME)
                .description("Reserved placeholder. Never available. Never call this.")
                .inputSchema(Tool.InputSchema.builder().putAllAdditionalProperties(schema).build())
                .deferLoading(true)
                .build();
    }

    /**
     * 消息侧（pi {@code convertMessages}，{@code :1225-1397}）。
     *
     * <p>三条纪律：① 中途系统消息**不就地发**，攒进 {@code pendingSystemMessages}，在
     * <b>下一条 assistant 消息之前</b>（{@code :1309-1310}）或转录末尾（{@code :1397}）刷出 ——
     * Anthropic 要求 {@code tool_result} 紧跟 {@code tool_use}，夹一条系统消息会被拒，
     * 于是「转录里排在 user 消息前的更新，线上落到它之后」；② 系统消息的块序是
     * {@code [text?, …tool_removal, …tool_addition]}（{@code :1250-1270}）；
     * ③ 连续 toolResult 归并成一条 user 消息（z.ai 端点需要）。</p>
     *
     * <p>包 A-01：断点要挂在**组装完成后最后一条消息的末块**上（pi {@code :1398-1424}），
     * 所以这里先攒 {@link Row}、末尾才 wrap 成 {@code MessageParam} —— pi 也是先建
     * {@code params[]} 再改最后一个元素。⚠️ 顺序不可颠倒：pi 的判定发生在
     * {@code flushPendingSystemMessages()} <b>之后</b>，故转录末尾刷出的 held 系统消息
     * 会成为「最后一条」并吃下断点（探针 P10 实测，断点落在 {@code tool_addition} 上）。</p>
     */
    private static void addMessages(MessageCreateParams.Builder builder, List<Message> conversation,
                                    boolean allowEmptySignature, boolean nativeToolChanges,
                                    CacheControlEphemeral cacheControl) {
        var rows = new ArrayList<Row>();
        var pendingSystem = new ArrayList<Row>();
        for (int i = 0; i < conversation.size(); i++) {
            var msg = conversation.get(i);

            if (msg instanceof Message.SystemMessage system) {
                var blocks = new ArrayList<ContentBlockParam>();
                var text = MessageTexts.renderSystemMessageUpdate(system);
                if (!text.isEmpty()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text(SanitizeUnicode.surrogates(text)).build()));
                }
                if (nativeToolChanges) {
                    for (var removed : system.toolsRemoved()) {
                        blocks.add(toolReferenceBlock("tool_removal", removed.name()));
                    }
                    for (var added : system.toolsAdded()) {
                        blocks.add(toolReferenceBlock("tool_addition", added.name()));
                    }
                }
                if (!blocks.isEmpty()) {
                    pendingSystem.add(new Row(MessageParam.Role.SYSTEM, blocks));
                }
                continue;
            }

            if (msg instanceof Message.ToolResultMessage) {
                var resultBlocks = new ArrayList<ContentBlockParam>();
                int j = i;
                while (j < conversation.size()
                        && conversation.get(j) instanceof Message.ToolResultMessage tool) {
                    resultBlocks.add(AnthropicMessageConverter.toToolResultBlock(tool));
                    j++;
                }
                i = j - 1;
                rows.add(new Row(MessageParam.Role.USER, resultBlocks));
                continue;
            }

            // pi 在 assistant 分支的**入口**就刷 pending（即使这条 assistant 最终不落线）
            if (msg instanceof Message.AssistantMessage) {
                flushPendingSystemMessages(rows, pendingSystem);
            }
            var blockParams = AnthropicMessageConverter.toBlockParams(msg, allowEmptySignature,
                    msg instanceof Message.UserMessage);
            if (blockParams.isEmpty()) {
                continue;
            }
            var role = msg instanceof Message.UserMessage
                    ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
            rows.add(new Row(role, blockParams));
        }
        flushPendingSystemMessages(rows, pendingSystem);

        applyConversationCacheBreakpoint(rows, cacheControl);

        for (var row : rows) {
            builder.addMessage(row.toParam());
        }
    }

    /** 一条**尚未 wrap** 的消息：角色 ＋ 块列表。存在的唯一理由是末块的断点判定要在组装之后。 */
    private record Row(MessageParam.Role role, List<ContentBlockParam> blocks) {
        MessageParam toParam() {
            return MessageParam.builder()
                .role(role)
                .content(MessageParam.Content.ofBlockParams(blocks))
                .build();
        }
    }

    /**
     * pi {@code :1398-1424}：断点挂**最后一条**消息的**末块**上，且仅当它是 user 或 system。
     *
     * <p>末条是 assistant 时**一个字都不挂**（探针 P8：连字符串都没转），这与「末块类型不在
     * 白名单」是两条独立的条件。这里用「整条替换」而不是就地改 list ——
     * pi 是 JS 数组原地改，java 的 SDK 对象不可变，且这样也**不依赖** SDK 是否持有 list 的引用。</p>
     */
    private static void applyConversationCacheBreakpoint(List<Row> rows,
                                                         CacheControlEphemeral cacheControl) {
        if (cacheControl == null || rows.isEmpty()) {
            return;
        }
        var last = rows.get(rows.size() - 1);
        if (last.role() != MessageParam.Role.USER && last.role() != MessageParam.Role.SYSTEM) {
            return;
        }
        var blocks = last.blocks();
        if (blocks.isEmpty()) {
            return;
        }
        var patched = withCacheControl(blocks.get(blocks.size() - 1), cacheControl);
        if (patched == null) {
            return;
        }
        var replaced = new ArrayList<>(blocks);
        replaced.set(replaced.size() - 1, patched);
        rows.set(rows.size() - 1, new Row(last.role(), replaced));
    }

    /**
     * 给一个块挂断点（pi {@code :1406-1422} 的白名单分支）。
     *
     * <p>白名单是 {@code text | image | tool_result | tool_addition | tool_removal}。
     * 前三个走 SDK 的 {@code toBuilder()} 重建；后两个是 beta 形状的块，java 侧经**原始 JSON
     * 直通**承载（{@code docs/51 §12.4}），故取 {@code _json()} 加键后回读 ——
     * 这条路径产出的键序与 pi **逐字节相同**（探针 J9）。</p>
     *
     * <p>⚠️ 返回 {@code null} 表示**这个块不在白名单里**（pi 的 {@code else} ⇒ 静默不挂）。
     * 按可达输入算这个分支取不到（user 消息的块只能是 text/image/tool_result，system 消息的
     * 只能是 text/tool_removal/tool_addition，五种全在名单内，见 {@code docs/54 §4.6}）——
     * <b>照抄保留，且这不是夹具没牙</b>：白名单本身就是 pi 的行为，删掉它会在 pi 未来新增
     * 块类型时静默改变行为。</p>
     */
    private static ContentBlockParam withCacheControl(ContentBlockParam block,
                                                      CacheControlEphemeral cacheControl) {
        if (block.isText()) {
            return ContentBlockParam.ofText(
                block.asText().toBuilder().cacheControl(cacheControl).build());
        }
        if (block.isImage()) {
            return ContentBlockParam.ofImage(
                block.asImage().toBuilder().cacheControl(cacheControl).build());
        }
        if (block.isToolResult()) {
            return ContentBlockParam.ofToolResult(
                block.asToolResult().toBuilder().cacheControl(cacheControl).build());
        }
        var raw = block._json().orElse(null);
        if (raw == null) {
            return null;
        }
        var mapper = ObjectMappers.jsonMapper();
        var node = mapper.valueToTree(raw);
        if (!(node instanceof ObjectNode object)) {
            return null;
        }
        var type = object.path("type").asText("");
        if (!"tool_addition".equals(type) && !"tool_removal".equals(type)) {
            return null;
        }
        object.set("cache_control", mapper.valueToTree(cacheControl));
        try {
            return mapper.treeToValue(object, ContentBlockParam.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot attach a cache breakpoint to a raw block", e);
        }
    }

    /**
     * {@link CacheBreakpointSpec} → SDK 的 {@code CacheControlEphemeral}。
     * 常量部分是 {@code {type:"ephemeral"}}（由 SDK 自己写），变量只有长缓存的 {@code ttl}。
     */
    private static CacheControlEphemeral toCacheControl(CacheBreakpointSpec spec) {
        var builder = CacheControlEphemeral.builder();
        if (spec.oneHourTtl()) {
            builder.ttl(CacheControlEphemeral.Ttl.TTL_1H);
        }
        return builder.build();
    }

    private static void flushPendingSystemMessages(List<Row> rows, List<Row> pending) {
        rows.addAll(pending);
        pending.clear();
    }

    /**
     * {@code {type:"tool_addition"|"tool_removal", tool:{type:"tool_reference", name}}}。
     *
     * <p>⚠️ 这两个块**只存在于** {@code com.anthropic.models.beta.messages.*} —— 非 beta 的
     * {@link ContentBlockParam} 没有对应变体。这里用 SDK 的**未知变体直通**（原始 JSON 反序列化，
     * 序列化时原样写出）把 beta 形状装进非 beta 参数，绕开整条车道的类型族迁移。
     * 通路与逐字节往返证据见 {@code SdkJsonEscapeHatchTest}（{@code docs/51 §12.4}）。</p>
     */
    private static ContentBlockParam toolReferenceBlock(String type, String toolName) {
        var tool = new LinkedHashMap<String, Object>();
        tool.put("type", "tool_reference");
        tool.put("name", toolName);
        var block = new LinkedHashMap<String, Object>();
        block.put("type", type);
        block.put("tool", tool);
        var mapper = com.anthropic.core.ObjectMappers.jsonMapper();
        try {
            return mapper.treeToValue(mapper.valueToTree(block), ContentBlockParam.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot build a raw Anthropic content block", e);
        }
    }
}

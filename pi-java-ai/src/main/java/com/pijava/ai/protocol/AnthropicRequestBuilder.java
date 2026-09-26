package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.api.Transcripts;
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

    static MessageCreateParams buildParams(StreamRequest request) {
        // 包 A7：车道的 compat 在这里**解析一次**（pi `getAnthropicCompat(model)` 的落点，
        // `anthropic-messages.ts:206`），其余读点全部改读它 —— 不再有第二处读 `model.compat()`。
        var compat = CompatResolver.forAnthropic(request.model());
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
        var initialSystemMessage = Transcripts.getInitialSystemMessage(transcript.messages());
        var systemText = initialSystemMessage == null
                ? "" : MessageTexts.getSystemMessageText(initialSystemMessage);
        if (!systemText.isEmpty()) {
            builder.system(SanitizeUnicode.surrogates(systemText));
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

        addMessages(builder, conversation, allowEmptySignature, nativeToolChanges);
        addTools(builder, transcript, initialTools, nativeToolChanges);

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
     */
    private static void addTools(MessageCreateParams.Builder builder,
                                 com.pijava.ai.api.TranscriptContext transcript,
                                 List<ToolDefinition> initialTools, boolean nativeToolChanges) {
        if (!nativeToolChanges) {
            for (var td : Transcripts.getCurrentTools(transcript.messages())) {
                builder.addTool(ToolUnion.ofTool(toAnthropicTool(td, false)));
            }
            return;
        }
        for (var td : initialTools) {
            builder.addTool(ToolUnion.ofTool(toAnthropicTool(td, false)));
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
            builder.addTool(ToolUnion.ofTool(toAnthropicTool(td, true)));
        }
    }

    /** 工具定义 → Anthropic 的 {@code Tool}；{@code deferLoading} ＝ pi 的 {@code defer_loading: true}。 */
    private static Tool toAnthropicTool(ToolDefinition td, boolean deferLoading) {
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
     */
    private static void addMessages(MessageCreateParams.Builder builder, List<Message> conversation,
                                    boolean allowEmptySignature, boolean nativeToolChanges) {
        var pendingSystem = new ArrayList<MessageParam>();
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
                    pendingSystem.add(MessageParam.builder()
                        .role(MessageParam.Role.SYSTEM)
                        .content(MessageParam.Content.ofBlockParams(blocks))
                        .build());
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
                builder.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(resultBlocks))
                    .build());
                continue;
            }

            // pi 在 assistant 分支的**入口**就刷 pending（即使这条 assistant 最终不落线）
            if (msg instanceof Message.AssistantMessage) {
                flushPendingSystemMessages(builder, pendingSystem);
            }
            var blockParams = AnthropicMessageConverter.toBlockParams(msg, allowEmptySignature,
                    msg instanceof Message.UserMessage);
            if (blockParams.isEmpty()) {
                continue;
            }
            var role = msg instanceof Message.UserMessage
                    ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
            builder.addMessage(MessageParam.builder()
                    .role(role)
                    .content(MessageParam.Content.ofBlockParams(blockParams))
                    .build());
        }
        flushPendingSystemMessages(builder, pendingSystem);
    }

    private static void flushPendingSystemMessages(MessageCreateParams.Builder builder,
                                                   List<MessageParam> pending) {
        for (var message : pending) {
            builder.addMessage(message);
        }
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

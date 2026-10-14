package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.catalog.CacheBreakpointSpec;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.utils.SanitizeUnicode;

/** Builds the Anthropic Messages request after shared message transformation. */
final class AnthropicRequestBuilder {

    private static final String INTERLEAVED_THINKING_BETA =
            "interleaved-thinking-2025-05-14";

    /**
     * pi {@code anthropic-messages.ts:195} —— 原生工具增删的 beta 头（**定义内联形状**）。
     *
     * <p>{@code b271b0a52} 把旧值 {@code mid-conversation-tool-changes-2026-07-01} 换成了它：
     * 后续工具不再按名引用（{@code tool_reference}），而是在 {@code tool_addition} 块里
     * **按值内联**（{@code tool_definition}），于是**同名重定义**也能表达。</p>
     */
    private static final String INLINE_TOOLS_BETA = "inline-tools-2026-09-15";

    /** B98（pi {@code anthropic-messages.ts:192}）：服务端拒绝回退 beta。 */
    private static final String SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /**
     * 包 09（B90）：pi {@code anthropic-messages.ts:181} —— 不支持
     * {@code eager_input_streaming} 的端点改挂的 beta 头。
     */
    private static final String FINE_GRAINED_TOOL_STREAMING_BETA =
            "fine-grained-tool-streaming-2025-05-14";

    private AnthropicRequestBuilder() {
    }

    static MessageCreateParams buildParams(StreamRequest request,
                                           Optional<CacheBreakpointSpec> cacheSpec) {
        // 直调夹具形态：无亲和头（B103 的 sessionId/baseUrl 由车道传入），无显式 beta 覆写。
        return buildParams(request, cacheSpec, null, null, java.util.Set.of());
    }

    /**
     * B103（docs/52）：带 cacheSessionId（过 cacheRetention=none 门、Bearer 分支给 null）
     * 与有效 baseUrl 的形态；亲和头在 {@code return} 前经 params 级 additionalHeader 发出
     * （pi {@code anthropic-messages.ts:1038-1048}）。
     */
    static MessageCreateParams buildParams(StreamRequest request,
                                           Optional<CacheBreakpointSpec> cacheSpec,
                                           String affinitySessionId, String baseUrl,
                                           java.util.Set<String> effectiveHeaderNames) {
        // 包 A7：车道的 compat 在这里**解析一次**（pi `getAnthropicCompat(model)` 的落点，
        // `anthropic-messages.ts:206`），其余读点全部改读它 —— 不再有第二处读 `model.compat()`。
        var compat = CompatResolver.forAnthropic(request.model(), baseUrl);
        // 包 A-01：断点规格（pi `:1041` 的 `getCacheControl` 返回值）在这里翻成 SDK 形状，
        // 一次算好、三处落点共用 —— pi 也是同一个 `cacheControl` 对象挂三处。
        var cacheControl = cacheSpec.map(AnthropicRequestBuilder::toCacheControl).orElse(null);
        // pi `:1114`：工具那一处多一道门。⚠️ 这道门**只**管工具（`system` 与消息照挂）。
        var toolCacheControl = Boolean.TRUE.equals(compat.supportsCacheControlOnTools())
            ? cacheControl : null;
        // 包 09（B90）：pi `:209` 的缺省是 **true** —— 只有显式 false 才走
        // 「不发 `eager_input_streaming` ＋ 改挂细粒度 beta 头」那一支。
        var eagerToolInputStreaming =
            !Boolean.FALSE.equals(compat.supportsEagerToolInputStreaming());
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
                    : java.util.OptionalInt.empty(),
                request.transcript(),
                request.thinkingBudgets());
        // 包 A-10：`.maxTokens(...)` 的回落**不再是**本仓自造的字面量 `4096L` ——
        // pi 的回落是 `options?.maxTokens ?? model.maxTokens`
        // （`anthropic-messages.ts:1072`，**低层** stream 里也有这一跳）。
        // ⚠️ 回落**不夹取**（pi 只在 streamSimple 里夹）；夹取由 AbstractChatApi →
        // SimpleOptions 在漏斗处做完，故生产路径上 request.maxTokens() 到这里已是解析值。
        // 这条回落服务**直接调 buildParams** 的路径（夹具等），且必须与漏斗共用同一个
        // 助手 —— 否则目录未命中的模型（ModelInfo.minimal，0/0）会产出 `max_tokens: 0`。
        var builder = MessageCreateParams.builder()
                .model(request.modelId().modelName())
                .maxTokens(thinking.maxTokens().isPresent()
                    ? thinking.maxTokens().getAsInt()
                    : SimpleOptions.maxTokensOrDefault(request.model(), request.maxTokens()));
        thinking.thinking().ifPresent(builder::thinking);
        thinking.outputConfig().ifPresent(builder::outputConfig);
        // B99（裁决②）：managed thinking 走原始 JSON 树（typed adaptive 无 block_binding）。
        // 与 typed thinking 互斥（managed 支的 thinking 为空），不会同键两写。
        thinking.rawThinking().ifPresent(tree ->
            builder.putAdditionalBodyProperty("thinking", JsonValue.from(tree)));

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
        // pi :1136-1141 的三条件门（⚠️ `b271b0a52` 前是**四条**，`!hasToolRedefinitions`
        // 已删）：
        // ① 模型接受中途系统消息；② 模型接受原生工具增删块；③ 至少一个初始活跃工具
        //（Anthropic 拒绝「全部 deferred」的工具表 ⇒ 必须有活跃工具给占位符做锚）。
        // **重定义不再是回退判据** —— 定义内联形状本身能表达它（那是这次换形状的动机）。
        boolean nativeToolChanges = Boolean.TRUE.equals(compat.supportsMidConvoSystemMessages())
                && Boolean.TRUE.equals(compat.supportsMidConvoToolChanges())
                && !initialTools.isEmpty();

        var betas = new ArrayList<String>();
        if (request.model() != null
                && request.model().capabilities().contains(com.pijava.ai.model.ModelCapability.THINKING)
                && request.reasoning().isPresent()
                && !compat.forceAdaptiveThinking()) {
            betas.add(INTERLEAVED_THINKING_BETA);
        }
        if (nativeToolChanges) {
            betas.add(INLINE_TOOLS_BETA);
        }
        // 包 09（B90）：pi `:1450-1454` 的两个合取项 —— 工具表非空 **且** 端点不支持 eager。
        if (!eagerToolInputStreaming
                && !Transcripts.getCurrentTools(transcript.messages()).isEmpty()) {
            betas.add(FINE_GRAINED_TOOL_STREAMING_BETA);
        }
        // B98（pi anthropic-messages.ts:211-213/:1116）：模型带 allowedFallbackModels
        // 才挂服务端回退 beta；流内回退块处理随 B148。
        var allowedFallbackModels = compat.allowedFallbackModels();
        if (allowedFallbackModels != null && !allowedFallbackModels.isEmpty()) {
            betas.add(SERVER_SIDE_FALLBACK_BETA);
        }
        // B99（pi :1117-1119）：managed effort 两 beta **无条件**随 flag 挂
        // （不看 thinkingEnabled/effort；managed 模型 forceAdaptive 恒真，
        // interleaved 已被上面的第四合取项自然排除）。
        if (Boolean.TRUE.equals(compat.supportsMidConvoEffort())) {
            betas.add(AnthropicThinking.MID_CONVERSATION_OUTPUT_CONFIG_BETA);
            betas.add(AnthropicThinking.THINKING_BINDING_CONTROLS_BETA);
        }
        // B128 批探针（docs/13 裁决①）实测：stable SDK 不认识 body 的 "betas" 键 ——
        // 它既不被提升成请求头（抓包：请求头集合无 anthropic-beta），只作为未知键留在
        // JSON 体里，服务端收不到 beta 协商。pi 的 TS SDK 是把 betas 编成
        // `anthropic-beta: a,b` **请求头**。故全部 beta 走 putAdditionalHeader
        // （params 级 additionalHeader 经 streaming 路径透传，SessionAffinityHeaders
        // 已证），顺带修正此前 interleaved/inline-tools/fine-grained/fallback 四个
        // beta 一直没真正生效的隐患。
        //
        // B99 裁决③（pi getBetaFeatures :1087-1103）：**覆写陷阱** —— 有效 headers
        // （models.json 的 provider/model headers 合并后）里显式配置了 anthropic-beta
        // （大小写不敏感）⇒ 自动 beta 列表整组跳过（pi 是整体替换，值为 null ⇒ 空列表；
        // java 的 header 值非 null 才进得来，故无需处理 null 支）。
        boolean explicitBetaHeader = effectiveHeaderNames != null && effectiveHeaderNames.stream()
            .anyMatch(name -> name != null
                && "anthropic-beta".equalsIgnoreCase(name.trim()));
        if (!betas.isEmpty() && !explicitBetaHeader) {
            builder.putAdditionalHeader("anthropic-beta", String.join(",", betas));
        }

        // B99（pi :1148）：managedProvider 只在 flag 为真时等于 model.provider；
        // activeEffort ＝队尾 marker 与盖章共用的同一个值（AnthropicThinking.activeEffort）。
        boolean managed = Boolean.TRUE.equals(compat.supportsMidConvoEffort());
        String managedProvider = managed ? request.modelId().provider() : null;
        String activeEffort = managed
            ? AnthropicThinking.activeEffort(request.model(), request.reasoning()) : null;
        addMessages(builder, conversation, allowEmptySignature, nativeToolChanges, cacheControl,
            compat.supportsStrictTools(), eagerToolInputStreaming,
            managed, managedProvider, activeEffort);
        AnthropicToolWire.addTools(builder, transcript, initialTools, nativeToolChanges,
            toolCacheControl, compat.supportsStrictTools(), eagerToolInputStreaming);

        // pi :1104-1110 —— temperature 是**四重合取**：{@code temperature !== undefined} ＋
        // {@code !thinkingEnabled} ＋ {@code supportsMidConvoEffort !== true} ＋
        // {@code compat.supportsTemperature}（B99 批 4 起四项齐全）。
        if (request.temperature() >= 0 && request.reasoning().isEmpty()
                && !Boolean.TRUE.equals(compat.supportsMidConvoEffort())
                && compat.supportsTemperature()) {
            builder.temperature(request.temperature());
        }

        // B98（pi :1281-1284）：请求只发 model；provider/cost 是本地成本元数据（B148）。
        // 空表必须省略 —— Anthropic 对无许可目标的模型拒绝该字段。
        if (allowedFallbackModels != null && !allowedFallbackModels.isEmpty()) {
            var fallbacks = allowedFallbackModels.stream()
                .map(f -> java.util.Map.of("model", f.model()))
                .toList();
            builder.putAdditionalBodyProperty("fallbacks",
                JsonValue.from(ObjectMappers.jsonMapper().valueToTree(fallbacks)));
        }

        // B103（docs/52，pi :1038-1048）：会话亲和头（单头）。Bearer 分支不挂 ——
        // 调用方在 OAuth/copilot 时传 null（pi :992-1031 的 mergeClientHeaders 不带它）。
        SessionAffinityHeaders.anthropic(affinitySessionId,
                Boolean.TRUE.equals(compat.sendSessionAffinityHeaders()),
                compat.sessionAffinityFormat(),
                request.modelId() == null ? null : request.modelId().provider(),
                baseUrl)
            .forEach(builder::putAdditionalHeader);
        return builder.build();
    }

    /**
     * 消息侧（pi {@code convertMessages}，{@code :1225-1397}）。
     *
     * <p>三条纪律：① 中途系统消息**不就地发**，攒进 {@code pendingSystemMessages}，在
     * <b>下一条 assistant 消息之前</b>（{@code :1309-1310}）或转录末尾（{@code :1397}）刷出 ——
     * Anthropic 要求 {@code tool_result} 紧跟 {@code tool_use}，夹一条系统消息会被拒，
     * 于是「转录里排在 user 消息前的更新，线上落到它之后」；② 系统消息的块序是
     * {@code [text?, …tool_removal, …tool_addition]}（{@code :1250-1270}）；但现在
     * {@code tool_addition} 按值内联（{@code :1339-1351}），且「同名新定义取代旧定义」
     * ⇒ 同名时**不发** {@code tool_removal}；③ 连续 toolResult 归并成一条 user 消息
     * （z.ai 端点需要）。</p>
     *
     * <p>⚠️ {@code supportsStrictTools}／{@code eagerToolInputStreaming} 这两个形参是给
     * <b>内联定义</b>用的：它必须与顶层表走**同一套**变换（pi 两侧调的是同一个
     * {@code convertTools}），否则同一份工具定义在两处的形状会分叉。</p>
     *
     * <p>包 A-01：断点要挂在**组装完成后最后一条消息的末块**上（pi {@code :1398-1424}），
     * 所以这里先攒 {@link Row}、末尾才 wrap 成 {@code MessageParam} —— pi 也是先建
     * {@code params[]} 再改最后一个元素。⚠️ 顺序不可颠倒：pi 的判定发生在
     * {@code flushPendingSystemMessages()} <b>之后</b>，故转录末尾刷出的 held 系统消息
     * 会成为「最后一条」并吃下断点（探针 P10 实测，断点落在 {@code tool_addition} 上）。</p>
     */
    private static void addMessages(MessageCreateParams.Builder builder, List<Message> conversation,
                                    boolean allowEmptySignature, boolean nativeToolChanges,
                                    CacheControlEphemeral cacheControl,
                                    boolean supportsStrictTools, boolean eagerToolInputStreaming,
                                    boolean managed, String managedProvider,
                                    String activeEffort) {
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
                    var redefined = new HashSet<String>();
                    for (var added : system.toolsAdded()) {
                        redefined.add(added.name());
                    }
                    for (var removed : system.toolsRemoved()) {
                        // 同名新定义**取代**旧定义 ⇒ 不需要 removal 块（pi :1343）。
                        if (redefined.contains(removed.name())) {
                            continue;
                        }
                        blocks.add(AnthropicToolWire.toolReferenceBlock("tool_removal",
                            removed.name()));
                    }
                    for (var added : system.toolsAdded()) {
                        blocks.add(AnthropicToolWire.toolDefinitionBlock(added, supportsStrictTools,
                            eagerToolInputStreaming));
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
            // B99（pi convertMessages :1449-1461 → insertThinkingLevelMessages :1518-1532）：
            // 合格的**非空**历史 assistant 之前插 effort marker（空块在上面已 continue、
            // 不索引，与 pi :1448 的空 blocks 跳过同）。pending 系统消息已在 assistant
            // 入口刷出，故 marker 紧贴 assistant，线序 user(result) → system(marker) → assistant。
            if (managed && msg instanceof Message.AssistantMessage a
                    && isManagedHistoryAssistant(a, managedProvider)
                    && isAnthropicEffort(a.providerThinkingLevel())) {
                rows.add(new Row(MessageParam.Role.SYSTEM, List.of(),
                    markerOutputConfig(a.providerThinkingLevel())));
            }
            var role = msg instanceof Message.UserMessage
                    ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
            rows.add(new Row(role, blockParams));
        }
        flushPendingSystemMessages(rows, pendingSystem);

        applyConversationCacheBreakpoint(rows, cacheControl);

        // B99（pi insertThinkingLevelMessages :1530）：队尾 active marker **无条件**追加
        // （即使与历史末级相同）。刻意放在断点**之后**——pi 的断点在 convertMessages
        // 内部、insert 之前挂，但 active marker 内容为空不会吃断点；java 这里先断点后
        // 追加，保证 cache_control 永远落在它前面的消息上，形状等价且更直白。
        if (managed) {
            rows.add(new Row(MessageParam.Role.SYSTEM, List.of(),
                markerOutputConfig(activeEffort)));
        }

        for (var row : rows) {
            builder.addMessage(row.toParam());
        }
    }

    /**
     * 一条**尚未 wrap** 的消息：角色 ＋ 块列表（＋ B99 的消息级附加 JSON）。
     * 存在的唯一理由是末块的断点判定要在组装之后。
     *
     * @param messageExtra B99 effort marker 的 {@code output_config:{effort}}（消息级，
     *                     不是块）；普通消息为 {@code null}
     */
    private record Row(MessageParam.Role role, List<ContentBlockParam> blocks,
                       com.fasterxml.jackson.databind.node.ObjectNode messageExtra) {
        Row(MessageParam.Role role, List<ContentBlockParam> blocks) {
            this(role, blocks, null);
        }

        MessageParam toParam() {
            var builder = MessageParam.builder()
                .role(role)
                .content(MessageParam.Content.ofBlockParams(blocks));
            if (messageExtra != null) {
                // marker 形状：{role:"system", content:[], output_config:{effort}}
                builder.putAdditionalProperty("output_config",
                    JsonValue.from(messageExtra));
            }
            return builder.build();
        }
    }

    /**
     * B99（pi {@code types.ts:1514-1516}）：盖章值白名单
     * {@code low|medium|high|xhigh|max}；非法/旧消息（null）不插 marker。
     */
    private static boolean isAnthropicEffort(String value) {
        return "low".equals(value) || "medium".equals(value) || "high".equals(value)
            || "xhigh".equals(value) || "max".equals(value);
    }

    /**
     * B99（pi {@code convertMessages :1454}）：历史 assistant 只有在<b>同 api 同
     * provider</b>（managedProvider 是当前模型的 provider）时才在其前插 marker。
     */
    private static boolean isManagedHistoryAssistant(Message.AssistantMessage a,
                                                      String managedProvider) {
        return "anthropic-messages".equals(a.api())
            && managedProvider != null && managedProvider.equals(a.provider());
    }

    /** B99：marker 的消息级 {@code output_config} 树（{@code {effort}}，键序 effort）。 */
    private static com.fasterxml.jackson.databind.node.ObjectNode markerOutputConfig(
            String effort) {
        var node = ObjectMappers.jsonMapper().createObjectNode();
        node.put("effort", effort);
        return node;
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
     * 直通**承载（{@code 原 docs/51 §12.4}），故取 {@code _json()} 加键后回读 ——
     * 这条路径产出的键序与 pi **逐字节相同**（探针 J9）。</p>
     *
     * <p>⚠️ 返回 {@code null} 表示**这个块不在白名单里**（pi 的 {@code else} ⇒ 静默不挂）。
     * 按可达输入算这个分支取不到（user 消息的块只能是 text/image/tool_result，system 消息的
     * 只能是 text/tool_removal/tool_addition，五种全在名单内，见 {@code 原 docs/54 §4.6}）——
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
}

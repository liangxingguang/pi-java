package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;

import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.catalog.CacheBreakpointSpec;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.utils.SanitizeUnicode;

/** Builds the Anthropic Messages request after shared message transformation. */
final class AnthropicRequestBuilder {

    /**
     * #101（pi {@code buildParams} 的返回形状）：请求参数 ＋ 本轮是否含有
     * 「prefer 且实际 strict」的工具（运行时 400 降级的判据之一）＋ **完整逻辑请求的消息条数**
     * （线程态记录用；`continue` 只发尾部，计数必须按完整请求算，pi {@code :473}）＋
     * **断点前的逐行指纹**与**设置签名**（线程前缀哈希的两半，见
     * {@link AnthropicThreadRequests}）。
     */
    record BuiltParams(MessageCreateParams params, boolean hasPreferredStrictTools,
                       int messageCount, List<String> rowKeys, String settingsKey) {}

    private AnthropicRequestBuilder() {
    }

    static MessageCreateParams buildParams(StreamRequest request,
                                           Optional<CacheBreakpointSpec> cacheSpec) {
        // 直调夹具形态：无亲和头（B103 的 sessionId/baseUrl 由车道传入），无显式 beta 覆写。
        return buildParams(request, cacheSpec, null, null, java.util.Set.of(), false, false)
            .params();
    }

    /**
     * B103（docs/52）：带 cacheSessionId（过 cacheRetention=none 门、Bearer 分支给 null）
     * 与有效 baseUrl 的形态；亲和头在 {@code return} 前经 params 级 additionalHeader 发出
     * （pi {@code anthropic-messages.ts:1038-1048}）。
     */
    static MessageCreateParams buildParams(StreamRequest request,
                                           Optional<CacheBreakpointSpec> cacheSpec,
                                           String affinitySessionId, String baseUrl,
                                           java.util.Set<String> effectiveHeaderNames,
                                           boolean oauth) {
        return buildParams(request, cacheSpec, affinitySessionId, baseUrl,
            effectiveHeaderNames, oauth, false).params();
    }

    /**
     * #101：多一轮 {@code disablePreferredStrictTools}（pi 的运行时降级开关）。
     */
    static BuiltParams buildParams(StreamRequest request,
                                   Optional<CacheBreakpointSpec> cacheSpec,
                                   String affinitySessionId, String baseUrl,
                                   java.util.Set<String> effectiveHeaderNames,
                                   boolean oauth, boolean disablePreferredStrictTools) {
        return buildParams(request, cacheSpec, affinitySessionId, baseUrl, effectiveHeaderNames,
            oauth, disablePreferredStrictTools, null);
    }

    /** 线程形态：{@code null} ≙ 不发 {@code thread}；形态语义见 {@link AnthropicThreadWire}。 */
    static BuiltParams buildParams(StreamRequest request,
                                   Optional<CacheBreakpointSpec> cacheSpec,
                                   String affinitySessionId, String baseUrl,
                                   java.util.Set<String> effectiveHeaderNames,
                                   boolean oauth, boolean disablePreferredStrictTools,
                                   AnthropicThreadWire.ThreadRequest thread) {
        return buildParams(request, cacheSpec, affinitySessionId, baseUrl, effectiveHeaderNames,
            oauth, disablePreferredStrictTools, thread, thread != null);
    }

    /**
     * 线程形态 ＋ **beta 开关**分读（pi {@code :908} 与 {@code :1116}）。
     *
     * <p>⚠️ {@code messageThreads} 不能由 {@code thread != null} 推出：会话被判
     * 「该端点不支持 threads」后（pi 的 {@code "off"}）仍要**照发自证 beta** ——
     * 拒绝 threads 的那次重发就是带着它被接受的（pi 注释，{@code :864-866}）。</p>
     */
    static BuiltParams buildParams(StreamRequest request,
                                   Optional<CacheBreakpointSpec> cacheSpec,
                                   String affinitySessionId, String baseUrl,
                                   java.util.Set<String> effectiveHeaderNames,
                                   boolean oauth, boolean disablePreferredStrictTools,
                                   AnthropicThreadWire.ThreadRequest thread,
                                   boolean messageThreads) {
        // 包 A7：车道的 compat 在这里**解析一次**（pi `getAnthropicCompat(model)` 的落点，
        // `anthropic-messages.ts:206`），其余读点全部改读它 —— 不再有第二处读 `model.compat()`。
        var compat = CompatResolver.forAnthropic(request.model(), baseUrl);
        // 包 A-01：断点规格（pi `:1041` 的 `getCacheControl` 返回值）在这里翻成 SDK 形状，
        // 一次算好、三处落点共用 —— pi 也是同一个 `cacheControl` 对象挂三处。
        var cacheControl = cacheSpec.map(AnthropicRequestBuilder::toCacheControl).orElse(null);
        // 线程 continue 省略 system 与 tools（服务端保存），只发尾部消息（pi :376-382）。
        boolean continuing = thread != null && thread.continuing();
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
        // （`params.system = [{type:"text", text, …}]`，非 OAuth 支 `:1181-1188`），
        // 且 `cacheRetention` 为 `none` 时**也是块**——只是块上没有 `cache_control`。
        //
        // B81（pi `:1168-1188`）：OAuth 车道**恒发 system**，首块固定为 Claude Code
        // 前缀（ASCII 字面量，不过净化），其后才是前导系统文本；两块同挂 cacheControl。
        var initialSystemMessage = Transcripts.getInitialSystemMessage(transcript.messages());
        var systemText = initialSystemMessage == null
                ? "" : MessageTexts.getSystemMessageText(initialSystemMessage);
        if (!continuing && oauth) {
            var systemBlocks = new ArrayList<TextBlockParam>();
            var prefixBlock = TextBlockParam.builder().text(ClaudeCodeNames.SYSTEM_PREFIX);
            if (cacheControl != null) {
                prefixBlock.cacheControl(cacheControl);
            }
            systemBlocks.add(prefixBlock.build());
            if (!systemText.isEmpty()) {
                var ownBlock = TextBlockParam.builder()
                    .text(SanitizeUnicode.surrogates(systemText));
                if (cacheControl != null) {
                    ownBlock.cacheControl(cacheControl);
                }
                systemBlocks.add(ownBlock.build());
            }
            builder.systemOfTextBlockParams(systemBlocks);
        } else if (!continuing && !systemText.isEmpty()) {
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

        // B81：beta 自动列表的拼装（顺序＝pi getBetaFeatures）抽到 AnthropicBetaFeatures。
        var currentWireTools = Transcripts.getCurrentTools(transcript.messages());
        var allowedFallbackModels = compat.allowedFallbackModels();
        var betas = AnthropicBetaFeatures.assemble(request, compat, oauth,
            currentWireTools, eagerToolInputStreaming, allowedFallbackModels,
            nativeToolChanges, messageThreads);
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

        // B99（pi :1148）＋#1：managedProvider 只在 flag 为真**且思考未被关闭**时等于
        // model.provider；activeEffort ＝队尾 marker 与盖章共用的同一个值。
        boolean managed = !AnthropicThinking.thinkingDisabled(request.model(), request.reasoning())
            && Boolean.TRUE.equals(compat.supportsMidConvoEffort());
        String managedProvider = managed ? request.modelId().provider() : null;
        String activeEffort = managed
            ? AnthropicThinking.activeEffort(request.model(), request.reasoning()) : null;
        var messageResult = AnthropicConversationWire.addMessages(
            builder, conversation, allowEmptySignature,
            nativeToolChanges, cacheControl, compat.supportsStrictTools(),
            eagerToolInputStreaming, managed, managedProvider, activeEffort, oauth,
            disablePreferredStrictTools, continuing ? thread.prefixCount() : 0);
        // 线程 continue 不发 tools（服务端保存），pi :376-382 解构掉 tools 同式。
        boolean topLevelPreferred = !continuing
            && AnthropicToolWire.addTools(builder, transcript, initialTools,
                nativeToolChanges, toolCacheControl, compat.supportsStrictTools(),
                eagerToolInputStreaming, oauth, disablePreferredStrictTools);
        boolean hasPreferredStrictTools = messageResult.hasPreferredStrictTools()
            | topLevelPreferred;

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
        // pi :357-359 / :376-382 —— 线程字段。continue 只换 thread，其余设置原样发送。
        AnthropicThreadWire.apply(builder, thread);
        var params = builder.build();
        return new BuiltParams(params, hasPreferredStrictTools,
            messageResult.messageCount(), messageResult.rowKeys(), settingsKey(params, betas));
    }

    /**
     * 线程前缀哈希的**设置**半边（pi {@code hashThreadPrefix :336-347} 的前十个元素）。
     *
     * <p>取的是**已构造**参数的逐字段 {@code toString()}：稳定 SDK 的 {@code MessageCreateParams}
     * 无法反序列化回 JSON（{@code valueToTree} 实测返回 {@code {}}），但它的 {@code toString()}
     * 是 dataclass 式的逐字段打印（{@code Body{maxTokens=…, system=…, tools=…}}），逐字段取用
     * 即得 pi 那份「设置数组」的等价物，且**不需要第二套序列化**（那正是方案 B 的代价）。</p>
     *
     * <p>⚠️ 三处刻意的取舍：</p>
     * <ol>
     * <li>{@code messages} 不进设置（它由 {@link AnthropicConversationWire.Row#key()} 逐行提供，
     *     且不能含 {@code cache_control}）；</li>
     * <li>{@code thread} 从附加体属性里剔掉 —— create／continue 的差异不是设置差异；</li>
     * <li>{@code betas} 在 java 侧是**请求头**（见上面的 B128 说明）而不是体字段，故显式拼入。</li>
     * </ol>
     *
     * <p>⚠️ {@code continue} 形态的 {@code params} <b>不发</b> {@code system}／{@code tools}
     * ⇒ 用它会得到另一个签名。线程判定一律取**完整请求**（{@code thread == null} 那一次）
     * 的 {@link BuiltParams#settingsKey()}，见车道里的用法。</p>
     */
    private static String settingsKey(MessageCreateParams params, List<String> betas) {
        var extras = new java.util.TreeMap<String, JsonValue>(
            params._additionalBodyProperties());
        extras.remove("thread");
        return params._model() + "|" + params._maxTokens() + "|" + params._system()
            + "|" + params._tools() + "|" + params._thinking() + "|" + params._outputConfig()
            + "|" + params._toolChoice() + "|" + params._temperature()
            + "|" + extras
            + "|" + String.join(",", betas);
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

}

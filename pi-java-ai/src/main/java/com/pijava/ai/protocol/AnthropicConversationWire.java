package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;

import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * 会话 → 行 → 线上消息（pi {@code anthropic-messages.ts} 的 {@code convertMessages}，
 * {@code :1225-1424}）。从 {@link AnthropicRequestBuilder} 拆出，只为守住「文件 ≤ 500 行」，
 * 拆缝与 pi 自己的函数边界一致（{@code buildParams} 调 {@code convertMessages}）。
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
final class AnthropicConversationWire {

    private AnthropicConversationWire() {
    }

    static AnthropicThreadWire.MessagesResult addMessages(
                                       MessageCreateParams.Builder builder,
                                       List<Message> conversation,
                                       boolean allowEmptySignature, boolean nativeToolChanges,
                                       CacheControlEphemeral cacheControl,
                                       boolean supportsStrictTools, boolean eagerToolInputStreaming,
                                       boolean managed, String managedProvider,
                                       String activeEffort, boolean oauth,
                                       boolean disablePreferredStrictTools, int prefixCount) {
        boolean hasPreferredStrictTools = false;
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
                            removed.name(), oauth));
                    }
                    for (var added : system.toolsAdded()) {
                        var wired = AnthropicToolWire.toolDefinitionBlock(added, supportsStrictTools,
                            eagerToolInputStreaming, oauth, disablePreferredStrictTools);
                        blocks.add(wired.block());
                        hasPreferredStrictTools |= wired.preferredStrict();
                    }
                }
                if (!blocks.isEmpty()) {
                    pendingSystem.add(new Row(MessageParam.Role.SYSTEM, blocks));
                }
                continue;
            }

            if (msg instanceof Message.ToolResultMessage) {
                var resultBlocks = new ArrayList<ContentBlockParam>();
                var errorImages = new ArrayList<ContentBlockParam>();
                int j = i;
                while (j < conversation.size()
                        && conversation.get(j) instanceof Message.ToolResultMessage tool) {
                    var converted = AnthropicMessageConverter.convertToolResult(tool);
                    resultBlocks.add(converted.block());
                    if (converted.caption() != null) {
                        errorImages.add(ContentBlockParam.ofText(TextBlockParam.builder()
                            .text(converted.caption()).build()));
                        converted.images().forEach(image ->
                            errorImages.add(ContentBlockParam.ofImage(image)));
                    }
                    j++;
                }
                resultBlocks.addAll(errorImages);
                i = j - 1;
                rows.add(new Row(MessageParam.Role.USER, resultBlocks));
                continue;
            }

            // pi 在 assistant 分支的**入口**就刷 pending（即使这条 assistant 最终不落线）
            if (msg instanceof Message.AssistantMessage) {
                flushPendingSystemMessages(rows, pendingSystem);
            }
            var blockParams = AnthropicMessageConverter.toBlockParams(msg, allowEmptySignature,
                    msg instanceof Message.UserMessage, oauth);
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

        // 线程前缀指纹必须在**挂断点之前**取：cache_control 会移动，进指纹会让每轮都判前缀变了。
        var rowKeys = rowKeys(rows);

        applyConversationCacheBreakpoint(rows, cacheControl);

        // B99（pi insertThinkingLevelMessages :1530）：队尾 active marker **无条件**追加
        // （即使与历史末级相同）。刻意放在断点**之后**——pi 的断点在 convertMessages
        // 内部、insert 之前挂，但 active marker 内容为空不会吃断点；java 这里先断点后
        // 追加，保证 cache_control 永远落在它前面的消息上，形状等价且更直白。
        if (managed) {
            rows.add(new Row(MessageParam.Role.SYSTEM, List.of(),
                markerOutputConfig(activeEffort)));
        }

        // 线程 continue：只写 prefixCount 之后的尾行（pi 的 `messages.slice(prefixCount)`）。
        // 断点在上方对**完整** rows 已挂好，尾部切片自然带着自己那份标记，与 pi 同式。
        int messageCount = rows.size();
        int from = Math.min(prefixCount, messageCount);
        for (int i = from; i < messageCount; i++) {
            builder.addMessage(rows.get(i).toParam());
        }
        return new AnthropicThreadWire.MessagesResult(hasPreferredStrictTools, messageCount,
            rowKeys);
    }

    /** 逐行指纹（线程前缀哈希的原料，见 {@link Row#key()}）。 */
    static List<String> rowKeys(List<Row> rows) {
        var out = new ArrayList<String>(rows.size());
        for (var row : rows) {
            out.add(row.key());
        }
        return List.copyOf(out);
    }

    /** 一行的指纹（{@link Row#key()} 的对外形态 —— 回复行记账用，见 {@link AnthropicThreadRequests}）。 */
    static String rowKey(MessageParam.Role role, List<ContentBlockParam> blocks) {
        return new Row(role, blocks).key();
    }

    /**
     * 一条**尚未 wrap** 的消息：角色 ＋ 块列表（＋ B99 的消息级附加 JSON）。
     * 存在的唯一理由是末块的断点判定要在组装之后。
     *
     * @param messageExtra B99 effort marker 的 {@code output_config:{effort}}（消息级，
     *                     不是块）；普通消息为 {@code null}
     */
    record Row(MessageParam.Role role, List<ContentBlockParam> blocks,
               com.fasterxml.jackson.databind.node.ObjectNode messageExtra) {
        Row(MessageParam.Role role, List<ContentBlockParam> blocks) {
            this(role, blocks, null);
        }

        /**
         * 行的**确定性指纹**（线程前缀哈希的原料，pi {@code hashThreadPrefix} 的消息半边）。
         *
         * <p>为什么不用线上 JSON：稳定 SDK 的 {@code MessageCreateParams} 反序列化回来是
         * {@code {}}（实测），而 {@code MessageParam.toString()} 里的块会带上 {@code cache_control}
         * —— 那个标记**会移动**（挂到最新消息上），进指纹就会让每轮都判「前缀变了」、线程永不续上。
         * 本方法在 {@link #applyConversationCacheBreakpoint} <b>之前</b>被调用，块上还没有标记，
         * 且刻意只取 SDK 的 {@code toString()}（同一版本内稳定、不依赖额外序列化）。</p>
         *
         * <p>⚠️ 字符串 content 与块数组的形态差异在 java 侧不存在 —— 本仓一律以块数组落线
         * （pi {@code :1360-1362} 的归一在转录层就已发生）。</p>
         */
        String key() {
            // SDK 的 Role 不是 enum（带 value 包装类），故取 toString()：同一版本内稳定。
            var out = new StringBuilder(role.toString());
            if (messageExtra != null) {
                out.append('|').append(messageExtra);
            }
            for (var block : blocks) {
                out.append('\n').append(block);
            }
            return out.toString();
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
        var patched = AnthropicCacheBreakpoint.withCacheControl(
            blocks.get(blocks.size() - 1), cacheControl);
        if (patched == null) {
            return;
        }
        var replaced = new ArrayList<>(blocks);
        replaced.set(replaced.size() - 1, patched);
        rows.set(rows.size() - 1, new Row(last.role(), replaced));
    }

    private static void flushPendingSystemMessages(List<Row> rows, List<Row> pending) {
        rows.addAll(pending);
        pending.clear();
    }
}

package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * 「消息 → Anthropic 线格」的转换 —— 从 {@link AnthropicMessagesApi} 抽出的请求面
 * （包 H2 收尾的拆文件提交；{@code AnthropicMessagesApi} 已 696 行，超 500 行上限）。
 *
 * <p><b>纯函数、零状态</b>：只依赖入参，不碰 client/流/鉴权 —— 那些留在车道类里。
 * 抽出的边界就是「一个块变成什么线格」，与 pi 的 {@code convertMessages} 内层一致。</p>
 *
 * <p>⚠️ 这里的每一处**刻意不对称**都有 pi 行号背书（{@code docs/44 D3}），改动前先读注释：
 * user 分支过滤空文本块、toolResult 分支不滤且补占位块、assistant 分支忽略图片。</p>
 *
 * @see AnthropicMessagesApi
 */
final class AnthropicMessageConverter {

    private AnthropicMessageConverter() {}

    /**
     * 一条消息的块 → 线格。{@code allowImages} 只在 **user** 消息上为真
     * （pi {@code anthropic-messages.ts:1307-1369} 的 assistant 分支**没有** image 分支）。
     */
    static List<ContentBlockParam> toBlockParams(Message msg, boolean allowEmptySignature,
                                                 boolean allowImages) {
        var result = new ArrayList<ContentBlockParam>();
        for (var block : msg.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi 两条车道都按 trim 判空丢弃文本块：assistant 车道 `:1282`
                // （`if (block.text.trim().length === 0) continue;`）、user 车道
                // `:1262-1268` 的 filteredBlocks + `:1269 continue`（另有字符串形态内容
                // 的 `:1241-1246`）。pi-java 的 toBlockParams 两条车道共用 ⇒ 一处即够。
                // 整个消息的块被清空后不再落线，由调用点 `blockParams.isEmpty()` 承担，
                // 对应 pi 的 `:1269`/`:1331` 两处 continue。
                if (tc.text() == null || tc.text().trim().isEmpty()) {
                    continue;
                }
                // pi :1276(user 串)/:1284(user 文本块)/:1318(assistant 文本块) —— 一律净化。
                result.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(SanitizeUnicode.surrogates(tc.text())).build()));
            } else if (block instanceof ContentBlock.ThinkingContent th) {
                appendThinkingBlock(result, th, allowEmptySignature);
            } else if (block instanceof ContentBlock.ToolUseContent tu) {
                var input = ToolUseBlockParam.Input.builder()
                        .putAllAdditionalProperties(toJsonValues(tu.arguments()))
                        .build();
                result.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(tu.id())
                        .name(tu.name())
                        .input(input)
                        .build()));
            } else if (allowImages && block instanceof ContentBlock.ImageContent img) {
                result.add(ContentBlockParam.ofImage(toImageBlock(img)));
            }
            // 其余变体静默丢弃，**逐条有据**：
            //  - ImageContent：只在 user 车道发（pi :1250-1260）；assistant 分支 pi
            //    压根没有 image 分支（`:1307-1369` 的 if text / else if thinking /
            //    else if toolCall，无 else）⇒ 靠 `allowImages` 挡掉，照抄。
            //  - UrlImageContent：java 扩展（pi 无此类型）。SDK 虽有 UrlImageSource，
            //    但 pi 的 TS 类型里没有 URL 图片来源 ⇒ 用它＝发明行为（docs/44 D4 选项 A）。
            //  - DiffContent：显示专用（ContentBlock.DiffContent 的 javadoc）。
        }
        return result;
    }

    /**
     * 一块 {@code ImageContent} 落成什么线格 —— pi {@code anthropic-messages.ts:1250-1260}（user）
     * 与 {@code :156-163}（tool result，同形）：
     * {@code {type:"image", source:{type:"base64", media_type, data}}}。
     *
     * <p>⚠️ {@code media_type} 是**照抄**，不做白名单：pi 的联合类型只写
     * jpeg/png/gif/webp，但那是 TS 的 {@code as} 断言（运行时不校验），而
     * {@code PathUtils.detectImageMimeType} 会返回 {@code image/bmp} ⇒ pi 也会把 bmp 发出去。
     * 照缝（{@code docs/44 J14}）；真被 provider 拒的话，两侧一起拒。</p>
     */
    private static ImageBlockParam toImageBlock(ContentBlock.ImageContent img) {
        return ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.of(img.mediaType()))
                        .data(img.data())
                        .build())
                .build();
    }

    /**
     * 落线：一块 thinking 变成什么线格（pi {@code anthropic-messages.ts:1287-1321}，逐分支对照）。
     *
     * <pre>
     * block.redacted                  → {type:"redacted_thinking", data: signature}   // :1289-1294
     * hasSignature = !!sig &amp;&amp; trim 非空                                                  // :1296
     * text trim 为空 且 无签名          → 丢弃                                             // :1298
     * 无签名 → allowEmptySignature ? {type:"thinking",thinking,signature:""} : {type:"text",text}  // :1300-1312
     * 有签名                          → {type:"thinking",thinking,signature}           // :1313-1319
     * </pre>
     *
     * <p>⚠️ 此处**不再判同模型/异模型**：那个决定已由闸（{@code TransformMessages}）做完，
     * 能走到这里的 thinking 恒是同模型的（异模型的在闸里已降级成 TextContent 或被丢弃）。
     * pi 在同一位置也不判身份 —— 判身份的是 {@code transformMessages} 那一层。</p>
     *
     * <p>⚠️ 与 pi 的**一处刻意偏差**：pi `:1316` 落线的是**未 trim** 的
     * {@code thinkingSignature}（它只在 `:1296` 的判空里 trim 过）。pi-java 落 trim 后的值
     * （沿用包①之前 `:318` 的写法）。差别只在签名首尾带空白时可见，而真 Anthropic 的
     * 签名是无空白 base64；两处判空语义一致，故**不改**这一处（§8.34.6-2）。</p>
     */
    private static void appendThinkingBlock(List<ContentBlockParam> result,
                                            ContentBlock.ThinkingContent th,
                                            boolean allowEmptySignature) {
        if (th.redacted()) {
            result.add(ContentBlockParam.ofRedactedThinking(
                    RedactedThinkingBlockParam.builder().data(th.signature()).build()));
            return;
        }
        var signature = th.signature() == null ? "" : th.signature().trim();
        var text = th.text() == null ? "" : th.text();
        var hasSignature = !signature.isEmpty();
        if (text.trim().isEmpty() && !hasSignature) {
            return;
        }
        // pi :1340/:1345/:1351 —— thinking 三分支的文本一律净化（值域同一，一处即可）。
        var sanitized = SanitizeUnicode.surrogates(text);
        if (!hasSignature) {
            result.add(allowEmptySignature
                    ? ContentBlockParam.ofThinking(
                        com.anthropic.models.messages.ThinkingBlockParam.builder()
                                .thinking(sanitized)
                                .signature("")
                                .build())
                    : ContentBlockParam.ofText(
                        TextBlockParam.builder().text(sanitized).build()));
            return;
        }
        result.add(ContentBlockParam.ofThinking(
                com.anthropic.models.messages.ThinkingBlockParam.builder()
                        .thinking(sanitized)
                        .signature(signature)
                        .build()));
    }

    /** 一条工具结果 → 一个 {@code tool_result} 块（pi {@code anthropic-messages.ts:1210-1217}）。 */
    static ContentBlockParam toToolResultBlock(Message.ToolResultMessage tool) {
        var resultContent = ToolResultBlockParam.Content.ofBlocks(
                convertContentBlocks(tool.content()));
        var toolResult = ToolResultBlockParam.builder()
                .toolUseId(tool.toolUseId())
                .content(resultContent)
                .isError(tool.isError())
                .build();
        return ContentBlockParam.ofToolResult(toolResult);
    }

    /**
     * 工具结果的 content 落线 —— pi {@code anthropic-messages.ts:128-174} 的
     * {@code convertContentBlocks}（**唯一**调用者 {@code :1216}＝{@code convertToolResult}），
     * 逐分支对照：
     *
     * <pre>
     * 无图片 ⇒ 各文本块 join("\n") 成**一个**块，净化的是**拼好之后**的串     // :142-144
     * 有图片 ⇒ 逐块映射（text 净化 ／ image 走 {type:"image",source:{…}}）      // :147-163
     * 映射后**没有文本块**（判的是块类型，不是非空）⇒ 头部插 "(see attached image)"  // :166-172
     * </pre>
     *
     * <p>⚠️ 三处与 user 分支（{@link #toBlockParams}）**刻意不同**，别顺手统一（{@code docs/44 D3}）：
     * ① 空文本块**不**过滤；② 无文本时**补**占位块；③ 多文本块**合并**成一个。</p>
     *
     * <p>⚠️ 顺带的行为变更（包 H2 步2）：旧实现 {@code toTextBlocks} 是「一块一文本块」，
     * 本方法改成 join 成一个 —— 与 pi 对齐（真实工具都只产一个文本块，线上不可观察）。</p>
     *
     * <p>⚠️ 净化位置也跟着 pi 从「逐块」挪到「拼完」（{@code docs/43 §9-2} 记的两种口径之别）；
     * 不过 {@code "\n"} 永不是代理 ⇒ 在**这条**路径上两种顺序结果恒等（{@code docs/44 §9}）。</p>
     */
    private static List<ToolResultBlockParam.Content.Block> convertContentBlocks(
            List<ContentBlock> blocks) {
        boolean hasImages = blocks.stream()
                .anyMatch(ContentBlock.ImageContent.class::isInstance);
        if (!hasImages) {
            // pi :142-144 —— `content.map(c => c.text).join("\n")` 后净化。
            String joined = blocks.stream()
                    .filter(ContentBlock.TextContent.class::isInstance)
                    .map(b -> ((ContentBlock.TextContent) b).text())
                    .collect(java.util.stream.Collectors.joining("\n"));
            return List.of(ToolResultBlockParam.Content.Block.ofText(
                    TextBlockParam.builder().text(SanitizeUnicode.surrogates(joined)).build()));
        }
        var out = new ArrayList<ToolResultBlockParam.Content.Block>(blocks.size());
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) {
                out.add(ToolResultBlockParam.Content.Block.ofText(
                        TextBlockParam.builder().text(SanitizeUnicode.surrogates(tc.text())).build()));
            } else if (block instanceof ContentBlock.ImageContent img) {
                out.add(ToolResultBlockParam.Content.Block.ofImage(toImageBlock(img)));
            }
            // pi 的 content 类型只可能是 Text 或 Image（`:128` 的入参签名）⇒ 它那句
            // `block.type === "text" ? text : image` 的 else 只可能是图片。java 的
            // List<ContentBlock> 无限定（Thinking/ToolUse/Diff 都可能出现）⇒ 这里**跳过**
            // 而不是像 pi 那样把它们当图片发出去（pi 那条分支在类型上不可达）。
        }
        // pi :166 —— `hasText` 判的是**块类型**：一个空文本块照样算「有文本」。
        boolean hasText = out.stream().anyMatch(ToolResultBlockParam.Content.Block::isText);
        if (!hasText) {
            out.add(0, ToolResultBlockParam.Content.Block.ofText(
                    TextBlockParam.builder().text("(see attached image)").build()));
        }
        return List.copyOf(out);
    }

    /** 工具入参／工具 schema 的 JSON 值映射（pi 把两者都当 {@code JsonObject} 直接下发）。 */
    static Map<String, com.anthropic.core.JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new java.util.LinkedHashMap<String, com.anthropic.core.JsonValue>();
        schema.forEach((key, value) -> out.put(key, com.anthropic.core.JsonValue.from(value)));
        return out;
    }
}

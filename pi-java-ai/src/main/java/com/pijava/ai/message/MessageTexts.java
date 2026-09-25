package com.pijava.ai.message;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * pi {@code packages/ai/src/utils/text.ts} 的移植：内容取文本、系统消息渲染。
 *
 * <p>与包 A2 的关系见 {@code docs/49 §5.3}：五条 provider 车道从「transcript 的前导系统消息」
 * 取提示文本时，走的就是这两个渲染函数。</p>
 */
public final class MessageTexts {

    private MessageTexts() {}

    /**
     * pi {@code text.ts:6-11} 的默认分隔符 —— {@code contentText(content)} 的 {@code separator = "\n"}。
     */
    private static final String DEFAULT_SEPARATOR = "\n";

    /**
     * Extract and join the text of every text block (pi {@code text.ts:6-11}).
     *
     * <p>{@code separator} 与 pi 的默认值一致（{@code "\n"}）；非文本块（thinking／图片／
     * 工具块）**不参与**，逐块丢弃 —— pi 的 {@code filter(block => block.type === "text")}。</p>
     *
     * <p>⚠️ {@code TextContent.text()} 在 java 上可空（{@code ContentBlock.TextContent} 是裸 record），
     * 而 pi 的 {@code TextContent.text} 是必填 string：那里 {@code undefined} 经 JS 的
     * {@code join} 渲染成空串。故此处把 null 当空串 —— 否则会渲染出字面量 {@code "null"}。</p>
     */
    public static String contentText(List<ContentBlock> content) {
        return contentText(content, DEFAULT_SEPARATOR);
    }

    /** {@link #contentText(List)} 的显式分隔符形态（pi 的第二形参）。 */
    public static String contentText(List<ContentBlock> content, String separator) {
        var parts = new ArrayList<String>();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent text) {
                parts.add(text.text() == null ? "" : text.text());
            }
        }
        return String.join(separator, parts);
    }

    /**
     * pi {@code text.ts:15-21} —— 完整提示：content 文本后接各 section 值，以 {@code "\n\n"} 连，
     * **空段滤掉**（所以在 content 为空时不产生前导 {@code "\n\n"}）。
     *
     * <p>section 的顺序就是 {@link Message.SystemMessage#sections()} 的迭代顺序 ——
     * pi 用 {@code Object.values} （插入顺序），故该 Map 必须**保序**构造。</p>
     */
    public static String getSystemMessageText(Message.SystemMessage message) {
        var parts = new ArrayList<String>();
        parts.add(contentText(message.content()));
        parts.addAll(message.sections().values());
        return parts.stream()
            .filter(part -> !part.isEmpty())
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * pi {@code text.ts:23-40} —— 中途系统消息的「按名框住」渲染。
     *
     * <p>⚠️ 与 {@link #getSystemMessageText} 的**关键差别**：此处**不滤空段**
     * （pi 只对 content 做 {@code length > 0} 判断，section 帧照发）。pi 自述这是
     * 请求期文本、版本间可改（{@code text.ts:25-27}）。</p>
     *
     * <p>⚠️ pi 的 {@code value === null} 分支（{@code Removed system prompt section "x".}）
     * 在 java 上**表达不了** —— {@code sections} 是 {@code Map<String,String>}，
     * 没有「值在场但为 null」这个状态（{@code docs/49 §9 R3①}，登记 L3）。
     * 故这里只写 {@code Updated} 一支，不写永远走不到的分支。</p>
     */
    public static String renderSystemMessageUpdate(Message.SystemMessage message) {
        var parts = new ArrayList<String>();
        var text = contentText(message.content());
        if (!text.isEmpty()) {
            parts.add(text);
        }
        for (var entry : message.sections().entrySet()) {
            parts.add("Updated system prompt section \"" + entry.getKey() + "\":\n\n"
                + entry.getValue());
        }
        return String.join("\n\n", parts);
    }
}

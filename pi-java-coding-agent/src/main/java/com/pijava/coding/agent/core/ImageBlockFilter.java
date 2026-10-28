package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * pi sdk.ts:296-329 的 blockImages 过滤（defense-in-depth）逐字对位：user 与
 * toolResult 两角色的 {@code ImageContent} 换 "Image reading is disabled." 文本块，
 * 连续占位文本去重；assistant 等其余角色不动；URL 图片不过滤（pi 的转换层无此变体
 * ——java 的 {@code UrlImageContent} 是 JSONL 解码方言，主循环不产）。
 *
 * <p>触及面（pi 同）：只罩 Agent 主循环的 transformContext 尾；压缩、分支摘要、
 * bug 报告直引裸转换，不过滤。</p>
 */
public final class ImageBlockFilter {

    /** pi 占位文案逐字（sdk.ts:310）。 */
    public static final String PLACEHOLDER = "Image reading is disabled.";

    private ImageBlockFilter() {}

    /** Filter one message list (returns the input untouched when nothing blocks). */
    public static List<Message> apply(List<Message> messages) {
        List<Message> out = null;
        for (int i = 0; i < messages.size(); i++) {
            var filtered = filterMessage(messages.get(i));
            if (filtered != null) {
                if (out == null) {
                    out = new ArrayList<>(messages);
                }
                out.set(i, filtered);
            }
        }
        return out == null ? messages : out;
    }

    /** pi 两臂：user/toolResult 且含 image ⇒ 重建该条；否则 {@code null}（不动）。 */
    private static Message filterMessage(Message message) {
        List<ContentBlock> content = switch (message) {
            case Message.UserMessage u -> u.content();
            case Message.ToolResultMessage t -> t.content();
            default -> null;
        };
        if (content == null
                || content.stream().noneMatch(c -> c instanceof ContentBlock.ImageContent)) {
            return null;
        }
        // pi：map（image ⇒ 占位文本）→ 连续占位去重（查映射后数组的前一元素）。
        var mapped = new ArrayList<ContentBlock>(content.size());
        for (var c : content) {
            mapped.add(c instanceof ContentBlock.ImageContent
                ? new ContentBlock.TextContent(PLACEHOLDER) : c);
        }
        var filtered = new ArrayList<ContentBlock>(mapped.size());
        for (int i = 0; i < mapped.size(); i++) {
            var c = mapped.get(i);
            if (i > 0 && isPlaceholder(c) && isPlaceholder(mapped.get(i - 1))) {
                continue;
            }
            filtered.add(c);
        }
        return switch (message) {
            case Message.UserMessage u -> new Message.UserMessage(filtered, u.timestamp());
            case Message.ToolResultMessage t -> new Message.ToolResultMessage(
                t.toolUseId(), t.toolName(), filtered, t.details(), t.usage(),
                t.addedToolNames(), t.isError(), t.timestamp(), t.durationMs(), t.nestedCalls());
            default -> throw new IllegalStateException("unreachable");
        };
    }

    private static boolean isPlaceholder(ContentBlock block) {
        return block instanceof ContentBlock.TextContent t && PLACEHOLDER.equals(t.text());
    }
}

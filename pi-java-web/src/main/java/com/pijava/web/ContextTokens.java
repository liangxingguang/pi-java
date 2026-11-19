package com.pijava.web;

import java.util.List;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 上下文 token 估算 —— 与 {@code ContextUsageEstimator} 同一条 pi 启发式
 * （每块字符数 / 4，图片 4800 字符常数），供 Context 面板与跨会话洞察共用。
 */
final class ContextTokens {

    /** pi {@code ESTIMATED_IMAGE_CHARS} (4800) / 4。 */
    static final double IMAGE_TOKENS = 1200;

    private ContextTokens() {}

    static double text(String text) {
        return text == null ? 0 : Math.ceil(text.length() / 4.0);
    }

    static double block(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextContent text -> text(text.text());
            case ContentBlock.ThinkingContent thinking -> text(thinking.text());
            case ContentBlock.ToolUseContent call ->
                text(call.name()) + text(json(call.arguments()));
            case ContentBlock.ImageContent ignored -> IMAGE_TOKENS;
            case ContentBlock.UrlImageContent ignored -> IMAGE_TOKENS;
            case ContentBlock.ToolResultContent nested -> blocks(nested.content());
            case ContentBlock.DiffContent diff -> text(diff.diffText());
        };
    }

    static double blocks(List<ContentBlock> blocks) {
        double tokens = 0;
        for (var block : blocks) {
            tokens += block(block);
        }
        return tokens;
    }

    static double tool(String name, String description, String schemaJson) {
        return text(name) + text(description) + text(schemaJson);
    }

    static double assistantOutput(Message.AssistantMessage assistant) {
        double tokens = 0;
        for (var block : assistant.content()) {
            tokens += switch (block) {
                case ContentBlock.TextContent text -> text(text.text());
                case ContentBlock.ThinkingContent thinking -> text(thinking.text());
                case ContentBlock.ToolUseContent call ->
                    text(call.name()) + text(json(call.arguments()));
                default -> 0;
            };
        }
        return tokens;
    }

    static double prompt(Usage usage) {
        return usage.input() + usage.cacheRead() + usage.cacheWrite();
    }

    static String textOf(List<ContentBlock> blocks) {
        var out = new StringBuilder();
        for (var block : blocks) {
            switch (block) {
                case ContentBlock.TextContent text -> out.append(text.text());
                case ContentBlock.ThinkingContent thinking -> out.append(thinking.text());
                case ContentBlock.DiffContent diff -> out.append(diff.diffText());
                case ContentBlock.ToolResultContent nested -> out.append(textOf(nested.content()));
                default -> {
                    // 图片块没有可读文本。
                }
            }
        }
        return out.toString();
    }

    static String json(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return SessionJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return "[unserializable]";
        }
    }
}

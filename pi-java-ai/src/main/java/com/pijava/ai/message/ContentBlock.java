package com.pijava.ai.message;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A block of content within a {@link Message}.
 *
 * <p>This sealed interface covers text, images, and tool-related blocks.
 * The design mirrors the content-block model used by Anthropic, OpenAI,
 * and Google Gemini.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlock.TextContent.class, name = "text"),
    @JsonSubTypes.Type(value = ContentBlock.ThinkingContent.class, name = "thinking"),
    @JsonSubTypes.Type(value = ContentBlock.ImageContent.class, name = "image"),
    @JsonSubTypes.Type(value = ContentBlock.UrlImageContent.class, name = "image_url"),
    @JsonSubTypes.Type(value = ContentBlock.ToolUseContent.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ContentBlock.ToolResultContent.class, name = "tool_result")
})
public sealed interface ContentBlock {

    /** Plain text content. */
    record TextContent(String text) implements ContentBlock {}

    /**
     * Model reasoning/thinking text (e.g. DeepSeek {@code reasoning_content}).
     * Kept separate from {@link TextContent} so providers that require passing
     * reasoning back on later turns (DeepSeek thinking mode) can serialize it
     * into the provider-specific field instead of mixing it into content.
     */
    record ThinkingContent(String text) implements ContentBlock {}

    /**
     * An image provided as a base64-encoded data URL.
     *
     * @param mediaType the MIME type (e.g. "image/png")
     * @param data      base64-encoded bytes (without the data: URL prefix)
     */
    record ImageContent(String mediaType, String data) implements ContentBlock {}

    /**
     * An image referenced by a remote URL（P6-19，非 base64 内联）。
     *
     * <p>由协议适配器按各自能力下发：OpenAI Responses/兼容 API 走
     * {@code image_url}，Gemini 走 {@code fileData}。Anthropic 不支持 URL 图片，
     * 需先下载转 base64（不在本任务范围）。</p>
     *
     * @param url 图片 http(s) 地址
     */
    record UrlImageContent(String url) implements ContentBlock {}

    /**
     * A tool-use request emitted by the assistant.
     *
     * @param id        unique call identifier
     * @param name      tool name
     * @param arguments tool arguments as a JSON-compatible map
     */
    record ToolUseContent(String id, String name, Map<String, Object> arguments) implements ContentBlock {
        /** Compact constructor that defensively copies the arguments map. */
        public ToolUseContent {
            arguments = Map.copyOf(arguments);
        }
    }

    /**
     * A tool result returned to the assistant.
     *
     * @param toolUseId the ID of the corresponding {@link ToolUseContent}
     * @param toolName  the name of the tool that produced this result
     * @param content   the result content blocks (text or image)
     * @param isError   {@code true} if the tool execution failed
     */
    record ToolResultContent(String toolUseId, String toolName,
                             List<ContentBlock> content, boolean isError) implements ContentBlock {
        /** Compact constructor that defensively copies the content blocks. */
        public ToolResultContent {
            content = List.copyOf(content);
        }
    }
}

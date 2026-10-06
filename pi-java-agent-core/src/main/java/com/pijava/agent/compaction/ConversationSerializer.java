package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * turn-prefix 摘要请求里的会话序列化（B171，{@code docs/18}；pi
 * {@code serializeConversation}，{@code compaction/utils.ts:114-153}）。把消息
 * 渲染成 {@code [User]}/{@code [Assistant]}/{@code [Tool result]} 的文本块，防止
 * 模型把它当成要续写的对话。tool result 截到 {@link #TOOL_RESULT_MAX_CHARS}
 * 字符，摘要不需要全文。
 */
final class ConversationSerializer {

    /** pi {@code TOOL_RESULT_MAX_CHARS}（utils.ts:94）。 */
    static final int TOOL_RESULT_MAX_CHARS = 2_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConversationSerializer() {}

    static String serialize(List<Message> messages) {
        List<String> parts = new ArrayList<>();
        for (Message message : messages) {
            switch (message) {
                case Message.UserMessage user -> {
                    String content = textOf(user.content());
                    if (!content.isEmpty()) {
                        parts.add("[User]: " + content);
                    }
                }
                case Message.AssistantMessage assistant -> {
                    List<String> thinking = new ArrayList<>();
                    List<String> toolCalls = new ArrayList<>();
                    boolean hasText = false;
                    for (ContentBlock block : assistant.content()) {
                        switch (block) {
                            case ContentBlock.ThinkingContent t -> {
                                thinking.add(t.text());
                            }
                            case ContentBlock.ToolUseContent t -> {
                                toolCalls.add(t.name() + "(" + argsOf(t.arguments()) + ")");
                            }
                            case ContentBlock.TextContent t -> {
                                hasText = true;
                            }
                            default -> { /* 其它块不参与序列化 */ }
                        }
                    }
                    if (!thinking.isEmpty()) {
                        parts.add("[Assistant thinking]: " + String.join("\n", thinking));
                    }
                    if (hasText) {
                        parts.add("[Assistant]: " + textOf(assistant.content()));
                    }
                    if (!toolCalls.isEmpty()) {
                        parts.add("[Assistant tool calls]: " + String.join("; ", toolCalls));
                    }
                }
                case Message.ToolResultMessage toolResult -> {
                    String content = textOf(toolResult.content());
                    if (!content.isEmpty()) {
                        parts.add("[Tool result]: " + truncateForSummary(content));
                    }
                }
                // pi 的 Message 联合无 system 角色；进本序列化前 system 已被
                // getMessagesFromProjectedEntryForCompaction 剔除，这里不产文本。
                case Message.SystemMessage ignored -> { }
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * pi {@code truncateForSummary}（utils.ts:99-104）：留前 2000 字符，
     * 后接被截字符数标记。JS {@code String.length}/{@code slice} 与 Java
     * {@code String.length}/{@code substring} 同为 UTF-16 码元，逐字相同。
     */
    private static String truncateForSummary(String text) {
        if (text.length() <= TOOL_RESULT_MAX_CHARS) {
            return text;
        }
        int truncatedChars = text.length() - TOOL_RESULT_MAX_CHARS;
        return text.substring(0, TOOL_RESULT_MAX_CHARS)
            + "\n\n[... " + truncatedChars + " more characters truncated]";
    }

    /** pi {@code Object.entries(args).map(([k,v]) -> k=JSON.stringify(v))}。 */
    private static String argsOf(Map<String, Object> arguments) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            entries.add(entry.getKey() + "=" + jsonStringify(entry.getValue()));
        }
        return String.join(", ", entries);
    }

    /** pi {@code JSON.stringify}：内存中序列化 JSON 值，失败不可达（值来自消息模型）。 */
    private static String jsonStringify(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to stringify tool-call argument", e);
        }
    }

    /** pi {@code contentText}：text 块以 \n 连接。 */
    private static String textOf(List<ContentBlock> blocks) {
        List<String> parts = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.TextContent text) {
                parts.add(text.text());
            }
        }
        return String.join("\n", parts);
    }
}

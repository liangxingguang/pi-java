package com.pijava.ai.provider.faux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolReference;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.stream.StreamEvent;

/**
 * docs/20 §4：pi {@code withUsageEstimate}（{@code providers/faux.ts:246-287}）的 Java 落点。
 *
 * <p>pi 的 faux 用「序列化后的转录文本长度 ÷ 4」模拟 provider 上报的用量，并按
 * {@code sessionId} 记住上一次的提示文本，用公共前缀长度模拟 {@code cacheRead}／
 * {@code cacheWrite}。计时单位两侧都是 UTF-16 码元（JS {@code String.length} 与
 * Java {@link String#length()}），故长度算法逐字照搬即可。</p>
 *
 * <p>本类持有一个实例级缓存 ⇒ 每个 {@link FauxCore} 一个实例，与 pi 的闭包同形。</p>
 */
final class FauxUsageEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "sessionId";
    private static final String CACHE_RETENTION = "cacheRetention";
    private static final String CACHE_NONE = "none";

    private final Map<String, List<String>> promptCache = new ConcurrentHashMap<>();

    /**
     * 估算 {@code message} 的 usage：{@code transcript} 是提示文本的来源，
     * {@code options} 提供 {@code sessionId}／{@code cacheRetention}。
     */
    AssistantMessage estimate(AssistantMessage message, TranscriptContext transcript,
                              ApiOptions options) {
        var prompt = transcript.messages().stream()
            .map(entry -> piRole(entry) + ":" + messageToText(entry))
            .toList();
        long promptTokens = estimateTokens(joinedLength(prompt));
        long outputTokens = estimateTokens(assistantContentToText(message.content()).length());

        long input = promptTokens;
        long cacheRead = 0;
        long cacheWrite = 0;
        var sessionId = stringExtra(options, SESSION_ID);
        if (sessionId != null && !CACHE_NONE.equals(stringExtra(options, CACHE_RETENTION))) {
            var previous = promptCache.get(sessionId);
            if (previous != null) {
                long cachedChars = commonPromptPrefixLength(previous, prompt);
                cacheRead = estimateTokens(cachedChars);
                cacheWrite = Math.max(0, promptTokens - cacheRead);
            } else {
                cacheWrite = promptTokens;
            }
            // Every prompt token is either read from or written to the simulated cache.
            input = 0;
            promptCache.put(sessionId, prompt);
        }

        var usage = new Usage(input, outputTokens, cacheRead, cacheWrite, null, null,
            input + outputTokens + cacheRead + cacheWrite, Usage.Cost.zero());
        return message.withUsage(new StreamEvent.UsageInfo(input, outputTokens, null, usage));
    }

    private static String stringExtra(ApiOptions options, String key) {
        if (options == null) {
            return null;
        }
        var raw = options.extra().get(key);
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    /** pi 弱类型 {@code message.role} 的判别字面量；Java 的 {@code "tool"} 方言要翻回 {@code "toolResult"}。 */
    private static String piRole(Message message) {
        return switch (message) {
            case Message.SystemMessage ignored -> "system";
            case Message.UserMessage ignored -> "user";
            case Message.AssistantMessage ignored -> "assistant";
            case Message.ToolResultMessage ignored -> "toolResult";
        };
    }

    private static String messageToText(Message message) {
        return switch (message) {
            case Message.SystemMessage system -> systemToText(system);
            case Message.UserMessage user -> contentToText(user.content());
            case Message.AssistantMessage assistant ->
                assistantContentToText(assistant.content());
            case Message.ToolResultMessage result -> toolResultToText(result);
        };
    }

    private static String systemToText(Message.SystemMessage message) {
        var parts = new ArrayList<String>();
        parts.add(MessageTexts.getSystemMessageText(message));
        for (ToolReference removed : message.toolsRemoved()) {
            parts.add("tool-:" + json(Map.of("name", removed.name())));
        }
        for (ToolDefinition added : message.toolsAdded()) {
            parts.add("tool+:" + toolJson(added));
        }
        return parts.stream()
            .filter(part -> !part.isEmpty())
            .collect(Collectors.joining("\n"));
    }

    /** pi 只 stringify 自己的 {@code Tool} 形状；Java 的多余字段不进估算文本。 */
    private static String toolJson(ToolDefinition tool) {
        var shape = new LinkedHashMap<String, Object>();
        shape.put("name", tool.name());
        if (tool.description() != null) {
            shape.put("description", tool.description());
        }
        shape.put("parameters", tool.inputSchema());
        if (tool.constrainedSampling() != null) {
            shape.put("constrainedSampling", tool.constrainedSampling());
        }
        return json(shape);
    }

    /** pi {@code contentToText}：文本原样、图片为稳定占位；Java 侧其它扩展块跳过。 */
    private static String contentToText(List<ContentBlock> content) {
        var parts = new ArrayList<String>();
        for (ContentBlock block : content) {
            switch (block) {
                case ContentBlock.TextContent text -> parts.add(text.text() == null ? "" : text.text());
                case ContentBlock.ImageContent image ->
                    parts.add("[image:" + image.mediaType() + ":" + image.data().length() + "]");
                case ContentBlock.UrlImageContent url ->
                    parts.add("[image-url:" + url.url().length() + "]");
                default -> { }
            }
        }
        return String.join("\n", parts);
    }

    /** pi {@code assistantContentToText}：text、thinking 与 {@code name:arguments} 三种块。 */
    private static String assistantContentToText(List<ContentBlock> content) {
        var parts = new ArrayList<String>();
        for (ContentBlock block : content) {
            switch (block) {
                case ContentBlock.TextContent text -> parts.add(text.text() == null ? "" : text.text());
                case ContentBlock.ThinkingContent thinking ->
                    parts.add(thinking.text() == null ? "" : thinking.text());
                case ContentBlock.ToolUseContent call ->
                    parts.add(call.name() + ":" + json(call.arguments()));
                default -> { }
            }
        }
        return String.join("\n", parts);
    }

    /** pi {@code toolResultToText}：工具名一行，随后每个内容块各一行。 */
    private static String toolResultToText(Message.ToolResultMessage message) {
        var parts = new ArrayList<String>();
        parts.add(message.toolName());
        for (ContentBlock block : message.content()) {
            parts.add(contentToText(List.of(block)));
        }
        return String.join("\n", parts);
    }

    /** pi {@code joinedLength}：段间以空行连接，故 {@code (count-1)*2} 的额外长度。 */
    private static int joinedLength(List<String> messages) {
        return joinedLength(messages, messages.size());
    }

    private static int joinedLength(List<String> messages, int count) {
        int length = count > 0 ? (count - 1) * 2 : 0;
        for (int index = 0; index < count; index++) {
            length += messages.get(index).length();
        }
        return length;
    }

    /**
     * pi {@code commonPromptPrefixLength}：先比整条消息，首个不同消息之后退化为
     * 字符串公共前缀；返回值按「拼接口径」计数。
     */
    private static int commonPromptPrefixLength(List<String> previous, List<String> current) {
        int index = 0;
        while (index < previous.size() && index < current.size()
                && previous.get(index).equals(current.get(index))) {
            index++;
        }
        return joinedLength(previous, index)
            + commonPrefixLength(rest(previous, index), rest(current, index));
    }

    private static String rest(List<String> messages, int index) {
        if (index == messages.size()) {
            return "";
        }
        return (index > 0 ? "\n\n" : "") + String.join("\n\n", messages.subList(index, messages.size()));
    }

    private static int commonPrefixLength(String left, String right) {
        int length = Math.min(left.length(), right.length());
        int index = 0;
        while (index < length && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    private static long estimateTokens(long length) {
        return (length + 3L) / 4L;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Faux usage estimate requires JSON-serializable content", e);
        }
    }
}

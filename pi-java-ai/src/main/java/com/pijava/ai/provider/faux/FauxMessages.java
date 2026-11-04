package com.pijava.ai.provider.faux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.pijava.ai.Usage;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;

/**
 * docs/20 §3.2：pi {@code fauxText／fauxThinking／fauxToolCall／fauxAssistantMessage}
 * （{@code providers/faux.ts:61-99}）的便捷构造。
 *
 * <p>默认身份 {@code faux}/{@code faux}/{@code faux-1}、零 usage、{@code now} 时间戳 ——
 * 与 pi 的默认常量一致；核心在解析时会改写身份与 usage。</p>
 */
public final class FauxMessages {

    /** pi 默认 api 字面量。 */
    public static final String DEFAULT_API = "faux";
    /** pi 默认 provider 名。 */
    public static final String DEFAULT_PROVIDER = "faux";
    /** pi 默认模型 id。 */
    public static final String DEFAULT_MODEL_ID = "faux-1";
    /** pi 默认模型显示名。 */
    public static final String DEFAULT_MODEL_NAME = "Faux Model";

    private FauxMessages() {
    }

    /** 一条纯文本助手消息（stop）。 */
    public static AssistantMessage text(String text) {
        return assistant(List.of(new ContentBlock.TextContent(text)), StopReason.STOP);
    }

    /** 一条思考块助手消息（stop）。 */
    public static AssistantMessage thinking(String text) {
        return assistant(List.of(new ContentBlock.ThinkingContent(text)), StopReason.STOP);
    }

    /** 一个工具调用助手消息（toolUse），id 随机生成。 */
    public static AssistantMessage toolCall(String name, Map<String, Object> arguments) {
        return toolCall(name, arguments, randomId("tool"));
    }

    /** 一个指定 id 的工具调用助手消息。 */
    public static AssistantMessage toolCall(String name, Map<String, Object> arguments, String id) {
        return assistant(List.of(new ContentBlock.ToolUseContent(id, name, arguments)),
            StopReason.TOOL_USE);
    }

    /** 任意内容块的助手消息（stop）。 */
    public static AssistantMessage assistant(List<ContentBlock> content) {
        return assistant(content, StopReason.STOP);
    }

    /** 任意内容块 + 停因的助手消息。 */
    public static AssistantMessage assistant(List<ContentBlock> content, StopReason reason) {
        return new AssistantMessage(
            randomId("msg"), content, zeroUsage(), reason,
            DEFAULT_API, DEFAULT_PROVIDER, DEFAULT_MODEL_ID, null, null, null,
            Instant.now(), null, null, null, null, null);
    }

    /** pi {@code DEFAULT_USAGE}（全零，含 cost）。 */
    static StreamEvent.UsageInfo zeroUsage() {
        return new StreamEvent.UsageInfo(0, 0, null,
            new Usage(0, 0, 0, 0, null, null, 0, Usage.Cost.zero()));
    }

    /** pi {@code randomId(prefix)}：{@code prefix:<ts>:<base36>}。 */
    static String randomId(String prefix) {
        return prefix + ":" + System.currentTimeMillis() + ":"
            + Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
    }
}

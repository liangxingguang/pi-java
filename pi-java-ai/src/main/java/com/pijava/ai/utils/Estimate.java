package com.pijava.ai.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.Usage;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;

/**
 * 上下文 token 估算 —— pi {@code ai/src/utils/estimate.ts} 的逐条移植（包 A-10）。
 *
 * <p>消费方是 {@link com.pijava.ai.api.SimpleOptions#clampMaxTokensToContext}：pi 的
 * {@code max_tokens} 要按「模型上下文窗口 − 已占 token − 4096 安全余量」夹取，
 * 中间的「已占 token」就是这个类算的。</p>
 *
 * <h2>⚠️ 它与 {@code agent-core} 的两个同名物<b>不是</b>一个东西，别合流</h2>
 *
 * <p>pi 自带<b>三份</b> {@code estimateContextTokens}，算法<b>真的不同</b>：</p>
 * <table border="1">
 *   <caption>pi 的三份估算器</caption>
 *   <tr><th>#</th><th>位置</th><th>扫描</th><th>时间戳守卫</th><th>{@code system} 角色</th></tr>
 *   <tr><td>①</td><td>{@code ai/src/utils/estimate.ts:97}（<b>本类</b>）</td>
 *       <td><b>正向</b></td><td><b>有</b></td><td>计入（文本 ＋ 工具增删）</td></tr>
 *   <tr><td>②</td><td>{@code agent/src/harness/compaction/compaction.ts:215}</td>
 *       <td>反向</td><td>无</td><td>计 0</td></tr>
 *   <tr><td>③</td><td>{@code coding-agent/src/core/compaction/compaction.ts:217}</td>
 *       <td>反向</td><td>无</td><td>计 0</td></tr>
 * </table>
 *
 * <p>java 的 {@code com.pijava.agent.context.ContextUsageEstimator}（agent-core，包 3b）是
 * <b>②</b> 的忠实移植 —— 那份服务压缩触发，反向扫描正是它不需要逐条时间戳的原因。
 * 另一处自造启发式 {@code com.pijava.agent.context.ContextEstimator}
 * （3.5 字符/token，与 pi 无对应物）已随 A-20（docs/68）删除。
 * 本类补的是 <b>①</b>。</p>
 *
 * <p>⚠️ <b>不能把三者统一</b>：{@code pi-java-ai} 依赖不到 {@code pi-java-agent-core}
 * （两侧 pom 已核），而夹取发生在车道内；且语义差异是 pi 自己的（守卫之于压缩摘要插入、
 * {@code system} 角色之于工具声明），选哪一份就等于放弃另一份的对齐。</p>
 *
 * <h2>与 pi 的两处形状差异（如实登记，见 {@code docs/57 §10}）</h2>
 *
 * <ol>
 *   <li><b>逐条时间戳</b>：pi 的每条 {@code Message} 都带 {@code timestamp}（必填），
 *       而 java 只有 {@link Message.SystemMessage}/{@link Message.AssistantMessage} 带
 *       ⇒「前缀最新时间戳」只在带时间戳的消息上推进（其余不计入）。
 *       守卫的<b>意图</b>（「压缩摘要插进来后旧 usage 不再描述当前前缀」）完整保留 ——
 *       生产转录里助手消息带真时间戳，比较的两侧都在。</li>
 *   <li><b>工具声明的字节数</b>：pi 的 {@code SystemMessage.toolsAdded} 是 ai 包的
 *       {@code Tool}（4 个键：name/description/parameters/constrainedSampling），java 是
 *       {@link com.pijava.ai.api.ToolDefinition}（7 个键）。⇒ 本类的工具估算**偏大**
 *       ⇒ 夹出来的 {@code max_tokens} **偏小** —— 方向是**保守**那一侧（宁短不溢出）。</li>
 * </ol>
 */
public final class Estimate {

    private Estimate() {}

    /** pi {@code estimate.ts:4-13} {@code ContextUsageEstimate}。 */
    public record ContextUsage(double tokens,
                               double usageTokens,
                               double trailingTokens,
                               Integer lastUsageIndex) {}

    /** pi {@code estimate.ts:15}。 */
    static final double CHARS_PER_TOKEN = 4;

    /** pi {@code estimate.ts:16} —— 一张图折 4800 字符（≈1200 token）。 */
    static final double ESTIMATED_IMAGE_CHARS = 4800;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * pi {@code estimate.ts:18-20} —— provider 报的用量折成「上下文占用」。
     *
     * <p>⚠️ pi 用的是 {@code ||} 而**不是** {@code ??}：{@code totalTokens === 0} 会落到求和。
     * java 这边连 {@code NaN} 一起照顾（JS 的 {@code NaN || x} 也取 {@code x}）。</p>
     */
    public static double calculateContextTokens(Usage usage) {
        var total = usage.totalTokens();
        if (total == 0 || Double.isNaN(total)) {
            return usage.input() + usage.output() + usage.cacheRead() + usage.cacheWrite();
        }
        return total;
    }

    /**
     * pi {@code estimate.ts:22-28} {@code safeJsonStringify} —— 序列化失败**不抛**，
     * 退化成一个固定串（估算器不该让请求构建失败）。
     *
     * <p>⚠️ 这里比 pi 多做一步 {@link #jsNumbers}：JS 的 {@code JSON.stringify(1.0)} 得
     * {@code "1"}，Jackson 得 {@code "1.0"} —— 而工具 schema 里 {@code maximum}/{@code minimum}
     * 一类的**整数值**很常见，不归一就会把每个数字多算两个字符。</p>
     *
     * <p>java 的 {@code null} ≙ pi 的 {@code undefined} ⇒ 得 {@code "undefined"}
     * （JS 的 {@code JSON.stringify(null)} 是 {@code "null"}，那是另一件事）。</p>
     */
    static String safeJsonStringify(Object value) {
        if (value == null) {
            return "undefined";
        }
        try {
            return MAPPER.writeValueAsString(jsNumbers(value));
        } catch (JsonProcessingException e) {
            return "[unserializable]";
        }
    }

    /** 把整数值的浮点折成整数，使序列化结果与 JS 的 {@code JSON.stringify} 同形。 */
    private static Object jsNumbers(Object value) {
        if (value instanceof Double d) {
            return d == Math.rint(d) && !d.isInfinite() ? (Object) d.longValue() : d;
        }
        if (value instanceof Float f) {
            return f == Math.rint(f) && !f.isInfinite() ? (Object) f.longValue() : f;
        }
        if (value instanceof Map<?, ?> map) {
            var normalized = new LinkedHashMap<Object, Object>(map.size());
            map.forEach((k, v) -> normalized.put(k, jsNumbers(v)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            var normalized = new ArrayList<Object>(list.size());
            for (var item : list) {
                normalized.add(jsNumbers(item));
            }
            return normalized;
        }
        return value;
    }

    /**
     * pi {@code estimate.ts:30-36} —— 文本与图片混合内容的字符数。
     *
     * <p>pi 的并集是 {@code string | (TextContent | ImageContent)[]} ⇒ 除文本外**一律**
     * 按图片算。java 的 {@link ContentBlock} 是更大的并集，其余变体在 pi 那一格上不存在
     * ⇒ 计 0（不猜、不外推）。</p>
     */
    static double estimateTextAndImageContentChars(List<ContentBlock> content) {
        double chars = 0;
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent text) {
                chars += text.text().length();
            } else if (block instanceof ContentBlock.ImageContent
                || block instanceof ContentBlock.UrlImageContent) {
                chars += ESTIMATED_IMAGE_CHARS;
            }
        }
        return chars;
    }

    /** pi {@code estimate.ts:38-40}。 */
    public static double estimateTextTokens(String text) {
        return Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /** pi {@code estimate.ts:42-44}。 */
    public static double estimateTextAndImageContentTokens(List<ContentBlock> content) {
        return Math.ceil(estimateTextAndImageContentChars(content) / CHARS_PER_TOKEN);
    }

    /**
     * pi {@code estimate.ts:46-69} {@code estimateMessageTokens} —— 逐角色。
     *
     * <p>{@code system} ⇒ 提示文本 ＋ 工具增删的 JSON；{@code user}/{@code toolResult} ⇒
     * 文本与图片；其余（assistant）⇒ {@code text} ＋ {@code thinking} ＋
     * {@code name + JSON(args)}。</p>
     *
     * <p>⚠️ pi 的 assistant 分支是「不是 text 也不是 thinking 的**一律**当工具调用」
     * （它的并集里只剩 {@code toolCall}）。java 的并集更大 ⇒ 这里显式认
     * {@link ContentBlock.ToolUseContent}，其余计 0（同 {@link #estimateTextAndImageContentChars}
     * 的理由）。</p>
     */
    public static double estimateMessageTokens(Message message) {
        if (message instanceof Message.SystemMessage system) {
            return estimateTextTokens(MessageTexts.getSystemMessageText(system))
                + estimateToolsTokens(system.toolsAdded())
                + estimateToolsTokens(system.toolsRemoved());
        }
        if (message instanceof Message.UserMessage || message instanceof Message.ToolResultMessage) {
            return estimateTextAndImageContentTokens(message.content());
        }

        double chars = 0;
        for (var block : message.content()) {
            if (block instanceof ContentBlock.TextContent text) {
                chars += text.text().length();
            } else if (block instanceof ContentBlock.ThinkingContent thinking) {
                chars += thinking.text().length();
            } else if (block instanceof ContentBlock.ToolUseContent call) {
                chars += call.name().length() + safeJsonStringify(call.arguments()).length();
            }
        }
        return Math.ceil(chars / CHARS_PER_TOKEN);
    }

    /**
     * pi {@code estimate.ts:71-95} {@code getLastAssistantUsageInfo} —— <b>正向</b>扫，
     * 只收「自己的时间戳不早于任何前缀消息」且非 aborted/error、且用量非零的那条。
     *
     * <p>守卫的由来见 pi 的注释（{@code :79-80}）：压缩摘要会被插在响应<b>之后</b>，
     * 那条 assistant 的 usage 于是描述不了**当前**前缀。这是本类与
     * {@code agent-core.ContextUsageEstimator}（反向取末条、无守卫）唯一的语义分叉。</p>
     */
    private static UsageAnchor lastAssistantUsageInfo(List<Message> messages) {
        var latestPrefixTimestamp = Double.NEGATIVE_INFINITY;
        UsageAnchor anchor = null;

        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            // pi 的 `assistant.timestamp >= latestPrefixTimestamp`；java 里没有时间戳的消息
            // 不参与推进、也不因它被判过期（见类 javadoc 的第一处形状差异）。
            var timestamp = epochMillis(message);
            if (message instanceof Message.AssistantMessage assistant) {
                var appliesToPrefix = timestamp == null || timestamp >= latestPrefixTimestamp;
                // `usage() != null` 是 java 的防御：pi 的 usage 必填，兼容构造器允许缺席。
                if (appliesToPrefix
                    && !"aborted".equals(assistant.stopReason())
                    && !"error".equals(assistant.stopReason())
                    && assistant.usage() != null
                    && calculateContextTokens(assistant.usage()) > 0) {
                    anchor = new UsageAnchor(assistant.usage(), i);
                }
            }
            // pi :91 —— 推进在**判定之后**，故比较的基准是「前缀」而不是含自己的最大值。
            if (timestamp != null) {
                latestPrefixTimestamp = Math.max(latestPrefixTimestamp, timestamp);
            }
        }
        return anchor;
    }

    /** 一条带用量的助手消息在表里的位置。 */
    private record UsageAnchor(Usage usage, int index) {}

    /**
     * 四个变体的时间戳（docs/71 G1 之前只有 system/assistant 有字段）。
     *
     * <p>兼容构造器造出的消息仍可能是 {@code null}（旧数据解码）—— pi 的推进对每条
     * 消息都做（{@code estimate.ts:91}），Java 对 null 跳过（见类 javadoc）。</p>
     */
    private static Double epochMillis(Message message) {
        if (message instanceof Message.AssistantMessage assistant) {
            return epochMillis(assistant.timestamp());
        }
        if (message instanceof Message.SystemMessage system) {
            return epochMillis(system.timestamp());
        }
        if (message instanceof Message.UserMessage user) {
            return epochMillis(user.timestamp());
        }
        if (message instanceof Message.ToolResultMessage tool) {
            return epochMillis(tool.timestamp());
        }
        return null;
    }

    private static Double epochMillis(java.time.Instant instant) {
        return instant == null ? null : (double) instant.toEpochMilli();
    }

    /** pi {@code estimate.ts:97-112}，{@code TranscriptContext} 入口。 */
    public static ContextUsage estimateContextTokens(TranscriptContext context) {
        return estimateContextTokens(context.messages());
    }

    /**
     * pi {@code estimate.ts:97-112}，裸消息表入口。
     *
     * <p>命中用量锚 ⇒ {@code usage + 其后逐条估算}；未命中 ⇒ 全表逐条估算。</p>
     */
    public static ContextUsage estimateContextTokens(List<Message> messages) {
        var anchor = lastAssistantUsageInfo(messages);
        if (anchor != null) {
            var usageTokens = calculateContextTokens(anchor.usage());
            double trailing = 0;
            for (int i = anchor.index() + 1; i < messages.size(); i++) {
                trailing += estimateMessageTokens(messages.get(i));
            }
            return new ContextUsage(usageTokens + trailing, usageTokens, trailing, anchor.index());
        }

        double tokens = 0;
        for (var message : messages) {
            tokens += estimateMessageTokens(message);
        }
        return new ContextUsage(tokens, 0, tokens, null);
    }

    /** pi {@code estimate.ts:114-117} —— 工具声明组的字符估算（空 ⇒ 0，不问序列化）。 */
    static double estimateToolsTokens(List<?> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        return estimateTextTokens(safeJsonStringify(tools));
    }
}

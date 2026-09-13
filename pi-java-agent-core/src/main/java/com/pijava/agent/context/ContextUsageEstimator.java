package com.pijava.agent.context;

import java.util.List;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.session.SessionJson;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 上下文令牌估算 —— pi {@code harness/compaction/compaction.ts} 的
 * {@code calculateContextTokens} / {@code estimateContextTokens} /
 * {@code estimateTokens} / {@code shouldCompact} 的逐条移植（package 3b，
 * {@code docs/31 §8.20}）。
 *
 * <p>pi 的估算<b>优先信 provider 用量</b>：从消息列表尾部找最后一条「有效
 * assistant 用量」（role assistant、usage 在、stopReason 不是 aborted/error、
 * 折算值 &gt; 0，compaction.ts:167-180），命中则
 * {@code tokens = calculateContextTokens(usage) + 其后消息的字符估算}；
 * 没命中则整列表字符估算（:215-243）。这与旧的「纯 transcript 字符 / 4」
 * 估算在触发时机上有实质差异 —— 用量项是真实上下文尺寸，字符估算是下界。
 * 阈值门（{@code agent-session.ts:542-557}）和手动压缩的 tokensBefore
 * （compaction.ts:667）、切点扫描（:387）都从这一份函数读起，三者共用。</p>
 *
 * <p><b>Java 方言映射</b>（pi 的 AgentMessage 有 8 个角色，pi-java 的
 * {@link Message} 只有三个 —— 其余角色在进上下文前已被投影）：</p>
 * <ul>
 *   <li>pi 的 user {@code content} 可为裸字符串（{@code typeof content ===
 *       "string"} 分支）；Java 的 UserMessage 恒为块列表，字符串形状不存在。</li>
 *   <li>pi 的 image 块 ⇒ 4800 字符；pi-java 多出 {@link
 *       ContentBlock.UrlImageContent}（URL 方言），按同 4800 计 —— 计入 URL
 *       本身的长度没有 pi 对应物，取图像常数。</li>
 *   <li>pi 的 {@code toolCall} ⇒ {@link ContentBlock.ToolUseContent}，
 *       长度 = {@code name.length + JSON.stringify(arguments).length}
 *       （:288）；{@code arguments} 为 {@code null} ≙ pi 的 {@code undefined}
 *       ⇒ {@code "undefined"}（pi 的 {@code safeJsonStringify} :38-43 的
 *       {@code ?? "undefined"}），序列化抛错 ⇒ {@code "[unserializable]"}。</li>
 *   <li>pi 的 {@code custom} / {@code toolResult} 走同一条 text+image 规则；
 *       pi-java 的 toolResult ⇒ {@link Message.ToolResultMessage} 直接对应。
 *       {@code bashExecution}/{@code branchSummary}/{@code compactionSummary}
 *       在 pi-java 由 ContextEntries 投影成带前后缀的 user 消息，估算按 user
 *       规则读其全文 —— 前缀/后缀字符被计入，比 pi 的原角色分支略多，是有界
 *       启发式差异（pi 自己也是启发式），登记于此。</li>
 *   <li>pi 的 {@code thinking} 块字段名是 {@code thinking}；Java 的
 *       {@link ContentBlock.ThinkingContent} 字段名是 {@code text}，同义。</li>
 * </ul>
 */
public final class ContextUsageEstimator {

    /** pi {@code ESTIMATED_IMAGE_CHARS}（compaction.ts:251）。 */
    static final int ESTIMATED_IMAGE_CHARS = 4800;

    private ContextUsageEstimator() {}

    /**
     * 估算结果 —— pi {@code ContextUsageEstimate}（compaction.ts:195-204）。
     *
     * @param tokens         总估算（用量优先）
     * @param usageTokens    最后一条有效 assistant 用量的折算值（无则 0）
     * @param trailingTokens 该用量之后的消息字符估算之和
     * @param lastUsageIndex 提供用量的消息下标；无有效用量时 {@code null}
     *                       （pi 的 {@code number | null}）
     */
    public record Estimate(
        double tokens,
        double usageTokens,
        double trailingTokens,
        Integer lastUsageIndex
    ) {}

    /** pi {@code calculateContextTokens}（compaction.ts:164-166）：{@code totalTokens || 分项和}。 */
    public static double calculateContextTokens(Usage usage) {
        // JS 的 `||`：totalTokens 为 0（falsy）⇒ 走分项和。Java 用显式 0 判等。
        if (usage.totalTokens() != 0) {
            return usage.totalTokens();
        }
        return usage.input() + usage.output() + usage.cacheRead() + usage.cacheWrite();
    }

    /**
     * pi {@code getAssistantUsage}（compaction.ts:167-180）：只有「非 aborted、
     * 非 error、usage 在、折算值 &gt; 0」的 assistant 消息才供出用量。
     */
    private static Usage assistantUsageOf(Message message) {
        if (message instanceof Message.AssistantMessage assistant && assistant.usage() != null) {
            String stopReason = assistant.stopReason();
            if (!"aborted".equals(stopReason) && !"error".equals(stopReason)
                    && calculateContextTokens(assistant.usage()) > 0) {
                return assistant.usage();
            }
        }
        return null;
    }

    /** pi {@code estimateContextTokens}（compaction.ts:215-243）。 */
    public static Estimate estimateContextTokens(List<Message> messages) {
        Integer usageIndex = null;
        Usage usage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var candidate = assistantUsageOf(messages.get(i));
            if (candidate != null) {
                usageIndex = i;
                usage = candidate;
                break;
            }
        }

        if (usageIndex == null) {
            double estimated = 0;
            for (var message : messages) {
                estimated += estimateTokens(message);
            }
            return new Estimate(estimated, 0, estimated, null);
        }

        double usageTokens = calculateContextTokens(usage);
        double trailingTokens = 0;
        for (int i = usageIndex + 1; i < messages.size(); i++) {
            trailingTokens += estimateTokens(messages.get(i));
        }
        return new Estimate(usageTokens + trailingTokens, usageTokens,
            trailingTokens, usageIndex);
    }

    /** pi {@code shouldCompact}（compaction.ts:246-249）：{@code enabled} 且超出 {@code window - reserve}。 */
    public static boolean shouldCompact(double contextTokens, int contextWindow,
                                        CompactionSettings settings) {
        if (!settings.enabled()) return false;
        return contextTokens > contextWindow - settings.reserveTokens();
    }

    /** pi {@code estimateTokens}（compaction.ts:270-310）：每消息 {@code ceil(chars/4)}。 */
    public static int estimateTokens(Message message) {
        long chars = switch (message) {
            // user 与 toolResult/custom 共用 text+image 规则（:293-297）；
            // 压缩摘要在 Java 侧已投影为 user 消息，走同一条（类注释）。
            case Message.UserMessage user -> textAndImageChars(user.content());
            case Message.ToolResultMessage toolResult -> textAndImageChars(toolResult.content());
            case Message.AssistantMessage assistant -> {
                long total = 0;
                for (var block : assistant.content()) {
                    total += switch (block) {
                        case ContentBlock.TextContent t -> t.text().length();
                        case ContentBlock.ThinkingContent t -> t.text().length();
                        case ContentBlock.ToolUseContent t ->
                            t.name().length() + safeJsonStringify(t.arguments()).length();
                        default -> 0;
                    };
                }
                yield total;
            }
        };
        return (int) Math.ceil(chars / 4.0);
    }

    /** pi {@code estimateTextAndImageContentChars} 的块列表分支（:253-267）。 */
    private static long textAndImageChars(List<ContentBlock> blocks) {
        long chars = 0;
        for (var block : blocks) {
            chars += switch (block) {
                case ContentBlock.TextContent t -> t.text().length();
                case ContentBlock.ImageContent ignored -> (long) ESTIMATED_IMAGE_CHARS;
                case ContentBlock.UrlImageContent ignored -> (long) ESTIMATED_IMAGE_CHARS;
                default -> 0;
            };
        }
        return chars;
    }

    /** pi {@code safeJsonStringify}（compaction.ts:38-43）：失败 ⇒ {@code "[unserializable]"}。 */
    private static String safeJsonStringify(Object value) {
        if (value == null) {
            // pi 的 JSON.stringify(undefined) 返回 undefined ⇒ `?? "undefined"`。
            return "undefined";
        }
        try {
            return SessionJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return "[unserializable]";
        }
    }
}

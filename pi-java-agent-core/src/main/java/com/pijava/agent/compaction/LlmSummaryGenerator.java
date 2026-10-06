package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.Usage;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.utils.RetryBackoff;
import com.pijava.ai.utils.RetryableError;

/**
 * LLM 驱动摘要生成器（对齐 pi {@code compaction.ts} 的
 * {@code generateSummaryWithUsage}/{@code generateTurnPrefixSummary}；prompt 文本
 * 集中在 {@link SummaryPrompts}，会话序列化在 {@link ConversationSerializer}）。
 *
 * <p><b>3d 的两环之一</b>（{@code 原 docs/31 §8.22}）：每次摘要调用包在
 * pi {@code completeSummarization}（{@code compaction.ts:579-599}）同形的
 * {@code retryAssistantCall} 环里（{@code ai/utils/retry.ts:174-224} 的逐条移植），
 * 用<b>同一份</b> {@code settings.retry} 预算（pi 注释原文）。瞬断的摘要流不再
 * 一次打崩整个压缩；确定性错误与中止立即返回（环的语义照抄，见本法）。</p>
 *
 * <p><b>失败 = 抛，不兜底</b>（pi {@code generateSummaryWithUsage} :715-721）：
 * error/length 收尾 ⇒ 抛 {@code getSummarizationFailure} 的文案（前缀
 * {@code "Summarization failed: "}）；响应含 toolCall ⇒ 抛
 * "Summarization attempted to call a tool"。异常经 {@code CompactionExecutor}
 * 的兜底 catch 转成 {@code compaction_end{errorMessage}}。此前的
 * 「失败/空输出 ⇒ 截断摘要」回退在 pi 不存在，已删。空文本在 stop 收尾下
 * 合法（pi 直接返回 {@code contentText}）。</p>
 *
 * <p><b>环的终局形状</b>（{@code retryAssistantCall}）：aborted 永不计重试，
 * 但若此前 schedule 过则以未成功收场（finished）；预算耗尽/不可重试 ⇒ 原样返回
 * 末次错误响应；<b>退避睡眠中被中止</b> ⇒ finished + 把错误响应归一化成
 * {@code stopReason:"aborted"}、剥掉 {@code errorMessage} 的同形消息
 * （pi :210-221 的 normalize，调用方不必区分中止发生的时点）。</p>
 */
public final class LlmSummaryGenerator implements SummaryGenerator {

    private final StreamFn streamFn;
    private final Supplier<ModelId<?>> model;
    private final Supplier<RetrySettings> retrySettings;
    private final BooleanSupplier retryAborted;
    private final RetryObserver retryObserver;
    private final IntSupplier modelMaxOutputTokens;

    /** 兼容构造（无重试装配）：默认设置、永不中止、NOOP 观察口、无模型上限。 */
    public LlmSummaryGenerator(StreamFn streamFn, Supplier<ModelId<?>> model) {
        this(streamFn, model, RetrySettings::defaults, () -> false, RetryObserver.NOOP);
    }

    /**
     * @param streamFn      LLM 调用函数（与 harness 同源）
     * @param model         生成摘要所用模型（运行时读取）
     * @param retrySettings 与 post-run ① <b>同一份</b>重试设置（pi 的 getRetrySettings()）
     * @param retryAborted  退避睡眠的中止观察口（pi 传进流的 AbortSignal）
     * @param retryObserver {@code summarization_retry_*} 事件观察口
     */
    public LlmSummaryGenerator(StreamFn streamFn, Supplier<ModelId<?>> model,
                               Supplier<RetrySettings> retrySettings,
                               BooleanSupplier retryAborted,
                               RetryObserver retryObserver) {
        this(streamFn, model, retrySettings, retryAborted, retryObserver, () -> 0);
    }

    /**
     * @param modelMaxOutputTokens pi {@code model.maxTokens} 来源（目录
     *                             {@code maxOutputTokens}）；{@code 0}（未编目）⇒
     *                             不封顶，即 pi {@code > 0} 守卫的假分支形状
     */
    public LlmSummaryGenerator(StreamFn streamFn, Supplier<ModelId<?>> model,
                               Supplier<RetrySettings> retrySettings,
                               BooleanSupplier retryAborted,
                               RetryObserver retryObserver,
                               IntSupplier modelMaxOutputTokens) {
        this.streamFn = streamFn;
        this.model = model;
        this.retrySettings = retrySettings;
        this.retryAborted = retryAborted;
        this.retryObserver = retryObserver;
        this.modelMaxOutputTokens = modelMaxOutputTokens;
    }

    @Override
    public SummaryResult summarize(List<Message> compressed, String previousSummary,
                                   String customInstructions, int reserveTokens, String reason) {
        var response = callWithRetries(() -> produceOnce(compressed, previousSummary,
            customInstructions, reserveTokens), reason);
        // pi generateSummaryWithUsage 的后半（:715-725）：failure 文案 → toolCall 守卫 → 文本。
        var failure = getSummarizationFailure(response, "Summarization");
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
        for (var block : response.content()) {
            if (block instanceof ContentBlock.ToolUseContent) {
                throw new IllegalStateException("Summarization attempted to call a tool");
            }
        }
        return new SummaryResult(contentText(response.content()), response.usage());
    }

    @Override
    public SummaryResult summarizeTurnPrefix(List<Message> messages, int reserveTokens,
                                             String reason) {
        // pi generateTurnPrefixSummary（compaction.ts:1076-1120）：0.5×reserve 上限
        // （模型 cap 封顶）+ # Conversation/# Instructions 包装的逐字 prompt。
        int maxTokens = turnPrefixMaxTokens(reserveTokens);
        String promptText = "# Conversation\n" + ConversationSerializer.serialize(messages)
            + "\n\n# Instructions\n" + SummaryPrompts.TURN_PREFIX;
        var response = callWithRetries(() -> produceOnce(promptText, maxTokens), reason);
        var failure = getSummarizationFailure(response, "Turn prefix summarization");
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
        for (var block : response.content()) {
            if (block instanceof ContentBlock.ToolUseContent) {
                throw new IllegalStateException("Turn prefix summarization attempted to call a tool");
            }
        }
        return new SummaryResult(contentText(response.content()), response.usage());
    }

    /**
     * pi {@code compaction.ts:1083-1087}：{@code min(floor(0.5*reserveTokens),
     * model.maxTokens > 0 ? model.maxTokens : Infinity)}。
     */
    private int turnPrefixMaxTokens(int reserveTokens) {
        int halfReserve = (int) Math.floor(0.5 * reserveTokens);
        int modelCap = modelMaxOutputTokens.getAsInt();
        return modelCap > 0 ? Math.min(halfReserve, modelCap) : halfReserve;
    }

    // ═══════════════════════════════════════════════════════════
    // retryAssistantCall 同形环（retry.ts:174-224）——历史与 turn-prefix 两路共用
    // ═══════════════════════════════════════════════════════════

    /** pi {@code maxAttempts = policy?.enabled ? policy.maxRetries : 0}。 */
    private Message.AssistantMessage callWithRetries(
            Supplier<Message.AssistantMessage> produce, String reason) {
        var policy = retrySettings.get();
        int maxAttempts = policy.enabled() ? policy.maxRetries() : 0;
        int attempt = 0;
        // pi 以 lastRetry 是否有值决定终局要不要发 finished。
        boolean scheduled = false;
        for (;;) {
            var response = produce.get();

            // Abort: terminal but not successful. Never retry an aborted message.
            if ("aborted".equals(response.stopReason())) {
                if (scheduled) {
                    retryObserver.onSummarizationRetryFinished();
                }
                return response;
            }
            // Success: non-error, non-abort responses return as-is.
            if (!"error".equals(response.stopReason())) {
                if (scheduled) {
                    retryObserver.onSummarizationRetryFinished();
                }
                return response;
            }
            // Non-retryable, or budget exhausted: return the final error message.
            if (attempt >= maxAttempts || !RetryableError.isRetryableAssistantError(response)) {
                if (scheduled) {
                    retryObserver.onSummarizationRetryFinished();
                }
                return response;
            }
            attempt++;
            scheduled = true;
            var lastError = messageOrFallback(response.errorMessage());
            long delayMs = RetryBackoff.delayMs(policy.baseDelayMs(), policy.maxAgentDelayMs(),
                attempt);
            retryObserver.onSummarizationRetryScheduled(attempt, maxAttempts, delayMs, lastError);
            // Normalize aborts during retry backoff to the same shape as provider stream
            // aborts (pi :210-221) —— 调用方不区分中止发生的时点。
            if (sleepInterruptible(delayMs)) {
                retryObserver.onSummarizationRetryFinished();
                return normalizeAborted(response);
            }
            retryObserver.onSummarizationRetryAttemptStart("compaction", reason);
        }
    }

    /** pi {@code getSummarizationFailure(response, label)}（compaction.ts:580-588）。 */
    private static String getSummarizationFailure(Message.AssistantMessage response, String label) {
        if ("error".equals(response.stopReason())) {
            return label + " failed: " + messageOrFallback(response.errorMessage());
        }
        if ("length".equals(response.stopReason())) {
            return label + " failed: generation hit the token cap and the summary is incomplete";
        }
        return null;
    }

    /** pi 的 {@code {...rest, stopReason:"aborted"}} 且剥掉 errorMessage（:217-218）。 */
    private static Message.AssistantMessage normalizeAborted(Message.AssistantMessage response) {
        return new Message.AssistantMessage(response.content(), "aborted", response.deferred(),
            response.api(), response.provider(), response.model(), response.usage(),
            response.timestamp(), null, response.rawStopReason());
    }

    /** pi 的 {@code message.errorMessage || "Unknown error"}（空串 ≙ falsy）。 */
    private static String messageOrFallback(String errorMessage) {
        return errorMessage == null || errorMessage.isEmpty() ? "Unknown error" : errorMessage;
    }

    /** pi {@code contentText}（text.ts:6-12）：text 块以 \n 连接，无 strip。 */
    private static String contentText(List<ContentBlock> content) {
        var parts = new ArrayList<String>();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent t) {
                parts.add(t.text());
            }
        }
        return String.join("\n", parts);
    }

    /** 每 50ms 轮询中止标志（Java 方言，同 PostRunRetry.sleepInterruptible）。 */
    private boolean sleepInterruptible(long delayMs) {
        long end = System.nanoTime() + delayMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (retryAborted.getAsBoolean()) {
                return true;
            }
            long remainingMs = (end - System.nanoTime()) / 1_000_000L;
            try {
                Thread.sleep(Math.min(50, Math.max(1, remainingMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // produce —— pi 的 produce = (await streamFn(...)).result()
    // ═══════════════════════════════════════════════════════════

    /**
     * 历史摘要路：标签包裹的逐字 prompt，maxTokens = {@code min(0.8×reserve, 模型 cap)}
     * （pi {@code compaction.ts:712-715}）。
     */
    private Message.AssistantMessage produceOnce(List<Message> compressed, String previousSummary,
                                                 String customInstructions, int reserveTokens) {
        return request(buildPrompt(compressed, previousSummary, customInstructions),
            OptionalInt.of(historyMaxTokens(reserveTokens)));
    }

    /** pi {@code compaction.ts:712-715}：{@code min(floor(0.8*reserveTokens), cap > 0 ? cap : ∞)}。 */
    private int historyMaxTokens(int reserveTokens) {
        int eightyReserve = (int) Math.floor(0.8 * reserveTokens);
        int cap = modelMaxOutputTokens.getAsInt();
        return cap > 0 ? Math.min(eightyReserve, cap) : eightyReserve;
    }

    /** turn-prefix 路：prompt 已按 prefix 形状构造，maxTokens 带 0.5×reserve 上限。 */
    private Message.AssistantMessage produceOnce(String promptText, int maxTokens) {
        return request(promptText, OptionalInt.of(maxTokens));
    }

    /**
     * 一次摘要调用，返回**真** {@code Message.AssistantMessage}（3a 的形状）：
     * 终局事件带 partial ⇒ 全字段投影；脚本化流没有 partial ⇒ 由收集到的
     * TextDelta/ToolCallEnd/UsageInfo 合成，stopReason 取终局 reason
     * （StreamError ⇒ {@code err.reason()} 即 error/aborted，errorMessage 取
     * 异常文本；流被截断没有终局 ⇒ 中止方言，与 PiLoopRunner 的 cutShort 同形）。
     */
    private Message.AssistantMessage request(String promptText, OptionalInt maxTokens) {
        // 原 docs/71 G1：摘要请求的用户消息在构造点盖时间戳（pi compaction.ts:582 的 Date.now()）。
        var user = new Message.UserMessage(
            List.of(new ContentBlock.TextContent(promptText)),
            java.time.Instant.now());
        // 包 A-01：摘要请求主动关缓存 —— pi 的 `completeSummarization` 逐字照抄
        // （`coding-agent/src/core/compaction/compaction.ts:614-618` 的
        // `cacheRetention: "none"`）：一次性的摘要与主会话前缀不同，给它写缓存条目
        // 既无收益、又会与主会话的缓存竞争。⚠️ pi 同一处还发 `sessionId: … ?? uuidv7()`
        // （routing id），那属于会话亲和线、本包不做（原 docs/54 §1.2 的 B103）。
        var options = new StreamOptions(
            maxTokens, OptionalDouble.empty(), Optional.empty(),
            Optional.of(CacheRetention.NONE));
        var toolCalls = new ArrayList<ContentBlock>();
        StringBuilder text = new StringBuilder();
        Usage[] usage = {null};
        var iter = streamFn.stream(model.get(),
            new Context(SummaryPrompts.SYSTEM, List.of(user), List.of()), options);
        Message.AssistantMessage result;
        try {
            result = null;
            while (iter.hasNext()) {
                var event = iter.next();
                if (event instanceof StreamEvent.TextDelta td) {
                    text.append(td.delta());
                } else if (event instanceof StreamEvent.ToolCallEnd tc) {
                    toolCalls.add(new ContentBlock.ToolUseContent(tc.id(), tc.name(), tc.arguments()));
                } else if (event instanceof StreamEvent.UsageInfo ui) {
                    usage[0] = ui.usage() != null ? ui.usage() : synthesizeUsage(ui);
                } else if (event instanceof StreamEvent.StreamDone done) {
                    result = terminal(done.partial(), done.reason(), text, toolCalls, usage[0], null);
                    break;
                } else if (event instanceof StreamEvent.StreamError err) {
                    result = terminal(err.partial(), err.reason(), text, toolCalls, usage[0],
                        StreamEvent.StreamError.textOf(err));
                    break;
                }
            }
            if (result == null) {
                // 流被截断、终局事件缺席 —— provider 方言的 aborted（见上注释）。
                result = terminal(null, "aborted", text, toolCalls, usage[0], null);
            }
        } finally {
            iter.close();
        }
        return result;
    }

    /**
     * 终局合成：partial 优先（全字段投影，pi 的 partial ≙ 终局同形状），但 partial
     * 上缺位的 usage/errorMessage 用收集值补上；无 partial（或 partial 连
     * stopReason 都没有 —— 那是「流未成」的空快照，采信它会把 error 轮伪装成
     * 无终局的成功）⇒ 用收集到的 text + toolCall 块按 reason 合成（空文本合法，
     * 不塞占位块）。
     *
     * <p>C 批次（{@code 原 docs/55}）：{@code errorMessage} 参数现为**兜底** —— 文本
     * 的正源是消息上的 {@code partial.errorMessage()}（生产者落定，见
     * {@code StreamError.settle}），只在消息上没有时才用这里传进来的值。</p>
     */
    private static Message.AssistantMessage terminal(
            com.pijava.ai.message.AssistantMessage partial, String reason,
            StringBuilder text, List<ContentBlock> toolCalls, Usage usage, String errorMessage) {
        if (partial != null && partial.stopReason() != null) {
            var projected = Message.AssistantMessage.fromPartial(partial);
            // ⚠️ 包⑨（原 docs/36 B41）：判据从 `projected.usage() == null` 挪到
            // **`partial.usage() == null`**。原来那个 null 是「流里没报用量」的信号，
            // 而 B41 让 `usageOf` 恒兜零（pi 的 AssistantMessage.usage 必填）⇒ 投影上
            // 的 null 消失了、信号被消灭 ⇒ 摘要跨度的 token 计数会静默归零
            // （夹具 HarnessCompactionSummarySpanTest 抓到的正是这条）。
            // partial 才是「这一次流有没有报用量」的原件，从这里读同一语义。
            boolean backfillUsage = usage != null && partial.usage() == null;
            boolean backfillError = errorMessage != null && projected.errorMessage() == null;
            if (backfillUsage || backfillError) {
                projected = new Message.AssistantMessage(projected.content(),
                    projected.stopReason(), projected.deferred(), projected.api(),
                    projected.provider(), projected.model(),
                    backfillUsage ? usage : projected.usage(),
                    projected.timestamp(),
                    backfillError ? errorMessage : projected.errorMessage(),
                    projected.rawStopReason());
            }
            return projected;
        }
        var blocks = new ArrayList<ContentBlock>();
        if (text.length() > 0) {
            blocks.add(new ContentBlock.TextContent(text.toString()));
        }
        blocks.addAll(toolCalls);
        return new Message.AssistantMessage(blocks, reason, null, null, null, null,
            usage, null, errorMessage, null);
    }

    private static Usage synthesizeUsage(StreamEvent.UsageInfo ui) {
        return new Usage(ui.inputTokens(), ui.outputTokens(), 0, 0, null, null,
            ui.inputTokens() + ui.outputTokens(), Usage.Cost.zero());
    }

    /**
     * pi {@code compaction.ts:717-733}：会话经 {@code <conversation>} 标签包裹，
     * 有旧摘要时插 {@code <previous-summary>} 标签并切换 UPDATE 指令；
     * {@code customInstructions} 非空则追 {@code Additional focus} 后缀。
     */
    private static String buildPrompt(List<Message> compressed, String previousSummary,
                                      String customInstructions) {
        String conversationText = ConversationSerializer.serialize(compressed);
        var sb = new StringBuilder()
            .append("<conversation>\n").append(conversationText).append("\n</conversation>\n\n");
        // pi 真值判定：空串即假（不用 isBlank —— JS 空白串为真）。
        boolean updating = previousSummary != null && !previousSummary.isEmpty();
        if (updating) {
            sb.append("<previous-summary>\n").append(previousSummary)
                .append("\n</previous-summary>\n\n");
        }
        sb.append(updating ? SummaryPrompts.UPDATE_SUMMARIZATION : SummaryPrompts.SUMMARIZATION);
        if (customInstructions != null && !customInstructions.isEmpty()) {
            sb.append("\n\nAdditional focus: ").append(customInstructions);
        }
        return sb.toString();
    }
}

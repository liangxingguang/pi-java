package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingConfig;
import com.pijava.ai.utils.RetryBackoff;
import com.pijava.ai.utils.RetryableError;

/**
 * LLM 驱动摘要生成器（对齐 pi {@code compaction.ts} 的
 * {@code SUMMARIZATION_SYSTEM_PROMPT} + {@code serializeConversation}）。
 *
 * <p><b>3d 的两环之一</b>（{@code docs/31 §8.22}）：每次摘要调用包在
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

    /** 对齐 pi {@code SUMMARIZATION_SYSTEM_PROMPT}。 */
    private static final String SYSTEM_PROMPT =
        "You are a context summarization assistant. Read a conversation between a user "
        + "and an AI assistant, then produce a structured summary following the exact "
        + "format. Do NOT continue the conversation. Do NOT respond to any questions "
        + "in the conversation. ONLY output the structured summary.";

    private final StreamFn streamFn;
    private final Supplier<ModelId<?>> model;
    private final Supplier<RetrySettings> retrySettings;
    private final BooleanSupplier retryAborted;
    private final RetryObserver retryObserver;

    /** 兼容构造（无重试装配）：默认设置、永不中止、NOOP 观察口。 */
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
        this.streamFn = streamFn;
        this.model = model;
        this.retrySettings = retrySettings;
        this.retryAborted = retryAborted;
        this.retryObserver = retryObserver;
    }

    @Override
    public SummaryResult summarize(List<Message> compressed, String previousSummary,
                                   String customInstructions, int reserveTokens, String reason) {
        var response = retryAssistantCall(compressed, previousSummary, reason);
        // pi generateSummaryWithUsage 的后半（:715-725）：failure 文案 → toolCall 守卫 → 文本。
        var failure = getSummarizationFailure(response);
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

    // ═══════════════════════════════════════════════════════════
    // retryAssistantCall 同形环（retry.ts:174-224）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code maxAttempts = policy?.enabled ? policy.maxRetries : 0}。 */
    private Message.AssistantMessage retryAssistantCall(List<Message> compressed,
                                                        String previousSummary, String reason) {
        var policy = retrySettings.get();
        int maxAttempts = policy.enabled() ? policy.maxRetries() : 0;
        int attempt = 0;
        // pi 以 lastRetry 是否有值决定终局要不要发 finished。
        boolean scheduled = false;
        for (;;) {
            var response = produceOnce(compressed, previousSummary);

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

    /** pi {@code getSummarizationFailure(response, "Summarization")}（:545-553）。 */
    private static String getSummarizationFailure(Message.AssistantMessage response) {
        if ("error".equals(response.stopReason())) {
            return "Summarization failed: " + messageOrFallback(response.errorMessage());
        }
        if ("length".equals(response.stopReason())) {
            return "Summarization failed: generation hit the token cap and the summary is incomplete";
        }
        return null;
    }

    /** pi 的 {@code {...rest, stopReason:"aborted"}} 且剥掉 errorMessage（:217-218）。 */
    private static Message.AssistantMessage normalizeAborted(Message.AssistantMessage response) {
        return new Message.AssistantMessage(response.content(), "aborted", response.deferred(),
            response.api(), response.provider(), response.model(), response.usage(),
            response.timestamp(), null);
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
     * 一次摘要调用，返回**真** {@code Message.AssistantMessage}（3a 的形状）：
     * 终局事件带 partial ⇒ 全字段投影；脚本化流没有 partial ⇒ 由收集到的
     * TextDelta/ToolCallEnd/UsageInfo 合成，stopReason 取终局 reason
     * （StreamError ⇒ {@code err.reason()} 即 error/aborted，errorMessage 取
     * 异常文本；流被截断没有终局 ⇒ 中止方言，与 PiLoopRunner 的 cutShort 同形）。
     */
    private Message.AssistantMessage produceOnce(List<Message> compressed, String previousSummary) {
        var user = new Message.UserMessage(
            List.of(new ContentBlock.TextContent(buildPrompt(compressed, previousSummary))));
        var options = new StreamOptions(
            OptionalInt.empty(), OptionalDouble.empty(), ThinkingConfig.OFF);
        var toolCalls = new ArrayList<ContentBlock>();
        StringBuilder text = new StringBuilder();
        Usage[] usage = {null};
        var iter = streamFn.stream(model.get(),
            new Context(SYSTEM_PROMPT, List.of(user), List.of()), options);
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
                        err.error() == null ? null : err.error().getMessage());
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
     */
    private static Message.AssistantMessage terminal(
            com.pijava.ai.message.AssistantMessage partial, String reason,
            StringBuilder text, List<ContentBlock> toolCalls, Usage usage, String errorMessage) {
        if (partial != null && partial.stopReason() != null) {
            var projected = Message.AssistantMessage.fromPartial(partial);
            boolean backfillUsage = usage != null && projected.usage() == null;
            boolean backfillError = errorMessage != null && projected.errorMessage() == null;
            if (backfillUsage || backfillError) {
                projected = new Message.AssistantMessage(projected.content(),
                    projected.stopReason(), projected.deferred(), projected.api(),
                    projected.provider(), projected.model(),
                    backfillUsage ? usage : projected.usage(),
                    projected.timestamp(),
                    backfillError ? errorMessage : projected.errorMessage());
            }
            return projected;
        }
        var blocks = new ArrayList<ContentBlock>();
        if (text.length() > 0) {
            blocks.add(new ContentBlock.TextContent(text.toString()));
        }
        blocks.addAll(toolCalls);
        return new Message.AssistantMessage(blocks, reason, null, null, null, null,
            usage, null, errorMessage);
    }

    private static Usage synthesizeUsage(StreamEvent.UsageInfo ui) {
        return new Usage(ui.inputTokens(), ui.outputTokens(), 0, 0, null, null,
            ui.inputTokens() + ui.outputTokens(), Usage.Cost.zero());
    }

    /** 结构化摘要 prompt（对齐 pi：Goal / Constraints / Progress / Current State）。 */
    private static String buildPrompt(List<Message> compressed, String previousSummary) {
        var sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("Previous summary:\n").append(previousSummary).append("\n\n");
        }
        sb.append("Create a structured context checkpoint summary that another LLM will "
            + "use to continue the work. Use this EXACT format:\n\n"
            + "## Goal\n[What is the user trying to accomplish?]\n\n"
            + "## Constraints & Preferences\n- [Any constraints, or \"(none)\"]\n\n"
            + "## Progress\n- [Key steps taken, or \"(none)\"]\n\n"
            + "## Current State\n- [Files/tools/tasks in progress]\n\n"
            + "Conversation:\n");
        for (var msg : compressed) {
            sb.append("user".equals(msg.role()) ? "[User]: " : "[Assistant]: ");
            sb.append(textOf(msg)).append('\n');
        }
        return sb.toString();
    }

    private static String textOf(Message msg) {
        var sb = new StringBuilder();
        for (var block : msg.content()) {
            if (block instanceof ContentBlock.TextContent t) {
                sb.append(t.text());
            } else if (block instanceof ContentBlock.ThinkingContent t) {
                sb.append("[thinking] ").append(t.text());
            }
        }
        return sb.toString();
    }
}

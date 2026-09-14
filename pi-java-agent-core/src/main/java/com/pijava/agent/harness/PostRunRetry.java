package com.pijava.agent.harness;

import com.pijava.ai.message.Message;
import com.pijava.ai.utils.ContextOverflow;
import com.pijava.ai.utils.RetryableError;
import com.pijava.ai.utils.RetryBackoff;

/**
 * post-run ① 的自动重试环 —— pi {@code _isRetryableError} + {@code _prepareRetry}
 * + {@code _willRetryAfterAgentEnd}（{@code agent-session.ts:2876-2880, 2917-2965,
 * 721-733}）的逐条移植（package 3d，{@code docs/31 §8.22}，裁决①：环住 agent-core
 * 引擎，SessionRunner 的外层 do-while 撤销）。
 *
 * <p><b>为什么必须在引擎里</b>：pi 的 {@code _handlePostAgentRun} 顺序是
 * <b>①重试 → ②压缩 → ③队列</b>（:1116-1144）——①命中就 continue，本轮
 * <b>不</b>跑压缩。3c 的压缩检查已住引擎，①留在宿主外层会让两步倒置。</p>
 *
 * <p><b>与会话层的既有分工</b>（照 pi）：</p>
 * <ul>
 *   <li>计数 {@code lane.retryAttempt} 是<b>会话级</b>（pi {@code _retryAttempt}
 *       :339，跨 prompt 存活，运行边界不复位）；三个复位点里 message_end 成功复位
 *       在 {@link PiLaneSink}，终局失败复位在本环调用方（checkAfterRun），取消复位在
 *       {@link #prepareRetry} 内部。</li>
 *   <li>{@code agent_end.willRetry} 装饰（{@link #retryWouldFollow}）与 ① 的判定
 *       <b>各算各的</b>，pi 也不共享缓存（:666 装饰、:1123 判定时重读设置与计数）。</li>
 * </ul>
 *
 * <p><b>Java 方言</b>：退避睡眠阻塞驱动线程，每 50ms 轮询 {@code ctx.retryAborted()}
 * （pi 是 AbortController 信号）；线程中断（如宿主关池）与中止同形处理。</p>
 */
final class PostRunRetry {

    private final ExecutionContext ctx;

    PostRunRetry(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    /**
     * pi {@code _isRetryableError(message)}（:2876-2880）：溢出交压缩不重试
     * （交接在分类器这一层），否则过 ai 层白名单。窗口操作数传**当前模型的
     * 真窗口**（pi {@code this.model?.contextWindow ?? 0}，3d 撤掉宿主层的
     * 「合成消息 + null 窗口」形状）。
     */
    boolean isRetryableError(Message.AssistantMessage message) {
        var model = ctx.model().get();
        int window = model == null ? 0 : ctx.contextWindow(model);
        if (ContextOverflow.isContextOverflow(message, window)) {
            return false;
        }
        return RetryableError.isRetryableAssistantError(message);
    }

    /**
     * pi {@code _prepareRetry(message)}（:2917-2965），返回「调用方应 continue」。
     * 守卫顺序照 pi：enabled 复查 ⇒ 计数 ++ ⇒ **超预算则回退计数并 false**
     * （保留完成计数给终局失败事件，:1127-1134）⇒ 算延迟 ⇒ 发
     * {@code auto_retry_start} ⇒ <b>只摘工作副本尾部</b>的助手消息（日志保留，
     * :2937-2941，用户历史里看得见那次失败）⇒ 可中止退避（取消 ⇒
     * {@code auto_retry_end{false,…,"Retry cancelled"}} + 清零 + false）。
     */
    boolean prepareRetry(LaneState lane, Message.AssistantMessage message) {
        var settings = ctx.retrySettings().get();
        if (!settings.enabled()) {
            return false;
        }
        lane.retryAttempt++;
        if (lane.retryAttempt > settings.maxRetries()) {
            // Preserve the completed attempt count so post-run handling can emit the final failure.
            lane.retryAttempt--;
            return false;
        }
        long delayMs = RetryBackoff.delayMs(settings.baseDelayMs(),
            settings.maxAgentDelayMs(), lane.retryAttempt);
        ctx.retryObserver().onAutoRetryStart(lane.retryAttempt, settings.maxRetries(),
            delayMs, piOrFallback(message.errorMessage()));
        // Remove error message from agent state (keep in session for history).
        var messages = lane.messages;
        if (!messages.isEmpty()
                && messages.get(messages.size() - 1) instanceof Message.AssistantMessage) {
            messages.remove(messages.size() - 1);
        }
        if (sleepInterruptible(delayMs)) {
            int attempt = lane.retryAttempt;
            lane.retryAttempt = 0;
            ctx.retryObserver().onAutoRetryEnd(false, attempt, "Retry cancelled");
            return false;
        }
        return true;
    }

    /**
     * pi {@code _willRetryAfterAgentEnd}（:721-733）的单消息形状：enabled、
     * {@code attempt >= maxRetries} 两道门先过，再判该消息是否可重试
     * （倒扫 {@code event.messages} 找「第一条 assistant」是调用方的事 ——
     * pi-java 把扫描放在装饰发射点）。注意门用 {@code >=}：预算耗尽 pass 的
     * agent_end 已 false，随后终局失败块（{@code >} + 回退链）补 end 事件。
     */
    boolean retryWouldFollow(LaneState lane, Message.AssistantMessage lastAssistant) {
        if (lastAssistant == null) {
            return false;
        }
        var settings = ctx.retrySettings().get();
        if (!settings.enabled() || lane.retryAttempt >= settings.maxRetries()) {
            return false;
        }
        return isRetryableError(lastAssistant);
    }

    /** pi 的 {@code message.errorMessage || "Unknown error"}（空串 ≙ falsy）。 */
    private static String piOrFallback(String errorMessage) {
        return errorMessage == null || errorMessage.isEmpty() ? "Unknown error" : errorMessage;
    }

    /** 每 50ms 轮询中止标志；返回 true ⇒ 睡眠被中止/打断。 */
    private boolean sleepInterruptible(long delayMs) {
        long end = System.nanoTime() + delayMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (ctx.retryAborted().getAsBoolean()) {
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
}

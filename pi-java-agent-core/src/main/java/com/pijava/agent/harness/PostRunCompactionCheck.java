package com.pijava.agent.harness;

import java.util.List;
import java.util.Objects;

import com.pijava.agent.context.ContextUsageEstimator;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.ai.utils.ContextOverflow;

/**
 * 运行收口后的收尾判定 —— pi {@code _handlePostAgentRun}（①②③ 全序，① 委托
 * {@link PostRunRetry}）与 {@code _checkCompaction}（{@code agent-session.ts:
 * 1116-1144, 2154-2258}）的逐条移植（package 3c 落 ②③，3d 补 ① 与终局失败，
 * {@code docs/31 §8.21/§8.22}）。
 *
 * <p><b>输入消息的来源</b>与 pi 一致：post-run 读事件跟踪的
 * {@link PiLaneSink#lastAssistant()}（pi {@code _lastAssistantMessage}，读后即清，
 * 每个 pass 一个新 sink）；prompt 起手前读对工作副本的扫描
 * （pi {@code _findLastAssistantMessage}，{@code :737-745}，且
 * {@code skipAbortedCheck=false} —— 它专抓被中止的响应，{@code :1258-1263}）。</p>
 *
 * <p><b>3d 起 ① 也在场</b>：{@link #checkAfterRun} 是 {@code _handlePostAgentRun}
 * 的全序 <b>①重试 → 终局失败收尾 → ②压缩 → ③队列</b> —— ① 住在
 * {@link PostRunRetry}（{@code _isRetryableError && _prepareRetry}），命中即
 * continue，本轮<b>不</b>跑 ②；① 没救活的 error 链在这里收口（终局失败事件 +
 * 清零）。{@link #checkBeforePrompt} 照旧只跑 ②（pi :1258-1263 调的就是
 * {@code _checkCompaction}，没有重试环）。</p>
 *
 * <p><b>守卫顺序照 pi</b>：G0 设置未启用 ⇒ false；G1 跳过 aborted（仅 post-run）；
 * 窗口 :2161；G3 sameModel 只罩 C1/C2（换模型后的旧溢出错误不该压新模型）；
 * G4 全局陈旧界（消息早于最新压缩边界 ⇒ false，防压缩后第一条 prompt 被旧用量
 * 再触发一次）；C1/C2 溢出判定；R0 {@code willRetry = stopReason !== "stop"}；
 * R1 闩已立 ⇒ 只发 end{overflow, errorMessage=固定文案} 不再压；R2 置闩、摘副本尾
 * 的助手消息（日志不动）再压且重试；都没命中走 T1/T2 阈值路。</p>
 *
 * <p><b>T1 的读数</b>（{@code :2226-2256}）：有 usage 直读
 * {@code calculateContextTokens}；error 收尾或折算值为 0 ⇒ 退回
 * {@code estimateContextTokens(messages)}，并且当估算挂着**用量锚点**
 * （{@code lastUsageIndex}）时校验锚点消息不早于最新压缩边界，陈旧 ⇒ false。
 * usage 为 null / 时间戳为 null 在 JS 里是 falsy/NaN 比较 ⇒ 不跳过，Java 侧
 * 同形（裁决⑤）。</p>
 */
final class PostRunCompactionCheck {

    /** pi {@code :2200} 的固定文案（overflow 分支）。 */
    private static final String OVERFLOW_RETRY_FAILED =
        "Context overflow recovery failed after one compact-and-retry attempt. "
            + "Try reducing context or switching to a larger-context model.";

    /** pi {@code :2201} 的固定文案（截断分支）。 */
    private static final String TRUNCATED_RETRY_FAILED =
        "Truncated response recovery failed after one compact-and-retry attempt.";

    private final ExecutionContext ctx;
    private final CompactionExecutor compactions;
    private final PostRunRetry retry;

    PostRunCompactionCheck(ExecutionContext ctx, CompactionExecutor compactions) {
        this.ctx = ctx;
        this.compactions = compactions;
        this.retry = new PostRunRetry(ctx);
    }

    /**
     * pi {@code _handlePostAgentRun}（{@code :1116-1144}）的全序，返回
     * 「驱动再 continue 一轮」与否：
     *
     * <ol>
     *   <li><b>①</b>（3d）：{@code _isRetryableError(msg) && await _prepareRetry(msg)}
     *       ⇒ true。prepareRetry 自己负责 auto_retry_start、只摘副本尾、退避睡眠与
     *       取消路径（取消 ⇒ false，落到终局块下面）。</li>
     *   <li><b>终局失败</b>（3d，{@code :1127-1134}）：error 收尾且 {@code
     *       _retryAttempt > 0} ⇒ 发 {@code auto_retry_end{success:false, attempt,
     *       finalError: msg.errorMessage}}（透传，null ≙ pi 的 undefined）+ 清零。
     *       走到这里说明 ① 没救活：预算耗尽（{@code >} 守卫回退计数）或链被中止。</li>
     *   <li><b>②</b>：{@code _checkCompaction(msg, true)}。</li>
     *   <li><b>③</b>：队列有货（pi {@code :1143} —— 只能是 agent_end 扩展塞的，
     *       3c 无扩展层，判据照抄）。</li>
     * </ol>
     *
     * @param lastAssistant 本 pass 事件跟踪的最后一个助手消息；{@code null} ⇒ false
     *                      （pi {@code :1118-1121} 的 !msg 短路）
     */
    boolean checkAfterRun(String laneName, LaneState lane, Message.AssistantMessage lastAssistant) {
        if (lastAssistant == null) {
            return false;
        }
        if (retry.isRetryableError(lastAssistant) && retry.prepareRetry(lane, lastAssistant)) {
            return true;
        }
        if ("error".equals(lastAssistant.stopReason()) && lane.retryAttempt > 0) {
            ctx.retryObserver().onAutoRetryEnd(false, lane.retryAttempt, lastAssistant.errorMessage());
            lane.retryAttempt = 0;
        }
        if (check(laneName, lane, lastAssistant, true)) {
            return true;
        }
        // pi :1143 —— 队列在这之后还有货只能是 agent_end 扩展塞的（3c 无扩展层，
        // 但判据照抄：它同样驱动 continue）。
        return CompactionExecutor.hasQueuedMessages(lane);
    }

    /**
     * pi {@code _willRetryAfterAgentEnd}（{@code :721-733}）的引擎侧入口 ——
     * 委托 {@link PostRunRetry#retryWouldFollow}。宿主用它装饰
     * {@code agent_end.willRetry}（3d，{@code docs/31 §8.22} 裁决①：环在引擎，
     * 装饰数据也出自引擎），与 ① 各算各的。
     */
    boolean retryWouldFollow(LaneState lane, Message.AssistantMessage lastAssistant) {
        return retry.retryWouldFollow(lane, lastAssistant);
    }

    /**
     * pi prompt 起手前的同一判定（{@code :1258-1263}）：扫工作副本找最后的助手
     * 消息，{@code skipAbortedCheck=false}。返回值被 pi 忽略 —— 用户的新 prompt
     * 马上就要发出去，不该在这里 continue。压缩本身照跑。
     */
    void checkBeforePrompt(String laneName, LaneState lane) {
        var lastAssistant = findLastAssistant(lane);
        if (lastAssistant != null) {
            check(laneName, lane, lastAssistant, false);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // _checkCompaction 本体
    // ═══════════════════════════════════════════════════════════

    private boolean check(String laneName, LaneState lane, Message.AssistantMessage message,
                          boolean skipAbortedCheck) {
        var settings = ctx.compactionSettings().get();
        // G0：pi 的 settings.enabled（:2155-2156）。
        if (settings == null || !settings.enabled()) {
            return false;
        }
        // G1：aborted 是用户取消，不是上下文问题（:2158-2159）。
        if (skipAbortedCheck && "aborted".equals(message.stopReason())) {
            return false;
        }
        var model = ctx.model().get();
        // 窗口 = pi 的 this.model?.contextWindow ?? 0（:2161）。
        int window = model == null ? 0 : ctx.contextWindow(model);
        // G3：sameModel 只罩 C1/C2（:2167-2168）。pi 比的是 model.id，
        // pi-java 的身份戳是 ModelId.modelName（3a 起由 AbstractChatApi 盖章）。
        boolean sameModel = model != null
            && Objects.equals(message.provider(), model.provider())
            && Objects.equals(message.model(), model.modelName());
        // G4：全局陈旧界（:2172-2178）——早于最新压缩边界的消息不再触发。
        var compactionEntry = latestCompaction(lane);
        if (compactionEntry != null && isAtOrBefore(message, compactionEntry)) {
            return false;
        }
        // C1/C2：溢出与可恢复截断（:2183-2184）。
        boolean contextOverflow = sameModel && ContextOverflow.isContextOverflow(message, window);
        boolean recoverableLength = sameModel && ContextOverflow.isRecoverableLength(message,
            model == null ? 0 : ctx.maxOutputTokens(model));
        if (contextOverflow || recoverableLength) {
            // R0：stop 收尾的溢出（case 2）压完不重试 —— continue 接不上
            // 「已完成」的响应（:2186-2187）。
            boolean willRetry = !"stop".equals(message.stopReason());
            if (!willRetry) {
                return compactions.runAutoCompaction(laneName, lane, "overflow", false).shouldContinue();
            }
            if (lane.overflowRecoveryAttempted) {
                // R1：预算只有一次。pi :2194-2211 只发 end{overflow}（无 start），
                // 伴生的 _emitSessionCompactFailed 属扩展层，不在 3c 面。
                ctx.compactionObserver().onEnd("overflow", null, false, false,
                    contextOverflow ? OVERFLOW_RETRY_FAILED : TRUNCATED_RETRY_FAILED);
                return false;
            }
            // R2：置闩、把失败的助手消息从**副本**摘掉（日志留着，:2214-2223），
            // 再 compact-and-retry。
            lane.overflowRecoveryAttempted = true;
            var messages = lane.messages;
            if (!messages.isEmpty()
                    && messages.get(messages.size() - 1) instanceof Message.AssistantMessage) {
                messages.remove(messages.size() - 1);
            }
            return compactions.runAutoCompaction(laneName, lane, "overflow", willRetry).shouldContinue();
        }
        // T1：阈值读数（:2230-2256）。
        double contextTokens;
        double direct = message.usage() != null
            ? ContextUsageEstimator.calculateContextTokens(message.usage()) : 0;
        if ("error".equals(message.stopReason()) || direct == 0) {
            // 错误/全零用量 ⇒ 退回纯估算，并校验用量锚点不是压缩前的旧值。
            var estimate = ContextUsageEstimator.estimateContextTokens(List.copyOf(lane.messages));
            if (estimate.lastUsageIndex() != null) {
                var usageMsg = lane.messages.get(estimate.lastUsageIndex());
                if (compactionEntry != null
                        && usageMsg instanceof Message.AssistantMessage usageAssistant
                        && isAtOrBefore(usageAssistant, compactionEntry)) {
                    return false;
                }
            }
            contextTokens = estimate.tokens();
        } else {
            contextTokens = direct;
        }
        // T2：阈值压缩，不重试（:2254-2256）。
        if (com.pijava.agent.context.ContextUsageEstimator
                .shouldCompact(contextTokens, window, settings)) {
            return compactions.runAutoCompaction(laneName, lane, "threshold", false).shouldContinue();
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 视图与工具
    // ═══════════════════════════════════════════════════════════

    /** pi {@code _findLastAssistantMessage}（:737-745）：倒扫工作副本，含 aborted。 */
    private static Message.AssistantMessage findLastAssistant(LaneState lane) {
        var messages = lane.messages;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage assistant) {
                return assistant;
            }
        }
        return null;
    }

    /** pi {@code getLatestCompactionEntry(branch)}：沿当前路径找最新压缩边界。 */
    private static Entry.Compaction latestCompaction(LaneState lane) {
        var transcript = lane.transcript;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (transcript.get(i) instanceof Entry.Compaction compaction) {
                return compaction;
            }
        }
        return null;
    }

    /**
     * pi 的 {@code message.timestamp <= new Date(compactionEntry.timestamp).getTime()}。
     * 两边都折到毫秒（裁决⑤）；消息时间戳为 null ≙ JS 的 {@code undefined <= x} ⇒ false
     * （不跳过）。
     */
    private static boolean isAtOrBefore(Message.AssistantMessage message, Entry.Compaction boundary) {
        return message.timestamp() != null
            && message.timestamp().toEpochMilli() <= boundary.timestamp().toEpochMilli();
    }
}

package com.pijava.agent.harness;

/**
 * 自动重试事件的宿主观察口 —— 与 {@code CompactionObserver}（3c）并列的轻量槽
 * （package 3d，{@code docs/31 §8.22}，裁决③：不占 {@code HookSystem}）。
 * 覆盖 pi 两环的全部会话事件：
 *
 * <ul>
 *   <li>{@code auto_retry_start / auto_retry_end} —— AgentSession 的 post-run ①
 *       （{@code agent-session.ts:2925-2933} 的 {@code _prepareRetry}、
 *       :1127-1134 的终局失败、:698-706 的成功复位、:2948-2957 的取消）；</li>
 *   <li>{@code summarization_retry_scheduled / attempt_start / finished} ——
 *       摘要重试（{@code _summarizationRetryCallbacks}，:2888-2911），由
 *       {@code LlmSummaryGenerator} 内的 {@code retryAssistantCall} 同形环发出。</li>
 * </ul>
 *
 * <p>回调在<b>驱动线程</b>上被调用（退避睡眠阻塞驱动是 Java 方言，见
 * {@code PostRunRetry}），宿主实现必须线程安全且不得抛异常。</p>
 */
public interface RetryObserver {

    RetryObserver NOOP = new RetryObserver() { };

    /**
     * pi {@code auto_retry_start{attempt,maxAttempts,delayMs,errorMessage}}。
     * {@code errorMessage} 已由发射方过 pi 的 {@code || "Unknown error"} 归一。
     */
    default void onAutoRetryStart(int attempt, int maxAttempts, long delayMs, String errorMessage) { }

    /**
     * pi {@code auto_retry_end{success,attempt,finalError?}}。三个终局点共用本法：
     * 成功复位（{@code finalError=null}）、退避中被中止（
     * {@code finalError="Retry cancelled"}）、终局失败（{@code finalError=末次
     * errorMessage}，可为 null ≙ pi 的 undefined 透传）。
     */
    default void onAutoRetryEnd(boolean success, int attempt, String finalError) { }

    /** pi {@code summarization_retry_scheduled{attempt,maxAttempts,delayMs,errorMessage}}。 */
    default void onSummarizationRetryScheduled(int attempt, int maxAttempts, long delayMs,
                                               String errorMessage) { }

    /**
     * pi {@code summarization_retry_attempt_start} —— {@code source} 为
     * {@code "compaction"}（携带 {@code reason}）或 {@code "branchSummary"}
     * （3d 无 branch-summary 实现，槽位照形状保留，docs/31 §8.22.5-2）。
     */
    default void onSummarizationRetryAttemptStart(String source, String reason) { }

    /** pi {@code summarization_retry_finished} —— 仅当本环曾 schedule 过重试才发。 */
    default void onSummarizationRetryFinished() { }
}

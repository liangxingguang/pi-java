package com.pijava.agent.compaction;

/**
 * 压缩事件的宿主观察口 —— pi {@code AgentSession._emit} 的
 * {@code compaction_start} / {@code compaction_end} 两个会话事件
 * （{@code agent-session.ts:157-170, 1970, 2085, 2098, 2199, 2290-2440}；
 * package 3c，{@code docs/31 §8.21}）。
 *
 * <p><b>为什么是 observer 而不是 {@code HookSystem}</b>（裁决③）：钩子是用户的
 * 改写/否决面（{@code session_before_compact} 对应物），而 start/end 是**通知**，
 * 通知不该有返回值。宿主（coding-agent）把这两个回调映射成
 * {@code AgentSessionEvent.CompactionStart/CompactionEnd} 发进会话事件流。</p>
 *
 * <p><b>reason 取 pi 的字面量</b>：{@code "manual"} / {@code "threshold"} /
 * {@code "overflow"}（pi 的判别联合成员，非新造枚举）。R1 一次性闩锁的失败是
 * pi 唯一「只发 end、不发 start」的事件（{@code :2198-2205}），发射点在
 * {@code PostRunCompactionCheck}，不在压缩体内。</p>
 */
public interface CompactionObserver {

    /** 无事发生的默认实现（未装配时的宿主）。 */
    CompactionObserver NOOP = new CompactionObserver() {
        @Override
        public void onStart(String reason) { }

        @Override
        public void onEnd(String reason, CompactionResult result,
                          boolean aborted, boolean willRetry, String errorMessage) { }
    };

    /** pi {@code { type: "compaction_start"; reason }}。 */
    void onStart(String reason);

    /**
     * pi {@code { type: "compaction_end"; reason; result; aborted; willRetry; errorMessage }}。
     *
     * @param result       压缩产物；{@code null} ≙ pi 的 {@code undefined}
     *                     （取消、中止、失败、R1 闩锁都带空结果）
     * @param errorMessage pi 的可选错误文案，{@code null} ≙ 无
     */
    void onEnd(String reason, CompactionResult result,
               boolean aborted, boolean willRetry, String errorMessage);
}

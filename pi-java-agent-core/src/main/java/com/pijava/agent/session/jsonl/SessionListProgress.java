package com.pijava.agent.session.jsonl;

import java.util.List;

/**
 * pi {@code SessionListProgress}（session-manager.ts:884-889）对位（#79/B55）：
 * 会话列表渐进发布回调。
 */
@FunctionalInterface
public interface SessionListProgress {

    /**
     * @param loaded         已加载（扫描完）的会话文件数
     * @param total          候选文件总数
     * @param partialSessions 已加载会话的活动序快照 —— <b>仅在周期发布点非 null</b>
     *        （pi 的 {@code partialSessions?}：非发布点回调照样触发但不带快照）
     */
    void onProgress(int loaded, int total, List<RichSessionInfo> partialSessions);
}

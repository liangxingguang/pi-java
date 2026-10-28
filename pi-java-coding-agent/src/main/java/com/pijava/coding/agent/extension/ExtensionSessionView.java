package com.pijava.coding.agent.extension;

import java.util.List;

import com.pijava.agent.entry.Entry;

/**
 * 批 28（docs/40 T2，裁决③）：pi {@code ReadonlySessionManager}
 * （session-manager.ts:245-261，15 成员 Pick）的对位——扩展可见的会话<b>只读</b>面。
 *
 * <p>本批只落 {@link #getEntries()}（组 1 唯一消费点：confirm-destructive）；
 * 余 14 成员（getBranch/getEntry/getLabel/…）随后续批消费点逐个补（docs/04 挂账）。</p>
 */
public interface ExtensionSessionView {

    /**
     * pi {@code SessionManager.getEntries()}——全分支提交序条目快照。
     * 无持久面（{@code --no-session}）⇒ harness 转录兜底（pi-java 既有
     * {@code accumulatedEntries()} 语义，docs/40 F1 取证）。
     */
    List<Entry> getEntries();
}

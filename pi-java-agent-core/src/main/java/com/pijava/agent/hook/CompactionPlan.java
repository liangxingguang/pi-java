package com.pijava.agent.hook;

import com.pijava.agent.entry.Entry;
import java.util.List;

/**
 * Returned by {@code before_compaction} hook to specify the desired compaction plan.
 *
 * <p>docs/15 裁决⑥ 三臂（优先级从高到低，pi {@code SessionBeforeCompactResult}
 * 的 {@code cancel}＞{@code compaction} 序）：</p>
 * <ul>
 *   <li>{@code cancel} —— 否决本次压缩（pi {@code cancelledByExtension}：走中止臂，
 *       手动路随后抛 {@code "Compaction cancelled"}）；</li>
 *   <li>{@code supplied} —— 扩展供应压缩产物，跳过默认摘要生成（fromHook=true）；</li>
 *   <li>{@code keepEntries} 非空 —— 批前的保留清单臂（自定义转录，无压缩标记）。</li>
 * </ul>
 */
public record CompactionPlan(List<Entry> keepEntries, int targetTokens,
                             Boolean cancel, SuppliedCompaction supplied) {

    /** Defensively copies {@code keepEntries}. */
    public CompactionPlan {
        keepEntries = keepEntries == null ? List.of() : List.copyOf(keepEntries);
    }

    /** 兼容批前 2 组件形状（保留清单臂）。 */
    public CompactionPlan(List<Entry> keepEntries, int targetTokens) {
        this(keepEntries, targetTokens, null, null);
    }

    /** pi {@code {cancel: true}}。 */
    public static CompactionPlan cancelled() {
        return new CompactionPlan(List.of(), 0, true, null);
    }

    /** pi {@code {compaction: …}} —— 扩展接管生成。 */
    public static CompactionPlan supplied(SuppliedCompaction supplied) {
        return new CompactionPlan(List.of(), 0, null, supplied);
    }
}

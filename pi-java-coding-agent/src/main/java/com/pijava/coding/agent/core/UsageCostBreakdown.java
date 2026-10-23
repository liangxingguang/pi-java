package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;

/**
 * pi {@code getUsageCostBreakdown}（usage-totals.ts:61-96）对位（批 15 T11）：
 * assistant 按 {@code provider/responseModel ?? model} 分组、usage 条目按
 * {@code provider/model}、toolResult/branch_summary/compaction 归
 * {@code Tools/summaries}；滤零桶；cost 降序。
 */
public final class UsageCostBreakdown {

    private UsageCostBreakdown() {}

    /** pi {@code UsageCostBreakdownEntry}；tokens＝四件之和。 */
    public record Bucket(String key, double cost, long tokens) {}

    /** pi 逐循环对位（{@code null} usage 的 assistant 桶键也照产——pi 无 continue 前判键）。 */
    public static List<Bucket> of(List<Entry> entries) {
        var totalsByKey = new LinkedHashMap<String, SessionStats.Totals>();
        for (var entry : entries) {
            String key = null;
            Usage usage = null;
            if (entry instanceof Entry.Message messageEntry
                    && messageEntry.message() instanceof Message.AssistantMessage assistant) {
                var model = assistant.responseModel() != null
                    ? assistant.responseModel() : assistant.model();
                key = assistant.provider() + "/" + model;
                usage = assistant.usage();
            } else if (entry instanceof Entry.Usage usageEntry) {
                key = usageEntry.provider() + "/" + usageEntry.model();
                usage = usageEntry.usage();
            } else if (entry instanceof Entry.Message messageEntry
                    && messageEntry.message() instanceof Message.ToolResultMessage toolResult
                    && toolResult.usage() instanceof Usage toolUsage) {
                key = "Tools/summaries";
                usage = toolUsage;
            } else if (entry instanceof Entry.BranchSummary bs && bs.usage() != null) {
                key = "Tools/summaries";
                usage = bs.usage();
            } else if (entry instanceof Entry.Compaction c && c.usage() != null) {
                key = "Tools/summaries";
                usage = c.usage();
            }
            if (key == null || usage == null) {
                continue;
            }
            totalsByKey.computeIfAbsent(key, k -> new SessionStats.Totals()).add(usage);
        }
        var buckets = new ArrayList<Bucket>();
        totalsByKey.forEach((key, totals) ->
            buckets.add(new Bucket(key, totals.cost, totals.tokens())));
        buckets.removeIf(b -> !(b.cost() > 0 || b.tokens() > 0));   // pi 滤零桶
        buckets.sort((a, b) -> Double.compare(b.cost(), a.cost()));  // pi cost 降序
        return List.copyOf(buckets);
    }
}

package com.pijava.agent.hook;

import com.pijava.agent.entry.Entry;
import java.util.List;

/** Returned by {@code before_compaction} hook to specify the desired compaction plan. */
public record CompactionPlan(List<Entry> keepEntries, int targetTokens) {
    public CompactionPlan {
        keepEntries = List.copyOf(keepEntries);
    }
}

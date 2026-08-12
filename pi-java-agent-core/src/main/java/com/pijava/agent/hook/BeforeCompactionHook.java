package com.pijava.agent.hook;

/** Hook triggered before compaction. Can modify the compaction plan. */
@FunctionalInterface
public interface BeforeCompactionHook {
    CompactionPlan beforeCompaction(CompactionContext ctx);
}

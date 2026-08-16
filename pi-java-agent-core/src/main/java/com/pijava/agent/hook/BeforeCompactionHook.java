package com.pijava.agent.hook;

/** Hook triggered before compaction. Can modify the compaction plan. */
@FunctionalInterface
public interface BeforeCompactionHook {
    /** Invoked before compaction; may return a modified {@link CompactionPlan}. */
    CompactionPlan beforeCompaction(CompactionContext ctx);
}

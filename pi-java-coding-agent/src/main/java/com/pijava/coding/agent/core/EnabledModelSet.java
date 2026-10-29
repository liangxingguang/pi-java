package com.pijava.coding.agent.core;

import java.util.List;

/** pi EnabledIds: All（null）与显式有序列表是两个不同状态。 */
public sealed interface EnabledModelSet {

    /** All models are enabled; no Ctrl+P scope filter. */
    record All() implements EnabledModelSet {}

    /** Explicit ordered ids, including ids unavailable in the current catalog. */
    record Explicit(List<String> orderedIds) implements EnabledModelSet {
        /** Defensively copy the ordered id list. */
        public Explicit {
            orderedIds = List.copyOf(orderedIds);
        }
    }
}

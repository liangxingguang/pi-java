package com.pijava.agent.harness;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;

/**
 * A user-visible, persisted event in the agent transcript.
 *
 * <p>Entries form a tree (via {@code parentId}) and are immutable.
 * Each permitted subtype models one kind of event the user sees in
 * the conversation history.</p>
 */
public sealed interface Entry {

    /** Unique entry identifier. */
    String id();

    /** Monotonic sequence number. */
    long seq();

    /** Parent entry ID (or empty string for root). */
    String parentId();

    /** When this entry was created. */
    Instant timestamp();

    /** A user, assistant, or tool message. */
    record Message(
        String id, long seq, String parentId, Instant timestamp,
        String role,
        List<ContentBlock> blocks
    ) implements Entry {
        public Message {
            blocks = List.copyOf(blocks);
        }
    }

    /** The model was changed. */
    record ModelChange(
        String id, long seq, String parentId, Instant timestamp,
        String provider,
        String modelId
    ) implements Entry {}

    /** The thinking level was changed. */
    record ThinkingLevelChange(
        String id, long seq, String parentId, Instant timestamp,
        String level
    ) implements Entry {}

    /** The set of active tools was changed. */
    record ActiveToolsChange(
        String id, long seq, String parentId, Instant timestamp,
        List<String> toolNames
    ) implements Entry {
        public ActiveToolsChange {
            toolNames = List.copyOf(toolNames);
        }
    }

    /** A context compaction occurred. */
    record Compaction(
        String id, long seq, String parentId, Instant timestamp,
        String reason,
        int entriesBefore,
        int entriesAfter
    ) implements Entry {}

    /** A branch summary was generated. */
    record BranchSummary(
        String id, long seq, String parentId, Instant timestamp,
        String summary
    ) implements Entry {}

    /** A custom extension event. */
    record Custom(
        String id, long seq, String parentId, Instant timestamp,
        String kind,
        Map<String, Object> data
    ) implements Entry {
        public Custom {
            data = Map.copyOf(data);
        }
    }
}

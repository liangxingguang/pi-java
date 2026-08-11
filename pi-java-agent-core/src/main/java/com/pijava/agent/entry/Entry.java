package com.pijava.agent.entry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pijava.ai.message.ContentBlock;

/**
 * A user-visible, persisted event in the agent transcript.
 *
 * <p>Entries form a tree (via {@code parentId}) and are immutable.
 * Each permitted subtype models one kind of event the user sees in
 * the conversation history. Aligned with pi's Entry sealed union.</p>
 *
 * <p>Phase 2a uses: {@link Message} (user + assistant roles),
 * {@link ThinkingLevelChange} (initialization).
 * The remaining 5 types are defined for future phases.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Entry.Message.class, name = "message"),
    @JsonSubTypes.Type(value = Entry.ModelChange.class, name = "model_change"),
    @JsonSubTypes.Type(value = Entry.ThinkingLevelChange.class, name = "thinking_level_change"),
    @JsonSubTypes.Type(value = Entry.ActiveToolsChange.class, name = "active_tools_change"),
    @JsonSubTypes.Type(value = Entry.Compaction.class, name = "compaction"),
    @JsonSubTypes.Type(value = Entry.BranchSummary.class, name = "branch_summary"),
    @JsonSubTypes.Type(value = Entry.Custom.class, name = "custom")
})
public sealed interface Entry {

    /** Shared identity fields. */
    EntryHeader header();

    // ── Helpers ──────────────────────────────────────────────

    /** Convenience: create a new EntryHeader with a fresh UUID and current timestamp. */
    static EntryHeader newHeader(long seq, String parentId) {
        return new EntryHeader(UUID.randomUUID().toString(), seq, parentId, Instant.now());
    }

    // ═══════════════════════════════════════════════════════════
    // Subtypes
    // ═══════════════════════════════════════════════════════════

    /** A user, assistant, or tool message. Phase 2a uses user + assistant roles. */
    record Message(
        EntryHeader header,
        String role,           // "user" | "assistant" | "tool"
        List<ContentBlock> blocks
    ) implements Entry {
        public Message {
            blocks = List.copyOf(blocks);
        }
    }

    /** The model was changed (Phase 2c). */
    record ModelChange(
        EntryHeader header,
        String provider,
        String modelId
    ) implements Entry {}

    /**
     * The thinking level was changed.
     * Phase 2a uses this on initialization.
     * Level values: "off" | "minimal" | "low" | "medium" | "high" | "xhigh".
     */
    record ThinkingLevelChange(
        EntryHeader header,
        String level
    ) implements Entry {}

    /** The set of active tools was changed (Phase 2b). */
    record ActiveToolsChange(
        EntryHeader header,
        List<String> toolNames
    ) implements Entry {
        public ActiveToolsChange {
            toolNames = List.copyOf(toolNames);
        }
    }

    /** A context compaction occurred (Phase 2c). */
    record Compaction(
        EntryHeader header,
        String reason,        // "overflow" | "manual"
        int entriesBefore,
        int entriesAfter
    ) implements Entry {}

    /** A branch summary was generated (Phase 2c). */
    record BranchSummary(
        EntryHeader header,
        String summary
    ) implements Entry {}

    /** A custom extension event. */
    record Custom(
        EntryHeader header,
        String kind,
        Map<String, Object> data
    ) implements Entry {
        public Custom {
            data = Map.copyOf(data);
        }
    }
}

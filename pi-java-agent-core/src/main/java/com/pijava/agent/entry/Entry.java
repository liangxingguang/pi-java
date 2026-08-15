package com.pijava.agent.entry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A user-visible, persisted event in the agent transcript.
 *
 * <p>Entries form a tree (via {@code parentId}) and are immutable. Identity
 * fields ({@code id}/{@code seq}/{@code parentId}/{@code timestamp}) are
 * stored flat on every subtype, aligned with pi's {@code Entry} union. The
 * storage assigns {@code seq}/{@code parentId}/{@code timestamp} on commit;
 * a provisioned entry carries placeholder identity until then.</p>
 *
 * <p>Optional pi fields ({@code terminate}/{@code details}/{@code usage}/
 * {@code data}) are omitted from JSON when {@code null}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    /** Unique entry identifier. */
    String id();

    /** Monotonic sequence number shared by entries, records, lanes and facts. */
    long seq();

    /** Parent entry id, or {@code null} for the root. */
    String parentId();

    /** When this entry was created. */
    Instant timestamp();

    /** The type discriminant (matches the JSON {@code type} property). */
    default String type() {
        return switch (this) {
            case Message m -> "message";
            case ModelChange mc -> "model_change";
            case ThinkingLevelChange tlc -> "thinking_level_change";
            case ActiveToolsChange atc -> "active_tools_change";
            case Compaction c -> "compaction";
            case BranchSummary bs -> "branch_summary";
            case Custom c -> "custom";
        };
    }

    /** Rebuild this entry with storage-assigned identity fields. */
    default Entry committed(long seq, String parentId, Instant timestamp) {
        return switch (this) {
            case Message e -> new Message(e.id(), seq, parentId, timestamp, e.message(), e.terminate());
            case ModelChange e -> new ModelChange(e.id(), seq, parentId, timestamp, e.provider(), e.modelId());
            case ThinkingLevelChange e ->
                new ThinkingLevelChange(e.id(), seq, parentId, timestamp, e.thinkingLevel());
            case ActiveToolsChange e ->
                new ActiveToolsChange(e.id(), seq, parentId, timestamp, e.activeToolNames());
            case Compaction e -> new Compaction(e.id(), seq, parentId, timestamp, e.summary(),
                e.retainedTail(), e.tokensBefore(), e.details(), e.usage());
            case BranchSummary e -> new BranchSummary(e.id(), seq, parentId, timestamp, e.fromId(),
                e.summary(), e.details(), e.usage());
            case Custom e -> new Custom(e.id(), seq, parentId, timestamp, e.customType(), e.data());
        };
    }

    // ═══════════════════════════════════════════════════════════
    // Subtypes
    // ═══════════════════════════════════════════════════════════

    /** A user, assistant, or tool message. */
    record Message(
        String id,
        long seq,
        String parentId,
        Instant timestamp,
        com.pijava.ai.message.Message message,
        Boolean terminate
    ) implements Entry {}

    /** The model was changed. */
    record ModelChange(
        String id,
        long seq,
        String parentId,
        Instant timestamp,
        String provider,
        String modelId
    ) implements Entry {}

    /**
     * The thinking level was changed.
     * Level values: "off" | "minimal" | "low" | "medium" | "high" | "xhigh".
     */
    record ThinkingLevelChange(
        String id,
        long seq,
        String parentId,
        Instant timestamp,
        String thinkingLevel
    ) implements Entry {}

    /** The set of active tools was changed. */
    record ActiveToolsChange(
        String id,
        long seq,
        String parentId,
        Instant timestamp,
        List<String> activeToolNames
    ) implements Entry {
        public ActiveToolsChange {
            activeToolNames = List.copyOf(activeToolNames);
        }
    }

    /** A context compaction occurred. */
    record Compaction(
        String id,
        long seq,
        String parentId,
        Instant timestamp,
        String summary,
        List<com.pijava.ai.message.Message> retainedTail,
        int tokensBefore,
        Map<String, Object> details,
        com.pijava.ai.Usage usage
    ) implements Entry {
        public Compaction {
            retainedTail = List.copyOf(retainedTail);
        }
    }

    /** A branch summary was generated. */
    record BranchSummary(
        String id,
        long seq,
        String parentId,
        Instant timestamp,
        String fromId,
        String summary,
        Map<String, Object> details,
        com.pijava.ai.Usage usage
    ) implements Entry {}

    /** A custom extension event. */
    record Custom(
        String id,
        long seq,
        String parentId,
        Instant timestamp,
        String customType,
        Map<String, Object> data
    ) implements Entry {
        public Custom {
            data = data == null ? null : Map.copyOf(data);
        }
    }
}
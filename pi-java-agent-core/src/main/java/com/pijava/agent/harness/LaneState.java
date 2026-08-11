package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * Internal per-lane state for {@link AgentHarness}.
 *
 * <p>Package-private — only AgentHarness creates and mutates this.
 * Phase 2a supports a single lane; multi-lane in Phase 2c.</p>
 *
 * <p>Aligned with pi's {@code LaneState}. Key design: messages are NOT stored
 * directly — they are built from {@link #transcript} entries on each LLM request
 * via {@link #buildMessages()}.</p>
 */
final class LaneState {

    /** Current run phase. */
    RunPhase phase = RunPhase.IDLE;

    /** The full transcript of entries (messages are built from these). */
    final List<Entry> transcript = new ArrayList<>();

    /** Current run identifier. */
    String runId;

    /** Monotonic step counter within the current run. */
    int stepIndex;

    /** Current assistant message partial snapshot (from last event). */
    AssistantMessage partial;

    /** Summary of the newest own entry (for stopReason checks). */
    NewestOwn newestOwn;

    /** Entries provisioned but not yet persisted. */
    final List<ProvisionedEntry> pendingWrites = new ArrayList<>();

    /** Internal operation records for debugging and audit (Phase 2a). */
    final List<LaneRecord> records = new ArrayList<>();

    // ── Helpers ──────────────────────────────────────────────

    /** Derive the next sequence number. */
    long nextSeq() {
        return transcript.size();
    }

    /** The most recent entry, or null. */
    Entry lastEntry() {
        return transcript.isEmpty() ? null : transcript.get(transcript.size() - 1);
    }

    /**
     * Build the LLM message list from transcript entries.
     * Extracts user/assistant messages, prepends system prompt if present.
     */
    List<Message> buildMessages(String systemPrompt) {
        var messages = new ArrayList<Message>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(new Message.SystemMessage(
                    List.of(new ContentBlock.TextContent(systemPrompt))));
        }
        for (var entry : transcript) {
            if (entry instanceof Entry.Message msg) {
                messages.add(toMessage(msg));
            }
        }
        return messages;
    }

    private static Message toMessage(Entry.Message entry) {
        return switch (entry.role()) {
            case "user" -> new Message.UserMessage(entry.blocks());
            case "assistant" -> new Message.AssistantMessage(entry.blocks());
            case "tool" -> new Message.UserMessage(entry.blocks()); // tool results as user content
            default -> new Message.UserMessage(entry.blocks());
        };
    }

    // ═══════════════════════════════════════════════════════════
    // NewestOwn — summary of the latest own entry
    // ═══════════════════════════════════════════════════════════

    /**
     * Summary of the newest entry produced by the agent itself
     * (not by the user or external systems).
     *
     * <p>Aligned with pi {@code LaneState.newestOwn}.
     * Used in checkpoint phase to determine the outcome of
     * {@code TryFinishRun}.</p>
     */
    record NewestOwn(
        String entryId,
        String entryType,    // "message" | "thinking_level_change" | ...
        String role,         // "user" | "assistant" | "tool" (only for message type)
        String stopReason    // "stop" | "tool_use" | "error" | "length" | null
    ) {}
}

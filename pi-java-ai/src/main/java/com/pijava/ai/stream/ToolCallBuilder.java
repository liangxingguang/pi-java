package com.pijava.ai.stream;

import com.pijava.ai.message.AssistantMessage;

/**
 * Accumulates streaming JSON deltas for a single tool call into a complete
 * set of arguments.
 *
 * <p>Protocol-agnostic: shared by OpenAI and Mistral adapters. Each adapter
 * creates and manages builder instances keyed by {@code tool_call_id}.</p>
 *
 * <p>Phase 2a: updated to work with the new 13-event protocol (contentIndex
 * + partial snapshots). Use {@link StreamPartialBuilder} for full protocol
 * compliance; this builder is a lower-level accumulator for adapters that
 * need finer control over tool-call event emission.</p>
 */
public final class ToolCallBuilder {

    private String id = "";
    private String name = "";
    private ToolCallArgumentsStream arguments = new ToolCallArgumentsStream();
    private boolean started;

    /** Record the start of a tool call. */
    public void start(String toolCallId, String toolName) {
        this.id = toolCallId;
        this.name = toolName;
        this.arguments = new ToolCallArgumentsStream();
        this.started = true;
    }

    /** Append a JSON fragment to the argument accumulator. */
    public void append(String jsonDelta) {
        arguments.append(jsonDelta);
    }

    /** Returns {@code true} once {@link #start(String, String)} has been called. */
    public boolean isStarted() {
        return started;
    }

    /** Returns {@code true} if the accumulated JSON is parseable. */
    public boolean isComplete() {
        return started && !arguments.text().isEmpty();
    }

    /** The tool call ID assigned at start. */
    public String id() {
        return id;
    }

    /** The tool name assigned at start. */
    public String name() {
        return name;
    }

    /** The accumulated arguments as a JSON string. */
    public String argumentsJson() {
        return arguments.text();
    }

    /**
     * Build the terminal {@link StreamEvent.ToolCallEnd} event.
     * Phase 2a: requires contentIndex and partial snapshot.
     */
    public StreamEvent.ToolCallEnd toEnd(int contentIndex, AssistantMessage partial) {
        var finish = arguments.finish();
        return new StreamEvent.ToolCallEnd(contentIndex, id, name, finish.arguments(),
            null, finish.argumentsError(), partial);
    }
}

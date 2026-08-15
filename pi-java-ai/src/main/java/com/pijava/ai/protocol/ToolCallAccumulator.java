package com.pijava.ai.protocol;

import java.util.function.Consumer;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * Accumulates one streaming tool call across arbitrarily split chunks.
 *
 * <p>OpenAI-compatible endpoints (DeepSeek in particular) do not guarantee
 * that {@code id}, {@code function.name} and {@code function.arguments}
 * arrive in the same chunk — the first chunk may carry only the id, the next
 * only name + arguments. Accumulating by slot and emitting
 * {@code ToolCallStart} on the FIRST tool-call chunk (whatever it contains)
 * ensures the call is never dropped. Before this, a split arrival left the
 * tool call unstarted: the run then "completed" with only the preamble text
 * and the tool was never executed.</p>
 *
 * <p>Matches {@link StreamPartialBuilder}'s single-tool-call model; parallel
 * tool calls would need multi-slot support in the builder.</p>
 */
final class ToolCallAccumulator {

    private boolean started;
    private String id = "";
    private String name = "";

    /** Apply one chunk's optional fields and forward any emitted events. */
    void update(String chunkId, String chunkName, String chunkArguments,
                Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        if (chunkId != null) {
            id = chunkId;
        }
        if (chunkName != null) {
            name = chunkName;
        }
        if (!started) {
            started = true;
            emit.accept(builder.emitToolCallStart());
        }
        if (chunkArguments != null) {
            emit.accept(builder.emitToolCallDelta(id, chunkArguments));
        }
    }

    /** Emit {@code ToolCallEnd} if any tool-call chunk was seen. */
    void finish(Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        if (started) {
            emit.accept(builder.emitToolCallEnd(id, name));
        }
    }

    /** Whether any tool-call chunk was seen (drives the stop reason). */
    boolean started() {
        return started;
    }
}

package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.stream.ToolCallSlotKey;

/** Completions-style tool calls accumulated independently by provider index. */
final class ToolCallAccumulator {

    private final Map<ToolCallSlotKey, Slot> slots = new LinkedHashMap<>();
    private boolean seen;

    /** Backwards-compatible single-slot entry point. */
    void update(String chunkId, String chunkName, String chunkArguments,
                Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        update(0, chunkId, chunkName, chunkArguments, emit, builder);
    }

    /** Apply one indexed chunk's optional function fields. */
    void update(long index, String chunkId, String chunkName, String chunkArguments,
                Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        var key = ToolCallSlotKey.indexed(index);
        seen = true;
        Slot slot = slots.get(key);
        if (slot == null) {
            slot = new Slot();
            slots.put(key, slot);
            slot.start(key, chunkId, chunkName, emit, builder);
        }
        slot.identify(chunkId, chunkName);
        if (chunkArguments != null) {
            emit.accept(builder.emitToolCallDelta(key, slot.id, chunkArguments));
        }
    }

    /** Backwards-compatible single-slot custom-tool entry point. */
    void updateCustom(String chunkId, CompletionsCustomChunks.CustomChunk custom,
                       Map<String, String> grammarProperties,
                       Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        updateCustom(0, chunkId, custom, grammarProperties, emit, builder);
    }

    /** Apply one indexed custom-tool chunk with an independent grammar buffer. */
    void updateCustom(long index, String chunkId, CompletionsCustomChunks.CustomChunk custom,
                       Map<String, String> grammarProperties,
                       Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        var key = ToolCallSlotKey.indexed(index);
        seen = true;
        Slot slot = slots.get(key);
        if (slot == null) {
            slot = new Slot();
            var property = grammarProperties.get(custom.name() == null ? "" : custom.name());
            slot.customBuffer = new GrammarInputBuffer(property != null ? property : "input");
            slots.put(key, slot);
            slot.start(key, chunkId, custom.name(), emit, builder);
        }
        slot.identify(chunkId, custom.name());
        if (custom.input() != null) {
            var fragment = slot.customBuffer.appendDelta(custom.input());
            // Completions preserves an empty delta frame for an empty custom input delta.
            emit.accept(builder.emitToolCallDelta(key, slot.id,
                fragment == null ? "" : fragment));
        }
    }

    /** Close custom grammar and finish every open call in provider start order. */
    void finish(Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        for (var entry : new ArrayList<>(slots.entrySet())) {
            var key = entry.getKey();
            Slot slot = entry.getValue();
            if (slot.customBuffer != null && !slot.customBuffer.closed()) {
                var fragment = slot.customBuffer.close(slot.customBuffer.input());
                if (fragment != null) {
                    emit.accept(builder.emitToolCallDelta(key, slot.id, fragment));
                }
            }
            emit.accept(builder.emitToolCallEnd(key, slot.id, slot.name));
            slots.remove(key);
        }
    }

    /** Whether any tool-call chunk was seen, including calls already finished. */
    boolean started() {
        return seen;
    }

    private static final class Slot {
        private String id = "";
        private String name = "";
        private GrammarInputBuffer customBuffer;

        private void start(ToolCallSlotKey key, String chunkId, String chunkName,
                           Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
            identify(chunkId, chunkName);
            emit.accept(builder.emitToolCallStart(key, id, name, null));
        }

        private void identify(String chunkId, String chunkName) {
            if (chunkId != null) {
                id = chunkId;
            }
            if (chunkName != null) {
                name = chunkName;
            }
        }
    }
}

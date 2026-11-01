package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.message.ContentBlock;

/** Provider-indexed tool-call state owned by {@link StreamPartialBuilder}. */
final class ToolCallBlockAccumulator {

    private final StreamPartialBuilder owner;
    private final Map<ToolCallSlotKey, Slot> slots = new LinkedHashMap<>();

    ToolCallBlockAccumulator(StreamPartialBuilder owner) {
        this.owner = owner;
    }

    StreamEvent.ToolCallStart emitToolCallStart(
            ToolCallSlotKey key, String id, String name, String namespace) {
        return emitToolCallStart(key, id, name, namespace, Map.of());
    }

    /** Start with a provider-supplied preview without feeding it to the JSON delta stream. */
    StreamEvent.ToolCallStart emitToolCallStart(
            ToolCallSlotKey key, String id, String name, String namespace,
            Map<String, Object> initialArguments) {
        slots.remove(key);
        Slot slot = new Slot(normalize(id), normalize(name), namespace,
            owner.blocks.size());
        slots.put(key, slot);
        owner.blocks.add(new ContentBlock.ToolUseContent(slot.id, slot.name,
            initialArguments, null, slot.namespace));
        int index = owner.nextContentIndex++;
        return new StreamEvent.ToolCallStart(index, owner.snapshot());
    }

    /** Start a function call whose initial raw arguments must be visible in the start snapshot. */
    StreamEvent.ToolCallStart emitSeededToolCallStart(
            ToolCallSlotKey key, String id, String name, String namespace, String initial) {
        slots.remove(key);
        Slot slot = new Slot(normalize(id), normalize(name), namespace, owner.blocks.size());
        slots.put(key, slot);
        var arguments = initial.isEmpty() ? Map.<String, Object>of()
            : slot.arguments.replace(initial).arguments();
        owner.blocks.add(new ContentBlock.ToolUseContent(slot.id, slot.name,
            arguments, null, slot.namespace));
        int index = owner.nextContentIndex++;
        return new StreamEvent.ToolCallStart(index, owner.snapshot());
    }

    /** Seed Responses initial arguments without publishing a synthetic provider delta. */
    void seedToolArguments(ToolCallSlotKey key, String initial) {
        Slot slot = requireSlot(key);
        var replacement = slot.arguments.replace(initial);
        updateBlock(slot, replacement.arguments(), null);
    }

    /** Apply authoritative complete text and publish only a non-empty prefix-extension tail. */
    Optional<StreamEvent.ToolCallDelta> replaceToolArguments(
            ToolCallSlotKey key, String completeText) {
        Slot slot = requireSlot(key);
        var replacement = slot.arguments.replace(completeText);
        updateBlock(slot, replacement.arguments(), null);
        if (replacement.delta() == null || replacement.delta().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new StreamEvent.ToolCallDelta(slot.blockIndex, slot.id,
            replacement.delta(), owner.snapshot()));
    }

    StreamEvent.ToolCallDelta emitToolCallDelta(
            ToolCallSlotKey key, String id, String jsonDelta) {
        Slot slot = slots.computeIfAbsent(key, ignored -> createImplicitSlot(id));
        slot.id = normalize(id);
        var arguments = slot.arguments.append(jsonDelta);
        updateBlock(slot, arguments, null);
        return new StreamEvent.ToolCallDelta(slot.blockIndex, slot.id, jsonDelta,
            owner.snapshot());
    }

    StreamEvent.ToolCallEnd emitToolCallEnd(
            ToolCallSlotKey key, String id, String name,
            String thoughtSignature, String namespace) {
        Slot slot = requireSlot(key);
        slot.id = normalize(id);
        slot.name = normalize(name);
        if (namespace != null) {
            slot.namespace = namespace;
        }
        var finish = slot.arguments.finish();
        updateBlock(slot, finish.arguments(), finish.argumentsError());
        owner.blocks.set(slot.blockIndex, new ContentBlock.ToolUseContent(
            slot.id, slot.name, finish.arguments(), thoughtSignature, slot.namespace,
            finish.argumentsError()));
        slots.remove(key);
        return new StreamEvent.ToolCallEnd(slot.blockIndex, slot.id, slot.name,
            finish.arguments(), slot.namespace, finish.argumentsError(), owner.snapshot());
    }

    /** Project a terminal pi-messages toolCall verbatim instead of reparsing accumulated raw text. */
    StreamEvent.ToolCallEnd emitAuthoritativeToolCallEnd(ToolCallSlotKey key, String id, String name,
            Map<String, Object> arguments, String argumentsError,
            String thoughtSignature, String namespace) {
        Slot slot = requireSlot(key);
        slots.remove(key);
        owner.blocks.set(slot.blockIndex, new ContentBlock.ToolUseContent(
            id, name, arguments, thoughtSignature, namespace, argumentsError));
        return new StreamEvent.ToolCallEnd(slot.blockIndex, id, name, arguments,
            namespace, argumentsError, owner.snapshot());
    }

    /** Finish calls still open at end-of-stream in their original start order. */
    List<StreamEvent.ToolCallEnd> emitOpenToolCallEnds() {
        var open = new ArrayList<>(slots.entrySet());
        List<StreamEvent.ToolCallEnd> ends = new ArrayList<>();
        for (var entry : open) {
            Slot slot = entry.getValue();
            ends.add(emitToolCallEnd(entry.getKey(), slot.id, slot.name, null, null));
        }
        return List.copyOf(ends);
    }

    private Slot createImplicitSlot(String id) {
        Slot slot = new Slot(normalize(id), "", null, owner.blocks.size());
        owner.blocks.add(new ContentBlock.ToolUseContent(slot.id, "", Map.of()));
        owner.nextContentIndex++;
        return slot;
    }

    private Slot requireSlot(ToolCallSlotKey key) {
        Slot slot = slots.get(key);
        if (slot == null) {
            throw new IllegalStateException("tool call slot has not been started: " + key);
        }
        return slot;
    }

    private void updateBlock(Slot slot, Map<String, Object> arguments, String argumentsError) {
        owner.blocks.set(slot.blockIndex, new ContentBlock.ToolUseContent(
            slot.id, slot.name, arguments, null, slot.namespace, argumentsError));
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    /** All mutable state that must remain isolated between provider calls. */
    private static final class Slot {
        private final ToolCallArgumentsStream arguments = new ToolCallArgumentsStream();
        private final int blockIndex;
        private String id;
        private String name;
        private String namespace;

        private Slot(String id, String name, String namespace, int blockIndex) {
            this.id = id;
            this.name = name;
            this.namespace = namespace;
            this.blockIndex = blockIndex;
        }
    }
}

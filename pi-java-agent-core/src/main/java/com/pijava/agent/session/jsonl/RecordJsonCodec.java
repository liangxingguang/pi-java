package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.record.ReplayKind;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;

/**
 * Decodes {@link LaneRecord} subtypes from JSON nodes. Identity fields may be
 * read from the node or supplied by the caller (SQLite payload decode).
 */
final class RecordJsonCodec {

    private RecordJsonCodec() {}

    static LaneRecord decode(JsonNode node, String id, long seq, String lane, Instant timestamp) {
        String type = JsonlCodec.requireString(node, "type");
        return switch (type) {
            case "operation_started" -> decodeOperationStarted(node, id, seq, lane, timestamp);
            case "abort_requested" -> new LaneRecord.AbortRequested(id, seq, lane, timestamp,
                JsonlCodec.requireString(node, "runId"));
            case "operation_finished" -> new LaneRecord.OperationFinished(id, seq, lane, timestamp,
                JsonlCodec.requireString(node, "runId"),
                enumOf(OperationOutcome.class, node, "outcome"),
                decodeError(node.get("error")));
            case "step_attempt" -> new LaneRecord.StepAttempt(id, seq, lane, timestamp,
                JsonlCodec.requireString(node, "runId"),
                enumOf(StepKind.class, node, "step"),
                JsonlCodec.requireInt(node, "attempt"),
                JsonlCodec.requireString(node, "resultEntryId"),
                JsonlCodec.optionalString(node, "compactionReason"));
            case "tool_started" -> new LaneRecord.ToolStarted(id, seq, lane, timestamp,
                JsonlCodec.requireString(node, "runId"),
                JsonlCodec.requireString(node, "assistantEntryId"),
                JsonlCodec.requireInt(node, "toolIndex"),
                JsonlCodec.requireString(node, "toolCallId"),
                JsonlCodec.requireString(node, "toolName"),
                objectMap(node, "effectiveArgs"),
                JsonlCodec.requireString(node, "resultEntryId"),
                enumOf(ReplayKind.class, node, "replay"));
            case "queue_enqueued" -> new LaneRecord.QueueEnqueued(id, seq, lane, timestamp,
                enumOf(QueueKind.class, node, "queue"),
                JsonlCodec.optionalString(node, "runId"),
                decodeTarget(node.get("target")));
            case "queue_cancelled" -> new LaneRecord.QueueCancelled(id, seq, lane, timestamp,
                JsonlCodec.optionalString(node, "runId"),
                JsonlCodec.requireString(node, "entryId"));
            case "write_deferred" -> new LaneRecord.WriteDeferred(id, seq, lane, timestamp,
                JsonlCodec.requireString(node, "runId"),
                decodeTarget(node.get("target")));
            case "usage" -> new LaneRecord.UsageRecord(id, seq, lane, timestamp,
                EntryJsonCodec.decodeUsage(node.get("usage")),
                enumOf(UsageCause.class, node, "cause"),
                JsonlCodec.optionalString(node, "runId"),
                JsonlCodec.optionalString(node, "entryId"),
                JsonlCodec.optionalString(node, "toolCallId"),
                node.has("attempt") && node.get("attempt").isIntegralNumber()
                    ? node.get("attempt").intValue() : null,
                JsonlCodec.optionalString(node, "stopReason"));
            default -> throw JsonlCodec.DecodeError.schema("has unknown record type " + type);
        };
    }

    private static LaneRecord decodeOperationStarted(JsonNode node, String id, long seq,
                                                     String lane, Instant timestamp) {
        JsonNode intent = node.get("intent");
        String kind = JsonlCodec.requireString(intent, "kind");
        LaneRecord.OperationStarted.Intent payload = switch (kind) {
            case "run" -> new LaneRecord.OperationStarted.Run(
                MessageJsonCodec.decodeList(intent.get("originalPrompt")),
                decodeTargetList(intent.get("initialMessages")),
                JsonlCodec.optionalString(intent, "systemPromptOverride"),
                JsonlCodec.optionalObject(intent, "resumeData"));
            case "compaction" -> new LaneRecord.OperationStarted.Compaction(
                JsonlCodec.optionalString(intent, "customInstructions"),
                JsonlCodec.requireString(intent, "resultEntryId"));
            case "navigation" -> new LaneRecord.OperationStarted.Navigation(
                JsonlCodec.nullableString(intent, "targetId"),
                intent.has("summarize") && intent.get("summarize").asBoolean(false),
                JsonlCodec.optionalString(intent, "customInstructions"),
                JsonlCodec.optionalString(intent, "label"),
                JsonlCodec.optionalString(intent, "summaryEntryId"));
            default -> throw JsonlCodec.DecodeError.schema("has unknown operation kind " + kind);
        };
        return new LaneRecord.OperationStarted(id, seq, lane, timestamp,
            JsonlCodec.nullableString(node, "sourceLeafId"), payload);
    }

    private static LaneRecord.OperationFinished.OperationError decodeError(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid error");
        }
        return new LaneRecord.OperationFinished.OperationError(
            JsonlCodec.requireString(node, "code"),
            JsonlCodec.requireString(node, "message"));
    }

    private static ProvisionedEntry<?> decodeTarget(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid target");
        }
        // Targets are provisioned entries: no seq/parentId/timestamp yet.
        String id = JsonlCodec.requireString(node, "id");
        String type = JsonlCodec.requireString(node, "type");
        Entry entry = EntryJsonCodec.decode(node, id, 0, null, Instant.EPOCH, type);
        return new ProvisionedEntry<>(entry);
    }

    private static java.util.List<ProvisionedEntry<?>> decodeTargetList(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw JsonlCodec.DecodeError.schema("has invalid initialMessages");
        }
        var list = new java.util.ArrayList<ProvisionedEntry<?>>(node.size());
        for (var item : node) {
            list.add(decodeTarget(item));
        }
        return list;
    }

    private static Map<String, Object> objectMap(JsonNode node, String field) {
        Map<String, Object> map = JsonlCodec.optionalObject(node, field);
        return map == null ? Map.of() : map;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonNode node, String field) {
        String value = JsonlCodec.requireString(node, field);
        for (var constant : type.getEnumConstants()) {
            try {
                var valueMethod = type.getMethod("value");
                if (value.equals(valueMethod.invoke(constant))) {
                    return constant;
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Enum " + type.getSimpleName() + " lacks value()", e);
            }
        }
        throw JsonlCodec.DecodeError.schema("has unknown " + field + " " + value);
    }
}

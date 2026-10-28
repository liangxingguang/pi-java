package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.session.SessionMutation;
import com.pijava.agent.session.jsonl.JsonlCodec.DecodeError;

/**
 * {@link JsonlCodec} 的 **legacy mutation 解析臂簇**抽离（结构债拆分：行为零变化纯移动，
 * javadoc/注释逐字随代码搬）—— 本仓旧行（{@code kind:"entry"/"record"/"lane"/"fact"}）
 * 的四个分派臂，加上两臂共享的 pi 形 entry 读取（{@link #parsePiEntry}，
 * 供 {@link PiV3Wire#parseFlatLine} 的分派复用）与旧 usage 行转换（{@link #legacyUsageEntry}）。
 *
 * <p>共享校验助手（{@code requireString}/{@code optionalString}/…）留在
 * {@link JsonlCodec}，这里经 {@code JsonlCodec.} 限定调用。</p>
 */
final class LegacyMutationParser {

    private static final List<String> ENTRY_TYPES = List.of(
        Entry.TYPE_MESSAGE, Entry.TYPE_MODEL_CHANGE, Entry.TYPE_THINKING_LEVEL_CHANGE,
        // 仅 legacy 接收：无对应 Entry 变体（active_tools_change 已删，docs/15 实施期记）。
        "active_tools_change",
        Entry.TYPE_COMPACTION, Entry.TYPE_BRANCH_SUMMARY, Entry.TYPE_CUSTOM,
        Entry.TYPE_CUSTOM_MESSAGE, Entry.TYPE_USAGE, Entry.TYPE_CONTEXT_EDIT);

    private static final List<String> RECORD_TYPES = List.of(
        "operation_started", "abort_requested", "operation_finished", "step_attempt",
        "tool_started", "tool_finished", "queue_enqueued", "queue_cancelled",
        "queue_consumed", "write_deferred");

    private static final List<String> OPERATION_KINDS = List.of("run", "compaction", "navigation");

    private LegacyMutationParser() {}

    static SessionMutation parseEntryMutation(JsonNode node, long seq) {
        return parseEntryMutation(node, seq, JsonlCodec.instant(node, "timestamp"));
    }

    /** 读一行 **pi 形状**的 entry（判别键 {@code type}）。供 {@link PiV3Wire#parseFlatLine} 的分派复用。 */
    static SessionMutation parsePiEntry(JsonNode node, long seq) {
        return parseEntryMutation(node, seq, PiV3Wire.parseTimestamp(node, "timestamp"));
    }

    /**
     * 读一条 entry。{@code timestamp} 由调用方给 —— 两种线形状的**时间戳型不同**：
     * 旧行是 epoch 毫秒、pi 的行是 ISO 串（{@code PiV3Wire.parseTimestamp}）。
     */
    private static SessionMutation parseEntryMutation(JsonNode node, long seq, Instant timestamp) {
        String lane = node.has("lane") ? JsonlCodec.requireString(node, "lane") : null;
        String id = JsonlCodec.requireString(node, "id");
        String type = JsonlCodec.requireString(node, "type");
        if (!ENTRY_TYPES.contains(type)) {
            throw DecodeError.schema("has unknown entry type " + type);
        }
        if ("custom".equals(type) || "custom_message".equals(type)) {
            JsonlCodec.requireString(node, "customType");
        }
        String parentId = JsonlCodec.nullableString(node, "parentId");
        Entry entry = EntryJsonCodec.decode(node, id, seq, parentId, timestamp);
        return lane == null
            ? new SessionMutation.Entry(null, entry)
            : new SessionMutation.Entry(lane, entry);
    }

    /**
     * 读**旧的** usage 记录行 ⇒ 转成一等 entry（D6 之后记录族里不再有这一型）。
     *
     * <p>两个来源共用它：{@code kind:"record"} 的旧行，以及已迁移文件里的
     * {@code custom} ＋ {@code pi-java.record.usage} 行。</p>
     *
     * <p>{@code parentId} 取调用方给的那个（旧行没有就是 {@code null}）。</p>
     */
    static Entry.Usage legacyUsageEntry(JsonNode node, String id, String parentId,
                                        Instant timestamp) {
        return new Entry.Usage(id, 0, parentId, timestamp,
            JsonlCodec.optionalString(node, "cause"),
            JsonlCodec.optionalString(node, "provider"),
            JsonlCodec.optionalString(node, "model"),
            EntryJsonCodec.decodeUsage(node.get("usage")),
            JsonlCodec.optionalString(node, "note"),
            JsonlCodec.optionalString(node, "runId"),
            JsonlCodec.optionalString(node, "entryId"),
            JsonlCodec.optionalString(node, "toolCallId"),
            JsonlCodec.optionalInteger(node, "attempt"),
            JsonlCodec.readStopReason(node));
    }

    static SessionMutation parseRecordMutation(JsonNode node, long seq) {
        String id = JsonlCodec.requireString(node, "id");
        String lane = JsonlCodec.requireString(node, "lane");
        String type = JsonlCodec.requireString(node, "type");
        if ("usage".equals(type)) {
            // D6：usage 已提为一等 entry，旧文件里的这一型在**装载时**转换。
            // 车道传 null：记录族本来不参与车道的叶链（记录不推进叶），硬按 entry 的
            // 「does not chain to the lane leaf」校验会把整份旧文件读崩。
            JsonlCodec.requireString(node, "cause");
            return new SessionMutation.Entry(null, legacyUsageEntry(node, id,
                JsonlCodec.nullableString(node, "parentId"), JsonlCodec.instant(node, "timestamp")));
        }
        if (!RECORD_TYPES.contains(type)) {
            throw DecodeError.schema("has unknown record type " + type);
        }
        if ("operation_started".equals(type)) {
            JsonNode intent = node.get("intent");
            if (intent == null || !intent.isObject()) {
                throw DecodeError.schema("has invalid intent");
            }
            String operationKind = JsonlCodec.requireString(intent, "kind");
            if (!OPERATION_KINDS.contains(operationKind)) {
                throw DecodeError.schema("has unknown operation kind " + operationKind);
            }
        }
        if ("operation_finished".equals(type)) {
            JsonlCodec.requireString(node, "runId");
        }
        Instant timestamp = JsonlCodec.instant(node, "timestamp");
        LaneRecord record = RecordJsonCodec.decode(node, id, seq, lane, timestamp);
        // 旧行没有 parentId（docs/12 §6 D2 才补的）。
        return new SessionMutation.Record(null, record);
    }

    /**
     * 读**旧形状**的 lane 行（{@code kind:"lane"}）。
     *
     * <p>⚠️ 旧行里**没有** {@code parentId}／{@code timestamp} —— 那两个字段是本包为 pi 的线形状
     * 才补的（{@code docs/12 §6 D2}）。历史文件里没有就是没有：父级给 {@code null}、
     * 时间戳给 {@link Instant#EPOCH}，由惰性迁移重写成 pi 形状。</p>
     */
    static SessionMutation parseLaneMutation(JsonNode node, long seq) {
        return new SessionMutation.Lane(seq, null, Instant.EPOCH,
            JsonlCodec.requireString(node, "lane"), JsonlCodec.nullableString(node, "leafId"));
    }

    /** 读**旧形状**的 fact 行（{@code kind:"fact"}）。身份字段的缺省处理同 {@link #parseLaneMutation}。 */
    static SessionMutation parseFactMutation(JsonNode node, long seq) {
        String fact = JsonlCodec.requireString(node, "fact");
        return switch (fact) {
            case "name" -> new SessionMutation.FactName(seq, null, Instant.EPOCH,
                JsonlCodec.optionalString(node, "name"));
            case "label" -> new SessionMutation.FactLabel(seq, null, Instant.EPOCH,
                JsonlCodec.requireString(node, "targetId"), JsonlCodec.optionalString(node, "label"));
            default -> throw DecodeError.schema("has unknown fact type");
        };
    }
}

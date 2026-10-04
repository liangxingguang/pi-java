package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.session.SessionJson;
import com.pijava.agent.session.SessionMutation;

/**
 * JSONL v4 codec: strict validation with Result-style errors
 * ({@code syntax} = JSON syntax error, {@code schema} = field/enum error).
 * Aligned with pi {@code codec.ts}.
 */
public final class JsonlCodec {

    private static final List<String> ENTRY_TYPES = List.of(
        "message", "model_change", "thinking_level_change", "active_tools_change",
        "compaction", "branch_summary", "custom", "custom_message");

    private static final List<String> RECORD_TYPES = List.of(
        "operation_started", "abort_requested", "operation_finished", "step_attempt",
        "tool_started", "tool_finished", "queue_enqueued", "queue_cancelled",
        "queue_consumed", "write_deferred", "usage");

    private static final List<String> OPERATION_KINDS = List.of("run", "compaction", "navigation");

    private JsonlCodec() {}

    /**
     * A decode error. {@code kind} is {@code "syntax"} or {@code "schema"}.
     * Thrown during strict decoding and captured in {@link ParseResult}.
     */
    public static final class DecodeError extends RuntimeException {
        private final String kind;

        private DecodeError(String kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        /** {@code "syntax"} or {@code "schema"}. */
        public String kind() {
            return kind;
        }

        /** Create a syntax-type decode error. */
        public static DecodeError syntax(String message, Throwable cause) {
            return new DecodeError("syntax", message, cause);
        }

        /** Create a schema-type decode error. */
        public static DecodeError schema(String message) {
            return new DecodeError("schema", message, null);
        }
    }

    /** A parse result: exactly one of {@code value} / {@code error} is non-null. */
    public record ParseResult<T>(T value, DecodeError error) {
        /** Whether the parse succeeded ({@code value} is non-null). */
        public boolean ok() {
            return value != null;
        }

        /** Create a successful parse result. */
        public static <T> ParseResult<T> ok(T value) {
            return new ParseResult<>(value, null);
        }

        /** Create a failed parse result carrying the error. */
        public static <T> ParseResult<T> err(DecodeError error) {
            return new ParseResult<>(null, error);
        }
    }

    // ── Header ──────────────────────────────────────────────

    /** 本仓旧头的判别值（{@code kind:"header"}）—— 需迁移到 pi v3 线（{@code docs/12}）。 */
    public static final String LEGACY_HEADER_KIND = "header";

    /** Encode a header line (with trailing newline) in **pi's v3 wire shape**. */
    public static String encodeHeader(JsonlV4Header header) {
        return PiV3Wire.encodeHeader(header);
    }

    /**
     * Parse a header line —— **两种判别键**：
     * <ul>
     *   <li>{@code type:"session"} ⇒ pi v3 线（当前格式），转 {@link PiV3Wire#parseHeader}；</li>
     *   <li>{@code kind:"header"} ⇒ 本仓旧头，由 {@link #parseLegacyHeader} 读。</li>
     * </ul>
     *
     * <p>⚠️ <b>不能用 {@code version} 区分</b>：本仓旧文件也写 {@code version:3}
     * （见 {@code JsonlSessionMetadata} 的格式判别），数字同、所指不同 ⇒ 只能靠键名。
     * 这也正是 {@link PiV3Wire#SESSION_KIND} 被复用为内部判别键的原因。</p>
     */
    public static ParseResult<JsonlV4Header> parseHeader(String line) {
        try {
            var node = parseObject(line);
            return node.has("type") ? PiV3Wire.parseHeader(node) : parseLegacyHeader(node);
        } catch (DecodeError e) {
            return ParseResult.err(e);
        } catch (Exception e) {
            return ParseResult.err(DecodeError.syntax("is not valid JSON", e));
        }
    }

    /** 读本仓旧头（{@code kind:"header"}，{@code version} 3 或 4）。 */
    private static ParseResult<JsonlV4Header> parseLegacyHeader(JsonNode node) {
        if (!LEGACY_HEADER_KIND.equals(stringValue(node, "kind"))) {
            return ParseResult.err(DecodeError.schema("is not a header"));
        }
        int version = requireInt(node, "version");
        if (version != 3 && version != 4) {
            return ParseResult.err(DecodeError.schema("has unsupported session version"));
        }
        String parentSessionId = optionalString(node, "parentSessionId");
        String legacyParentSessionPath = optionalString(node, "legacyParentSessionPath");
        if (parentSessionId != null && legacyParentSessionPath != null) {
            return ParseResult.err(DecodeError.schema(
                "has both parentSessionId and legacyParentSessionPath"));
        }
        Map<String, Object> metadata = optionalObject(node, "metadata");
        var header = new JsonlV4Header(
            LEGACY_HEADER_KIND, version, requireString(node, "id"),
            requireLong(node, "createdAt"), requireString(node, "cwd"),
            parentSessionId, legacyParentSessionPath, metadata);
        return ParseResult.ok(header);
    }

    // ── Mutations ───────────────────────────────────────────

    /** Encode a mutation as a JSONL line (with trailing newline). */
    public static String encodeMutation(SessionMutation mutation) {
        var mapper = SessionJson.mapper();
        var node = mapper.createObjectNode();
        switch (mutation) {
            case SessionMutation.Entry m -> PiV3Wire.encodeEntryLine(node, m);
            case SessionMutation.Record m -> PiV3Wire.encodeRecordLine(node, m);
            case SessionMutation.Lane m -> PiV3Wire.encodeLaneLine(node, m);
            case SessionMutation.FactName m -> PiV3Wire.encodeSessionInfoLine(node, m);
            case SessionMutation.FactLabel m -> PiV3Wire.encodeLabelLine(node, m);
        }
        try {
            return mapper.writeValueAsString(node) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode mutation", e);
        }
    }

    /**
     * Parse a legacy mutation line（{@code kind} 判别）。pi 形状的行需要
     * {@link #parseMutation(String, long)} —— 它有 {@code seq} 以外的身份来源（行号）。
     */
    public static ParseResult<SessionMutation> parseMutation(String line) {
        return parseMutation(line, -1);
    }

    /**
     * Parse a mutation line —— **两种形状**（{@code docs/12}）：
     * <ul>
     *   <li>**无 {@code kind}** ⇒ pi 的行形状（判别键 {@code type}）。pi 的 {@code SessionEntryBase}
     *       没有 {@code seq}（{@code session-manager.ts:57-63}）⇒ 由调用方给**行号**
     *       （{@code assignedSeq}）；本仓的 {@code SessionState} 拿 seq 做「严格连续」校验，
     *       而文件里行的次序本身就是那个校验。</li>
     *   <li>**有 {@code kind}** ⇒ 本仓旧行（entry/record/lane/fact），{@code seq} 从行内读。</li>
     * </ul>
     */
    public static ParseResult<SessionMutation> parseMutation(String line, long assignedSeq) {
        try {
            var node = parseObject(line);
            if (!node.has("kind")) {
                if (assignedSeq < 1) {
                    throw DecodeError.schema("pi-shaped line needs an assigned seq");
                }
                return ParseResult.ok(PiV3Wire.parseFlatLine(node, assignedSeq));
            }
            long seq = requireLong(node, "seq");
            if (seq <= 0) {
                throw DecodeError.schema("has invalid seq");
            }
            String kind = requireString(node, "kind");
            return switch (kind) {
                case "entry" -> ParseResult.ok(parseEntryMutation(node, seq));
                case "record" -> ParseResult.ok(parseRecordMutation(node, seq));
                case "lane" -> ParseResult.ok(parseLaneMutation(node, seq));
                case "fact" -> ParseResult.ok(parseFactMutation(node, seq));
                default -> ParseResult.err(DecodeError.schema("has unknown mutation kind"));
            };
        } catch (DecodeError e) {
            return ParseResult.err(e);
        } catch (Exception e) {
            return ParseResult.err(DecodeError.syntax("is not valid JSON", e));
        }
    }

    private static SessionMutation parseEntryMutation(JsonNode node, long seq) {
        return parseEntryMutation(node, seq, instant(node, "timestamp"));
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
        String lane = node.has("lane") ? requireString(node, "lane") : null;
        String id = requireString(node, "id");
        String type = requireString(node, "type");
        if (!ENTRY_TYPES.contains(type)) {
            throw DecodeError.schema("has unknown entry type " + type);
        }
        if ("custom".equals(type) || "custom_message".equals(type)) {
            requireString(node, "customType");
        }
        String parentId = nullableString(node, "parentId");
        Entry entry = EntryJsonCodec.decode(node, id, seq, parentId, timestamp);
        return lane == null
            ? new SessionMutation.Entry(null, entry)
            : new SessionMutation.Entry(lane, entry);
    }

    private static SessionMutation parseRecordMutation(JsonNode node, long seq) {
        String id = requireString(node, "id");
        String lane = requireString(node, "lane");
        String type = requireString(node, "type");
        if (!RECORD_TYPES.contains(type)) {
            throw DecodeError.schema("has unknown record type " + type);
        }
        if ("operation_started".equals(type)) {
            JsonNode intent = node.get("intent");
            if (intent == null || !intent.isObject()) {
                throw DecodeError.schema("has invalid intent");
            }
            String operationKind = requireString(intent, "kind");
            if (!OPERATION_KINDS.contains(operationKind)) {
                throw DecodeError.schema("has unknown operation kind " + operationKind);
            }
        }
        if ("operation_finished".equals(type)) {
            requireString(node, "runId");
        }
        Instant timestamp = instant(node, "timestamp");
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
    private static SessionMutation parseLaneMutation(JsonNode node, long seq) {
        return new SessionMutation.Lane(seq, null, Instant.EPOCH,
            requireString(node, "lane"), nullableString(node, "leafId"));
    }

    /** 读**旧形状**的 fact 行（{@code kind:"fact"}）。身份字段的缺省处理同 {@link #parseLaneMutation}。 */
    private static SessionMutation parseFactMutation(JsonNode node, long seq) {
        String fact = requireString(node, "fact");
        return switch (fact) {
            case "name" -> new SessionMutation.FactName(seq, null, Instant.EPOCH,
                optionalString(node, "name"));
            case "label" -> new SessionMutation.FactLabel(seq, null, Instant.EPOCH,
                requireString(node, "targetId"), optionalString(node, "label"));
            default -> throw DecodeError.schema("has unknown fact type");
        };
    }

    // ── Shared validation helpers (also used by the SQLite payload codec) ──

    /** Parse a JSON object, mapping syntax errors to {@code DecodeError}. */
    public static JsonNode parseObject(String line) {
        JsonNode node;
        try {
            node = SessionJson.mapper().readTree(line);
        } catch (Exception e) {
            throw DecodeError.syntax("is not valid JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw DecodeError.schema("is not a JSON object");
        }
        return node;
    }

    /** Decode a full entry from its JSON node (identity fields read from node). */
    public static Entry decodeEntry(JsonNode node) {
        long seq = requireLong(node, "seq");
        String id = requireString(node, "id");
        String parentId = nullableString(node, "parentId");
        Instant timestamp = instant(node, "timestamp");
        return EntryJsonCodec.decode(node, id, seq, parentId, timestamp);
    }

    /** Decode an entry from a payload node with identity fields supplied separately. */
    public static Entry decodeEntryPayload(JsonNode payload, String id, long seq,
                                           String parentId, Instant timestamp) {
        return EntryJsonCodec.decode(payload, id, seq, parentId, timestamp);
    }

    /** Decode an entry whose payload excludes {@code type} (SQLite entry rows). */
    public static Entry decodeEntryPayload(JsonNode payload, String id, long seq,
                                           String parentId, Instant timestamp, String type) {
        return EntryJsonCodec.decode(payload, id, seq, parentId, timestamp, type);
    }

    /** Decode a record from a payload node with identity fields supplied separately. */
    public static LaneRecord decodeRecordPayload(JsonNode payload, String id, long seq,
                                                 String lane, Instant timestamp) {
        return RecordJsonCodec.decode(payload, id, seq, lane, timestamp);
    }

    /** Read a required textual field as a {@code String}. */
    public static String requireString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return value.textValue();
    }

    /** Read a required integral field as a {@code long}. */
    public static long requireLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return value.longValue();
    }

    /** Read a required integral field as an {@code int}. */
    public static int requireInt(JsonNode node, String field) {
        return (int) requireLong(node, field);
    }

    /** Read a nullable string field ({@code null} when absent or JSON null). */
    public static String nullableString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return value.textValue();
    }

    /** Read an optional string field, or {@code null} when absent. */
    public static String optionalString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return value.textValue();
    }

    /** 旧转录里的归一化停因写法（B109 之前）—— 只在本迁移垫片里出现。 */
    private static final String LEGACY_TOOL_USE_STOP_REASON = "tool_use";
    /** pi 的归一化停因写法，本仓自 B109 起同字面量。 */
    private static final String TOOL_USE_STOP_REASON = "toolUse";

    /**
     * 读归一化停因，顺带把旧值迁移过来（B109，{@code 原 docs/56 §6 R2/R3}）。
     *
     * <p>2026-09-27 之前落盘的会话文件里归一化停因写作 {@code "tool_use"}，此后与 pi 同词表
     * 写作 {@code "toolUse"}。两条读路径（助手消息、usage 审计记录）都走这里，
     * 别直接 {@link #optionalString}。</p>
     *
     * <p>⚠️ <b>本方法在 pi 侧没有对应物</b> —— pi 从未有过 {@code "tool_use"} 这个归一化取值，
     * 所以它不需要读懂旧文件。这是纯粹的迁移垫片：不归一的话，旧会话的停因会带着外来字面量
     * 漏到对外面（遥测 span 属性、终局帧、RPC 转录、HTML 导出）。
     * 当仓库里不再有该垫片之前的会话文件时，整个方法连常量一起删。</p>
     */
    public static String readStopReason(JsonNode node) {
        var raw = optionalString(node, "stopReason");
        return LEGACY_TOOL_USE_STOP_REASON.equals(raw) ? TOOL_USE_STOP_REASON : raw;
    }

    /** Read an optional boolean field, defaulting to {@code false} when absent. */
    public static boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return value.booleanValue();
    }

    /** Read an optional object field as a map, or {@code null} when absent. */
    public static Map<String, Object> optionalObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return SessionJson.mapper().convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    /**
     * Read an optional field of arbitrary JSON shape (object, array, or scalar)
     * as plain Java values, or {@code null} when absent. Used for pi-shaped
     * pass-through payloads such as a tool result's {@code details}/{@code usage},
     * which are re-encoded verbatim by {@code SessionJson.messageNode}.
     */
    public static Object optionalAny(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return SessionJson.mapper().convertValue(value, Object.class);
    }

    /** Read an optional integral field as a {@code Long}, or {@code null} when absent. */
    public static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return value.longValue();
    }

    /** Read an optional integral field as an {@code Integer}, or {@code null} when absent. */
    public static Integer optionalInteger(JsonNode node, String field) {
        Long value = optionalLong(node, field);
        return value == null ? null : value.intValue();
    }

    /** Read a non-negative epoch-millis field as an {@link Instant}. */
    public static Instant instant(JsonNode node, String field) {
        long ms = requireLong(node, field);
        if (ms < 0) {
            throw DecodeError.schema("has invalid " + field);
        }
        return Instant.ofEpochMilli(ms);
    }

    private static String stringValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}

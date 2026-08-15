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
        "compaction", "branch_summary", "custom");

    private static final List<String> RECORD_TYPES = List.of(
        "operation_started", "abort_requested", "operation_finished", "step_attempt",
        "tool_started", "queue_enqueued", "queue_cancelled", "write_deferred", "usage");

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

        public static DecodeError syntax(String message, Throwable cause) {
            return new DecodeError("syntax", message, cause);
        }

        public static DecodeError schema(String message) {
            return new DecodeError("schema", message, null);
        }
    }

    /** A parse result: exactly one of {@code value} / {@code error} is non-null. */
    public record ParseResult<T>(T value, DecodeError error) {
        public boolean ok() {
            return value != null;
        }

        public static <T> ParseResult<T> ok(T value) {
            return new ParseResult<>(value, null);
        }

        public static <T> ParseResult<T> err(DecodeError error) {
            return new ParseResult<>(null, error);
        }
    }

    // ── Header ──────────────────────────────────────────────

    /** Encode a header line (with trailing newline). */
    public static String encodeHeader(JsonlV4Header header) {
        var node = SessionJson.mapper().createObjectNode();
        node.put("kind", "header");
        node.put("version", header.version());
        node.put("id", header.id());
        node.put("createdAt", header.createdAtMs());
        node.put("cwd", header.cwd());
        if (header.parentSessionId() != null) {
            node.put("parentSessionId", header.parentSessionId());
        }
        if (header.legacyParentSessionPath() != null) {
            node.put("legacyParentSessionPath", header.legacyParentSessionPath());
        }
        if (header.metadata() != null) {
            node.set("metadata", SessionJson.mapper().valueToTree(header.metadata()));
        }
        try {
            return SessionJson.mapper().writeValueAsString(node) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode header", e);
        }
    }

    /** Parse a header line. */
    public static ParseResult<JsonlV4Header> parseHeader(String line) {
        try {
            var node = parseObject(line);
            if (!"header".equals(stringValue(node, "kind"))) {
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
                "header", version, requireString(node, "id"),
                requireLong(node, "createdAt"), requireString(node, "cwd"),
                parentSessionId, legacyParentSessionPath, metadata);
            return ParseResult.ok(header);
        } catch (DecodeError e) {
            return ParseResult.err(e);
        } catch (Exception e) {
            return ParseResult.err(DecodeError.syntax("is not valid JSON", e));
        }
    }

    // ── Mutations ───────────────────────────────────────────

    /** Encode a mutation as a JSONL line (with trailing newline). */
    public static String encodeMutation(SessionMutation mutation) {
        var mapper = SessionJson.mapper();
        var node = mapper.createObjectNode();
        switch (mutation) {
            case SessionMutation.Entry m -> {
                node.put("kind", "entry");
                if (m.lane() != null) {
                    node.put("lane", m.lane());
                }
                node.setAll((ObjectNode) mapper.valueToTree(m.entry()));
            }
            case SessionMutation.Record m -> {
                node.put("kind", "record");
                node.setAll((ObjectNode) mapper.valueToTree(m.record()));
            }
            case SessionMutation.Lane m -> {
                node.put("kind", "lane");
                node.put("seq", m.seq());
                node.put("lane", m.lane());
                if (m.leafId() != null) {
                    node.put("leafId", m.leafId());
                } else {
                    node.putNull("leafId");
                }
            }
            case SessionMutation.FactName m -> {
                node.put("kind", "fact");
                node.put("seq", m.seq());
                node.put("fact", "name");
                if (m.name() != null) {
                    node.put("name", m.name());
                }
            }
            case SessionMutation.FactLabel m -> {
                node.put("kind", "fact");
                node.put("seq", m.seq());
                node.put("fact", "label");
                node.put("targetId", m.targetId());
                if (m.label() != null) {
                    node.put("label", m.label());
                }
            }
        }
        try {
            return mapper.writeValueAsString(node) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode mutation", e);
        }
    }

    /** Parse a mutation line. */
    public static ParseResult<SessionMutation> parseMutation(String line) {
        try {
            var node = parseObject(line);
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
        String lane = node.has("lane") ? requireString(node, "lane") : null;
        String id = requireString(node, "id");
        String type = requireString(node, "entry type");
        if (!ENTRY_TYPES.contains(type)) {
            throw DecodeError.schema("has unknown entry type " + type);
        }
        if ("custom".equals(type)) {
            requireString(node, "customType");
        }
        String parentId = nullableString(node, "parentId");
        Instant timestamp = instant(node, "timestamp");
        Entry entry = EntryJsonCodec.decode(node, id, seq, parentId, timestamp);
        return lane == null
            ? new SessionMutation.Entry(null, entry)
            : new SessionMutation.Entry(lane, entry);
    }

    private static SessionMutation parseRecordMutation(JsonNode node, long seq) {
        String id = requireString(node, "id");
        String lane = requireString(node, "lane");
        String type = requireString(node, "record type");
        if (!RECORD_TYPES.contains(type)) {
            throw DecodeError.schema("has unknown record type " + type);
        }
        if ("operation_started".equals(type)) {
            JsonNode intent = node.get("intent");
            if (intent == null || !intent.isObject()) {
                throw DecodeError.schema("has invalid intent");
            }
            String operationKind = requireString(intent, "operation kind");
            if (!OPERATION_KINDS.contains(operationKind)) {
                throw DecodeError.schema("has unknown operation kind " + operationKind);
            }
        }
        if ("operation_finished".equals(type)) {
            requireString(node, "runId");
        }
        Instant timestamp = instant(node, "timestamp");
        LaneRecord record = RecordJsonCodec.decode(node, id, seq, lane, timestamp);
        return new SessionMutation.Record(record);
    }

    private static SessionMutation parseLaneMutation(JsonNode node, long seq) {
        return new SessionMutation.Lane(seq, requireString(node, "lane"),
            nullableString(node, "leafId"));
    }

    private static SessionMutation parseFactMutation(JsonNode node, long seq) {
        String fact = requireString(node, "fact");
        return switch (fact) {
            case "name" -> {
                String name = optionalString(node, "name");
                yield new SessionMutation.FactName(seq, name);
            }
            case "label" -> {
                String label = optionalString(node, "label");
                yield new SessionMutation.FactLabel(seq, requireString(node, "targetId"), label);
            }
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

    public static String requireString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return value.textValue();
    }

    public static long requireLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw DecodeError.schema("has invalid " + field);
        }
        return value.longValue();
    }

    public static int requireInt(JsonNode node, String field) {
        return (int) requireLong(node, field);
    }

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
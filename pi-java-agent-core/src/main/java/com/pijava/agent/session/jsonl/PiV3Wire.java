package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.SessionJson;
import com.pijava.agent.session.SessionMutation;

/**
 * pi 主流会话线格式（v3）的头编解码 —— 包 12，{@code docs/12}。
 *
 * <p>pi 侧逐字证据（{@code coding-agent/src/core/session-manager.ts}）：</p>
 * <pre>
 * :41  export const CURRENT_SESSION_VERSION = 3;
 * :43  export interface SessionHeader {
 * :44      type: "session";
 * :45      version?: number;        // v1 sessions don't have this
 * :46      id: string;
 * :47      timestamp: string;       // new Date().toISOString()
 * :48      cwd: string;
 * :49      parentSession?: string;
 *      }
 * </pre>
 *
 * <p>⚠️ 本仓写出的头**只有上面这六个键** —— pi 的 {@code _loadEntries} 靠
 * {@code e.type === "session"} 找头（{@code :1086}），找不到就 {@code newSession()} 新建空会话；
 * 这正是换锚前「pi 读不了本仓文件」的直接原因（{@code docs/12 §5}）。</p>
 */
public final class PiV3Wire {

    /** pi 的当前会话文件版本（{@code session-manager.ts:41}）。 */
    public static final int SESSION_VERSION = 3;

    /**
     * pi 头的判别值（{@code type:"session"}）。本仓把它**复用为内部格式判别键**：
     * {@link JsonlV4Header#kind()} 等于它 ⇒ 当前格式（pi v3 线），
     * 等于 {@link JsonlCodec#LEGACY_HEADER_KIND} ⇒ 本仓旧头（需迁移）。
     */
    public static final String SESSION_KIND = "session";

    /**
     * pi 的时间戳形状 —— {@code new Date().toISOString()} 固定三位毫秒 ＋ {@code Z}。
     * ⚠️ 不能直接用 {@link Instant#toString()}：它会省略尾随零（{@code .690Z} 会写成 {@code .69Z}）。
     */
    private static final DateTimeFormatter ISO_MILLIS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private PiV3Wire() {}

    /** 把 epoch 毫秒写成 pi 的 ISO 串。 */
    public static String isoTimestamp(long epochMs) {
        return ISO_MILLIS.format(Instant.ofEpochMilli(epochMs));
    }

    /** 读一个 pi 形状的 ISO 时间戳（{@code :47}）。 */
    public static Instant parseTimestamp(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw JsonlCodec.DecodeError.schema("has invalid " + field);
        }
        try {
            return Instant.parse(value.textValue());
        } catch (DateTimeParseException e) {
            throw JsonlCodec.DecodeError.schema("has invalid " + field);
        }
    }

    /** 编码一行 pi v3 头（含尾换行）。 */
    public static String encodeHeader(JsonlV4Header header) {
        var node = SessionJson.mapper().createObjectNode();
        node.put("type", SESSION_KIND);
        node.put("version", SESSION_VERSION);
        node.put("id", header.id());
        node.put("timestamp", isoTimestamp(header.createdAtMs()));
        node.put("cwd", header.cwd());
        // 本仓有两个父级来源（id 与 legacy 路径），pi 只有一个 parentSession 串。
        // 二者**从无消费者区分**（`legacyParentSessionPath` 只由旧的迁移写入），故合并。
        var parent = header.parentSessionId() != null
            ? header.parentSessionId() : header.legacyParentSessionPath();
        if (parent != null) {
            node.put("parentSession", parent);
        }
        try {
            return SessionJson.mapper().writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode header", e);
        }
    }

    /** 解析一行 pi v3 头；{@code kind} 固定为 {@link #SESSION_KIND}。 */
    public static JsonlCodec.ParseResult<JsonlV4Header> parseHeader(JsonNode node) {
        try {
            if (!SESSION_KIND.equals(text(node, "type"))) {
                return JsonlCodec.ParseResult.err(
                    JsonlCodec.DecodeError.schema("is not a session header"));
            }
            int version = node.hasNonNull("version") ? node.get("version").asInt() : 1;
            if (version != SESSION_VERSION) {
                // pi 的 v1/v2 是 2026 年前的格式，本仓从没写过，也不做它们的迁移。
                // 明说而不是静默新建 —— 静默新建正是 pi 侧那个坑（docs/12 §5）。
                return JsonlCodec.ParseResult.err(JsonlCodec.DecodeError.schema(
                    "has unsupported session version " + version));
            }
            var header = new JsonlV4Header(
                SESSION_KIND, version,
                JsonlCodec.requireString(node, "id"),
                parseTimestamp(node, "timestamp").toEpochMilli(),
                JsonlCodec.requireString(node, "cwd"),
                JsonlCodec.optionalString(node, "parentSession"),
                null, null);
            return JsonlCodec.ParseResult.ok(header);
        } catch (JsonlCodec.DecodeError e) {
            return JsonlCodec.ParseResult.err(e);
        }
    }

    /**
     * 把一条 entry 铺成 **pi 的行形状**（{@code session-manager.ts:57-63} 的基线）：
     * 判别键 {@code type} ＋ {@code id}/{@code parentId}/{@code timestamp} ＋ 变体载荷。
     *
     * <p>⚠️ 与旧形状的差别：**去 {@code seq}**（pi 的 {@code SessionEntryBase} 没有它）与
     * **去 {@code kind:"entry"} 包装**；时间戳从 epoch 毫秒换成 ISO 串。</p>
     *
     * <p>⚠️ <b>{@code lane} 保留为「pi 忽略的扩展键」</b>（实施期裁决，见 {@code docs/12 §6 D2}）：
     * 它是本仓多 lane 模型的**承重字段** —— {@code SessionState.applyEntry} 靠它更新 lane 的叶指针。
     * 去掉它会让叶指针恒为 null（实测：默认 fork 目标解析为空、非消息叶的校验被整个跳过）。
     * pi 侧无碍：{@code parseSessionEntries} 只做 {@code JSON.parse}（{@code session-manager.ts:353-368}），
     * 未知键照读不误。</p>
     *
     * <p>⚠️ {@code seq} 只在本仓内存里有意义（{@code SessionState} 拿它做「严格连续」校验）；
     * 落线后由**行号**重建。</p>
     */
    static void encodeEntryLine(ObjectNode target, SessionMutation.Entry m) {
        target.setAll(SessionWireShape.toPiTree(m.entry()));
        target.remove("seq");
        if (m.lane() != null) {
            target.put("lane", m.lane());
        }
        var createdAt = m.entry().timestamp();
        if (createdAt == null) {
            throw new IllegalStateException("Entry " + m.entry().id() + " has no timestamp");
        }
        target.put("timestamp", isoTimestamp(createdAt.toEpochMilli()));
        // pi 的 parentId 必填（可为 null）；NON_NULL 会把 null 吞掉。
        if (!target.has("parentId")) {
            target.putNull("parentId");
        }
        // pi 的 replacement 键必填、值可为显式 null（session-manager.ts:179 无 ?）。
        if (m.entry() instanceof Entry.ContextEdit && !target.has("replacement")) {
            target.putNull("replacement");
        }
    }

    // ── 本仓的三族合成行（pi 无对应物，docs/12 §6 D2）──────────────

    /**
     * lane 行的 {@code customType}。pi 的 {@code custom} 条目本就是给扩展存私有持久状态的
     * （{@code session-manager.ts:112-122} 的注释逐字这么说），且**不参与 LLM 上下文**
     * （{@code buildSessionContext} 忽略 {@code custom}）—— 正好是本仓审计行的性质。
     */
    static final String EXT_LANE = "pi-java.lane";

    /** record 行的 {@code customType} 前缀，后缀是记录类型（如 {@code operation_started}）。 */
    static final String EXT_RECORD = "pi-java.record.";

    /**
     * 合成行的 id —— 由 {@code seq} 派生。
     *
     * <p>确定且唯一（{@code seq} 在一会话内严格递增），且**不引入新的身份字段**：
     * 这三个 mutation 原本连 id 都没有（{@code docs/12 §6 D2}）。</p>
     */
    static String syntheticId(String kind, long seq) {
        return "pj-" + kind + "-" + seq;
    }

    /** 写一条合成行的共同基线（{@code SessionEntryBase}，{@code session-manager.ts:57-63}）。 */
    private static void base(ObjectNode target, String type, String id,
                             String parentId, java.time.Instant timestamp) {
        target.put("type", type);
        target.put("id", id);
        if (parentId != null) {
            target.put("parentId", parentId);
        } else {
            target.putNull("parentId");
        }
        target.put("timestamp", isoTimestamp(timestamp == null ? 0L : timestamp.toEpochMilli()));
    }

    /** lane 行 → pi {@code custom}。 */
    static void encodeLaneLine(ObjectNode target, SessionMutation.Lane m) {
        base(target, "custom", syntheticId("lane", m.seq()), m.parentId(), m.timestamp());
        target.put("customType", EXT_LANE);
        var data = target.putObject("data");
        data.put("lane", m.lane());
        if (m.leafId() != null) {
            data.put("leafId", m.leafId());
        } else {
            data.putNull("leafId");
        }
    }

    /** 会话名 → pi **原生**的 {@code session_info}（{@code session-manager.ts:142-146}）。 */
    static void encodeSessionInfoLine(ObjectNode target, SessionMutation.FactName m) {
        base(target, "session_info", syntheticId("name", m.seq()), m.parentId(), m.timestamp());
        if (m.name() != null) {
            target.put("name", m.name());
        }
    }

    /** 条目标签 → pi **原生**的 {@code label}（{@code session-manager.ts:135-140}）。 */
    static void encodeLabelLine(ObjectNode target, SessionMutation.FactLabel m) {
        base(target, "label", syntheticId("label", m.seq()), m.parentId(), m.timestamp());
        target.put("targetId", m.targetId());
        if (m.label() != null) {
            target.put("label", m.label());
        }
    }

    /** 审计记录 → pi {@code custom}（载荷原样进 {@code data}）。 */
    static void encodeRecordLine(ObjectNode target, SessionMutation.Record m) {
        var record = m.record();
        base(target, "custom", record.id(), m.parentId(), record.timestamp());
        target.put("customType", EXT_RECORD + record.type());
        target.set("data", SessionJson.mapper().valueToTree(record));
    }

    /** 读一行 pi 形状的**非** entry 行；真 entry 转 {@link JsonlCodec#parsePiEntry}。 */
    static SessionMutation parseFlatLine(JsonNode node, long seq) {
        String type = JsonlCodec.requireString(node, "type");
        return switch (type) {
            case "session_info" -> new SessionMutation.FactName(seq, parentId(node),
                parseTimestamp(node, "timestamp"), JsonlCodec.optionalString(node, "name"));
            case "label" -> new SessionMutation.FactLabel(seq, parentId(node),
                parseTimestamp(node, "timestamp"), JsonlCodec.requireString(node, "targetId"),
                JsonlCodec.optionalString(node, "label"));
            case "custom" -> parseCustom(node, seq);
            default -> JsonlCodec.parsePiEntry(node, seq);
        };
    }

    private static SessionMutation parseCustom(JsonNode node, long seq) {
        String customType = JsonlCodec.requireString(node, "customType");
        if (EXT_LANE.equals(customType)) {
            var data = node.path("data");
            return new SessionMutation.Lane(seq, parentId(node), parseTimestamp(node, "timestamp"),
                JsonlCodec.requireString(data, "lane"), JsonlCodec.optionalString(data, "leafId"));
        }
        if (customType.startsWith(EXT_RECORD)) {
            var data = node.path("data");
            if ("usage".equals(JsonlCodec.requireString(data, "type"))) {
                // D6：旧文件把 usage 落在 record 族（→ custom），现在它是一等 entry。
                // 同上：不带 lane，免得撞车道的叶链校验。
                return new SessionMutation.Entry(null, JsonlCodec.legacyUsageEntry(data,
                    JsonlCodec.requireString(data, "id"), parentId(node),
                    parseTimestamp(node, "timestamp")));
            }
            var record = RecordJsonCodec.decode(data,
                JsonlCodec.requireString(data, "id"), JsonlCodec.requireLong(data, "seq"),
                JsonlCodec.requireString(data, "lane"), JsonlCodec.instant(data, "timestamp"));
            return new SessionMutation.Record(parentId(node), record);
        }
        // 不是本仓的扩展 ⇒ 是一条真正的 custom 条目。
        return JsonlCodec.parsePiEntry(node, seq);
    }

    private static String parentId(JsonNode node) {
        return JsonlCodec.nullableString(node, "parentId");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}

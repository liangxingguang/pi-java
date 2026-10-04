package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        target.setAll((ObjectNode) SessionJson.mapper().valueToTree(m.entry()));
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
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}

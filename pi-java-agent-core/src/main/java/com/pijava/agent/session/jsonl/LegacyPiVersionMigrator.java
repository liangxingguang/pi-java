package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.agent.session.SessionJson;

/**
 * 批 33（#17）：pi 旧会话版本（v1／v2）→ v3 的节点级迁移（对齐 pi
 * {@code session-manager.ts:287-347} 的 {@code migrateV1ToV2}／{@code migrateV2ToV3}／
 * {@code migrateToCurrentVersion}）。
 *
 * <p>逐行解析（畸形行按 pi {@code parseSessionEntries} 跳过），保留头里的未知键，
 * 迁移后 temp＋rename 原子重写整份文件。只处理 {@code type:"session"} 的头；
 * 本仓旧头（{@code kind:"header"}）走另一条 {@code migrateLegacyToPiV3} 路径。</p>
 */
final class LegacyPiVersionMigrator {

    private static final int MAX_ID_ATTEMPTS = 100;

    private LegacyPiVersionMigrator() {}

    /** 文件首行（头）是否需要迁移：{@code type:"session"} 且版本 < 3。 */
    static boolean needsMigration(String content) {
        return needsMigration(firstLineNode(content));
    }

    /** 头节点是否需要迁移：{@code type:"session"} 且版本 < 3。 */
    static boolean needsMigration(JsonNode header) {
        if (header == null || !header.isObject()) {
            return false;
        }
        if (!PiV3Wire.SESSION_KIND.equals(header.path("type").asText())) {
            return false;
        }
        return headerVersion((ObjectNode) header) < PiV3Wire.SESSION_VERSION;
    }

    /**
     * 迁移并重写文件。首行必须是 {@code type:"session"} 头；否则原样不动
     * （由调用方的严格解析报错）。
     */
    static void migrate(JsonlSessionRepoFileSystem fs, Path path, String content) {
        List<ObjectNode> entries = parseEntries(content);
        if (entries.isEmpty()) {
            return;
        }
        int version = headerVersion(entries.get(0));
        if (version >= PiV3Wire.SESSION_VERSION) {
            return;
        }
        if (version < 2) {
            migrateV1ToV2(entries);
        }
        migrateV2ToV3(entries);
        StringBuilder rewritten = new StringBuilder();
        for (var entry : entries) {
            try {
                rewritten.append(SessionJson.mapper().writeValueAsString(entry)).append('\n');
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Failed to rewrite migrated session", e);
            }
        }
        String tempPath = path + ".tmp";
        fs.writeFile(Path.of(tempPath), rewritten.toString());
        fs.renameFile(Path.of(tempPath), path);
    }

    /** 逐行解析成节点；空行与畸形行跳过（pi {@code parseSessionEntries} 同）。 */
    private static List<ObjectNode> parseEntries(String content) {
        List<ObjectNode> entries = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            try {
                JsonNode node = SessionJson.mapper().readTree(line);
                if (node instanceof ObjectNode object) {
                    entries.add(object);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // pi 的 JSON.parse 在该行失败时 catch 后跳过（session-manager.ts:361-366）。
            }
        }
        return entries;
    }

    /** 头第一行解析成节点；无法解析 ⇒ null。 */
    private static JsonNode firstLineNode(String content) {
        int index = content.indexOf('\n');
        String line = index >= 0 ? content.substring(0, index) : content;
        try {
            return SessionJson.mapper().readTree(line);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static int headerVersion(ObjectNode header) {
        JsonNode value = header.get("version");
        return value != null && value.isNumber() ? value.asInt() : 1;
    }

    /**
     * pi {@code migrateV1ToV2}（session-manager.ts:287-313）：头版本改 2；每个非头
     * 条目补 8 位 id 与 parentId 链；compaction 的 {@code firstKeptEntryIndex}
     * 换成 {@code firstKeptEntryId} 并删除旧键。
     *
     * <p>与 pi 的一遍循环相比，这里用两遍（先分配全部 id、再解析索引）——
     * 对合法文件结果一致，对索引指向后续条目的文件更稳。</p>
     */
    private static void migrateV1ToV2(List<ObjectNode> entries) {
        entries.get(0).put("version", 2);
        Set<String> ids = new HashSet<>();
        String previousId = null;
        for (int i = 1; i < entries.size(); i++) {
            ObjectNode entry = entries.get(i);
            String id = generateId(ids);
            entry.put("id", id);
            if (previousId == null) {
                entry.putNull("parentId");
            } else {
                entry.put("parentId", previousId);
            }
            previousId = id;
        }
        for (int i = 1; i < entries.size(); i++) {
            ObjectNode entry = entries.get(i);
            if (!"compaction".equals(entry.path("type").asText())) {
                continue;
            }
            JsonNode indexNode = entry.get("firstKeptEntryIndex");
            if (indexNode == null || !indexNode.isNumber()) {
                continue;
            }
            int index = indexNode.asInt();
            if (index >= 0 && index < entries.size()) {
                ObjectNode target = entries.get(index);
                if (!PiV3Wire.SESSION_KIND.equals(target.path("type").asText())) {
                    entry.put("firstKeptEntryId", target.path("id").asText());
                }
            }
            entry.remove("firstKeptEntryIndex");
        }
    }

    /** pi {@code migrateV2ToV3}（session-manager.ts:316-331）：头版本改 3；hookMessage 就地改 custom。 */
    private static void migrateV2ToV3(List<ObjectNode> entries) {
        entries.get(0).put("version", 3);
        for (int i = 1; i < entries.size(); i++) {
            ObjectNode entry = entries.get(i);
            if (!"message".equals(entry.path("type").asText())) {
                continue;
            }
            JsonNode message = entry.get("message");
            if (message instanceof ObjectNode object
                    && "hookMessage".equals(object.path("role").asText())) {
                object.put("role", "custom");
            }
        }
    }

    /** pi {@code generateId}：{@code randomUUID().slice(0,8)}，碰撞重试（:277-284）。 */
    private static String generateId(Set<String> ids) {
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            String candidate = UUID.randomUUID().toString().substring(0, 8);
            if (ids.add(candidate)) {
                return candidate;
            }
        }
        String fallback = UUID.randomUUID().toString();
        ids.add(fallback);
        return fallback;
    }
}

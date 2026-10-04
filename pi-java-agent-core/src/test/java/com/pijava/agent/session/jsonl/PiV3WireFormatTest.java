package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话线格式必须与 pi 主流一致（包 12，{@code docs/12}）。
 *
 * <p>判据是**双向可读**：pi 能读本仓写的文件，本仓也能读 pi 写的。两条硬事实（{@code docs/12 §5}）：
 * pi 的 {@code _loadEntries} 靠 {@code e.type === "session"} 找头，找不到就 {@code newSession()}；
 * 本仓的 {@code JsonlSessionStorage.load} 靠 {@code kind == "header"} 找头，否则抛
 * {@code is missing a header}。</p>
 *
 * <p>pi 侧逐字证据：{@code coding-agent/src/core/session-manager.ts:41-49}（头）、{@code :57-63}
 * （条目基线，**无 {@code seq}**）、{@code :1060-1072}（写盘）、{@code :1187}（追加快）。
 * ⚠️ 时间戳两侧不同型：pi 是 ISO 串，本仓此前是 epoch 毫秒。</p>
 */
class PiV3WireFormatTest {

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    private static final String PI_V3_HEADER =
        "{\"type\":\"session\",\"version\":3,\"id\":\"s-1\",\"timestamp\":\"2026-10-03T12:50:57.069Z\","
            + "\"cwd\":\"D:\\\\work\"}";

    private static ProvisionedEntry<Entry.Message> message(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    private static Path writeSession(Path dir) throws Exception {
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(message("m1", "hello"), "main");
        session.storage().drain();
        return repo.list(JsonlSessionListOptions.all()).getFirst().path();
    }

    private static List<JsonNode> lines(Path file) throws Exception {
        var mapper = SessionJson.mapper();
        return Files.readAllLines(file).stream()
            .filter(l -> !l.isBlank())
            .map(l -> {
                try {
                    return mapper.readTree(l);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            })
            .toList();
    }

    // ── 写：头必须是 pi 的形状 ──────────────────────────────────────

    @Test
    void headerLineUsesThePiV3Shape() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-header");
        JsonNode header = lines(writeSession(dir)).getFirst();

        assertThat(header.path("type").asText())
            .as("pi 的判别键是 type:session（session-manager.ts:44），不是 kind:header")
            .isEqualTo("session");
        assertThat(header.path("version").asInt())
            .as("pi 的当前版本是 3（session-manager.ts:41）")
            .isEqualTo(3);
        assertThat(header.has("kind")).as("pi 的头里没有 kind 键").isFalse();
        assertThat(header.path("timestamp").isTextual())
            .as("pi 的时间戳是 ISO 串（session-manager.ts:46），不是 epoch 毫秒")
            .isTrue();
        assertThat(header.path("timestamp").asText()).startsWith("20");
        assertThat(header.has("createdAt")).as("epoch 毫秒键不得残留").isFalse();
        assertThat(header.path("cwd").asText()).as("cwd 保留（仓储会归一成绝对路径）").endsWith("work");
    }

    // ── 写：条目必须是扁平行 ────────────────────────────────────────

    @Test
    void entryLinesAreFlatWithIsoTimestamps() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-entry");
        JsonNode entry = lines(writeSession(dir)).get(1);

        assertThat(entry.path("type").asText()).isEqualTo("message");
        assertThat(entry.has("kind")).as("pi 没有 {kind:\"entry\"} 这层包装").isFalse();
        assertThat(entry.has("seq")).as("pi 的 SessionEntryBase 没有 seq（:57-63）").isFalse();
        assertThat(entry.path("lane").asText())
            .as("lane 是承重的扩展键（docs/12 §6 D2 实施期裁决）：pi 忽略未知键，"
                + "但去掉它会让 lane 叶指针恒为 null")
            .isEqualTo("main");
        assertThat(entry.path("id").asText()).isEqualTo("m1");
        assertThat(entry.has("parentId")).as("pi 的 parentId 必填（可为 null）").isTrue();
        assertThat(entry.path("timestamp").isTextual()).as("条目时间戳也必须是 ISO 串").isTrue();
        assertThat(entry.path("timestamp").asText()).startsWith("20");
        assertThat(entry.path("message").path("role").asText()).isEqualTo("user");
    }

    // ── 读：本仓必须能装载 pi 写的文件 ──────────────────────────────

    @Test
    void loadsAPiAuthoredSessionFile() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-read");
        Path file = dir.resolve("2026-10-03T12-50-57-069Z_s-1.jsonl");
        Files.writeString(file, PI_V3_HEADER + "\n"
            + "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,"
            + "\"timestamp\":\"2026-10-03T12:51:29.391Z\","
            + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n");

        var storage = JsonlSessionStorage.load(FS, file);

        assertThat(storage.getLog(com.pijava.agent.session.LogOptions.none()))
            .as("pi 写的条目必须能被装载（此前抛 is missing a header）")
            .hasSize(1);
    }

    // ── 往返 ────────────────────────────────────────────────────────

    @Test
    void roundTripsThroughTheNewWireFormat() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-roundtrip");
        Path file = writeSession(dir);
        var reloaded = JsonlSessionStorage.load(FS, file);

        assertThat(reloaded.getLog(com.pijava.agent.session.LogOptions.none()))
            .as("写→读 后条目数不变")
            .hasSize(1);
    }
}

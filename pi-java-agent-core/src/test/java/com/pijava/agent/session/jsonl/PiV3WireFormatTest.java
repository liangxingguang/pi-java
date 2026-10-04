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

    // ── 合成行也必须是 pi 的条目（docs/12 §6 D2）────────────────────

    @Test
    void nameAndLabelBecomeNativePiEntries() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-facts");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(message("m1", "hello"), "main");
        session.setName("my session");
        session.setLabel("m1", "keep");
        session.storage().drain();

        var rows = lines(repo.list(JsonlSessionListOptions.all()).getFirst().path());
        JsonNode info = rows.stream().filter(n -> "session_info".equals(n.path("type").asText()))
            .findFirst().orElseThrow();
        JsonNode label = rows.stream().filter(n -> "label".equals(n.path("type").asText()))
            .findFirst().orElseThrow();

        assertThat(info.path("name").asText()).as("会话名用 pi 的原生 session_info").isEqualTo("my session");
        assertThat(label.path("targetId").asText()).isEqualTo("m1");
        assertThat(label.path("label").asText()).isEqualTo("keep");
    }

    @Test
    void everyRowIsAPiFileEntryAndChains() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-chain");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(message("m1", "one"), "main");
        session.appendEntry(message("m2", "two"), "main");
        session.setName("named");
        session.setLabel("m1", "L");
        session.storage().drain();

        var rows = lines(repo.list(JsonlSessionListOptions.all()).getFirst().path());

        // ① 每一行都是 pi 能认的 FileEntry —— 头用 type:"session"、其余行都有 type 与 id，
        //    且**没有任何一行**带旧形状的 kind（pi 会把 {kind:...} 当成 type:undefined 的条目）。
        assertThat(rows).allSatisfy(row -> assertThat(row.has("kind"))
            .as("旧形状的 kind 键一行都不许剩：%s", row).isFalse());
        assertThat(rows.getFirst().path("type").asText()).isEqualTo("session");
        assertThat(rows).allSatisfy(row -> assertThat(row.path("id").isTextual()).isTrue());

        // ② pi 的 SessionEntryBase 要求 parentId **键存在**（根条目为 null）。
        assertThat(rows.subList(1, rows.size()))
            .allSatisfy(row -> assertThat(row.has("parentId"))
                .as("每行都要有 parentId 键：%s", row).isTrue());

        // ③ 合成行（session_info / label）**必须链到已有条目**，否则 pi 的 getBranch()
        //    从叶回溯看不到消息 —— 这是 D2 里「合成行要链进树」那条的钉子。
        var synthetic = rows.stream()
            .filter(row -> java.util.Set.of("session_info", "label", "custom")
                .contains(row.path("type").asText()))
            .toList();
        assertThat(synthetic).isNotEmpty();
        assertThat(synthetic).allSatisfy(row -> assertThat(row.path("parentId").isTextual())
            .as("合成行的 parentId 不许为 null：%s", row).isTrue());
    }

    @Test
    void syntheticRowsSurviveAReload() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-synth-reload");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(message("m1", "hello"), "main");
        session.setName("kept name");
        session.setLabel("m1", "kept label");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        // 从**旧形状的线**再读一遍 —— 迁移与解析都要能拿回 name/label。
        var reloaded = JsonlSessionStorage.load(FS, file);
        assertThat(reloaded.getName()).isEqualTo("kept name");
        assertThat(reloaded.getLabel("m1")).isEqualTo("kept label");
    }

    // ── 旧文件（本仓自己的 v4 形状）的惰性迁移 ──────────────────────

    @Test
    void legacyFileIsMigratedToFullyPiShapedRows() throws Exception {
        Path dir = Files.createTempDirectory("pi-v3-migrate");
        Path file = dir.resolve("legacy.jsonl");
        Files.writeString(file,
            "{\"kind\":\"header\",\"version\":4,\"id\":\"s-1\",\"createdAt\":1720000000000,\"cwd\":\"work\"}\n"
            + "{\"kind\":\"entry\",\"lane\":\"main\",\"type\":\"message\",\"id\":\"m1\",\"seq\":1,"
            + "\"parentId\":null,\"timestamp\":1720000001000,"
            + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n"
            + "{\"kind\":\"fact\",\"seq\":2,\"fact\":\"name\",\"name\":\"legacy name\"}\n");

        var storage = JsonlSessionStorage.load(FS, file);
        assertThat(storage.getName()).as("迁移后数据不许丢").isEqualTo("legacy name");

        var rows = lines(file);
        assertThat(rows.getFirst().path("type").asText()).isEqualTo("session");
        assertThat(rows).allSatisfy(row -> assertThat(row.has("kind"))
            .as("迁移后不许剩任何旧形状的 kind：%s", row).isFalse());

        JsonNode info = rows.stream().filter(n -> "session_info".equals(n.path("type").asText()))
            .findFirst().orElseThrow();
        assertThat(info.path("parentId").asText())
            .as("旧行没有 parentId，迁移必须补成「挂到最近的条目」—— 否则 pi 仍看不到消息")
            .isEqualTo("m1");
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

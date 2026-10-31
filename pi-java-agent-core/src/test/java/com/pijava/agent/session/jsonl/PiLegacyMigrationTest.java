package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.SessionJson;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 33（#17/D192-D194）：pi v1／v2 会话的读取迁移。
 *
 * <p>v1 头无 {@code version}，条目无 id/parentId，compaction 带
 * {@code firstKeptEntryIndex}；v2 的 {@code role:"hookMessage"} 迁移为
 * {@code "custom"}。load 在严格解析前迁移并原子重写为 v3。</p>
 */
class PiLegacyMigrationTest {

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    private static final String V1_HEADER =
        "{\"type\":\"session\",\"id\":\"v1-s\",\"timestamp\":\"2026-01-01T00:00:00.000Z\","
            + "\"cwd\":\"cwd\",\"provider\":\"anthropic\",\"modelId\":\"m1\","
            + "\"thinkingLevel\":\"high\",\"branchedFrom\":\"/old/session.jsonl\"}";

    private static final String V1_USER =
        "{\"type\":\"message\",\"timestamp\":\"2026-01-01T00:00:01.000Z\","
            + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";

    private static final String V1_ASSISTANT =
        "{\"type\":\"message\",\"timestamp\":\"2026-01-01T00:00:02.000Z\","
            + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],"
            + "\"stopReason\":\"stop\"}}";

    private static Path writeTemp(String name, String content) throws Exception {
        Path file = Files.createTempFile(name, ".jsonl");
        Files.writeString(file, content);
        return file;
    }

    private static List<JsonNode> lines(Path file) throws Exception {
        return Files.readAllLines(file).stream()
            .filter(line -> !line.isBlank())
            .map(line -> {
                try {
                    return SessionJson.mapper().readTree(line);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            })
            .toList();
    }

    private static String v1Compaction(String indexLine) {
        return "{\"type\":\"compaction\",\"timestamp\":\"2026-01-01T00:00:03.000Z\","
            + "\"summary\":\"sum\",\"tokensBefore\":10," + indexLine + "}";
    }

    @Test
    void v1SessionMigratesIdsParentChainAndCompactionIndex() throws Exception {
        Path file = writeTemp("pi-v1", String.join("\n",
            V1_HEADER, V1_USER, V1_ASSISTANT, v1Compaction("\"firstKeptEntryIndex\":2")) + "\n");

        var storage = JsonlSessionStorage.load(FS, file);
        List<JsonNode> migrated = lines(file);

        assertThat(migrated.get(0).path("version").asInt()).isEqualTo(3);
        assertThat(migrated.get(0).path("provider").asText())
            .as("头里的未知键必须保留")
            .isEqualTo("anthropic");
        assertThat(migrated.get(0).path("branchedFrom").asText()).isEqualTo("/old/session.jsonl");
        assertThat(migrated).hasSize(4);

        String firstId = migrated.get(1).path("id").asText();
        String secondId = migrated.get(2).path("id").asText();
        String thirdId = migrated.get(3).path("id").asText();
        assertThat(firstId).hasSize(8);
        assertThat(migrated.get(1).path("parentId").isNull()).isTrue();
        assertThat(migrated.get(2).path("parentId").asText()).isEqualTo(firstId);
        assertThat(migrated.get(3).path("parentId").asText()).isEqualTo(secondId);
        assertThat(migrated.get(3).path("firstKeptEntryId").asText()).isEqualTo(secondId);
        assertThat(migrated.get(3).has("firstKeptEntryIndex")).isFalse();

        var entries = storage.findEntries(com.pijava.agent.session.EntryQuery.all());
        assertThat(entries).hasSize(3);
        var compaction = (Entry.Compaction) entries.stream()
            .filter(Entry.Compaction.class::isInstance).findFirst().orElseThrow();
        assertThat(compaction.firstKeptEntryId()).isEqualTo(secondId);
        assertThat(compaction.retainedTail()).as("pi 行无 retainedTail ⇒ 空表").isEmpty();
        assertThat(thirdId).isNotEmpty();
    }

    @Test
    void v1CompactionIndexPointingAtHeaderProducesNoIdButDropsOldKey() throws Exception {
        Path file = writeTemp("pi-v1-header-index", String.join("\n",
            V1_HEADER, v1Compaction("\"firstKeptEntryIndex\":0")) + "\n");

        var storage = JsonlSessionStorage.load(FS, file);
        List<JsonNode> migrated = lines(file);
        JsonNode compaction = migrated.get(1);

        assertThat(compaction.has("firstKeptEntryIndex")).isFalse();
        assertThat(compaction.has("firstKeptEntryId")).isFalse();
        var entry = (Entry.Compaction) storage
            .findEntries(com.pijava.agent.session.EntryQuery.all()).getFirst();
        assertThat(entry.firstKeptEntryId()).isNull();
    }

    @Test
    void v2HookMessageBecomesCustomMessage() throws Exception {
        String v2 = String.join("\n",
            "{\"type\":\"session\",\"version\":2,\"id\":\"v2-s\","
                + "\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"cwd\":\"cwd\"}",
            "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,"
                + "\"timestamp\":\"2026-01-01T00:00:01.000Z\","
                + "\"message\":{\"role\":\"hookMessage\",\"content\":\"hooked\"}}") + "\n";
        Path file = writeTemp("pi-v2", v2);

        var storage = JsonlSessionStorage.load(FS, file);
        List<JsonNode> migrated = lines(file);
        assertThat(migrated.get(0).path("version").asInt()).isEqualTo(3);
        assertThat(migrated.get(1).path("message").path("role").asText()).isEqualTo("custom");

        var entry = (Entry.CustomMessage) storage
            .findEntries(com.pijava.agent.session.EntryQuery.all()).getFirst();
        assertThat(entry.customType()).isEqualTo("hookMessage");
        assertThat(entry.display()).isTrue();
        assertThat(entry.content().toBlocks())
            .containsExactly(new com.pijava.ai.message.ContentBlock.TextContent("hooked"));
        assertThat(com.pijava.agent.session.ContextEntries.toMessages(
            storage.findEntries(com.pijava.agent.session.EntryQuery.all())))
            .singleElement()
            .isInstanceOf(com.pijava.ai.message.Message.UserMessage.class);
    }

    @Test
    void alreadyMigratedV3CustomRoleLoadsWithoutMigration() throws Exception {
        String v3 = String.join("\n",
            "{\"type\":\"session\",\"version\":3,\"id\":\"v3-s\","
                + "\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"cwd\":\"cwd\"}",
            "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,"
                + "\"timestamp\":\"2026-01-01T00:00:01.000Z\","
                + "\"message\":{\"role\":\"custom\",\"content\":[{\"type\":\"text\",\"text\":\"c\"}]}}")
            + "\n";
        Path file = writeTemp("pi-v3-custom", v3);

        var storage = JsonlSessionStorage.load(FS, file);
        assertThat(storage.findEntries(com.pijava.agent.session.EntryQuery.all()).getFirst())
            .isInstanceOf(Entry.CustomMessage.class);
        // 已是 v3 ⇒ 文件不被重写。
        assertThat(Files.readString(file)).isEqualTo(v3);
    }

    @Test
    void malformedLineIsDroppedDuringMigration() throws Exception {
        Path file = writeTemp("pi-v1-malformed", String.join("\n",
            V1_HEADER, "{\"type\":\"model_change\",\"provider\":\"p\",\"modelId\":\"m1\"",
            V1_USER) + "\n");

        var storage = JsonlSessionStorage.load(FS, file);
        List<JsonNode> migrated = lines(file);
        assertThat(migrated).hasSize(2);
        assertThat(migrated.get(1).path("message").path("role").asText()).isEqualTo("user");
        assertThat(storage.findEntries(com.pijava.agent.session.EntryQuery.all())).hasSize(1);
    }

    @Test
    void futureVersionIsRejected() throws Exception {
        Path file = writeTemp("pi-v4",
            "{\"type\":\"session\",\"version\":4,\"id\":\"v4-s\","
                + "\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"cwd\":\"cwd\"}\n");

        assertThatThrownBy(() -> JsonlSessionStorage.load(FS, file))
            .isInstanceOf(JsonlSessionStorage.JsonlSessionError.class)
            .hasMessageContaining("unsupported session version 4");
    }
}

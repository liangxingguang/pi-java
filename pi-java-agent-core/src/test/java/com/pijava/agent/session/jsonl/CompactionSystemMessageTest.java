package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 33（#8/#16）：compaction 的 {@code systemMessage} 编解码与 {@code retainedTail}
 * 可选化（pi {@code CompactionEntry.systemMessage?}，{@code session-manager.ts:100-104}）。
 */
class CompactionSystemMessageTest {

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    @Test
    void systemMessageRoundTripsThroughJsonl() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-compaction-system");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions("s1", "cwd", null, null));
        // 批 33（#19）：惰性创建要求先有 user/assistant 消息才落盘。
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message("m1", 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")), null), null)),
            "main");

        var sections = new LinkedHashMap<String, String>();
        sections.put("alpha", "one");
        sections.put("obsolete", null);
        var at = Instant.ofEpochMilli(1234);
        var system = new Message.SystemMessage(
            List.of(new ContentBlock.TextContent("sys prompt")), at, sections,
            List.of(new ToolDefinition("bash", "run shell", Map.of("type", "object"))),
            List.of());
        var compaction = new Entry.Compaction("c1", 0, null, null, "SUMMARY", "keep-1",
            List.of(), 42, Map.of("k", "v"), null, null, system);

        session.appendEntry(new ProvisionedEntry<>(compaction), "main");
        Path file = ((JsonlSessionStorage) session.storage()).path();

        String raw = Files.readString(file);
        assertThat(raw).contains("\"systemMessage\"");
        assertThat(raw).contains("\"obsolete\":null");

        var loaded = ((Entry.Compaction) JsonlSessionStorage.load(FS, file)
            .findEntries(com.pijava.agent.session.EntryQuery.all()).getFirst()).systemMessage();
        assertThat(loaded).isNotNull();
        assertThat(loaded.content()).isEqualTo(system.content());
        assertThat(loaded.timestamp()).isEqualTo(at);
        assertThat(loaded.sections())
            .containsEntry("alpha", "one")
            .containsKey("obsolete");
        assertThat(loaded.sections().get("obsolete")).isNull();
        assertThat(loaded.toolsAdded()).hasSize(1);
        assertThat(loaded.toolsAdded().getFirst().name()).isEqualTo("bash");
        assertThat(loaded.toolsAdded().getFirst().inputSchema()).containsEntry("type", "object");
        assertThat(loaded.toolsRemoved()).isEmpty();
    }

    @Test
    void compactionWithoutSystemMessageDecodesNull() throws Exception {
        // pi 的 compaction 行没有 retainedTail，也没有 systemMessage。
        var node = com.pijava.agent.session.SessionJson.mapper().createObjectNode();
        node.put("type", "compaction");
        node.put("summary", "pi summary");
        node.put("tokensBefore", 77);
        var entry = (Entry.Compaction) EntryJsonCodec.decode(node, "c", 1, null, Instant.EPOCH);

        assertThat(entry.systemMessage()).isNull();
        assertThat(entry.retainedTail())
            .as("retainedTail 缺席 ⇒ 空表（pi compaction 行可读）")
            .isEmpty();
        assertThat(entry.firstKeptEntryId()).isNull();
    }
}

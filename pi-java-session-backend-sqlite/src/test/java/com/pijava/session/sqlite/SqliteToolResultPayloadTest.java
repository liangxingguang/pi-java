package com.pijava.session.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.EntryQuery;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A7 的 SQLite 路（docs/23c §5 四路之一）：工具结果消息的
 * {@code details}/{@code usage}/{@code addedToolNames} 经行编码落库再读回必须原样
 * 保真 —— 载荷节点与 JSONL 路共用 {@code SessionJson.messageNode}（写）与
 * {@code JsonlCodec.decodeEntryPayload}（读），这里钉的是 SQLite 入口的端到端。
 */
class SqliteToolResultPayloadTest {

    @Test
    void toolResultPayloadRoundTripsThroughSqlite() throws Exception {
        Path db = Files.createTempDirectory("pi-sqlite-a7").resolve("s.db");
        var repo = SqliteSessionRepository.open(db);
        var session = repo.create(new SqliteSessionCreateOptions(null, "cwd", null, null));
        var details = Map.of("kind", "card", "nested", Map.of("n", List.of(1, 2)));
        var usage = Map.of("input", 3, "output", 5);
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message("e1", 0, null, null,
            new Message.ToolResultMessage("call-1", "rich",
                List.of(new ContentBlock.TextContent("ok")), details, usage,
                List.of("mcp:late"), false), null)), "main");
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message("e2", 0, null, null,
            new Message.ToolResultMessage("call-2", "plain",
                List.of(new ContentBlock.TextContent("ok")), null, null, List.of(), true),
            null)), "main");
        session.storage().drain();
        repo.close();

        var reopened = SqliteSessionRepository.open(db);
        var restored = reopened.list(SqliteSessionListOptions.all()).getFirst();
        var entries = reopened.open(restored).storage().findEntries(EntryQuery.all());

        var rich = toolResultNamed(entries, "rich");
        var plain = toolResultNamed(entries, "plain");
        assertThat(rich.details()).isEqualTo(details);
        assertThat(rich.usage()).isEqualTo(usage);
        assertThat(rich.addedToolNames()).containsExactly("mcp:late");
        assertThat(rich.isError()).isFalse();
        assertThat(plain.details()).isNull();
        assertThat(plain.usage()).isNull();
        assertThat(plain.addedToolNames()).isEmpty();
        assertThat(plain.isError()).isTrue();
        reopened.close();
    }

    private static Message.ToolResultMessage toolResultNamed(
            java.util.List<Entry> entries, String toolName) {
        return entries.stream()
            .filter(e -> e instanceof Entry.Message m
                && m.message() instanceof Message.ToolResultMessage t
                && t.toolName().equals(toolName))
            .map(e -> (Message.ToolResultMessage) ((Entry.Message) e).message())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no toolResult entry named " + toolName));
    }
}

package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.Session;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 33（#19/D191）：会话文件的惰性创建 —— create 不落盘，首条 user/assistant
 * 消息才一次性写出 header ＋ 全部缓冲行（pi {@code _persist}，
 * {@code session-manager.ts:1163-1189}）。
 */
class JsonlLazyCreationTest {

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    private static ProvisionedEntry<Entry.Message> userMessage(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    private static ProvisionedEntry<Entry.Message> systemMessage(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.SystemMessage(text, null, null, null, null), null));
    }

    private static JsonlSessionStorage storageOf(Session<JsonlSessionMetadata> session) {
        return (JsonlSessionStorage) session.storage();
    }

    @Test
    void createLeavesNoFileBehind() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-lazy-create");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        var storage = storageOf(session);

        assertThat(Files.exists(storage.path()))
            .as("空会话在磁盘上不可见（D191）")
            .isFalse();
        assertThat(storage.getMetadata().modifiedAtMs())
            .as("无文件时 modifiedAtMs 用头的时间占位")
            .isEqualTo(storage.getMetadata().createdAt().toEpochMilli());
        assertThat(repo.list(JsonlSessionListOptions.all())).isEmpty();
    }

    @Test
    void systemMessageOnlyStillDoesNotFlush() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-lazy-system");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        var storage = storageOf(session);
        session.appendEntry(systemMessage("s1", "prompt"), "main");

        assertThat(Files.exists(storage.path()))
            .as("system 消息不是对话（pi _hasConversation 的三条件）")
            .isFalse();
        assertThat(session.findEntries(com.pijava.agent.session.EntryQuery.all()))
            .as("内存里仍有这条系统消息")
            .hasSize(1);
    }

    @Test
    void firstUserMessageFlushesHeaderAndBufferedLines() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-lazy-flush");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        var storage = storageOf(session);
        session.appendEntry(systemMessage("s1", "prompt"), "main");
        session.appendEntry(userMessage("m1", "hi"), "main");

        Path file = storage.path();
        assertThat(Files.exists(file)).isTrue();
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("\"type\":\"session\"");
        assertThat(lines.get(1)).contains("\"s1\"").contains("\"role\":\"system\"");
        assertThat(lines.get(2)).contains("\"m1\"").contains("\"role\":\"user\"");

        // 之后的追加直接 append，不再重写头。
        String prefix = Files.readString(file);
        session.appendEntry(userMessage("m2", "again"), "main");
        String after = Files.readString(file);
        assertThat(after).startsWith(prefix);
        assertThat(after.lines().filter(l -> l.contains("\"type\":\"session\"")).count())
            .isEqualTo(1);
    }

    @Test
    void laneAndNameLinesAreBufferedBeforeConversation() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-lazy-lane");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        var storage = storageOf(session);
        session.createLane("side", null);
        storage.setName("demo");
        session.appendEntry(userMessage("m1", "hi"), "main");

        List<String> lines = Files.readAllLines(storage.path());
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).contains("\"type\":\"session\"");
        assertThat(lines.get(1)).contains("\"pi-java.lane\"");
        assertThat(lines.get(2)).contains("\"session_info\"");
        assertThat(lines.get(3)).contains("\"m1\"");
    }

    @Test
    void loadExistingFileStartsFlushed() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-lazy-load");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        session.appendEntry(userMessage("m1", "hi"), "main");
        Path file = storageOf(session).path();

        var loaded = JsonlSessionStorage.load(FS, file);
        loaded.appendEntry(userMessage("m2", "again"), "main");
        assertThat(Files.readString(file)).contains("\"m2\"");
    }

    @Test
    void forkWithoutConversationMaterializesTarget() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-lazy-fork");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions("source-1", "cwd", null, null));
        Path sourcePath = storageOf(session).path();
        // 只有头的源文件（无对话）：fork 目标仍必须立即可 load（D191）。
        var header = new JsonlV4Header(PiV3Wire.SESSION_KIND, PiV3Wire.SESSION_VERSION,
            "source-1", 1_720_000_000_000L, "cwd", null, null, null);
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, JsonlCodec.encodeHeader(header));
        var sourceMetadata = JsonlSessionStorage.load(FS, sourcePath).getMetadata();

        Session<JsonlSessionMetadata> forked = repo.fork(sourceMetadata,
            new com.pijava.agent.session.ForkOptions.Tree(),
            new JsonlSessionCreateOptions(null, "cwd", null, null));
        var target = storageOf(forked);
        assertThat(Files.exists(target.path())).isTrue();
        assertThat(target.getLanes()).isNotEmpty();
    }
}

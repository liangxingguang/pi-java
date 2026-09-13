package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JSONL-specific behavior: torn-tail repair, v3 marking, cwd encoding, fork. */
class JsonlSessionStorageTest {

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    private static ProvisionedEntry<Entry.Message> message(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    @Test
    void tornTailIsRepairedOnLoad() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-torn");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        session.appendEntry(message("m1", "first"), "main");
        session.appendEntry(message("m2", "second"), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        // Truncate the last line mid-JSON (simulated crash): the incomplete
        // tail is dropped on load, the valid prefix survives.
        String content = Files.readString(file);
        int lastLineStart = content.lastIndexOf('\n', content.length() - 2) + 1;
        Files.writeString(file, content.substring(0, lastLineStart) + "{\"kind\":\"en");

        var storage = JsonlSessionStorage.load(FS, file);
        assertThat(storage.findEntries(com.pijava.agent.session.EntryQuery.all()))
            .extracting(Entry::id).containsExactly("m1");
    }

    @Test
    void v3FileIsMarkedSourceFormat3() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-v3");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        session.appendEntry(message("m1", "first"), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        // Rewrite the header as version 3 with a legacy parent path.
        String content = Files.readString(file);
        String v3Header = "{\"kind\":\"header\",\"version\":3,\"id\":\"v3-id\","
            + "\"createdAt\":1720000000000,\"cwd\":\"cwd\",\"legacyParentSessionPath\":\"/old/session.jsonl\"}";
        String body = content.substring(content.indexOf('\n') + 1);
        Files.writeString(file, v3Header + "\n" + body);

        var storage = JsonlSessionStorage.load(FS, file);
        assertThat(storage.getMetadata().sourceFormat()).isEqualTo(3);
        assertThat(storage.getMetadata().legacyParentSessionPath())
            .isEqualTo("/old/session.jsonl");
    }

    @Test
    void sessionDirectoryNameEncodesCwd() {
        assertThat(JsonlSessionRepository.sessionDirectoryName("D:/workplaceForai/pi"))
            .isEqualTo("--D--workplaceForai-pi--");
        assertThat(JsonlSessionRepository.sessionDirectoryName("/home/u/project"))
            .isEqualTo("--home-u-project--");
    }

    @Test
    void forkAtomicallyPublishesTreeCopy() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-fork");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        session.appendEntry(message("m1", "first"), "main");
        session.appendEntry(message("m2", "second"), "main");

        var source = repo.list(JsonlSessionListOptions.all()).getFirst();
        Session<?> forked = repo.fork(source, new com.pijava.agent.session.ForkOptions.Tree(),
            new JsonlSessionCreateOptions(null, "cwd", null, null));

        assertThat(forked.findEntries(com.pijava.agent.session.EntryQuery.all()))
            .extracting(Entry::id).containsExactlyInAnyOrder("m1", "m2");
        assertThat(repo.list(JsonlSessionListOptions.all())).hasSize(2);
    }

    @Test
    void importJsonlCopiesFileAndMarksV3() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-import");
        var repo = JsonlSessionRepository.over(dir);
        // Build a v3 file with a legacy parent path.
        Path source = Files.createTempFile("pi-import", ".jsonl");
        String v3Header = "{\"kind\":\"header\",\"version\":3,\"id\":\"imp-1\","
            + "\"createdAt\":1720000000000,\"cwd\":\"cwd\",\"legacyParentSessionPath\":\"/old/s.jsonl\"}";
        String entry = "{\"kind\":\"entry\",\"lane\":\"main\",\"type\":\"message\",\"id\":\"m1\","
            + "\"seq\":1,\"parentId\":null,\"timestamp\":1720000001000,"
            + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";
        Files.writeString(source, v3Header + "\n" + entry + "\n");

        Session<?> imported = repo.importJsonl(source, "cwd");
        var metadata = (JsonlSessionMetadata) imported.getMetadata();
        assertThat(metadata.id()).isEqualTo("imp-1");
        assertThat(metadata.sourceFormat()).isEqualTo(3);
        assertThat(metadata.legacyParentSessionPath()).isEqualTo("/old/s.jsonl");
        assertThat(imported.findEntries(com.pijava.agent.session.EntryQuery.all()))
            .extracting(Entry::id).containsExactly("m1");

        // Same id import into the same root conflicts.
        assertThatThrownBy(() -> repo.importJsonl(source, "cwd"))
            .extracting(e -> ((SessionError) e).code())
            .isEqualTo(SessionErrorCode.ALREADY_EXISTS);
    }

    @Test
    void urlImageMessageRoundTripsThroughStorage() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-url-img");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions("s1", "cwd", null, null));
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message("e1", 0, null, null,
            new Message.UserMessage(List.<ContentBlock>of(
                new ContentBlock.UrlImageContent("https://ex.com/a.png"))), null)), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        var storage = JsonlSessionStorage.load(FS, file);
        var block = ((Entry.Message) storage.findEntries(
            com.pijava.agent.session.EntryQuery.all()).get(0))
            .message().content().get(0);
        assertThat(block).isEqualTo(new ContentBlock.UrlImageContent("https://ex.com/a.png"));
    }

    @Test
    void toolResultPayloadRoundTripsThroughJsonl() throws Exception {
        // A7 的 L3 判据（docs/23c §5）：details/usage/addedToolNames 落库再读回，
        // 键集合与值原样保留；无载荷消息则保持「键缺席」，不生出 null 噪声。
        Path dir = Files.createTempDirectory("pi-jsonl-a7");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions("s1", "cwd", null, null));
        var details = java.util.Map.of("kind", "card",
            "nested", java.util.Map.of("n", List.of(1, 2)));
        var usage = java.util.Map.of("input", 3, "output", 5);
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message("e1", 0, null, null,
            new Message.ToolResultMessage("call-1", "rich",
                List.of(new ContentBlock.TextContent("ok")), details, usage,
                List.of("mcp:late"), false), null)), "main");
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message("e2", 0, null, null,
            new Message.ToolResultMessage("call-2", "plain",
                List.of(new ContentBlock.TextContent("ok")), null, null, List.of(), true),
            null)), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        var storage = JsonlSessionStorage.load(FS, file);
        var entries = storage.findEntries(com.pijava.agent.session.EntryQuery.all());
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
        // 原始行级判据（pi 的 stringify 丢 undefined ⇒ 键根本不出现）：
        // 无载荷消息不许写出 "details"/"usage"/"addedToolNames" 空壳键
        String raw = Files.readString(file);
        String plainLine = raw.lines()
            .filter(l -> l.contains("\"e2\"")).findFirst().orElseThrow();
        assertThat(plainLine).doesNotContain("\"details\"")
            .doesNotContain("\"usage\"").doesNotContain("\"addedToolNames\"");
    }

    @Test
    void assistantPayloadRoundTripsThroughJsonl() throws Exception {
        // 3a 的 L3 判据（docs/31 §8.19）：身份三元组 + usage + timestamp + errorMessage
        // 落库再读回逐字段相等；旧形状消息（兼容构造器）保持「键缺席」，不生出 null 噪声。
        Path dir = Files.createTempDirectory("pi-jsonl-3a");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions("s1", "cwd", null, null));
        var at = java.time.Instant.ofEpochMilli(1_700_000_000_123L);
        var usage = new com.pijava.ai.Usage(10, 5, 1, 2, null, null, 18,
            com.pijava.ai.Usage.Cost.zero());
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message("e1", 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("hi")),
                "error", null, "anthropic-messages", "anthropic", "claude-sonnet-5",
                usage, at, "boom"), null)), "main");
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message("e2", 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("old"))),
            null)), "main");
        session.storage().drain();
        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();

        var storage = JsonlSessionStorage.load(FS, file);
        var rich = assistantAt(storage.findEntries(
            com.pijava.agent.session.EntryQuery.all()), "e1");
        assertThat(rich.api()).isEqualTo("anthropic-messages");
        assertThat(rich.provider()).isEqualTo("anthropic");
        assertThat(rich.model()).isEqualTo("claude-sonnet-5");
        assertThat(rich.usage()).isEqualTo(usage);
        assertThat(rich.timestamp()).isEqualTo(at);
        assertThat(rich.errorMessage()).isEqualTo("boom");
        assertThat(rich.stopReason()).isEqualTo("error");
        var plain = assistantAt(storage.findEntries(
            com.pijava.agent.session.EntryQuery.all()), "e2");
        assertThat(plain.usage()).isNull();
        assertThat(plain.timestamp()).isNull();
        // 原始行级判据（null ≙ undefined ⇒ 键不出现）：旧形状行不许写出身份/计量空壳键。
        // 判到 **message 子对象**这一层 —— entry 自己就带 timestamp/parentId 等字段，
        // 整行 substring 会把 entry 层的合法键误当载荷噪声。
        String raw = Files.readString(file);
        String plainLine = raw.lines()
            .filter(l -> l.contains("\"e2\"")).findFirst().orElseThrow();
        var plainMessage = com.pijava.agent.session.SessionJson.mapper()
            .readTree(plainLine).get("message");
        assertThat(plainMessage.has("api")).isFalse();
        assertThat(plainMessage.has("provider")).isFalse();
        assertThat(plainMessage.has("model")).isFalse();
        assertThat(plainMessage.has("usage")).isFalse();
        assertThat(plainMessage.has("timestamp")).isFalse();
        assertThat(plainMessage.has("errorMessage")).isFalse();
    }

    private static Message.AssistantMessage assistantAt(List<Entry> entries, String entryId) {
        return entries.stream()
            .filter(e -> e instanceof Entry.Message m && m.id().equals(entryId)
                && m.message() instanceof Message.AssistantMessage)
            .map(e -> (Message.AssistantMessage) ((Entry.Message) e).message())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no assistant entry " + entryId));
    }

    private static Message.ToolResultMessage toolResultNamed(
            List<Entry> entries, String toolName) {
        return entries.stream()
            .filter(e -> e instanceof Entry.Message m
                && m.message() instanceof Message.ToolResultMessage t
                && t.toolName().equals(toolName))
            .map(e -> (Message.ToolResultMessage) ((Entry.Message) e).message())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no toolResult entry named " + toolName));
    }

    @Test
    void invalidSessionIdIsRejected() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-id");
        var repo = JsonlSessionRepository.over(dir);
        assertThatThrownBy(() -> repo.create(
            new JsonlSessionCreateOptions("bad id!", "cwd", null, null)))
            .extracting(e -> ((SessionError) e).code())
            .isEqualTo(SessionErrorCode.INVALID_PAYLOAD);
    }
}

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
    void invalidSessionIdIsRejected() throws Exception {
        Path dir = Files.createTempDirectory("pi-jsonl-id");
        var repo = JsonlSessionRepository.over(dir);
        assertThatThrownBy(() -> repo.create(
            new JsonlSessionCreateOptions("bad id!", "cwd", null, null)))
            .extracting(e -> ((SessionError) e).code())
            .isEqualTo(SessionErrorCode.INVALID_PAYLOAD);
    }
}

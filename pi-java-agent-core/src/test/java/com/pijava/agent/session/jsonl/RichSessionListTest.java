package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #79/B55：渐进富列表 —— pi {@code listSessionsFromDir}/{@code listAll}
 * （session-manager.ts:884-974,:1921-2012）对位夹具。
 */
class RichSessionListTest {

    private static final String CWD = System.getProperty("user.dir");
    private static final String OTHER =
        Path.of(CWD).resolveSibling("pi-java-rich-other").toString();

    private static Entry.Message user(String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    private static Entry.Message assistant(String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    private static String seed(Path root, String cwd, String name, String firstUserText)
            throws Exception {
        var repo = JsonlSessionRepository.over(root);
        var session = repo.create(new JsonlSessionCreateOptions(null, cwd, null, null));
        session.appendEntry(new ProvisionedEntry<>(user(firstUserText)), "main");
        session.appendEntry(new ProvisionedEntry<>(assistant("reply")), "main");
        if (name != null) {
            session.setName(name);
        }
        session.storage().drain();
        return session.getMetadata().id();
    }

    @Test
    void richInfoCarriesPiSessionInfoFields() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-fields");
        String id = seed(root, CWD, "my session", "hello there");

        var infos = JsonlSessionRepository.over(root).listRich(CWD, null, null);

        assertThat(infos).hasSize(1);
        var info = infos.getFirst();
        assertThat(info.id()).isEqualTo(id);
        assertThat(info.name()).isEqualTo("my session");
        assertThat(info.cwd()).isEqualTo(CWD);
        assertThat(info.messageCount()).isEqualTo(2);
        assertThat(info.firstMessage()).isEqualTo("hello there");
        assertThat(info.allMessagesText()).isEqualTo("hello there reply");
        assertThat(info.path()).endsWith(".jsonl");
        assertThat(info.modifiedMs()).isGreaterThan(0);
    }

    @Test
    void emptyNameBecomesNullAndNoMessagesGetsThePiPlaceholder() throws Exception {
        // pi :820-822（空名 ⇒ undefined）与 :866（firstMessage || "(no messages)"）。
        Path root = Files.createTempDirectory("pi-rich-empty");
        var repo = JsonlSessionRepository.over(root);
        var session = repo.create(new JsonlSessionCreateOptions(null, CWD, null, null));
        session.setName("   ");
        session.storage().drain();
        String id = session.getMetadata().id();

        var infos = repo.listRich(CWD, null, null);
        assertThat(infos).hasSize(1);
        assertThat(infos.getFirst().name()).isNull();
        assertThat(infos.getFirst().firstMessage()).isEqualTo("(no messages)");
        assertThat(infos.getFirst().id()).isEqualTo(id);
    }

    private record Publish(int loaded, boolean withSnapshot) {}

    @Test
    void publishesSnapshotsAtThePiPoints() throws Exception {
        // pi :963-965：loaded==1 || %10 || ==total 时快照非空；其余回调快照缺席。
        Path root = Files.createTempDirectory("pi-rich-publish");
        for (int i = 0; i < 12; i++) {
            seed(root, CWD, null, "msg-" + i);
        }
        var publishes = new ArrayList<Publish>();
        var infos = JsonlSessionRepository.over(root).listRich(CWD,
            (loaded, total, partial) -> publishes.add(new Publish(loaded, partial != null)),
            null);

        assertThat(infos).hasSize(12);
        assertThat(publishes).hasSize(12);
        assertThat(publishes.stream().filter(Publish::withSnapshot).map(Publish::loaded))
            .containsExactlyInAnyOrder(1, 10, 12);
        // 快照内容按活动序（modified desc）排好
        var snapshots = new ArrayList<List<RichSessionInfo>>();
        JsonlSessionRepository.over(root).listRich(CWD, (loaded, total, partial) -> {
            if (partial != null) {
                snapshots.add(partial);
            }
        }, null);
        assertThat(snapshots).isNotEmpty();
        for (var snapshot : snapshots) {
            assertThat(snapshot).isSortedAccordingTo(
                (a, b) -> Long.compare(b.modifiedMs(), a.modifiedMs()));
        }
    }

    @Test
    void abortedSignalThrowsBeforeAndDuringListing() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-abort");
        seed(root, CWD, null, "x");
        var signal = new AbortSignal();
        signal.abort();
        var repo = JsonlSessionRepository.over(root);
        assertThatThrownBy(() -> repo.listRich(CWD, null, signal))
            .isInstanceOf(SessionListAbortedException.class);
        assertThatThrownBy(() -> repo.listAllRich(null, signal))
            .isInstanceOf(SessionListAbortedException.class);
    }

    @Test
    void listAllRichSpansProjectsAndSortsByActivity() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-all");
        String mine = seed(root, CWD, null, "mine");
        String other = seed(root, OTHER, null, "other");

        var infos = JsonlSessionRepository.over(root).listAllRich(null, null);

        assertThat(infos).extracting(RichSessionInfo::id)
            .containsExactlyInAnyOrder(mine, other);
        assertThat(infos).isSortedAccordingTo(
            (a, b) -> Long.compare(b.modifiedMs(), a.modifiedMs()));
    }

    @Test
    void nonSessionFirstLineYieldsNoEntry() throws Exception {
        // pi :814：首条非 header ⇒ 不是会话文件，静默跳过。
        Path root = Files.createTempDirectory("pi-rich-garbage");
        seed(root, CWD, null, "real");
        Path dir = root.resolve(JsonlSessionRepository.sessionDirectoryName(
            new DefaultJsonlFileSystem().absolutePath(CWD)));
        Files.writeString(dir.resolve("garbage.jsonl"), "{\"type\":\"message\"}\n");

        var infos = JsonlSessionRepository.over(root).listRich(CWD, null, null);
        assertThat(infos).hasSize(1);
    }
}

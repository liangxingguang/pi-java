package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.agent.session.Session;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B278（docs/24 W4c）：pi 显式 {@code --session-dir} 的平铺布局与 cwd 过滤。
 */
class SessionDirLayoutTest {

    @TempDir Path tmp;

    private static final JsonlSessionRepoFileSystem FS = new DefaultJsonlFileSystem();

    @Test
    void explicitSessionDirStoresFilesFlat() throws Exception {
        Path custom = tmp.resolve("custom-sessions");
        Files.createDirectories(custom);
        var repo = new JsonlSessionRepository(custom, tmp.resolve("default-root"), true, FS);

        var session = create(repo, tmp.resolve("project-a").toString(), "m1");
        Path file = fileOf(session);
        assertThat(file.getParent())
            .as("文件直接落在 --session-dir，不再拼 --cwd-- 子目录")
            .isEqualTo(custom);
    }

    @Test
    void explicitSessionDirFiltersByCwd() throws Exception {
        Path custom = tmp.resolve("custom-sessions");
        Files.createDirectories(custom);
        var repo = new JsonlSessionRepository(custom, tmp.resolve("default-root"), true, FS);
        String cwdA = tmp.resolve("project-a").toString();
        String cwdB = tmp.resolve("project-b").toString();

        create(repo, cwdA, "a1");

        assertThat(repo.list(new JsonlSessionListOptions(cwdA))).hasSize(1);
        assertThat(repo.list(new JsonlSessionListOptions(cwdB)))
            .as("pi filterCwd：不同 cwd 的会话不收入列表")
            .isEmpty();
        assertThat(repo.list(JsonlSessionListOptions.all()))
            .as("listAll 不做 cwd 过滤")
            .hasSize(1);
        var only = repo.list(JsonlSessionListOptions.all()).getFirst();
        assertThat(repo.findById(cwdB, only.id()))
            .as("精确查找同样按 cwd 过滤")
            .isEmpty();
    }

    @Test
    void explicitDirEqualToDefaultPerCwdDirDoesNotFilter() throws Exception {
        String cwd = tmp.resolve("project-a").toString();
        Path defaultRoot = tmp.resolve("default-root");
        Path perCwdDir = defaultRoot.resolve(JsonlSessionRepository.sessionDirectoryName(
            FS.absolutePath(cwd)));
        Files.createDirectories(perCwdDir);
        var repo = new JsonlSessionRepository(perCwdDir, defaultRoot, true, FS);

        create(repo, cwd, "a1");
        create(repo, tmp.resolve("project-b").toString(), "b1");

        assertThat(repo.list(new JsonlSessionListOptions(cwd)))
            .as("pi pathsEqual：显式目录就是该 cwd 的默认目录 ⇒ filterCwd=false，收全部")
            .hasSize(2);
    }

    private static Session<?> create(JsonlSessionRepository repo, String cwd, String id)
            throws Exception {
        var session = repo.create(new JsonlSessionCreateOptions(null, cwd, null, null));
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message(
            id, 0, null, null,
            new Message.UserMessage(java.util.List.of(new ContentBlock.TextContent(id))), null)),
            "main");
        return session;
    }

    private static Path fileOf(Session<?> session) {
        return ((JsonlSessionStorage) session.storage()).path();
    }
}

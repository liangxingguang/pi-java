package com.pijava.agent.session.jsonl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * docs/18（L4 归属复核）：列表中止补检查（pi #11956）、列表错误吞噬与候选 stat 重试。
 *
 * <p>pi 证据：{@code session-manager.ts:860}（EOF 后补检查）、{@code :978-981}／
 * {@code :2031-2034}（catch 后转抛 abort 再返回空）、{@code :806,1999-2000}
 * （候选 stat 失败后二次尝试）。</p>
 */
class RichSessionListRobustnessTest {

    private static final String CWD = System.getProperty("user.dir");

    private static Entry.Message user(String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    private static Entry.Message assistant(String text) {
        return new Entry.Message(UUID.randomUUID().toString(), 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    private static String seed(JsonlSessionRepository repo) {
        var session = repo.create(new JsonlSessionCreateOptions(null, CWD, null, null));
        session.appendEntry(new ProvisionedEntry<>(user("hello")), "main");
        session.appendEntry(new ProvisionedEntry<>(assistant("reply")), "main");
        session.storage().drain();
        return session.getMetadata().id();
    }

    /** 第 {@code abortOnCall} 次查询才中止；用于钉「循环之后」的补检查。 */
    static final class StepSignal extends AbortSignal {
        private final int abortOnCall;
        private int calls;

        StepSignal(int abortOnCall) {
            this.abortOnCall = abortOnCall;
        }

        @Override
        public boolean isAborted() {
            calls++;
            return calls >= abortOnCall;
        }

        int calls() {
            return calls;
        }
    }

    @Test
    void abortAfterLastLineStillThrows() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-abort-eof");
        var repo = JsonlSessionRepository.over(root);
        seed(repo);
        Path file = Files.walk(root)
            .filter(path -> path.toString().endsWith(".jsonl"))
            .findFirst()
            .orElseThrow();
        int lines = Files.readString(file).split("\n").length;
        // listRich 的 isAborted 调用：入口 1 ＋ worker 头 1 ＋ 逐行 lines ＋ 循环后补检查 1。
        var signal = new StepSignal(2 + lines + 1);

        assertThatThrownBy(() -> repo.listRich(CWD, null, signal))
            .isInstanceOf(SessionListAbortedException.class);
        assertThat(signal.calls()).isEqualTo(2 + lines + 1);
    }

    /** 委托真实 fs；可按需让目录枚举或 stat 失败。 */
    static final class FaultyFs implements JsonlSessionRepoFileSystem {
        private final DefaultJsonlFileSystem delegate = new DefaultJsonlFileSystem();
        boolean failListDir;
        int statFailures;
        int statCalls;

        @Override public String absolutePath(String path) { return delegate.absolutePath(path); }
        @Override public String joinPath(List<String> parts) { return delegate.joinPath(parts); }
        @Override public String readTextFile(Path path) { return delegate.readTextFile(path); }
        @Override public List<String> readTextLines(Path path, int maxLines) {
            return delegate.readTextLines(path, maxLines);
        }
        @Override public void writeFile(Path path, String content) { delegate.writeFile(path, content); }
        @Override public void appendFile(Path path, String content) { delegate.appendFile(path, content); }
        @Override public void renameFile(Path from, Path to) { delegate.renameFile(from, to); }

        @Override public long fileInfoMtimeMs(Path path) {
            statCalls++;
            if (statFailures > 0) {
                statFailures--;
                throw new DefaultJsonlFileSystem.JsonlFileException(
                    "injected stat failure", new IOException("stat"));
            }
            return delegate.fileInfoMtimeMs(path);
        }

        @Override public List<DirEntry> listDir(Path path) {
            if (failListDir) {
                throw new DefaultJsonlFileSystem.JsonlFileException(
                    "injected list failure", new IOException("list"));
            }
            return delegate.listDir(path);
        }

        @Override public boolean exists(Path path) { return delegate.exists(path); }
        @Override public void createDir(Path path, boolean recursive) { delegate.createDir(path, recursive); }
        @Override public void remove(Path path, boolean force) { delegate.remove(path, force); }
    }

    @Test
    void listRichSwallowsDirectoryFailure() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-swallow");
        var fs = new FaultyFs();
        var repo = new JsonlSessionRepository(root, fs);
        seed(repo);
        fs.failListDir = true;

        assertThat(repo.listRich(CWD, null, null)).isEmpty();
    }

    @Test
    void listAllRichSwallowsDirectoryFailure() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-swallow-all");
        var fs = new FaultyFs();
        var repo = new JsonlSessionRepository(root, fs);
        seed(repo);
        fs.failListDir = true;

        assertThat(repo.listAllRich(null, null)).isEmpty();
    }

    @Test
    void statFailureRetriesThenIncludes() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-stat-retry");
        var fs = new FaultyFs();
        var repo = new JsonlSessionRepository(root, fs);
        String id = seed(repo);
        fs.statFailures = 1;

        var infos = repo.listRich(CWD, null, null);

        assertThat(infos).extracting(RichSessionInfo::id).containsExactly(id);
        assertThat(fs.statCalls).isEqualTo(2);
    }

    @Test
    void statFailureTwiceExcludes() throws Exception {
        Path root = Files.createTempDirectory("pi-rich-stat-twice");
        var fs = new FaultyFs();
        var repo = new JsonlSessionRepository(root, fs);
        seed(repo);
        fs.statFailures = 2;

        assertThat(repo.listRich(CWD, null, null)).isEmpty();
        assertThat(fs.statCalls).isEqualTo(2);
    }
}

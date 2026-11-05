package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B274（docs/24 W4a/W4b）：会话持久化细节 —— append 失败不污染内存；
 * fork 用单次写（不逐条 append）。
 */
class JsonlPersistenceDetailsTest {

    @TempDir Path tmp;

    @Test
    void appendFailureLeavesMemoryAndLeafUntouched() throws Exception {
        var fs = new CountingFs();
        fs.failAppend = true;
        var repo = new JsonlSessionRepository(tmp, fs);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        var first = append(session, "m1", "one");
        fs.failAppend = false;

        fs.failAppend = true;
        assertThatThrownBy(() -> append(session, "m2", "two"))
            .as("写文件失败 ⇒ 抛出，且内存不提交")
            .isInstanceOf(java.io.UncheckedIOException.class);

        assertThat(session.findEntries(EntryQuery.all()))
            .extracting(Entry::id)
            .as("失败条目不在内存日志")
            .contains("m1")
            .doesNotContain("m2");

        fs.failAppend = false;
        var third = append(session, "m3", "three");
        assertThat(third.parentId())
            .as("失败条目没有成为后续条目的 parent")
            .isEqualTo(first.id());
    }

    @Test
    void forkPublishesWithOneWriteAndNoPerEntryAppend() throws Exception {
        var fs = new CountingFs();
        var repo = new JsonlSessionRepository(tmp, fs);
        var session = repo.create(new JsonlSessionCreateOptions(null, "cwd", null, null));
        append(session, "m1", "one");
        append(session, "m2", "two");

        var source = repo.list(JsonlSessionListOptions.all()).getFirst();
        int appendsBefore = fs.appendFileCalls;
        Session<?> forked = repo.fork(source, new ForkOptions.Tree(),
            new JsonlSessionCreateOptions(null, "cwd", null, null));

        assertThat(fs.appendFileCalls)
            .as("fork 不再逐条 append（B274 W4b）")
            .isEqualTo(appendsBefore);
        assertThat(forked.findEntries(
            new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null)))
            .extracting(Entry::id).containsExactlyInAnyOrder("m1", "m2");
    }

    private static Entry append(Session<?> session, String id, String text) {
        return session.appendEntry(new ProvisionedEntry<>(new Entry.Message(
            id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null)), "main");
    }

    /** 委托真实 fs，记录写调用并在需要时让 append 失败。 */
    static final class CountingFs implements JsonlSessionRepoFileSystem {
        private final DefaultJsonlFileSystem delegate = new DefaultJsonlFileSystem();
        int appendFileCalls;
        int writeFileCalls;
        boolean failAppend;

        @Override public String absolutePath(String path) { return delegate.absolutePath(path); }
        @Override public String joinPath(List<String> parts) { return delegate.joinPath(parts); }
        @Override public String readTextFile(Path path) { return delegate.readTextFile(path); }
        @Override public List<String> readTextLines(Path path, int maxLines) {
            return delegate.readTextLines(path, maxLines);
        }
        @Override public void writeFile(Path path, String content) {
            writeFileCalls++;
            delegate.writeFile(path, content);
        }
        @Override public void appendFile(Path path, String content) {
            appendFileCalls++;
            if (failAppend) {
                throw new java.io.UncheckedIOException(
                    new java.io.IOException("injected append failure"));
            }
            delegate.appendFile(path, content);
        }
        @Override public void renameFile(Path from, Path to) { delegate.renameFile(from, to); }
        @Override public long fileInfoMtimeMs(Path path) { return delegate.fileInfoMtimeMs(path); }
        @Override public List<DirEntry> listDir(Path path) { return delegate.listDir(path); }
        @Override public boolean exists(Path path) { return delegate.exists(path); }
        @Override public void createDir(Path path, boolean recursive) {
            delegate.createDir(path, recursive);
        }
        @Override public void remove(Path path, boolean force) { delegate.remove(path, force); }
    }
}

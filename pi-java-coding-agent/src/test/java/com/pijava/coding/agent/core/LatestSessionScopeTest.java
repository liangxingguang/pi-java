package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.session.jsonl.DefaultJsonlFileSystem;
import com.pijava.agent.session.jsonl.JsonlSessionRepoFileSystem;
import com.pijava.agent.session.jsonl.JsonlSessionStorage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 台账 A12（docs/39）：{@code latest(cwd)} 只扫**当前项目**的会话目录。
 *
 * <p>夹具钉的是「打开了几下文件」这个**确定性计数**，不是计时 —— 生产里这一步
 * 就是根因：全量扫描每个项目目录、每个 {@code .jsonl} 打开一次读首行，成本随
 * 全部会话文件数线性增长（docs/39 §2/§3）。</p>
 *
 * <p>「本项目较旧、别的项目较新」是这个夹具的关键：全量取最新会**恢复别人的
 * 会话**，这既慢又是行为偏差（pi 的 {@code continueRecent} 只看一个项目目录）。</p>
 */
class LatestSessionScopeTest {

    private static final String LANE = AgentHarness.DEFAULT_LANE;

    /** 本项目 cwd —— 生产里同样取自 {@code user.dir}。 */
    private static final String CWD = System.getProperty("user.dir");

    /** 另外两个项目的 cwd：本模块的兄弟目录（只在临时根下建目录，不碰真实路径）。 */
    private static final String OTHER_A = Path.of(CWD).resolveSibling("pi-java-other-a").toString();
    private static final String OTHER_B = Path.of(CWD).resolveSibling("pi-java-other-b").toString();

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    /** 一个已落盘的会话：id、那条消息的 entry id、文件路径。 */
    private record Seeded(String id, String entryId, Path path) {}

    /** 夹具：3 个项目目录 × 2 个会话，{@code mine} 是本项目的两个。 */
    private record Fixture(List<Seeded> mine, List<Seeded> others) {}

    /** 造 6 个会话（3 个项目 × 2 个），并把 mtime 钉成 a1 &lt; a2 &lt; b1 … &lt; c2。 */
    private static Fixture threeProjects(Path root) throws IOException {
        List<Seeded> mine = List.of(seed(root, CWD, "a1"), seed(root, CWD, "a2"));
        List<Seeded> others = List.of(
            seed(root, OTHER_A, "b1"), seed(root, OTHER_A, "b2"),
            seed(root, OTHER_B, "c1"), seed(root, OTHER_B, "c2"));

        // mtime 由夹具钉死，**不能**靠「先建谁后建谁」：同一毫秒内创建会让
        // 「全局最新」变成掷骰子，变异探针就没有牙了（本项目纪律：确定性代理量）。
        List<Seeded> all = new ArrayList<>(mine);
        all.addAll(others);
        for (int i = 0; i < all.size(); i++) {
            Files.setLastModifiedTime(all.get(i).path(), FileTime.from(BASE.plusSeconds(i)));
        }
        return new Fixture(mine, others);
    }

    /** 在 {@code root} 下按 {@code cwd} 建一个带一条消息的会话。 */
    private static Seeded seed(Path root, String cwd, String text) throws IOException {
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var session = handle.create(cwd, null);
            session.createLane(LANE, null);
            String entryId = UUID.randomUUID().toString();
            session.appendEntry(new ProvisionedEntry<>(new Entry.Message(entryId, 0, null, null,
                new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null)), LANE);
            return new Seeded(session.getMetadata().id(), entryId, fileOf(session));
        } finally {
            handle.close();
        }
    }

    private static Path fileOf(com.pijava.agent.session.Session<?> session) {
        if (!(session.storage() instanceof JsonlSessionStorage storage)) {
            throw new IllegalStateException("fixture expects the JSONL backend");
        }
        return storage.path();
    }

    private static List<String> fileNames(List<Seeded> seeded) {
        return seeded.stream().map(s -> s.path().getFileName().toString()).toList();
    }

    @Test
    void latestOpensOnlyThisProjectsSessionFiles() throws Exception {
        Path root = Files.createTempDirectory("pi-latest-scope");
        var fixture = threeProjects(root);
        var counting = new CountingFileSystem();

        var handle = PersistentSessionRepositories.jsonl(root, counting);
        try {
            assertThat(handle.latest(CWD)).isPresent();
        } finally {
            handle.close();
        }

        // 本项目 2 个文件被打开，另外 4 个一眼都不看。
        assertThat(counting.readFileNames())
            .containsExactlyInAnyOrderElementsOf(fileNames(fixture.mine()));
    }

    @Test
    void latestPrefersThisProjectsSessionOverANewerOneElsewhere() throws Exception {
        Path root = Files.createTempDirectory("pi-latest-scope-newest");
        var fixture = threeProjects(root);

        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var latest = handle.latest(CWD).orElseThrow();
            // 全局最新其实是 c2（别的项目），恢复的必须是本项目较新的那个 a2。
            assertThat(latest.id()).isEqualTo(fixture.mine().get(1).id());
            assertThat(latest.id()).isNotIn(fixture.others().stream().map(Seeded::id).toList());
        } finally {
            handle.close();
        }
    }

    @Test
    void resumeAttachesToThisProjectsSessionNotANewerOneElsewhere() throws Exception {
        Path root = Files.createTempDirectory("pi-latest-scope-resume");
        var fixture = threeProjects(root);

        // 走生产路径：createWeb ⇒ resolvePersistentWeb ⇒ handle.latest(cwd)。
        var resumed = AgentSession.createWeb(ArgsParser.parse(new String[] {
            "--session-dir", root.toString()}));
        try {
            assertThat(resumed.accumulatedEntries()).extracting(Entry::id)
                .containsExactly(fixture.mine().get(1).entryId());
        } finally {
            resumed.close();
        }
    }

    /**
     * 计数用的文件系统装饰器：只统计「打开文件读首行」的次数。
     *
     * <p>这正是 A12 的成本单位（{@code readTextLines(path, 1)} 每次一个文件），
     * 用它替代计时断言，负载下也不会 flake。</p>
     */
    private static final class CountingFileSystem implements JsonlSessionRepoFileSystem {

        private final JsonlSessionRepoFileSystem delegate = new DefaultJsonlFileSystem();
        private final List<String> readFileNames = new ArrayList<>();

        List<String> readFileNames() {
            return List.copyOf(readFileNames);
        }

        @Override
        public List<String> readTextLines(Path path, int maxLines) {
            readFileNames.add(path.getFileName().toString());
            return delegate.readTextLines(path, maxLines);
        }

        @Override
        public String absolutePath(String path) {
            return delegate.absolutePath(path);
        }

        @Override
        public String joinPath(List<String> parts) {
            return delegate.joinPath(parts);
        }

        @Override
        public String readTextFile(Path path) {
            return delegate.readTextFile(path);
        }

        @Override
        public void writeFile(Path path, String content) {
            delegate.writeFile(path, content);
        }

        @Override
        public void appendFile(Path path, String content) {
            delegate.appendFile(path, content);
        }

        @Override
        public void renameFile(Path from, Path to) {
            delegate.renameFile(from, to);
        }

        @Override
        public long fileInfoMtimeMs(Path path) {
            return delegate.fileInfoMtimeMs(path);
        }

        @Override
        public List<DirEntry> listDir(Path path) {
            return delegate.listDir(path);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public void createDir(Path path, boolean recursive) {
            delegate.createDir(path, recursive);
        }

        @Override
        public void remove(Path path, boolean force) {
            delegate.remove(path, force);
        }
    }
}

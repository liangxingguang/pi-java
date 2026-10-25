package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.cli.ArgsParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 22（T1，W15）：{@code AgentSession.listSessionsSummary(cwd, progress,
 * signal)} 渐进重载——pi {@code SessionManager.list(path, onProgress, signal)}
 * 对位（publish 间隔 10 条：25 个文件 ⇒ ≥2 个带快照的发布点）。
 */
class SessionListProgressOverloadTest {

    private static final String CWD = System.getProperty("user.dir");

    @TempDir
    Path root;

    private static void seed(Path root, String text) throws Exception {
        var repo = JsonlSessionRepository.over(root);
        var session = repo.create(new JsonlSessionCreateOptions(null, CWD, null, null));
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message(
            UUID.randomUUID().toString(), 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null)),
            "main");
        session.storage().drain();
    }

    private AgentSession session() {
        return AgentSession.create(ArgsParser.parse(
            new String[] {"--session-dir", root.toString()}));
    }

    @Test
    void progressPublishesSnapshotsAtIntervals() throws Exception {
        int seeded = 25;
        for (int i = 0; i < seeded; i++) {
            seed(root, "session " + i);
        }
        try (var s = session()) {
            var frames = new CopyOnWriteArrayList<int[]>();
            var snapshotSizes = new CopyOnWriteArrayList<Integer>();
            var result = s.listSessionsSummary(CWD, (loaded, total, partials) -> {
                frames.add(new int[] {loaded, total});
                if (partials != null) {
                    snapshotSizes.add(partials.size());
                }
            }, null);
            // 被测会话自身也在同一 session-dir 落盘 ⇒ 总数 = seeded + 1。
            assertThat(result).hasSize(seeded + 1);
            // pi：每条都回调 loaded/total；发布点（每 10 条）携快照。
            assertThat(frames).isNotEmpty();
            assertThat(frames).allSatisfy(f -> {
                assertThat(f[0]).isBetween(1, seeded + 1);
                assertThat(f[1]).isEqualTo(seeded + 1);
            });
            assertThat(snapshotSizes).isNotEmpty()
                .allSatisfy(size -> assertThat(size).isBetween(1, seeded + 1));
        }
    }

    @Test
    void abortedSignalStopsListing() throws Exception {
        for (int i = 0; i < 30; i++) {
            seed(root, "session " + i);
        }
        try (var s = session()) {
            var signal = AbortSignal.create();
            var frames = new CopyOnWriteArrayList<Integer>();
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                s.listSessionsSummary(CWD, (loaded, total, partials) -> {
                    frames.add(loaded);
                    signal.abort();   // 首帧即中止（pi throwIfAborted 对位）
                }, signal))
                .hasMessageContaining("bort");   // Aborted 文案形（AbortException）
            assertThat(frames).hasSize(1);
        }
    }

    @Test
    void noProgressArmUnchanged() throws Exception {
        seed(root, "only");
        try (var s = session()) {
            // +1＝被测会话自身（同根落盘）。
            assertThat(s.listSessionsSummary(CWD)).hasSize(2);
            assertThat(s.listSessionsSummary(CWD, null, null)).hasSize(2);
        }
    }
}

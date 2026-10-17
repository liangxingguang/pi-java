package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.session.jsonl.JsonlSessionStorage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B53（docs/24 §10）：会话 {@code find()} 分级阶梯 —— pi {@code resolveSessionPath}
 * （main.ts:243-283）对位：① 项目内 header-only 精确 → ② 项目内前缀 →
 * ③ 跨项目**精确优先于前缀**；⓪ 路径快道另案（{@code openPath}）。
 */
class SessionFindLadderTest {

    private static final String LANE = AgentHarness.DEFAULT_LANE;
    private static final String CWD = System.getProperty("user.dir");
    private static final String OTHER = Path.of(CWD).resolveSibling("pi-java-ladder-other").toString();
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    /** 在 {@code root} 下按 {@code cwd} 建一个带固定 id 的会话，返回文件路径。 */
    private static Path seed(Path root, String cwd, String id, int mtimeSeconds) throws IOException {
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var repo = (com.pijava.agent.session.jsonl.JsonlSessionRepository) handle.repository();
            var session = repo.create(new com.pijava.agent.session.jsonl.JsonlSessionCreateOptions(
                id, cwd, null, null));
            session.createLane(LANE, null);
            session.appendEntry(new ProvisionedEntry<>(new Entry.Message(
                UUID.randomUUID().toString(), 0, null, null,
                new Message.UserMessage(List.of(new ContentBlock.TextContent("x"))), null)), LANE);
            session.storage().drain();
            Path path = ((JsonlSessionStorage) session.storage()).path();
            Files.setLastModifiedTime(path, FileTime.from(BASE.plusSeconds(mtimeSeconds)));
            return path;
        } finally {
            handle.close();
        }
    }

    @Test
    void exactIdInProjectWinsViaTheHeaderOnlyLane() throws Exception {
        Path root = Files.createTempDirectory("pi-ladder-exact");
        seed(root, CWD, "same-prefix-aaa", 1);
        seed(root, OTHER, "other-bbb", 2);
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var found = handle.find("same-prefix-aaa");
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo("same-prefix-aaa");
        } finally {
            handle.close();
        }
    }

    @Test
    void projectPrefixBeatsCrossProjectMatches() throws Exception {
        // ② 项目内前缀先于 ③ 跨项目：同名前缀两边都有 ⇒ 本项目的赢。
        Path root = Files.createTempDirectory("pi-ladder-prefix");
        seed(root, CWD, "shared-111-mine", 1);
        seed(root, OTHER, "shared-111-other", 2);
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var found = handle.find("shared-111");
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo("shared-111-mine");
        } finally {
            handle.close();
        }
    }

    @Test
    void crossProjectExactBeatsCrossProjectPrefix() throws Exception {
        // ③ 跨项目：精确优先于前缀（pi 的 `exact ?? prefix`）—— 即使前缀候选更新、
        // 排在列表前面，也必须返回精确命中的那个。
        Path root = Files.createTempDirectory("pi-ladder-cross");
        seed(root, OTHER, "target", 1);          // 精确候选（较旧）
        seed(root, OTHER, "target-longer", 2);   // 前缀候选（较新，列表排前）
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var found = handle.find("target");
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo("target");
        } finally {
            handle.close();
        }
    }

    @Test
    void pathFastLaneOpensTheFileDirectly() throws Exception {
        // ⓪ pi main.ts:253-256：路径形入参直接开文件，不走阶梯。
        Path root = Files.createTempDirectory("pi-ladder-path");
        Path file = seed(root, OTHER, "by-path", 1);
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var opened = handle.openPath(file);
            assertThat(opened).isPresent();
            assertThat(opened.get().getMetadata().id()).isEqualTo("by-path");
            assertThat(handle.openPath(root.resolve("does-not-exist.jsonl"))).isEmpty();
        } finally {
            handle.close();
        }
    }

    @Test
    void blankQueryFindsNothing() throws Exception {
        Path root = Files.createTempDirectory("pi-ladder-blank");
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            assertThat(handle.find(null)).isEmpty();
            assertThat(handle.find("   ")).isEmpty();
        } finally {
            handle.close();
        }
    }
}

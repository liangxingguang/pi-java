package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存态分支：新会话持**自己的** harness，日志从父会话播种（{@code docs/31 §4.3}）。
 *
 * <p>删除运行时多车道容器之前，这里靠「共享父 harness + 新建一条**空** lane」实现，
 * 分支会话拿不到任何历史（{@code LaneConfig.parentLeafId} 只写不读）。现在分支是会话层的
 * 事，与持久化路径同形：{@code harness.fork()} + {@code seedTranscript}。</p>
 */
class SessionForkTest {

    private static final String LANE = AgentHarness.DEFAULT_LANE;

    private static Entry.Message userEntry(String id, String parentId, String text) {
        return new Entry.Message(id, 0, parentId, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    private static AgentSession session(InMemorySessionRepository repo, Entry.Message... entries) {
        var session = AgentSession.create(
            ArgsParser.parse(new String[] {"--session-id", "src"}), repo);
        session.harness().seedTranscript(LANE, List.of(entries));
        return session;
    }

    @Test
    void forkCopySeedsTheWholeParentLogIntoAnIndependentHarness() {
        var repo = InMemorySessionRepository.create();
        try (var parent = session(repo, userEntry("e1", null, "one"))) {
            var forked = parent.forkCopy("branch");

            assertThat(forked.harness()).as("分支要有自己的 harness").isNotSameAs(parent.harness());
            assertThat(forked.laneName()).isEqualTo(LANE);
            assertThat(forked.entryCount()).as("分支继承父会话的历史").isEqualTo(1);
            assertThat(forked.harness().snapshot(LANE).transcript())
                .isEqualTo(parent.harness().snapshot(LANE).transcript());
            assertThat(parent.entryCount()).as("父会话不受影响").isEqualTo(1);
            assertThat(repo.find("src")).contains(parent);
        }
    }

    /**
     * {@code forkFromEntry} 在 entry **之前**截断（pi {@code position:"before"}，
     * {@code fork-policy.ts:25-27}）：请求的那条自己不入选。
     */
    @Test
    void forkFromEntrySeedsThePathUpToButNotIncludingTheEntry() {
        var repo = InMemorySessionRepository.create();
        try (var parent = session(repo,
                userEntry("e1", null, "one"),
                userEntry("e2", "e1", "two"),
                userEntry("e3", "e2", "three"))) {
            var forked = parent.forkFromEntry("e2");

            assertThat(forked.entryCount()).as("只到 e2 的父节点 e1").isEqualTo(1);
            assertThat(forked.harness().snapshot(LANE).transcript().get(0).id()).isEqualTo("e1");
        }
    }

    /** 不在本会话分支上的 entry 必须炸 —— pi 同样拒绝（{@code "not on source branch"}）。 */
    @Test
    void forkFromUnknownEntryIsRejected() {
        var repo = InMemorySessionRepository.create();
        try (var parent = session(repo, userEntry("e1", null, "one"))) {
            assertThatThrownBy(() -> parent.forkFromEntry("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not on this session's branch");
        }
    }
}

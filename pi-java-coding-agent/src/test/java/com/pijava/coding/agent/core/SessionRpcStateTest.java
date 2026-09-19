package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包④：{@code get_state} 三个新字段的取值来源（{@code docs/31 §8.37}）。
 *
 * <p>线格式那一侧由 {@code RpcDispatcherTest} 钉；这里钉宿主读取口本身 ——
 * 特别是 {@link AgentSession#sessionFile()} 的**两条路**：pi 的类型是
 * {@code string | undefined}（{@code agent-session.ts:1008-1010}），只有 JSONL
 * 后端取得到路径，临时会话必须回 {@code null}（线格式上省略该键）。</p>
 */
class SessionRpcStateTest {

    @Test
    void sessionFileIsAbsentForEphemeralSessions() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-state-cwd");
        try (var session = createEphemeral(tmp)) {
            assertThat(session.sessionFile())
                .as("--no-session 无落盘路径 ⇒ null（线格式省略 sessionFile 键）")
                .isNull();
            assertThat(session.sessionId())
                .as("无持久会话时回退显示名")
                .isEqualTo("session");
        }
    }

    @Test
    void sessionFileIsTheJsonlPathOnceAPersistentSessionIsAttached() throws Exception {
        var root = Files.createTempDirectory("pi-java-state-root");
        var tmp = Files.createTempDirectory("pi-java-state-cwd");
        try (var session = createEphemeral(tmp)) {
            // 手工挂上 JSONL 持久会话（--no-session 跳过了 resolveSession，
            // 与 SessionFlushTimingTest 同法）。
            session.session(PersistentSessionRepositories.jsonl(root).create("cwd", null));

            assertThat(session.sessionFile()).isNotNull().endsWith(".jsonl");
            assertThat(session.sessionId())
                .as("有持久会话时取元数据 id，不再是显示名")
                .isNotEqualTo("session");
        }
    }

    @Test
    void pendingMessageCountCountsSteeringAndFollowUpButNotNextRun() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-state-cwd");
        try (var session = createEphemeral(tmp)) {
            var harness = session.harness();
            var lane = session.laneName();

            assertThat(session.pendingMessageCount()).isZero();

            harness.steer(lane, "steer me");
            assertThat(session.pendingMessageCount()).isEqualTo(1);

            harness.followUp(lane, "follow up");
            assertThat(session.pendingMessageCount()).isEqualTo(2);

            // 判别点：pi 的 pendingMessageCount = _steeringMessages.length +
            // _followUpMessages.length（agent-session.ts:1619-1621）—— nextRun
            // **不在**那两个数组里，加了必须是 2 而不是 3。
            harness.nextRun(lane, "next run");
            assertThat(session.pendingMessageCount())
                .as("nextRun 不计入 pendingMessageCount")
                .isEqualTo(2);
        }
    }

    @Test
    void isCompactingIsFalseAtRest() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-state-cwd");
        try (var session = createEphemeral(tmp)) {
            assertThat(session.isCompacting())
                .as("静止态无压缩在飞；真值侧由 CompactionInFlightTest 钉")
                .isFalse();
        }
    }

    /** {@code --no-session} 会话（不落盘、无持久仓库）。 */
    private static AgentSession createEphemeral(Path cwd) {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-state", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        return AgentSession.create(args, providers,
            new ToolContext(cwd.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }
}

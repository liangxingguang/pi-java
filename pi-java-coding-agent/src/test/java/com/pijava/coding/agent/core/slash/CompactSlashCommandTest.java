package com.pijava.coding.agent.core.slash;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.support.RecordingChatProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B174（{@code docs/23}）：{@code /compact <text>} 把 trailing 文本
 * trim 后作为 customInstructions 透传（pi {@code interactive-mode.ts:3263-3267}）。
 * 用真会话 + {@link RecordingChatProvider} 钉摘要请求的 prompt 文本。
 */
class CompactSlashCommandTest {

    @TempDir
    Path tmp;

    /**
     * 构造三轮会话：turn1 "first"、turn2 "second"、turn3 100k chars。
     * 尾部累加在第三轮 user（25k tokens）处达 keep 门；回吸跨过 Usage 条目
     * （投影 0 消息，pi compaction.ts:858-862 同形）⇒ 切点判为 split，
     * turnStart＝turn2 user，历史＝turn1 的 user/assistant，历史摘要非空。
     */
    private AgentSession sessionWithThreeTurns(RecordingChatProvider recording) throws Exception {
        var args = com.pijava.coding.agent.cli.ArgsParser.parse(
            new String[] {"--provider", recording.name(), "--model", "hello", "--no-session"});
        // FileSettingsStorage 在构造时捕获 user.home —— 窗口只包住 create
        // 以免碰到真实用户目录（同 RpcDispatcherTest 的既有处理）。
        var home = Files.createTempDirectory("compact-slash-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var session = AgentSession.create(args, recording.register(),
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
            session.processPrompt("first").statusFuture().get(10, TimeUnit.SECONDS);
            session.processPrompt("second").statusFuture().get(10, TimeUnit.SECONDS);
            session.processPrompt("x".repeat(100_000)).statusFuture().get(10, TimeUnit.SECONDS);
            return session;
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }

    @Test
    void trailingTextIsTrimmedAndPassedAsCustomInstructions() throws Exception {
        var recording = new RecordingChatProvider("capture");
        var session = sessionWithThreeTurns(recording);

        var result = CommandRegistry.withBuiltins()
            .dispatch("/compact Focus on tests", SlashContext.of(session))
            .toCompletableFuture().join();

        assertThat(result).isEqualTo("Compacted.");
        assertThat(recording.userTexts())
            .as("pi interactive-mode.ts:3264：trailing 文本进入历史摘要 prompt")
            .anyMatch(text -> text.startsWith("<conversation>")
                && text.endsWith("\n\nAdditional focus: Focus on tests"));
    }

    @Test
    void noTrailingTextPassesNull() throws Exception {
        var recording = new RecordingChatProvider("capture");
        var session = sessionWithThreeTurns(recording);

        var result = CommandRegistry.withBuiltins()
            .dispatch("/compact", SlashContext.of(session))
            .toCompletableFuture().join();

        assertThat(result).isEqualTo("Compacted.");
        assertThat(recording.userTexts())
            .as("无 trailing ⇒ 不带 Additional focus")
            .noneMatch(text -> text.contains("Additional focus"));
    }

    @Test
    void whitespaceTrailingTextPassesNull() throws Exception {
        var recording = new RecordingChatProvider("capture");
        var session = sessionWithThreeTurns(recording);

        var result = CommandRegistry.withBuiltins()
            .dispatch("/compact    ", SlashContext.of(session))
            .toCompletableFuture().join();

        assertThat(result).isEqualTo("Compacted.");
        assertThat(recording.userTexts())
            .as("trim 后为空 ⇒ null（pi slice+trim）")
            .noneMatch(text -> text.contains("Additional focus"));
    }
}

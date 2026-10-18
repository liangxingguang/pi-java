package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #75（docs/10 §5）：pi {@code --fork} 调用面 ——
 * <ul>
 *   <li>F-b：{@code --fork} 与 {@code --session-id} 组合时新会话用给定 id
 *       （pi {@code forkSessionOrExit(..., parsed.sessionId)}，main.ts:380-386）；
 *       pi 的 fork 支配 sessionId（fork 源解析支消费它），故 java 的旗标链
 *       fork 排在 sessionId 之前。</li>
 *   <li>F-c：pi 不给 fork 会话改名 —— 删除 java 私加的 {@code " (fork)"} 后缀。</li>
 * </ul>
 */
class SessionPersistenceForkTest {

    @TempDir Path tmp;

    private static List<StreamEvent> reply(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private AgentSession rig(Path sessionDir, String... extra) throws Exception {
        var base = new String[] {
            "--provider", "faux-fork", "--model", "hello",
            "--session-dir", sessionDir.toString()};
        var argv = new String[base.length + extra.length];
        System.arraycopy(base, 0, argv, 0, base.length);
        System.arraycopy(extra, 0, argv, base.length, extra.length);
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-fork", List.of(reply("r"), reply("r2"))));
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        return AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
    }

    @Test
    void forkHonorsSessionIdAndKeepsTheNameUntouched() throws Exception {
        Path dir = Files.createTempDirectory("pi-fork-cli");
        String sourceId;
        try (var source = rig(dir)) {
            source.processPrompt("hi").status();
            source.name("origin");
            sourceId = source.sessionId();
        }

        try (var forked = rig(dir, "--fork", sourceId, "--session-id", "fork-id-1")) {
            assertThat(forked.sessionId())
                .as("pi forkFrom(..., { id: sessionId })：新会话用给定 id")
                .isEqualTo("fork-id-1");
            assertThat(forked.accumulatedEntries())
                .as("fork 拷贝源会话条目").isNotEmpty();
            assertThat(String.valueOf(forked.sessionName()))
                .as("pi 不给 fork 会话改名 —— 没有 java 私加的 \" (fork)\" 后缀")
                .doesNotContain("(fork)");
        }
    }

    @Test
    void forkWithoutSessionIdStillGetsAFreshId() throws Exception {
        Path dir = Files.createTempDirectory("pi-fork-cli-plain");
        String sourceId;
        try (var source = rig(dir)) {
            source.processPrompt("hi").status();
            sourceId = source.sessionId();
        }

        try (var forked = rig(dir, "--fork", sourceId)) {
            assertThat(forked.sessionId())
                .as("不带 --session-id ⇒ 新 id，不撞源会话")
                .isNotEqualTo(sourceId);
            assertThat(forked.accumulatedEntries()).isNotEmpty();
        }
    }
}

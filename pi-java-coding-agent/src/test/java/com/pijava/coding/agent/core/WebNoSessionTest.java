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
 * 批 14（docs/25 T14，B54 销号，§7 #14）：web 路径 {@code --no-session} 赢
 * （裁决⑤）——ephemeral，无持久仓写入。行为本体在 coding-agent 的
 * {@code resolvePersistentWeb}（web 模块经 createWeb 到达），夹具随本体放 core。
 */
class WebNoSessionTest {

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

    private ProviderRegistry providers() {
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-webns", List.of(reply("r"))));
        return providers;
    }

    @Test
    void noSessionWinsOverWebPersistence() throws Exception {
        Path dir = Files.createTempDirectory("pi-web-ns");
        var toolContext = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        // 先种一个持久会话：无 --no-session 时 createWeb 会 resume 它
        var base = new String[] {
            "--provider", "faux-webns", "--model", "hello",
            "--session-dir", dir.toString()};
        try (var s1 = AgentSession.createWeb(ArgsParser.parse(base), providers(), toolContext)) {
            s1.processPrompt("hi").status();
        }

        // --no-session ⇒ ephemeral 赢：不 resume、不新建持久会话
        var withFlag = new String[base.length + 1];
        System.arraycopy(base, 0, withFlag, 0, base.length);
        withFlag[base.length] = "--no-session";
        try (var s2 = AgentSession.createWeb(ArgsParser.parse(withFlag), providers(), toolContext)) {
            assertThat(s2.session())
                .as("裁决⑤：--no-session 赢 ⇒ 无持久会话附着")
                .isNull();
        }

        // 持久仓仍只有最初一个会话文件（ephemeral 未落盘新会话）
        try (var walk = Files.walk(dir)) {
            assertThat(walk.filter(p -> p.toString().endsWith(".jsonl")).count())
                .isEqualTo(1);
        }
    }
}

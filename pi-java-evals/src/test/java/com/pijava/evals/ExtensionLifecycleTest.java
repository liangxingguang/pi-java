package com.pijava.evals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-4: 扩展生命周期 —— ServiceLoader 装配后工具/命令/Provider/Skill 在
 * AgentSession 中可见；--no-extensions 禁用发现。
 */
class ExtensionLifecycleTest {

    @TempDir
    Path tmp;

    @Test
    void extensionRegistersToolCommandProviderSkill() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "session-model", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("session-model",
            List.of(textStream("hi"))));

        try (var session = AgentSession.create(args, providers, toolContext())) {
            // 命令
            assertThat(session.services().slashCommands().get("hello")).isNotNull();
            // 技能
            assertThat(session.harness().skillManager().get("sample-skill")).isNotNull();
            // Provider（SampleExtension 注册的 FauxProvider）
            assertThat(session.services().providers().get("faux")).isNotNull();
            // 工具
            assertThat(session.services().tools().get("echo")).isNotNull();
        }
    }

    @Test
    void noExtensionsDisablesDiscovery() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "session-model", "--model", "hello", "--no-session",
            "--no-extensions"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("session-model",
            List.of(textStream("hi"))));

        try (var session = AgentSession.create(args, providers, toolContext())) {
            assertThat(session.services().slashCommands().get("hello")).isNull();
            assertThat(session.services().tools().get("echo")).isNull();
        }
    }

    private ToolContext toolContext() throws Exception {
        return new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    private static List<StreamEvent> textStream(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }
}

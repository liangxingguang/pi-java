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
import com.pijava.coding.agent.extension.TestExtensionJar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 14（docs/25 T15，A-25，§7 #13）：{@code -e/--extension} 显式 JAR 加载
 * （pi additionalExtensionPaths）＋ {@code -ne} 旁路（显式路径仍载）＋
 * {@code -ne} 裸早退不变。
 */
class ExtensionCliPathsTest {

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
            "--provider", "faux-ext", "--model", "hello",
            "--session-dir", sessionDir.toString()};
        var argv = new String[base.length + extra.length];
        System.arraycopy(base, 0, argv, 0, base.length);
        System.arraycopy(extra, 0, argv, base.length, extra.length);
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-ext", List.of(reply("r"))));
        var handle = PersistentSessionRepositories.jsonl(sessionDir);
        return AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
    }

    @Test
    void explicitExtensionJarLoads() throws Exception {
        var jar = TestExtensionJar.build(tmp.resolve("ext/test-ext.jar"));
        var dir = Files.createTempDirectory("pi-ext-cli");

        try (var s = rig(dir, "-e", jar.toString())) {
            assertThat(s.services().slashCommands().get("sample"))
                .as("pi additionalExtensionPaths：显式 -e jar 加载")
                .isNotNull();
        }
    }

    @Test
    void explicitJarStillLoadsUnderNoExtensions() throws Exception {
        // pi resource-loader.ts:578/690：noExtensions ? cliEnabledExtensions : merge(...)
        // ——显式路径旁路 -ne。
        var jar = TestExtensionJar.build(tmp.resolve("ext-ne/test-ext.jar"));
        var dir = Files.createTempDirectory("pi-ext-cli-ne");

        try (var s = rig(dir, "-ne", "-e", jar.toString())) {
            assertThat(s.services().slashCommands().get("sample"))
                .as("-ne 旁路：显式路径仍载（扩展事件面随加载活着）")
                .isNotNull();
        }
    }

    @Test
    void bareNoExtensionsStillSkipsEverything() throws Exception {
        var dir = Files.createTempDirectory("pi-ext-cli-bare");

        try (var s = rig(dir, "-ne")) {
            assertThat(s.services().slashCommands().get("sample"))
                .as("-ne 裸早退臂不变")
                .isNull();
        }
    }
}

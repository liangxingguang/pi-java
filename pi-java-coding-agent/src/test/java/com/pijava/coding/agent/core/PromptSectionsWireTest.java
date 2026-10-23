package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_AGENT_START;

/**
 * 批 16（T3，docs/27 §7）：提示分节接线——扩展 {@code before_agent_start} 写
 * {@code sections} ⇒ 写回 harness ⇒ 下一 run 的装配消费（pi
 * system-prompt.ts:127-229 链路；java 的段名包装/diff 补丁侧既有）。
 *
 * <p>段补丁消息进转录的 diff 半区在 agent-core 测
 * （{@code ContextAssemblerSectionsTest}）；这里测「事件对象暴露可变 sections →
 * 写回装配输入」这条本批新链接。</p>
 */
class PromptSectionsWireTest {

    /** 处理器观察记录（序/可见性断言用）。 */
    static final List<String> OBSERVED = new CopyOnWriteArrayList<>();

    /** 探针扩展：两处理器——前者写段，后者应看到前者的改动（pi runner.ts:1425-1471）。 */
    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "sections-probe"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(BEFORE_AGENT_START, (e, c) -> {
                OBSERVED.add("first sees: " + e.sections());
                e.sections().put("mcp_servers", "server: probe");
                return null;
            });
            ctx.on(BEFORE_AGENT_START, (e, c) -> {
                // pi「同一 options 对象贯穿所有处理器」逐字：后者见前者改动。
                OBSERVED.add("second sees: " + e.sections().get("mcp_servers"));
                return null;
            });
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        OBSERVED.clear();
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Probe.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
    }

    @AfterEach
    void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousTccl);
    }

    private AgentSession session() {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux",
            List.of(reply("one"), reply("two"), reply("three"))));
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
    }

    private static List<com.pijava.ai.stream.StreamEvent> reply(String text) {
        var done = com.pijava.ai.message.AssistantMessage.empty()
            .withContent(List.of(new com.pijava.ai.message.ContentBlock.TextContent(text)))
            .withStopReason(com.pijava.ai.message.StopReason.STOP);
        return List.of(
            new com.pijava.ai.stream.StreamEvent.Start(
                com.pijava.ai.message.AssistantMessage.empty()),
            new com.pijava.ai.stream.StreamEvent.TextEnd(0, text, done),
            new com.pijava.ai.stream.StreamEvent.StreamDone(
                com.pijava.ai.message.StopReason.STOP, null, done));
    }

    /** 扩展写段 ⇒ 写回 harness（下一 run 装配的输入），处理器间可见性逐字。 */
    @Test
    void extensionSectionsWriteBackToHarness() throws Exception {
        try (var s = session()) {
            s.processPrompt("hi").status();

            assertThat(OBSERVED).containsExactly(
                "first sees: {}", "second sees: server: probe");
            assertThat(s.harness().getPromptSections())
                .containsEntry("mcp_servers", "server: probe");
        }
    }

    /** 写回跨 run 存活：第二跑的基线含第一跑的终值（pi 的转录重放基线对位的 java 形）。 */
    @Test
    void sectionsPersistAcrossRuns() throws Exception {
        try (var s = session()) {
            s.processPrompt("hi").status();
            OBSERVED.clear();
            s.processPrompt("again").status();

            assertThat(OBSERVED.get(0))
                .as("第二跑基线＝第一跑终值").isEqualTo("first sees: {mcp_servers=server: probe}");
        }
    }
}

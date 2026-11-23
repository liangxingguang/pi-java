package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
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

/**
 * L5 W6（pi agent-session.ts:1614-1617/482）：{@code _restoredToolNames}——减员
 * 只在被去掉的名属于恢复 loadout 时清 pending；扩展隐藏自己的工具不连带丢掉
 * 尚未注册的 MCP 工具。
 */
class RestoredToolLoadoutTest {

    static final String OWN_TOOL = "w6-own-tool";

    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "w6-restore-probe"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.tools().register(new AgentTool<Map<String, Object>, Void>() {
                @Override public String name() { return OWN_TOOL; }
                @Override public String label() { return OWN_TOOL; }
                @Override public String description() { return "own probe"; }
                @Override public Map<String, Object> inputSchema() {
                    return Map.of("type", "object");
                }
                @Override public ExecutionMode executionMode() {
                    return new ExecutionMode.Parallel();
                }
                @Override public ToolResult<Void> execute(String toolCallId,
                        Map<String, Object> params, AbortSignal signal,
                        ToolUpdateCallback<Void> onUpdate, ToolContext context) {
                    return ToolResult.success("done");
                }
            });
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
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
        providers.register(FauxProvider.text("ok"));
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
    }

    @Test
    void removingAToolOutsideTheRestoredLoadoutKeepsPending() {
        try (var s = session()) {
            var pending = s.harness().pendingToolNames();
            pending.add("mcp__late-srv__tool");

            // 恢复 loadout 只含 read；OWN_TOOL 是恢复后新注册的扩展工具。
            s.modelTools().markRestoredToolNames(List.of("read"));
            s.setActiveToolsByName(List.of("read"));
            assertThat(pending).as("去掉 loadout 之外的新工具不清 pending")
                .contains("mcp__late-srv__tool");

            // OWN_TOOL 若属于恢复 loadout，再减员即清 pending（pi :1614-1617）。
            s.setActiveToolsByName(List.of("read", OWN_TOOL));
            s.modelTools().markRestoredToolNames(List.of("read", OWN_TOOL));
            pending.add("mcp__late-srv__tool");
            s.setActiveToolsByName(List.of("read"));
            assertThat(pending).as("去掉恢复 loadout 内的名 ⇒ 清 pending").isEmpty();
        }
    }
}

package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.builtin.BashTool;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * B286：扩展以同名 {@code bash} 覆盖内置工具（带自有执行后端）后，注册表里生效的是
 * 覆盖件——证明「{@code wireSession} 先注册内置八件、扩展后加载」的覆盖序
 * （{@code AgentSessionFactory.java:377-385} ＋ {@code ToolRegistry} 的 {@code put}）。
 */
class ExtensionBashOverrideTest {

    private static final CopyOnWriteArrayList<String> SEEN = new CopyOnWriteArrayList<>();

    /** ServiceLoader 无参构造；把 bash 换成「docker 风格」的假后端。 */
    public static final class BashOverrider implements PiExtension {

        public BashOverrider() {}

        @Override public String name() { return "bash-overrider"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.tools().register(BashTool.create(null, true, (command, options) -> {
                SEEN.add(command);
                return new ShellResult("docker:" + command, 0, false, false, 1, 1);
            }));
        }
    }

    @TempDir Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        SEEN.clear();
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            BashOverrider.class.getName() + "\n");
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

    @Test
    void extensionSameNameBashToolWinsOverBuiltin() throws Exception {
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session", "--no-mcp"});
        // 本地 shell 一旦被执行即失败：证明命令确实走了扩展后端。
        var context = new ToolContext(tmp.toString(), Map.of(),
            (command, options) -> {
                throw new AssertionError("builtin bash must not run: " + command);
            }, new DefaultFileSystem());

        try (var session = AgentSession.create(
                args, InMemorySessionRepository.create(), providers, context)) {
            var bash = session.services().tools().get("bash");
            assertThat(bash.name()).isEqualTo("bash");
            @SuppressWarnings("unchecked")
            var tool = (AgentTool<BashTool.BashInput, BashTool.BashDetails>) bash;

            var result = tool.execute("id", new BashTool.BashInput("echo hi", Optional.empty()),
                null, null, session.harness().toolContext());

            assertThat(SEEN).containsExactly("echo hi");
            assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
                .isEqualTo("docker:echo hi");
        }
    }
}

package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;

/**
 * L5 W5（pi {@code rpc-mode.ts:754-758}）：扩展在 {@code session_start} 里调
 * {@code ctx.shutdown()} ⇒ 当前命令处理完、会话空闲后 RPC 循环自行退出
 * （不依赖 stdin EOF，也不在 handler 里直接 {@code return 1}）。
 */
class RpcIdleShutdownTest {

    /** 启动期就请求关停的探针（ctx.shutdown ⇒ closeForReplacement("quit")）。 */
    public static final class Quitter implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Quitter() {}

        @Override public String name() { return "rpc-idle-quitter"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(SESSION_START, (e, c) -> {
                c.shutdown();
                return null;
            });
        }
    }

    @TempDir
    Path tmp;

    /** TCCL 作用域注入 Quitter（仅第一个用例需要；批 4/5 夹具先例）。 */
    private ClassLoader installScopedLoader() throws Exception {
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Quitter.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previous));
        return previous;
    }

    private static List<StreamEvent> reply(String text) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    /** session_start 里 shutdown ⇒ 发一条命令后循环退出（stdin 不关也不阻塞）。 */
    @Test
    void ctxShutdownInSessionStartLeavesAfterTheCommand() throws Exception {
        var previousTccl = installScopedLoader();
        try {
            driveShutdownScenario();
        } finally {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
    }

    private void driveShutdownScenario() throws Exception {
        var args = ArgsParser.parse(new String[] {"--session-dir", tmp.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-idle", List.of(reply("ok"))));
        var toolContext = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
        var stdinOut = new PipedOutputStream();
        var stdinIn = new PipedInputStream(stdinOut);
        var stdout = new ByteArrayOutputStream();
        var exitCode = new AtomicInteger(-1);

        var thread = Thread.startVirtualThread(() ->
            exitCode.set(RpcMode.run(stdinIn, stdout, args, providers, toolContext)));
        stdinOut.write("{\"id\":\"1\",\"type\":\"get_state\"}\n"
            .getBytes(StandardCharsets.UTF_8));
        stdinOut.flush();

        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && thread.isAlive()) {
            Thread.sleep(10);
        }
        assertThat(thread.isAlive())
            .as("空闲 shutdown 后循环自行退出（不等 EOF）").isFalse();
        assertThat(exitCode.get()).isEqualTo(0);
        assertThat(stdout.toString(StandardCharsets.UTF_8))
            .as("退出前已处理该命令").contains("\"command\":");
        stdinOut.close();
    }

    /** shutdownRequested 跟随会话关闭；awaitIdle 在空闲会话上立即返回。 */
    @Test
    void shutdownRequestedTracksTheSession() {
        var args = ArgsParser.parse(new String[] {"--session-dir", tmp.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-idle", List.of(reply("ok"))));
        var toolContext = new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
        try (var session = com.pijava.coding.agent.core.AgentSession.create(
                args, providers, toolContext)) {
            var dispatcher = new RpcDispatcher(session, new JsonlWriter(new ByteArrayOutputStream()),
                args);
            assertThat(dispatcher.shutdownRequested()).isFalse();
            dispatcher.awaitIdle();
            session.close();
            assertThat(dispatcher.shutdownRequested()).isTrue();
        }
    }
}

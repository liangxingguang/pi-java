package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.harness.HarnessClosedException;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_SHUTDOWN;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;

/**
 * docs/16（T3/B252）：RPC 退出面 —— EOF（{@code dispatcher.close()}）关闭
 * <b>当前</b>会话（pi rpc-mode.ts:736 {@code runtimeHost.dispose()} 对位），
 * shutdown{quit} 自此在 RPC 退出路径发射；替换过的 run 里关的是新会话，
 * 旧会话已在替换路径关过（幂等门下 try-with-resources 的双关 no-op）。
 */
class RpcExitCloseTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 录制扩展：start/shutdown（退出序列断言用；TCCL 作用域注入，批 4/5 先例）。 */
    public static final class Recorder implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Recorder() {}

        @Override public String name() { return "rpc-exit-recorder"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(SESSION_START, (e, c) -> {
                EVENTS.add("start:" + e.reason() + ":" + e.previousSessionFile());
                return null;
            });
            ctx.on(SESSION_SHUTDOWN, (e, c) -> {
                EVENTS.add("shutdown:" + e.reason() + ":" + e.targetSessionFile());
                return null;
            });
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Recorder.class.getName() + "\n");
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

    private static List<StreamEvent> reply(String text) {
        var done = com.pijava.ai.message.AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(com.pijava.ai.message.StopReason.STOP);
        return List.of(
            new StreamEvent.Start(com.pijava.ai.message.AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(com.pijava.ai.message.StopReason.STOP, null, done));
    }

    /** ③：EOF ⇒ dispatcher 当前（替换后）会话关闭；原始会话的双关在幂等门下 no-op。 */
    @Test
    void eofClosesTheCurrentSessionAfterReplacement() throws Exception {
        var args = ArgsParser.parse(new String[] {"--session-dir", tmp.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-exit", List.of(reply("ok"))));
        var original = AgentSession.create(args, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(original, new JsonlWriter(out), args);
        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"new_session\"}");
        EVENTS.clear();

        // EOF：pi rpc-mode.ts:736 —— dispose 当前会话。
        dispatcher.close();

        assertThat(EVENTS).as("shutdown{quit} 在 RPC 退出路径发射（当前会话侧）")
            .containsExactly("shutdown:quit:null");
        assertThatThrownBy(() -> original.harness().prompt("x"))
            .as("原始会话已在 new_session 替换路径关闭")
            .isInstanceOf(HarnessClosedException.class);
        // RpcMode try-with-resources 关原始会话：幂等门下零追加（裁决⑥）。
        original.close();
        assertThat(EVENTS).containsExactly("shutdown:quit:null");
    }
}

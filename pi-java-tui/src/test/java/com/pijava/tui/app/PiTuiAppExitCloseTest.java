package com.pijava.tui.app;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.harness.HarnessClosedException;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_SHUTDOWN;

/**
 * docs/16（T3/B252）：TUI 退出面 —— {@code PiTuiApp.exit()} 关闭<b>当前</b>会话
 * （pi interactive shutdown 交互臂，interactive-mode.ts:4316-4322 对位），
 * {@code session_shutdown{quit}} 自此在 TUI 发射（map/04 D-10 摘帽证据）。
 */
class PiTuiAppExitCloseTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 录制扩展：仅 shutdown（退出路径断言用；TCCL 作用域注入，coding-agent 批 4/5 先例）。 */
    public static final class Recorder implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Recorder() {}

        @Override public String name() { return "tui-exit-recorder"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(SESSION_SHUTDOWN, (e, c) -> {
                EVENTS.add("shutdown:" + e.reason() + ":" + e.targetSessionFile());
                return null;
            });
        }
    }

    /** 夹具会话落盘到临时目录（原 docs/39 裁决 B）：空 args 会写到开发者真实 home。 */
    @TempDir
    Path sessionDir;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        var servicesDir = Files.createDirectories(sessionDir.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Recorder.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {sessionDir.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
    }

    @AfterEach
    void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousTccl);
    }

    private AgentSession session() {
        return AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
    }

    private PiTuiApp app(AgentSession session) {
        return new PiTuiApp(new InteractiveMode(session), new ChatScreen(),
            new KeybindingsManager(), new TuiEventDispatcher());
    }

    /** ①：exit ⇒ 当前会话关闭、shutdown{quit} 发射。 */
    @Test
    void exitClosesTheCurrentSessionAndFiresShutdownQuit() {
        var session = session();
        var app = app(session);

        app.exit();

        assertThat(EVENTS).contains("shutdown:quit:null");
        assertThatThrownBy(() -> session.harness().prompt("x"))
            .as("exit 后当前会话真正关闭（pi runtimeHost.dispose 对位）")
            .isInstanceOf(HarnessClosedException.class);
    }

    /** ②：替换后 exit ⇒ 关的是<b>新</b>会话（this.session 随 switchSession 跟踪）。 */
    @Test
    void exitAfterReplacementClosesTheNewSession() {
        var oldSession = session();
        var newSession = session();
        var app = app(oldSession);
        app.switchSession(newSession);
        EVENTS.clear();

        app.exit();

        assertThatThrownBy(() -> newSession.harness().prompt("x"))
            .as("关的是替换后的当前会话")
            .isInstanceOf(HarnessClosedException.class);
        assertThat(EVENTS.stream().filter(e -> e.equals("shutdown:quit:null")).count())
            .as("exit 恰关一条会话").isEqualTo(1);
        // 旧会话未被 exit 碰过：它的首次 close 仍会发射（幂等门下只此一次）。
        oldSession.close();
        assertThat(EVENTS.stream().filter(e -> e.equals("shutdown:quit:null")).count())
            .isEqualTo(2);
    }
}

package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
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
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeSwitchResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_SWITCH;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;

/**
 * 批 20（B257，T5）：RPC {@code new_session} 修形——响应 {@code {cancelled}}
 * （pi rpc-types.ts:131 对位，旧实现发全量 state）、{@code parentSession} 落
 * header、{@code session_before_switch} 取消臂（pi rpc-mode.ts:432-441＋
 * agent-session-runtime.ts:226-258）。
 */
class RpcNewSessionWireTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile boolean cancelSwitch;

    /** 录制扩展：before_switch（静态开关取消）＋start 载荷。 */
    public static final class Recorder implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Recorder() {}

        @Override public String name() { return "new-session-recorder"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(SESSION_BEFORE_SWITCH, (e, c) -> {
                EVENTS.add("before_switch:" + e.reason() + ":" + e.targetSessionFile());
                return new SessionBeforeSwitchResult(cancelSwitch);
            });
            ctx.on(SESSION_START, (e, c) -> {
                EVENTS.add("start:" + e.reason() + ":" + e.previousSessionFile());
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
        cancelSwitch = false;
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

    private AgentSession session() {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-ns", "--model", "hello",
            "--session-dir", tmp.toString()});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-ns", List.of()));
        return AgentSession.create(args, providers, new ToolContext(tmp.toString(),
            Map.of(), new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    private RpcDispatcher dispatcher(AgentSession s, ByteArrayOutputStream out) {
        return new RpcDispatcher(s, new JsonlWriter(out),
            ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));
    }

    private static String responseLine(ByteArrayOutputStream out) {
        return out.toString().lines()
            .filter(l -> l.contains("\"command\":\"new_session\""))
            .findFirst().orElse("");
    }

    @Test
    void respondsCancelledFalseWithPiShape() throws Exception {
        try (var s = session()) {
            var out = new ByteArrayOutputStream();
            var d = dispatcher(s, out);
            d.handleLine("{\"id\":\"1\",\"type\":\"new_session\"}");

            var line = responseLine(out);
            assertThat(line).contains("\"success\":true");
            // pi {cancelled:false} 形——不再是全量 state（旧形状差修真）。
            assertThat(line).contains("\"cancelled\":false");
            assertThat(line).doesNotContain("\"messageCount\"");
            assertThat(EVENTS).contains("before_switch:new:null");
            // session_start 载荷是被替换会话 id（pi previousSessionFile 语义）。
            assertThat(EVENTS).anySatisfy(e ->
                assertThat(e).startsWith("start:new:").isNotEqualTo("start:new:null"));
        }
    }

    @Test
    void cancelledByExtensionKeepsOldSession() throws Exception {
        try (var s = session()) {
            cancelSwitch = true;
            var out = new ByteArrayOutputStream();
            var d = dispatcher(s, out);
            d.handleLine("{\"id\":\"1\",\"type\":\"new_session\"}");

            assertThat(responseLine(out)).contains("\"cancelled\":true");
            assertThat(d.currentSession())
                .as("取消 ⇒ 不替换（pi {cancelled:true} 臂）").isSameAs(s);
            assertThat(EVENTS).doesNotContain("start:new:" + s.sessionId());
            assertThat(EVENTS.stream().filter(e -> e.startsWith("start:new:")).toList())
                .as("取消 ⇒ 无新会话 start").isEmpty();
        }
    }

    @Test
    void parentSessionLandsInNewSessionHeader() throws Exception {
        try (var s = session()) {
            var out = new ByteArrayOutputStream();
            var d = dispatcher(s, out);
            d.handleLine("{\"id\":\"1\",\"type\":\"new_session\","
                + "\"parentSession\":\"parent-file-id\"}");

            assertThat(responseLine(out)).contains("\"cancelled\":false");
            // header 落盘断言：新会话 jsonl 首行含 parentSession（pi 线键逐字，
            // session-manager.ts:1069 对位）。
            var file = Path.of(d.currentSession().sessionFile());
            var headerLine = Files.readAllLines(file).get(0);
            assertThat(headerLine)
                .as("parentSession 落 header（pi session-manager.ts:1069 对位）")
                .contains("\"parentSession\":\"parent-file-id\"");
            // 两字段不可串：session_start 载荷是被替换会话 id，非 parentSession。
            assertThat(EVENTS).anySatisfy(e ->
                assertThat(e).startsWith("start:new:" + s.sessionId()));
        }
    }
}

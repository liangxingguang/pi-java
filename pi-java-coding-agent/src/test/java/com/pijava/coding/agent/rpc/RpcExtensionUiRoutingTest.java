package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17 评审回补（Important #1）：RPC 扩展 UI 应答<b>按 id 路由</b>——pi
 * {@code pendingExtensionRequests} map（rpc-mode.ts:80-84）对位。广播队列＋
 * 失配即弃的旧形会让超时/abort 后的孤儿通道线程偷走后发对话的应答。
 */
class RpcExtensionUiRoutingTest {

    private static final class Rig {
        AgentSession session;
        RpcDispatcher dispatcher;
        ByteArrayOutputStream out;
    }

    private static Rig rig() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-rpc-route");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        var home = Files.createTempDirectory("pi-java-rpc-route-home");
        String savedHome = System.getProperty("user.home");
        AgentSession session;
        try {
            System.setProperty("user.home", home.toString());
            session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
        } finally {
            System.setProperty("user.home", savedHome);
        }
        var rig = new Rig();
        rig.session = session;
        rig.out = new ByteArrayOutputStream();
        rig.dispatcher = new RpcDispatcher(session, new JsonlWriter(rig.out), args);
        return rig;
    }

    /** 抓已上线的 extension_ui_request 的 id（按出现序）。 */
    private static List<String> requestIds(ByteArrayOutputStream out) {
        var ids = new ArrayList<String>();
        for (var line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.contains("extension_ui_request")) {
                var node = line.indexOf("\"id\":\"");
                ids.add(line.substring(node + 6, line.indexOf('"', node + 6)));
            }
        }
        return ids;
    }

    private static void awaitRequestCount(ByteArrayOutputStream out, int count) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (requestIds(out).size() >= count) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("timed out waiting for " + count + " ui requests");
    }

    /** 并发两对话、乱序应答：各拿各的（pi 按 id 路由，rpc-mode.ts:80-84）。 */
    @Test
    void concurrentDialogsRouteResponsesById() throws Exception {
        var rig = rig();
        var ui = rig.session.extensionUI();   // RpcExtensionUI（批 17 T3 注入）
        var first = new AtomicReference<String>();
        var second = new AtomicReference<String>();

        Thread.startVirtualThread(() -> first.set(ui.input("A", null, null)));
        awaitRequestCount(rig.out, 1);
        Thread.startVirtualThread(() -> second.set(ui.input("B", null, null)));
        awaitRequestCount(rig.out, 2);
        var ids = requestIds(rig.out);

        // 先答第二个（乱序）。
        rig.dispatcher.handleLine("{\"type\":\"extension_ui_response\",\"id\":\""
            + ids.get(1) + "\",\"value\":\"answer-b\"}");
        awaitValue(second);
        assertThat(second.get()).isEqualTo("answer-b");
        assertThat(first.get()).as("先答 B 不完成 A").isNull();

        rig.dispatcher.handleLine("{\"type\":\"extension_ui_response\",\"id\":\""
            + ids.get(0) + "\",\"value\":\"answer-a\"}");
        awaitValue(first);
        assertThat(first.get()).isEqualTo("answer-a");
    }

    /** 超时对话的孤儿通道不得偷走后发对话的应答（评审 Important #1 场景逐字）。 */
    @Test
    void timedOutDialogChannelCannotStealLaterResponses() throws Exception {
        var rig = rig();
        var ui = rig.session.extensionUI();
        // A：300ms 本地超时 ⇒ 返默认值（其通道线程进入孤儿等待）。
        var a = ui.input("A", null, new com.pijava.coding.agent.extension
            .ExtensionUiDialogOptions(null, 300L));
        assertThat(a).isNull();
        awaitRequestCount(rig.out, 1);

        var b = new AtomicReference<String>();
        Thread.startVirtualThread(() -> b.set(ui.input("B", null, null)));
        awaitRequestCount(rig.out, 2);
        var idB = requestIds(rig.out).get(1);
        rig.dispatcher.handleLine("{\"type\":\"extension_ui_response\",\"id\":\""
            + idB + "\",\"value\":\"answer-b\"}");
        awaitValue(b);
        assertThat(b.get()).isEqualTo("answer-b");
    }

    /**
     * 批 17 评审回补（Important #2）：{@code new_session} 后扩展 UI 通道随新会话
     * 重绑（pi finishSessionReplacement→rebindSession 对位，
     * agent-session-runtime.ts:187-191／rpc-mode.ts:313-321）。
     */
    @Test
    void newSessionRebindsExtensionUiChannel() throws Exception {
        var rig = rig();
        rig.dispatcher.handleLine("{\"id\":\"1\",\"type\":\"new_session\"}");
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
                && !rig.out.toString(StandardCharsets.UTF_8)
                    .contains("\"command\":\"new_session\"")) {
            Thread.sleep(20);
        }
        var current = rig.dispatcher.currentSession();
        assertThat(current).isNotSameAs(rig.session);
        assertThat(current.extensionUI())
            .as("新会话必须重绑 RPC 通道（不再 noop）")
            .isNotSameAs(com.pijava.coding.agent.extension.ExtensionUI.NOOP);
        assertThat(current.extensionMode()).isEqualTo("rpc");
    }

    private static void awaitValue(AtomicReference<String> ref) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (ref.get() != null) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("dialog did not complete");
    }
}

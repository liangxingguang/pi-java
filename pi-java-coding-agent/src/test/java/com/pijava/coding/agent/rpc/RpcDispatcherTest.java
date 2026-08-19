package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-5b: RpcDispatcher — prompt 先回 success 随后事件流；未知命令回失败；get_state。
 */
class RpcDispatcherTest {

    @Test
    void promptReturnsSuccessThenStreamsEvents() throws Exception {
        var ctx = context("faux-rpc", textStream("Hi there"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"prompt\",\"message\":\"hi\"}");

        String output = awaitSettled(out);
        String firstLine = output.lines().findFirst().orElse("");
        assertThat(firstLine).contains("\"id\":\"1\"")
            .contains("\"command\":\"prompt\"")
            .contains("\"success\":true");
        assertThat(output).contains("\"type\":\"message_update\"");
        assertThat(output).contains("\"type\":\"agent_end\"");
        assertThat(output).contains("\"type\":\"agent_settled\"");
        // 事件信封不带顶层 id（无关联通知流）；response 行除外。
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(output.lines()
            .filter(l -> !l.isBlank())
            .map(l -> {
                try {
                    return mapper.readTree(l);
                } catch (Exception e) {
                    return null;
                }
            })
            .filter(n -> n != null && n.get("type") != null)
            .filter(n -> !"response".equals(n.get("type").asText()))
            .allMatch(n -> n.get("id") == null)).isTrue();
    }

    @Test
    void unknownCommandReturnsFailure() throws Exception {
        var ctx = context("faux-unknown", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"4\",\"type\":\"bad_command\"}");
        dispatcher.handleLine("{\"id\":\"5\",\"type\":\"set_model\",\"model\":\"x\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"id\":\"4\"")
            .contains("\"command\":\"bad_command\"")
            .contains("\"success\":false")
            .contains("Unknown command: bad_command");
        assertThat(output).contains("\"id\":\"5\"")
            .contains("Unknown command: set_model");
    }

    @Test
    void getStateReturnsPayload() throws Exception {
        var ctx = context("faux-state", textStream("Hi"));
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine("{\"id\":\"2\",\"type\":\"get_state\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"id\":\"2\"")
            .contains("\"command\":\"get_state\"")
            .contains("\"success\":true")
            .contains("\"model\":\"faux-state/hello\"");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private record Ctx(AgentSession session, com.pijava.coding.agent.cli.Args args) {}

    private Ctx context(String provider, List<List<StreamEvent>> sequences) throws Exception {
        var tmp = Files.createTempDirectory("pi-java-rpc-test");
        var args = ArgsParser.parse(new String[] {
            "--provider", provider, "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence(provider, sequences));
        var session = AgentSession.create(args, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        return new Ctx(session, args);
    }

    private static List<List<StreamEvent>> textStream(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done)));
    }

    /** 轮询输出直到出现 agent_settled（异步事件），带超时。 */
    private static String awaitSettled(ByteArrayOutputStream out) throws IOException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String s = out.toString(StandardCharsets.UTF_8);
            if (s.contains("agent_settled")) {
                return s;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}

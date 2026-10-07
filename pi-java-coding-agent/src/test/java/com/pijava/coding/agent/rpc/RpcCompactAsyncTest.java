package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.support.BlockingSummaryProvider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B176（docs/27 §4-2）：RPC compact 压缩跑在 worker 线程，stdin 读线程在
 * 压缩期间继续处理命令（pi {@code void handleInputLine(line)}，
 * rpc-mode.ts:809）。
 */
class RpcCompactAsyncTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Entry messageEntry(String id, String parentId, boolean user,
                                      String text) {
        Message message = user
            ? new Message.UserMessage(List.of(new ContentBlock.TextContent(text)))
            : new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
        return new Entry.Message(id, 0, parentId, Instant.ofEpochMilli(1), message, false);
    }

    private static List<Entry> seededEntries() {
        return List.of(
            messageEntry("e1", null, true, "first"),
            messageEntry("e2", "e1", false, "second"),
            messageEntry("e3", "e2", true, "x".repeat(100_000)),
            messageEntry("e4", "e3", false, "fourth"));
    }

    @Test
    void compactDoesNotBlockReaderAndResponseArrivesAfterOtherCommands() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-rpc-async");
        var args = ArgsParser.parse(new String[] {
            "--provider", "block-rpc", "--model", "hello", "--no-session"});
        var provider = new BlockingSummaryProvider("block-rpc");
        var session = AgentSession.create(args, provider.register(),
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        session.harness().seedTranscript(AgentHarness.DEFAULT_LANE, seededEntries());

        var stdout = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(session, new JsonlWriter(stdout), args);

        // Watchdog：同步变异（M2）会让 handleLine 永久阻塞，2.5s 强制放行。
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            provider.release();
        });

        dispatcher.handleLine("{\"id\":\"c\",\"type\":\"compact\"}");
        awaitCompacting(session);

        // 压缩在 worker：get_state 在读线程立即得到响应，compact 响应还没写出。
        dispatcher.handleLine("{\"id\":\"s\",\"type\":\"get_state\"}");
        var lines = stdout.toString(StandardCharsets.UTF_8).split("\n");
        JsonNode stateResponse = null;
        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var node = JSON.readTree(line);
            assertThat(node.path("id").asText()).isNotEqualTo("c");
            if ("s".equals(node.path("id").asText())) {
                stateResponse = node;
            }
        }
        assertThat(stateResponse).isNotNull();
        assertThat(stateResponse.path("command").asText()).isEqualTo("get_state");

        provider.release();

        JsonNode compactResponse = awaitResponse(stdout, "c");
        assertThat(compactResponse.path("success").asBoolean()).isTrue();
        var data = compactResponse.path("data");
        // CompactResultWire 五键（CompactResultWire javadoc）。
        assertThat(data.has("summary")).isTrue();
        assertThat(data.has("firstKeptEntryId")).isTrue();
        assertThat(data.has("tokensBefore")).isTrue();
        assertThat(data.has("usage")).isTrue();
        assertThat(data.has("details")).isTrue();

        dispatcher.close();
        session.close();
    }

    private static JsonNode awaitResponse(ByteArrayOutputStream stdout, String id)
            throws Exception {
        var seen = new java.util.HashSet<String>();
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (var line : stdout.toString(StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || !seen.add(line)) {
                    continue;
                }
                var node = JSON.readTree(line);
                if ("response".equals(node.path("type").asText())
                        && id.equals(node.path("id").asText())) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out; output=\n"
            + stdout.toString(StandardCharsets.UTF_8));
    }

    private static void awaitCompacting(AgentSession session) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !session.isCompacting()) {
            Thread.sleep(5);
        }
        assertThat(session.isCompacting())
            .as("worker 已打开压缩窗口").isTrue();
    }
}

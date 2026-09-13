package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --trace-payloads} wiring: assembling a session with the flag records
 * {@code llm.payload.request}/{@code llm.payload.response} event lines bound to
 * the {@code llm.request} span, and the payloads carry the request-level view
 * (model, messages, tools, limits) plus the final assistant message.
 */
class PayloadRecordingStreamFnTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Read every JSONL line from the current {@code pi-java.traces.dir}. */
    private List<JsonNode> readLines() throws Exception {
        try (var stream = Files.list(PayloadRecordingStreamFn.tracesDir())) {
            var file = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .findFirst().orElseThrow();
            var lines = new ArrayList<JsonNode>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    lines.add(mapper.readTree(line));
                }
            }
            return lines;
        }
    }

    @Test
    void tracePayloadsRecordsRequestAndResponseBoundToLlmSpan(@TempDir Path tracesDir)
            throws Exception {
        System.setProperty("pi-java.traces.dir", tracesDir.toString());
        try {
            var done = AssistantMessage.empty().withContent(List.of(
                new ContentBlock.TextContent("hello"))).withStopReason("stop");
            var providers = ProviderRegistry.create();
            providers.register(FauxProvider.sequence("faux-payload", List.of(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "hello", done),
                new StreamEvent.TextEnd(0, "hello", done),
                new StreamEvent.StreamDone("stop", null, done)))));
            var args = ArgsParser.parse(new String[] {
                "--provider", "faux-payload", "--model", "hello",
                "--no-session", "--trace-payloads"});
            var toolContext = new ToolContext(
                tracesDir.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem());

            var session = AgentSession.create(
                args, InMemorySessionRepository.create(), providers, toolContext);
            try (session) {
                var result = session.processPrompt("say hi", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
            }

            var lines = readLines();
            var request = lines.stream()
                .filter(n -> "event".equals(n.get("kind").asText())
                    && "llm.payload.request".equals(n.get("name").asText()))
                .findFirst().orElseThrow();
            var response = lines.stream()
                .filter(n -> "event".equals(n.get("kind").asText())
                    && "llm.payload.response".equals(n.get("name").asText()))
                .findFirst().orElseThrow();

            // Events are bound to the llm.request span (same spanId/traceId).
            var llmStart = lines.stream()
                .filter(n -> "span_start".equals(n.get("kind").asText())
                    && "llm.request".equals(n.get("name").asText()))
                .findFirst().orElseThrow();
            assertThat(request.get("spanId").asText())
                .isEqualTo(llmStart.get("spanId").asText());
            assertThat(response.get("spanId").asText())
                .isEqualTo(llmStart.get("spanId").asText());
            assertThat(request.get("traceId").asText())
                .isEqualTo(llmStart.get("traceId").asText());

            // Request payload: model, systemPrompt, messages with role/content, tools, limits.
            var payload = request.get("payload");
            assertThat(payload.get("model").asText()).isEqualTo("faux-payload/hello");
            // 系统提示是请求上的独立字段（pi 的 Context.systemPrompt），不进消息列表 ——
            // 所以 messages[0] 是用户消息，而不是合成出来的 system 消息。
            assertThat(payload.hasNonNull("systemPrompt")).isTrue();
            assertThat(payload.get("messages").isArray()).isTrue();
            assertThat(payload.get("messages").get(0).get("role").asText())
                .isEqualTo("user");
            assertThat(payload.get("tools").isArray()).isTrue();
            assertThat(payload.get("maxTokens").asInt()).isEqualTo(-1);

            // Response payload: final assistant message content + stop reason.
            var respPayload = response.get("payload");
            assertThat(respPayload.get("stopReason").asText()).isEqualTo("stop");
            assertThat(respPayload.get("message").get("content").get(0).get("text").asText())
                .isEqualTo("hello");
        } finally {
            System.clearProperty("pi-java.traces.dir");
        }
    }

    @Test
    void withoutTracePayloadsOnlySpanAndMetricLinesAreWritten(@TempDir Path tracesDir)
            throws Exception {
        System.setProperty("pi-java.traces.dir", tracesDir.toString());
        try {
            var done = AssistantMessage.empty().withContent(List.of(
                new ContentBlock.TextContent("hi"))).withStopReason("stop");
            var providers = ProviderRegistry.create();
            providers.register(FauxProvider.sequence("faux-nopayload", List.of(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "hi", done),
                new StreamEvent.TextEnd(0, "hi", done),
                new StreamEvent.StreamDone("stop", null, done)))));
            var args = ArgsParser.parse(new String[] {
                "--provider", "faux-nopayload", "--model", "hello", "--no-session"});
            var session = AgentSession.create(
                args, InMemorySessionRepository.create(), providers,
                new ToolContext(tracesDir.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
            try (session) {
                var result = session.processPrompt("hi", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
            }

            var lines = readLines();
            assertThat(lines.stream().noneMatch(n -> "event".equals(n.get("kind").asText())
                && "llm.payload.request".equals(n.get("name").asText()))).isTrue();
            assertThat(lines.stream().anyMatch(n -> "span_start".equals(n.get("kind").asText())
                && "llm.request".equals(n.get("name").asText()))).isTrue();
            assertThat(lines.stream().anyMatch(n -> "counter".equals(n.get("kind").asText())
                && "harness.turn".equals(n.get("name").asText()))).isTrue();
        } finally {
            System.clearProperty("pi-java.traces.dir");
        }
    }
}

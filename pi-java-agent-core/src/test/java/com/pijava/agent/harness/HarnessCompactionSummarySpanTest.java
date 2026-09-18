package com.pijava.agent.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.LlmSummaryGenerator;
import com.pijava.ai.Usage;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.JsonlFileTelemetry;
import com.pijava.telemetry.TelemetryContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要请求有自己的跨度（{@code docs/31 §8.25.5-8} 的结案面）。
 *
 * <p>压缩走的是**同一个** {@code streamFn}，于是摘要请求的负载行
 * （{@code llm.payload.request/response}）由 {@code PayloadRecordingStreamFn} 照样发出 ——
 * 但压缩路径此前从不 {@code pushCurrent}，那些行既无 {@code traceId} 也无 {@code spanId}，
 * 事后既连不回哪一次运行、也连不回哪一次压缩。这里钉住修复后的形状：
 * {@code compaction.summary} 子跨度（父 = {@code compaction.apply}）在摘要生成期间
 * 绑定为当前跨度，负载行因此归属到它，跨度结束时还带上摘要长度与 token。</p>
 *
 * <p>跨线程那条边界（运行中手动 {@code /compact}）由
 * {@code HarnessTelemetryThreadAttributionTest} 的同名用例守着 —— 那里验的是
 * 「不借用在飞请求的跨度」，这里验的是「有自己的归属」。</p>
 */
class HarnessCompactionSummarySpanTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** {@code LlmSummaryGenerator} 的私有常量 {@code SUMMARIZATION_SYSTEM_PROMPT} 的开头。 */
    private static final String SUMMARIZATION_PROMPT_PREFIX =
        "You are a context summarization assistant";

    private static final String SUMMARY_TEXT = "[summary of the discarded prefix]";
    private static final long SUMMARY_INPUT_TOKENS = 1200L;
    private static final long SUMMARY_OUTPUT_TOKENS = 340L;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * {@code PayloadRecordingStreamFn} 的最小同形替身（后者在 {@code coding-agent} 的包内，
     * {@code agent-core} 看不到）：请求点发 {@code llm.payload.request}、终结事件发
     * {@code llm.payload.response}。载荷带系统提示，好让断言认出「这是摘要请求」。
     */
    private static StreamFn recording(TelemetryContext telemetry, StreamFn delegate) {
        return (model, context, options) -> {
            telemetry.recordEvent("llm.payload.request", Map.of(
                "sys", context.systemPrompt() == null ? "" : context.systemPrompt()));
            var inner = delegate.stream(model, context, options);
            return new StreamIterator() {
                @Override public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override public StreamEvent next() {
                    var event = inner.next();
                    if (event instanceof StreamEvent.StreamDone
                        || event instanceof StreamEvent.StreamError) {
                        telemetry.recordEvent("llm.payload.response", Map.of("sys", "summary"));
                    }
                    return event;
                }

                @Override public void close() {
                    inner.close();
                }
            };
        };
    }

    /**
     * 剧本流：每轮都带 {@code UsageInfo}（摘要那次的用量因此有来源）。摘要请求的正文
     * 固定为 {@link #SUMMARY_TEXT}，不是摘要的请求回一句普通文本。
     */
    private static StreamFn scripted() {
        return (model, context, options) -> {
            var summarization = (context.systemPrompt() == null ? "" : context.systemPrompt())
                .startsWith(SUMMARIZATION_PROMPT_PREFIX);
            var text = summarization ? SUMMARY_TEXT : "assistant reply";
            var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason("stop");
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, text, partial),
                new StreamEvent.UsageInfo(SUMMARY_INPUT_TOKENS, SUMMARY_OUTPUT_TOKENS, partial,
                    Usage.of(SUMMARY_INPUT_TOKENS, SUMMARY_OUTPUT_TOKENS)),
                new StreamEvent.StreamDone("stop", null, partial)));
        };
    }

    /** harness 与摘要生成器共用同一份 exporter + 同一份流（生产的接线形状）。 */
    private static AgentHarness harness(StreamFn streamFn, TelemetryContext recorder) {
        return AgentHarness.create(HarnessConfig.builder()
            .streamFn(streamFn)
            .model(MODEL)
            .thinkingLevel(ModelThinkingLevel.off())
            .systemPrompt("")
            .activeTools(java.util.Set.of())
            .maxInputTokens(200_000)
            .telemetry(recorder)
            .thinkingLevelMap(com.pijava.ai.thinking.ThinkingLevelMap.empty())
            .summaryGenerator(new LlmSummaryGenerator(streamFn, () -> MODEL))
            .build());
    }

    private List<JsonNode> readLines(Path tracesDir) throws IOException {
        try (var stream = Files.list(tracesDir)) {
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

    private static JsonNode spanStart(List<JsonNode> lines, String name) {
        return lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText()))
            .filter(n -> name.equals(n.get("name").asText()))
            .findFirst().orElseThrow(() -> new AssertionError("没有 " + name + " 的 span_start"));
    }

    private static JsonNode spanEnd(List<JsonNode> lines, String spanId) {
        return lines.stream()
            .filter(n -> "span_end".equals(n.get("kind").asText()))
            .filter(n -> spanId.equals(n.get("spanId").asText()))
            .findFirst().orElseThrow(() -> new AssertionError("跨度没有收尾：" + spanId));
    }

    /** 载荷 {@code sys} 以摘要系统提示开头的那些请求事件行。 */
    private static List<JsonNode> summarizationRequests(List<JsonNode> lines) {
        return lines.stream()
            .filter(n -> "event".equals(n.get("kind").asText()))
            .filter(n -> "llm.payload.request".equals(n.get("name").asText()))
            .filter(n -> n.get("payload").get("sys").asText()
                .startsWith(SUMMARIZATION_PROMPT_PREFIX))
            .toList();
    }

    @Test
    void theSummarizationRequestIsHostedByItsOwnSpan(@TempDir Path tracesDir) throws IOException {
        var recorder = JsonlFileTelemetry.create(tracesDir).withPayloads(true);
        var streamFn = recording(recorder, scripted());
        var h = harness(streamFn, recorder);

        // 先跑一轮：转录里得有东西可压，压缩才会调到摘要生成器
        h.prompt("hello");
        h.compact(CompactionSettings.defaults());

        var lines = readLines(tracesDir);
        var summarySpan = spanStart(lines, "compaction.summary");
        var applySpan = spanStart(lines, "compaction.apply");

        // 前提：摘要生成器确实被调到了（否则这条实验什么也没证明）
        var summarization = summarizationRequests(lines);
        assertThat(summarization).as("手动压缩应当调到摘要生成器").isNotEmpty();

        // 归属：负载行绑在**自己**的跨度上（修复前两处都是缺的）。
        // 读用 path(...)：键缺席时得到 MissingNode（"")而不是 NPE —— 回归时读到的
        // 是「expected: <spanId> but was: ""」，一眼看出是没绑上，而不是栈里一个空指针。
        var row = summarization.get(0);
        assertThat(row.path("traceId").asText())
            .isEqualTo(summarySpan.path("traceId").asText());
        assertThat(row.path("spanId").asText())
            .isEqualTo(summarySpan.path("spanId").asText());

        // 嵌套：compaction.summary 是 compaction.apply 的子跨度，同一条 trace
        assertThat(summarySpan.path("parentSpanId").asText())
            .isEqualTo(applySpan.path("spanId").asText());
        assertThat(summarySpan.path("traceId").asText())
            .isEqualTo(applySpan.path("traceId").asText());

        // 响应行也绑得住（与请求行同一宿主）
        var response = lines.stream()
            .filter(n -> "event".equals(n.get("kind").asText()))
            .filter(n -> "llm.payload.response".equals(n.get("name").asText()))
            .filter(n -> summarySpan.path("spanId").asText()
                .equals(n.path("spanId").asText()))
            .toList();
        assertThat(response).as("摘要的响应行也在同一宿主跨度下").isNotEmpty();

        // 收尾属性：摘要长度 + 这次调用的用量（修复前 trace 里看不到摘要花掉的 token）
        var attrs = spanEnd(lines, summarySpan.path("spanId").asText()).path("attrs");
        assertThat(attrs.path("summaryChars").asInt()).isEqualTo(SUMMARY_TEXT.length());
        assertThat(attrs.path("inputTokens").asDouble()).isEqualTo((double) SUMMARY_INPUT_TOKENS);
        assertThat(attrs.path("outputTokens").asDouble()).isEqualTo((double) SUMMARY_OUTPUT_TOKENS);
    }

    /**
     * 正向对照：主循环的请求仍然绑在 {@code llm.request} 上 —— 摘要那个绑定既没有
     * 抢走它、也没有在 pop 之后残留（同一线程上前后两次绑定各自独立）。
     */
    @Test
    void theRunRequestStaysBoundToItsOwnSpan(@TempDir Path tracesDir) throws IOException {
        var recorder = JsonlFileTelemetry.create(tracesDir).withPayloads(true);
        var streamFn = recording(recorder, scripted());
        var h = harness(streamFn, recorder);

        h.prompt("hello");
        h.compact(CompactionSettings.defaults());

        var lines = readLines(tracesDir);
        var llmSpan = spanStart(lines, "llm.request");
        var summarySpan = spanStart(lines, "compaction.summary");
        var runRequests = lines.stream()
            .filter(n -> "event".equals(n.get("kind").asText()))
            .filter(n -> "llm.payload.request".equals(n.get("name").asText()))
            .filter(n -> !n.get("payload").get("sys").asText()
                .startsWith(SUMMARIZATION_PROMPT_PREFIX))
            .toList();
        assertThat(runRequests).as("那一轮的请求行").isNotEmpty();
        assertThat(runRequests.get(0).path("spanId").asText())
            .isEqualTo(llmSpan.path("spanId").asText());
        assertThat(llmSpan.path("spanId").asText())
            .isNotEqualTo(summarySpan.path("spanId").asText());
    }
}

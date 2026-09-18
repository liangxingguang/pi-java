package com.pijava.agent.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.compaction.LlmSummaryGenerator;
import com.pijava.agent.compaction.SummaryGenerator;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.JsonlFileTelemetry;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetryContext;
import com.pijava.telemetry.TelemetrySpan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 遥测的「当前跨度」在真实 harness 运行里的线程归属（{@code docs/31 §8.25.6} ①-b 与 ③）。
 *
 * <p>这两条是 A1（`currentStack` → `ThreadLocal`）的证据面：①-b 实测默认路径的三处
 * （`pushCurrent` / `recordEvent` / `popCurrent`）确实同线程 —— 它是**特征化**断言，
 * 没有反向实验（共享栈在单线程下同样正确），价值在于给这个「调用形状的产物」装一条
 * 绊线；③ 才是**可达性**证据，它把「运行中手动 `/compact`」这条生产路径做出来，
 * 证明跨线程错配今天就跑得到，而不是只活在单测夹具里。</p>
 */
class HarnessTelemetryThreadAttributionTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** {@code LlmSummaryGenerator} 的私有常量 {@code SUMMARIZATION_SYSTEM_PROMPT} 的开头。 */
    private static final String SUMMARIZATION_PROMPT_PREFIX =
        "You are a context summarization assistant";

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------------------------------------------------------- 夹具

    /**
     * 记录三处线程号的装饰器。**放在最外层**（`HarnessConfig.telemetry` 收到的是它），
     * 因为 `PiLaneSink` 的 push/pop 与记录点的 `recordEvent` 都打在最外层实例上。
     */
    private static final class ThreadRecordingTelemetry implements TelemetryContext {

        private final TelemetryContext delegate;
        private final List<Long> pushThreads = new ArrayList<>();
        private final List<Long> popThreads = new ArrayList<>();
        private final List<Long> eventThreads = new ArrayList<>();

        ThreadRecordingTelemetry(TelemetryContext delegate) {
            this.delegate = delegate;
        }

        private static long here() {
            return Thread.currentThread().threadId();
        }

        @Override
        public <T> T startSpan(SpanOptions options,
                               java.util.function.Function<? super TelemetrySpan, ? extends T> body) {
            return delegate.startSpan(options, body);
        }

        /**
         * 必须显式转发：接口的 `openSpan` 默认实现是「在回调里取到 span 就立刻关掉它」
         * （{@code TelemetryContext:32-39}），不转发的话 harness 拿到的全是已结束的 span。
         */
        @Override
        public TelemetrySpan openSpan(SpanOptions options) {
            return delegate.openSpan(options);
        }

        @Override
        public void pushCurrent(TelemetrySpan span) {
            pushThreads.add(here());
            delegate.pushCurrent(span);
        }

        @Override
        public void popCurrent(TelemetrySpan span) {
            popThreads.add(here());
            delegate.popCurrent(span);
        }

        @Override
        public void recordEvent(String name, Map<String, Object> payload) {
            eventThreads.add(here());
            delegate.recordEvent(name, payload);
        }

        @Override
        public boolean recordsPayloads() {
            return delegate.recordsPayloads();
        }

        @Override
        public TelemetryContext with(String key, String value) {
            return new ThreadRecordingTelemetry(delegate.with(key, value));
        }

        @Override
        public void incrementCounter(String name, long delta) {
            delegate.incrementCounter(name, delta);
        }

        @Override
        public void recordTiming(String name, long durationMs) {
            delegate.recordTiming(name, durationMs);
        }
    }

    /**
     * {@code PayloadRecordingStreamFn} 的最小同形替身（后者在 `coding-agent` 的包内，
     * `agent-core` 看不到）：请求点发 {@code llm.payload.request}、终结事件发
     * {@code llm.payload.response}，两次都打在**同一个** telemetry 实例上 ——
     * 与生产的接线一致（{@code AgentSession:263-266} 与 {@code :366-367} 共用一份 exporter）。
     *
     * <p>载荷里带上系统提示，是为了让差分侧能一眼认出「这是摘要请求」。</p>
     */
    private static StreamFn recording(TelemetryContext telemetry, StreamFn delegate) {
        return (model, context, options) -> {
            telemetry.recordEvent("llm.payload.request", Map.of(
                "sys", systemPromptOf(context),
                "messages", context.messages().size()));
            var inner = delegate.stream(model, context, options);
            return new StreamIterator() {
                @Override public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override public StreamEvent next() {
                    var event = inner.next();
                    if (event instanceof StreamEvent.StreamDone
                        || event instanceof StreamEvent.StreamError) {
                        telemetry.recordEvent("llm.payload.response", Map.of("sys", systemPromptOf(context)));
                    }
                    return event;
                }

                @Override public void close() {
                    inner.close();
                }
            };
        };
    }

    private static String systemPromptOf(Context context) {
        return context.systemPrompt() == null ? "" : context.systemPrompt();
    }

    private static boolean isSummarization(Context context) {
        return systemPromptOf(context).startsWith(SUMMARIZATION_PROMPT_PREFIX);
    }

    /**
     * 剧本流。`armed` 打开时，**harness 自己的**请求在流内闩住（放行点必须在观察端，
     * {@code docs/31 §8.23.7}）；摘要请求永不闩 —— 它由测试线程发起，闩住就自锁。
     */
    private static StreamFn scripted(AtomicBoolean armed, CountDownLatch entered,
                                     CountDownLatch release) {
        return (model, context, options) -> {
            var summarization = isSummarization(context);
            if (!summarization && armed.get()) {
                entered.countDown();
                awaitQuietly(release);
            }
            var text = summarization ? "[summary of the discarded prefix]" : "assistant reply";
            var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason("stop");
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, text, partial),
                new StreamEvent.StreamDone("stop", null, partial)));
        };
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("闩锁超时：放行点没有被触发");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("闩锁等待被中断", e);
        }
    }

    /** harness 与摘要生成器共用同一份 recorder + 同一份流（生产的接线形状）。 */
    private static AgentHarness harness(StreamFn streamFn, TelemetryContext recorder,
                                        SummaryGenerator generator) {
        return AgentHarness.create(HarnessConfig.builder()
            .streamFn(streamFn)
            .model(MODEL)
            .thinkingLevel(ModelThinkingLevel.off())
            .systemPrompt("")
            .activeTools(Set.of())
            .maxInputTokens(200_000)
            .telemetry(recorder)
            .thinkingLevelMap(com.pijava.ai.thinking.ThinkingLevelMap.empty())
            .summaryGenerator(generator)
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

    /** 载荷 `sys` 以摘要系统提示开头的那些 `llm.payload.request` 事件行。 */
    private static List<JsonNode> summarizationRequests(List<JsonNode> lines) {
        return lines.stream()
            .filter(n -> "event".equals(n.get("kind").asText()))
            .filter(n -> "llm.payload.request".equals(n.get("name").asText()))
            .filter(n -> n.get("payload").get("sys").asText()
                .startsWith(SUMMARIZATION_PROMPT_PREFIX))
            .toList();
    }

    private static List<JsonNode> eventsNamed(List<JsonNode> lines, String name) {
        return lines.stream()
            .filter(n -> "event".equals(n.get("kind").asText()))
            .filter(n -> name.equals(n.get("name").asText()))
            .toList();
    }

    // ---------------------------------------------------------------- ①-b

    /**
     * ①-b：默认路径上三处同线程。**它是特征化断言，不是 A1 的回归哨兵** ——
     * 共享栈在单线程下同样给出正确归属。它钉的是「默认路径同线程**是调用形状的产物**」
     * 这句判断（{@code docs/18 §7.3}）：哪天有人把 push 或记录点挪到别的线程上，
     * 这条会先响。
     */
    @Test
    void pushAndRecordAndPopShareTheSameThreadOnTheDefaultPath(@TempDir Path tracesDir) {
        var recorder = new ThreadRecordingTelemetry(
            JsonlFileTelemetry.create(tracesDir).withPayloads(true));
        var streamFn = recording(recorder, scripted(new AtomicBoolean(false),
            new CountDownLatch(1), new CountDownLatch(1)));
        var h = harness(streamFn, recorder,
            new LlmSummaryGenerator(streamFn, () -> MODEL));

        h.prompt("hello");

        // 请求点与响应点两处 recordEvent 都真跑到了
        assertThat(recorder.eventThreads).hasSizeGreaterThanOrEqualTo(2);
        assertThat(recorder.pushThreads).hasSize(1);
        assertThat(recorder.popThreads).hasSize(1);

        // 每一处都只在自己的线程上发生……
        assertThat(Set.copyOf(recorder.pushThreads)).hasSize(1);
        assertThat(Set.copyOf(recorder.popThreads)).hasSize(1);
        assertThat(Set.copyOf(recorder.eventThreads)).hasSize(1);

        // ……而且是同一个线程
        var thread = recorder.pushThreads.get(0);
        assertThat(recorder.popThreads).containsExactly(thread);
        assertThat(recorder.eventThreads).allMatch(id -> id.equals(thread));
        assertThat(Thread.currentThread().threadId()).isEqualTo(thread);
    }

    // ---------------------------------------------------------------- ③（RE-2）

    /**
     * ③：面③-1 的可达性 —— **运行中手动 `/compact`**（生产形状：prompt 在另一条线程上，
     * 手动压缩从宿主线程进来；{@code RunLifecycle.compact:237-239} 没有 `isRunning` 门）。
     *
     * <p>预测（{@code docs/31 §8.25.6} ③）：A1 生效 ⇒ 摘要那次请求的 payload 行不带
     * 那条在飞请求的 {@code spanId}；还原成共享栈 ⇒ 同一行会带上它。后者就是「静默错配」
     * 在生产路径上的样子。当时 A1 下这条行的形状是「没有 {@code traceId}」，
     * **§8.29 之后改为「归于压缩自己的 {@code compaction.summary} 跨度」** —— 不变量
     * （不借用别人的绑定）不变，只是被观测的那一行现在有了自己的合法归属。</p>
     */
    @Test
    void compactionFromAnotherThreadDoesNotInheritTheInFlightRequestSpan(@TempDir Path tracesDir)
            throws Exception {
        var recorder = new ThreadRecordingTelemetry(
            JsonlFileTelemetry.create(tracesDir).withPayloads(true));
        var armed = new AtomicBoolean(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var streamFn = recording(recorder, scripted(armed, entered, release));
        var failure = new AtomicReference<Throwable>();
        var h = harness(streamFn, recorder, new LlmSummaryGenerator(streamFn, () -> MODEL));

        // 先跑一轮到完成：转录里得有东西可压，压缩的摘要生成器才会被调到
        h.prompt("hello");

        // 第二轮在**独立虚拟线程**上跑（生产形状 AgentSession:545），并在流内闩住 ——
        // 此刻那条线程正持有本次请求的 llm.request 绑定
        armed.set(true);
        var inFlight = Thread.ofVirtual().start(() -> {
            try {
                h.prompt("hello again");
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        var enteredInTime = entered.await(10, TimeUnit.SECONDS);
        // 放行必须在 finally 里：这条 await 失败时也不能把那轮请求留在闩上 ——
        // surefire 复用同一个 JVM，残留的活线程会干扰后续测试类的时序
        try {
            assertThat(enteredInTime)
                .as("harness 的第二轮请求应当已进入流").isTrue();

            // 宿主线程发起手动压缩 —— 摘要生成器走同一份 recordingStreamFn，
            // 于是 recordEvent 发生在**另一条线程**上
            h.compact(CompactionSettings.defaults());
        } finally {
            release.countDown();
        }
        inFlight.join(10_000);
        assertThat(failure.get()).as("在飞的那轮不应因压缩而失败").isNull();
        assertThat(inFlight.isAlive()).isFalse();

        var lines = readLines(tracesDir);

        // 前提：摘要请求确实跑到了（否则这条实验什么也没证明）
        var summarization = summarizationRequests(lines);
        assertThat(summarization).as("手动压缩应当调到摘要生成器").isNotEmpty();

        // 摘要请求的宿主是**压缩自己的**跨度（§8.29 起压缩路径也 push）。这条断言
        // 原先写的是「没有 traceId/spanId」，§8.29 给它补上归属后改为：**归属必须是
        // 它自己那条**，而不是从在飞请求那里借来的 —— 要守的不变量是「不借用」，
        // 「宁可无归属」只是当时没有归属时的兜底说法。
        var compactionSummarySpan = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText()))
            .filter(n -> "compaction.summary".equals(n.get("name").asText()))
            .findFirst().orElseThrow();
        assertThat(summarization.get(0).path("spanId").asText())
            .isEqualTo(compactionSummarySpan.path("spanId").asText());

        // 正向对照：在飞那一轮的请求行**带**自己的 traceId（不是「一行都没有」）
        var llmStart = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "llm.request".equals(n.get("name").asText()))
            .reduce((first, second) -> second).orElseThrow();
        var runRequests = eventsNamed(lines, "llm.payload.request").stream()
            .filter(n -> !n.get("payload").get("sys").asText()
                .startsWith(SUMMARIZATION_PROMPT_PREFIX))
            .toList();
        assertThat(runRequests).isNotEmpty();
        assertThat(runRequests.get(runRequests.size() - 1).path("spanId").asText())
            .isEqualTo(llmStart.path("spanId").asText());
        assertThat(summarization.get(0).path("spanId").asText())
            .isNotEqualTo(llmStart.path("spanId").asText());
    }
}

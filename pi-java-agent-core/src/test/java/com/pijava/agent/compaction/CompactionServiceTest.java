package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.StreamFn;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CompactionService LLM 摘要（P6 对齐）+ 环 B 重试链（3d，{@code docs/31 §8.22}）。
 *
 * <p>3d 起钉的是 pi 的语义：{@code generateSummaryWithUsage} 对 error/length
 * <b>抛</b>（{@code getSummarizationFailure} 文案），空文本在 stop 收尾下<b>合法</b>
 * （pi 没有截断兜底路 —— 旧「失败/空输出回退 truncating」钉已按裁决撤销）。
 * 瞬断错误走 {@code retryAssistantCall} 同形环，事件序列
 * scheduled → attempt_start → finished 逐条钉。</p>
 */
class CompactionServiceTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "summary");

    /** 3d 用的环 B 设置：预算内的瞬断重试要快（baseDelay 1ms）。 */
    private static final RetrySettings FAST_RETRY = new RetrySettings(true, 3, 1, 1_000L);

    private static StreamFn llm(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return (model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.UsageInfo(5, 7, done),
            new StreamEvent.StreamDone("stop", null, done)));
    }

    /** 一次瞬断错误的流（partial 无终局快照 —— 合成路）。 */
    private static List<StreamEvent> transientError(String message) {
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamError("error", new RuntimeException(message), null));
    }

    private static List<StreamEvent> success(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    /** 收集环 B 事件的观察口。 */
    private static final class Events implements RetryObserver {
        final List<String> lines = new ArrayList<>();

        @Override
        public void onSummarizationRetryScheduled(int attempt, int maxAttempts, long delayMs,
                                                  String errorMessage) {
            lines.add("scheduled|" + attempt + "|" + maxAttempts + "|" + errorMessage);
        }

        @Override
        public void onSummarizationRetryAttemptStart(String source, String reason) {
            lines.add("attempt_start|" + source + "|" + reason);
        }

        @Override
        public void onSummarizationRetryFinished() {
            lines.add("finished");
        }
    }

    private static LlmSummaryGenerator rig(StreamFn fn, Events events) {
        return new LlmSummaryGenerator(fn, () -> MODEL, () -> FAST_RETRY, () -> false, events);
    }

    private static final List<Message> ONE = List.of(
        new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))));

    @Test
    void llmSummaryUsesGeneratedTextNotFallback() {
        var generator = new LlmSummaryGenerator(llm("## Goal\nBuild the app."), () -> MODEL);
        var result = generator.summarize(ONE, null, null, 16_384);
        assertThat(result.text()).isEqualTo("## Goal\nBuild the app.");
        assertThat(result.usage()).isNotNull();
    }

    @Test
    void emptyOutputOnStopIsLegal() {
        // pi 的 contentText 可为空串 —— 旧「空输出 ⇒ truncating 兜底」路在 pi 不存在。
        var generator = new LlmSummaryGenerator(llm(""), () -> MODEL);
        var result = generator.summarize(ONE, null, null, 16_384);
        assertThat(result.text()).isEmpty();
        assertThat(result.usage()).isNotNull();
    }

    @Test
    void errorResponseThrowsSummarizationFailure() {
        // getSummarizationFailure（compaction.ts:545-547）：确定性错误不过重试预算？
        // 不 —— "boom" 不在白名单 ⇒ 环立即返回（fail fast），文案在 summarize 抛出。
        var events = new Events();
        var generator = rig((m, c, o) -> StreamIterator.from(transientError("boom")), events);
        assertThatThrownBy(() -> generator.summarize(ONE, "previous", null, 16_384, "threshold"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Summarization failed: boom");
        assertThat(events.lines).isEmpty(); // 不可重试 ⇒ 无事件
    }

    @Test
    void transientErrorIsRetriedAndSucceeds() {
        var calls = new AtomicInteger();
        StreamFn fn = (m, c, o) -> StreamIterator.from(
            calls.getAndIncrement() == 0 ? transientError("overloaded") : success("## Goal\nok"));
        var events = new Events();
        var result = rig(fn, events).summarize(ONE, null, null, 16_384, "threshold");
        assertThat(result.text()).isEqualTo("## Goal\nok");
        assertThat(events.lines).containsExactly(
            "scheduled|1|3|overloaded",
            "attempt_start|compaction|threshold",
            "finished");
    }

    @Test
    void exhaustedBudgetThrowsFinalErrorWithEvents() {
        // 一直瞬断 ⇒ 预算耗尽后环返回末次错误（finished 仍要发，pi :201 的
        // lastRetry 门），文案由 getSummarizationFailure 抛出。maxRetries=1 ⇒ 恰 1 次重试。
        var calls = new AtomicInteger();
        StreamFn fn = (m, c, o) -> {
            calls.incrementAndGet();
            return StreamIterator.from(transientError("overloaded"));
        };
        var events = new Events();
        var generator = new LlmSummaryGenerator(fn, () -> MODEL,
            () -> new RetrySettings(true, 1, 1, 1_000L), () -> false, events);
        assertThatThrownBy(() -> generator.summarize(ONE, null, null, 16_384, "overflow"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Summarization failed: overloaded");
        assertThat(calls).hasValue(2); // 初发 + 1 次重试
        assertThat(events.lines).containsExactly(
            "scheduled|1|1|overloaded",
            "attempt_start|compaction|overflow",
            "finished");
    }

    @Test
    void lengthStopThrowsIncompleteCapMessage() {
        // pi :549-551 固定文案；length 不是 error ⇒ 环不重试、无事件。
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("## Goal\nhalf"))).withStopReason("length");
        StreamFn fn = (m, c, o) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamDone("length", null, done)));
        var events = new Events();
        assertThatThrownBy(() -> rig(fn, events).summarize(ONE, null, null, 16_384, "manual"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Summarization failed: generation hit the token cap "
                + "and the summary is incomplete");
        assertThat(events.lines).isEmpty();
    }

    @Test
    void toolCallInSummaryThrows() {
        // pi :719-721：content.some(toolCall) ⇒ 抛。
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("t1", "read", java.util.Map.of()))).withStopReason("toolUse");
        StreamFn fn = (m, c, o) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamDone("tool_use", null, done)));
        var events = new Events();
        assertThatThrownBy(() -> rig(fn, events).summarize(ONE, null, null, 16_384, "manual"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Summarization attempted to call a tool");
    }

    @Test
    void abortDuringBackoffNormalizesToAborted() {
        // pi :210-221：睡眠被中止 ⇒ finished(false,…,err) + 归一化 aborted ⇒
        // getSummarizationFailure 放行（aborted 非 error/length）⇒ 部分文本照常返回，不抛。
        var calls = new AtomicInteger();
        StreamFn fn = (m, c, o) -> {
            calls.incrementAndGet();
            return StreamIterator.from(transientError("overloaded"));
        };
        var events = new Events();
        var generator = new LlmSummaryGenerator(fn, () -> MODEL, () -> FAST_RETRY,
            () -> true, events); // 恒中止 ⇒ 睡眠立即被掐
        var result = generator.summarize(ONE, null, null, 16_384, "threshold");
        assertThat(result.text()).isEmpty();
        assertThat(calls).hasValue(1); // schedule 之后中止，不再 produce
        assertThat(events.lines).containsExactly(
            "scheduled|1|3|overloaded",
            "finished"); // attempt_start 不发（睡眠没过），finished 必发
    }

    @Test
    void compactWithLlmSummaryProducesSummary() {
        var generator = new LlmSummaryGenerator(llm("## Goal\nx"), () -> MODEL);
        var entries = List.of(
            entry(new Message.UserMessage(List.of(new ContentBlock.TextContent("first")))),
            entry(new Message.AssistantMessage(List.of(new ContentBlock.TextContent("ok")))));
        // tokensBefore 由调用方传入（pi preparation 形状，3b）：本函数不再
        // 自己估 —— 单测直接钉「原样携带」。
        var result = CompactionService.compact(entries,
            new CompactionSettings(true, 16_384, 200), generator, 42L);
        assertThat(result.summary()).isEqualTo("## Goal\nx");
        assertThat(result.firstKeptEntryId()).isNotNull();
        assertThat(result.tokensBefore()).isEqualTo(42);
    }

    private static com.pijava.agent.entry.Entry entry(Message message) {
        return new com.pijava.agent.entry.Entry.Message(
            java.util.UUID.randomUUID().toString(), 0, null,
            java.time.Instant.now(), message, false);
    }
}

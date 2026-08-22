package com.pijava.agent.compaction;

import java.util.List;

import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CompactionService LLM 摘要（P6 对齐）：LlmSummaryGenerator 走 StreamFn 产出
 * 结构化摘要；失败/空输出回退 truncating。
 */
class CompactionServiceTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "summary");

    private static StreamFn llm(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return (messages, model, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.UsageInfo(5, 7, done),
            new StreamEvent.StreamDone("stop", null, done)));
    }

    @Test
    void llmSummaryUsesGeneratedTextNotFallback() {
        var generator = new LlmSummaryGenerator(llm("## Goal\nBuild the app."), () -> MODEL);
        var result = generator.summarize(List.of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            null, null, 16_384);
        assertThat(result.text()).isEqualTo("## Goal\nBuild the app.");
        assertThat(result.usage()).isNotNull();
    }

    @Test
    void llmSummaryFallsBackOnEmptyOutput() {
        var generator = new LlmSummaryGenerator(llm(""), () -> MODEL);
        var result = generator.summarize(List.of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            null, null, 16_384);
        assertThat(result.text()).contains("Compacted 1 earlier message(s).");
        assertThat(result.usage()).isNull();
    }

    @Test
    void llmSummaryFallsBackOnError() {
        var generator = new LlmSummaryGenerator(
            (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.StreamError("error",
                    new RuntimeException("boom"), AssistantMessage.empty()))),
            () -> MODEL);
        var result = generator.summarize(List.of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            "previous", null, 16_384);
        assertThat(result.text()).isEqualTo("previous");
    }

    @Test
    void compactWithLlmSummaryProducesSummary() {
        var generator = new LlmSummaryGenerator(llm("## Goal\nx"), () -> MODEL);
        var entries = List.of(
            entry(new Message.UserMessage(List.of(new ContentBlock.TextContent("first")))),
            entry(new Message.AssistantMessage(List.of(new ContentBlock.TextContent("ok")))));
        var result = CompactionService.compact(entries,
            new CompactionSettings(true, 16_384, 200), generator);
        assertThat(result.summary()).isEqualTo("## Goal\nx");
        assertThat(result.firstKeptEntryId()).isNotNull();
    }

    private static com.pijava.agent.entry.Entry entry(Message message) {
        return new com.pijava.agent.entry.Entry.Message(
            java.util.UUID.randomUUID().toString(), 0, null,
            java.time.Instant.now(), message, false);
    }
}

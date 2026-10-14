package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;


import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 5（pi {@code agent-loop.ts:409}）：终局助手消息盖<b>请求侧</b> pi 级别
 * {@code config.reasoning ?? "off"} —— 含 "off"；流中 message_update 不盖；
 * error/aborted/合成消息同盖。
 */
class AssistantThinkingLevelStampTest {

    /** 一段纯文本响应（start → text 三帧 → done）。 */
    private static List<StreamEvent> textTurn(String text) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextStart(0, partial),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private static StreamFn streamFn(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (model, context, options) -> {
            var script = scripts.get(index.getAndIncrement());
            return new StreamIterator() {
                private int i;
                @Override public boolean hasNext() { return i < script.size(); }
                @Override public StreamEvent next() { return script.get(i++); }
                @Override public void close() { }
            };
        };
    }

    private static PiLoop.Config config(StreamFn fn, ModelThinkingLevel level) {
        return new PiLoop.Config(
            ModelId.of("faux", "test-model"),
            level,
            ThinkingLevelMap.empty(),
            ToolExecution.defaultMode(),
            null,
            fn,
            null, null, null, null, null, null, null, null);
    }

    private static final class CollectingSink implements PiLoop.Sink {
        final List<PiLoop.Event> events = new CopyOnWriteArrayList<>();
        @Override public void emit(PiLoop.Event event) {
            events.add(event);
        }
    }

    @Test
    void stampsConfiguredLevelOnlyOnFinalAssistantMessage() {
        var sink = new CollectingSink();
        var context = Context.of(new ArrayList<>());

        PiLoop.run(List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))), context,
            config(streamFn(List.of(textTurn("hello"))),
                ModelThinkingLevel.of(new ThinkingLevel.Medium())),
            sink);

        var updates = sink.events.stream()
            .filter(PiLoop.Event.MessageUpdate.class::isInstance)
            .map(PiLoop.Event.MessageUpdate.class::cast)
            .map(e -> (Message.AssistantMessage) e.message())
            .toList();
        assertThat(updates).isNotEmpty();
        assertThat(updates).allSatisfy(m ->
            assertThat(m.thinkingLevel())
                .as("流中帧不盖请求侧级别（pi 只在终局 Object.assign）")
                .isNull());

        var finalMessage = (Message.AssistantMessage) sink.events.stream()
            .filter(PiLoop.Event.MessageEnd.class::isInstance)
            .map(e -> ((PiLoop.Event.MessageEnd) e).message())
            .filter(m -> m instanceof Message.AssistantMessage)
            .reduce((a, b) -> b).orElseThrow();
        assertThat(finalMessage.thinkingLevel()).isEqualTo("medium");
    }

    @Test
    void absentConfigLevelStampsOffIncludingSyntheticAbortedMessage() {
        var sink = new CollectingSink();
        var context = Context.of(new ArrayList<>());
        // 进场前已中止且 provider 一帧不发 ⇒ 合成 aborted 消息（PiLoopRunner:287-300）。
        var signal = com.pijava.ai.AbortSignal.create();
        signal.abort();

        PiLoop.run(List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))), context,
            new PiLoop.Config(
                ModelId.of("faux", "test-model"),
                ModelThinkingLevel.off(),
                ThinkingLevelMap.empty(),
                ToolExecution.defaultMode(),
                null,
                // 进场前已中止：provider 一帧不发（迭代器立竭），loop 合成 aborted 消息。
                (model, ctx, options) -> new StreamIterator() {
                    @Override public boolean hasNext() { return false; }
                    @Override public StreamEvent next() {
                        throw new java.util.NoSuchElementException();
                    }
                    @Override public void close() { }
                },
                signal, null, null, null, null, null, null, null),
            sink);

        var finalMessage = (Message.AssistantMessage) sink.events.stream()
            .filter(PiLoop.Event.MessageEnd.class::isInstance)
            .map(e -> ((PiLoop.Event.MessageEnd) e).message())
            .filter(m -> m instanceof Message.AssistantMessage)
            .reduce((a, b) -> b).orElseThrow();
        assertThat(finalMessage.stopReason()).isEqualTo(StopReason.ABORTED);
        assertThat(finalMessage.thinkingLevel())
            .as("config.reasoning 缺席恒 off（合成 aborted 消息同盖）")
            .isEqualTo("off");
    }
}

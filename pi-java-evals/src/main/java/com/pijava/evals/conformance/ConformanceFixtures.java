package com.pijava.evals.conformance;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.stream.StreamEvent;

/**
 * Offline FauxProvider sequences that cover C1–C10 ChatApi conformance.
 */
public final class ConformanceFixtures {

    private ConformanceFixtures() {}

    /** Plain text stream: Start → text → StreamDone(stop). */
    public static FauxProvider text() {
        return FauxProvider.text("hello");
    }

    /** Text stream that also emits {@link StreamEvent.UsageInfo}. */
    public static FauxProvider textWithUsage() {
        var empty = AssistantMessage.empty();
        var text = new ContentBlock.TextContent("hello");
        var mid = empty.withContent(List.of(text));
        var done = mid.withStopReason("stop");
        var usage = new StreamEvent.UsageInfo(3, 5, mid);
        return new FauxProvider("faux-usage", List.of(
            new StreamEvent.Start(empty),
            new StreamEvent.TextStart(0, empty.withContent(List.of(new ContentBlock.TextContent("")))),
            new StreamEvent.TextDelta(0, "hello", mid),
            new StreamEvent.TextEnd(0, "hello", mid),
            usage,
            new StreamEvent.StreamDone("stop", usage, done)
        ), 0);
    }

    /** Tool-call lifecycle including a JSON delta. */
    public static FauxProvider toolCall() {
        var empty = AssistantMessage.empty();
        var args = Map.<String, Object>of("q", "hi");
        var block = new ContentBlock.ToolUseContent("call_1", "echo", args);
        var done = empty.withContent(List.of(block)).withStopReason("tool_use");
        return new FauxProvider("faux-tool", List.of(
            new StreamEvent.Start(empty),
            new StreamEvent.ToolCallStart(0, empty),
            new StreamEvent.ToolCallDelta(0, "call_1", "{\"q\":\"hi\"}", empty),
            new StreamEvent.ToolCallEnd(0, "call_1", "echo", args, done.withStopReason(null)),
            new StreamEvent.StreamDone("tool_use", null, done)
        ), 0);
    }

    /** Start → StreamError. */
    public static FauxProvider error() {
        return FauxProvider.error("boom");
    }

    /** Thinking channel: Start → thinking → StreamDone. */
    public static FauxProvider thinking() {
        var empty = AssistantMessage.empty();
        var think = new ContentBlock.ThinkingContent("reason");
        var mid = empty.withContent(List.of(think));
        var done = mid.withStopReason("stop");
        return new FauxProvider("faux-think", List.of(
            new StreamEvent.Start(empty),
            new StreamEvent.ThinkingStart(0, empty),
            new StreamEvent.ThinkingDelta(0, "reason", mid),
            new StreamEvent.ThinkingEnd(0, "reason", mid),
            new StreamEvent.StreamDone("stop", null, done)
        ), 0);
    }

    /**
     * Pick the fixture that satisfies {@code caseName}.
     *
     * @param caseName conformance case id
     * @return matching FauxProvider
     */
    public static FauxProvider forCase(String caseName) {
        if (caseName.startsWith("C3") || caseName.startsWith("C4")) {
            return toolCall();
        }
        if (caseName.startsWith("C5")) {
            return textWithUsage();
        }
        if (caseName.startsWith("C6")) {
            return error();
        }
        if (caseName.startsWith("C9")) {
            return thinking();
        }
        return text();
    }
}

package com.pijava.evals.conformance;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.stream.StreamEvent;

/**
 * Checks the sealed {@link StreamEvent} sequence against the ChatApi contract:
 * start first, a single terminal event last, and nested text/thinking/tool
 * groups properly opened and closed.
 */
public final class StreamEventOrderValidator {

    private StreamEventOrderValidator() {}

    /**
     * Return human-readable problems; empty means the sequence is valid.
     *
     * @param events stream events in order
     * @return problem descriptions
     */
    public static List<String> problems(List<StreamEvent> events) {
        var problems = new ArrayList<String>();
        if (events == null || events.isEmpty()) {
            problems.add("stream is empty");
            return problems;
        }
        if (!(events.get(0) instanceof StreamEvent.Start)) {
            problems.add("first event must be Start");
        }
        var last = events.get(events.size() - 1);
        if (!(last instanceof StreamEvent.StreamDone)
                && !(last instanceof StreamEvent.StreamError)) {
            problems.add("last event must be StreamDone or StreamError");
        }

        int textOpen = 0;
        int thinkingOpen = 0;
        int toolOpen = 0;
        boolean terminated = false;
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            if (terminated) {
                problems.add("event after terminal at index " + i);
            }
            switch (event) {
                case StreamEvent.Start start -> {
                    if (i != 0) {
                        problems.add("duplicate Start at index " + i);
                    }
                }
                case StreamEvent.TextStart ignored -> textOpen++;
                case StreamEvent.TextDelta ignored -> {
                    if (textOpen == 0) {
                        problems.add("TextDelta without TextStart at index " + i);
                    }
                }
                case StreamEvent.TextEnd ignored -> {
                    if (textOpen == 0) {
                        problems.add("TextEnd without TextStart at index " + i);
                    } else {
                        textOpen--;
                    }
                }
                case StreamEvent.ThinkingStart ignored -> thinkingOpen++;
                case StreamEvent.ThinkingDelta ignored -> {
                    if (thinkingOpen == 0) {
                        problems.add("ThinkingDelta without ThinkingStart at index " + i);
                    }
                }
                case StreamEvent.ThinkingEnd ignored -> {
                    if (thinkingOpen == 0) {
                        problems.add("ThinkingEnd without ThinkingStart at index " + i);
                    } else {
                        thinkingOpen--;
                    }
                }
                case StreamEvent.ToolCallStart ignored -> toolOpen++;
                case StreamEvent.ToolCallDelta ignored -> {
                    if (toolOpen == 0) {
                        problems.add("ToolCallDelta without ToolCallStart at index " + i);
                    }
                }
                case StreamEvent.ToolCallEnd ignored -> {
                    if (toolOpen == 0) {
                        problems.add("ToolCallEnd without ToolCallStart at index " + i);
                    } else {
                        toolOpen--;
                    }
                }
                case StreamEvent.UsageInfo ignored -> { }
                case StreamEvent.StreamDone ignored -> terminated = true;
                case StreamEvent.StreamError ignored -> terminated = true;
            }
        }
        if (textOpen > 0) {
            problems.add("unclosed text block");
        }
        if (thinkingOpen > 0) {
            problems.add("unclosed thinking block");
        }
        if (toolOpen > 0) {
            problems.add("unclosed tool-call block");
        }
        return problems;
    }

    /**
     * Throw if the sequence is invalid.
     *
     * @param events stream events in order
     */
    public static void assertValid(List<StreamEvent> events) {
        var found = problems(events);
        if (!found.isEmpty()) {
            throw new AssertionError(String.join("; ", found));
        }
    }
}

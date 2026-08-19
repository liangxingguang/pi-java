package com.pijava.evals.conformance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalContext;
import com.pijava.evals.api.EvalResult;
import com.pijava.evals.api.EvalSuite;

/**
 * ChatApi conformance cases C1–C10. Each case inspects {@link EvalContext#chatApi()}.
 */
public final class ChatApiConformanceSuite implements EvalSuite {

    private static final ModelId<?> MODEL = ModelId.of("faux", "eval");

    @Override
    public String name() {
        return "chat-api-conformance";
    }

    @Override
    public List<EvalCase> cases() {
        return List.of(
            evalCase("C1-stream-start-end", ChatApiConformanceSuite::c1),
            evalCase("C2-text-flow", ChatApiConformanceSuite::c2),
            evalCase("C3-tool-lifecycle", ChatApiConformanceSuite::c3),
            evalCase("C4-tool-json", ChatApiConformanceSuite::c4),
            evalCase("C5-usage-info", ChatApiConformanceSuite::c5),
            evalCase("C6-stream-error", ChatApiConformanceSuite::c6),
            evalCase("C7-send-matches-stream", ChatApiConformanceSuite::c7),
            evalCase("C8-multi-turn-roles", ChatApiConformanceSuite::c8),
            evalCase("C9-thinking-flow", ChatApiConformanceSuite::c9),
            evalCase("C10-partial-snapshots", ChatApiConformanceSuite::c10)
        );
    }

    private static void c1(EvalContext ctx) {
        var events = collect(ctx.chatApi(), ping());
        StreamEventOrderValidator.assertValid(events);
    }

    private static void c2(EvalContext ctx) {
        var events = collect(ctx.chatApi(), ping());
        assertHas(events, StreamEvent.TextStart.class);
        assertHas(events, StreamEvent.TextDelta.class);
        assertHas(events, StreamEvent.TextEnd.class);
        var done = requireDone(events);
        if (!"stop".equals(done.reason())) {
            throw new AssertionError("expected stop reason, got " + done.reason());
        }
        if (events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCallStart)) {
            throw new AssertionError("plain text stream must not emit tool calls");
        }
    }

    private static void c3(EvalContext ctx) {
        var events = collect(ctx.chatApi(), ping());
        assertHas(events, StreamEvent.ToolCallStart.class);
        assertHas(events, StreamEvent.ToolCallDelta.class);
        assertHas(events, StreamEvent.ToolCallEnd.class);
        var done = requireDone(events);
        if (!"tool_use".equals(done.reason()) && !"toolUse".equals(done.reason())) {
            throw new AssertionError("expected tool_use reason, got " + done.reason());
        }
    }

    private static void c4(EvalContext ctx) {
        var events = collect(ctx.chatApi(), ping());
        var json = new StringBuilder();
        for (var event : events) {
            if (event instanceof StreamEvent.ToolCallDelta delta) {
                json.append(delta.jsonDelta());
            }
        }
        if (json.isEmpty()) {
            throw new AssertionError("no ToolCallDelta JSON accumulated");
        }
        try {
            var node = ctx.json().readTree(json.toString());
            if (!node.isObject()) {
                throw new AssertionError("tool JSON is not an object: " + json);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("invalid tool JSON: " + e.getMessage(), e);
        }
    }

    private static void c5(EvalContext ctx) {
        var events = collect(ctx.chatApi(), ping());
        var usage = events.stream()
            .filter(e -> e instanceof StreamEvent.UsageInfo)
            .map(e -> (StreamEvent.UsageInfo) e)
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing UsageInfo"));
        if (usage.inputTokens() < 0 || usage.outputTokens() < 0) {
            throw new AssertionError("usage tokens must be non-negative");
        }
    }

    private static void c6(EvalContext ctx) {
        var events = collect(ctx.chatApi(), ping());
        var error = events.stream()
            .filter(e -> e instanceof StreamEvent.StreamError)
            .map(e -> (StreamEvent.StreamError) e)
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing StreamError"));
        if (error.error() == null && (error.reason() == null || error.reason().isBlank())) {
            throw new AssertionError("StreamError must carry a reason or throwable");
        }
    }

    private static void c7(EvalContext ctx) {
        var request = ping();
        var options = ApiOptions.defaults();
        var events = collect(ctx.chatApi(), request);
        var done = requireDone(events);
        var streamed = done.partial().content();
        var sent = ctx.chatApi().send(request, options);
        if (!sent.content().equals(streamed)) {
            throw new AssertionError("send() content " + sent.content()
                + " != stream StreamDone content " + streamed);
        }
    }

    private static void c8(EvalContext ctx) {
        List<Message> messages = List.of(
            new Message.SystemMessage(List.of(new ContentBlock.TextContent("sys"))),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))),
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("yo"))),
            new Message.ToolResultMessage(
                "call_1", "echo", List.of(new ContentBlock.TextContent("ok")), false)
        );
        var events = collect(ctx.chatApi(), StreamRequest.of(MODEL, messages));
        if (events.isEmpty()) {
            throw new AssertionError("multi-turn request produced no events");
        }
    }

    private static void c9(EvalContext ctx) {
        var events = collect(ctx.chatApi(), ping());
        assertHas(events, StreamEvent.ThinkingStart.class);
        assertHas(events, StreamEvent.ThinkingDelta.class);
        assertHas(events, StreamEvent.ThinkingEnd.class);
    }

    private static void c10(EvalContext ctx) {
        var events = collect(ctx.chatApi(), ping());
        int lastSize = 0;
        for (var event : events) {
            if (event.partial() == null) {
                throw new AssertionError("partial is null on " + event.getClass().getSimpleName());
            }
            int size = event.partial().content().size();
            if (size < lastSize) {
                throw new AssertionError("partial content shrank from " + lastSize + " to " + size);
            }
            lastSize = size;
        }
    }

    private static StreamRequest ping() {
        return StreamRequest.of(MODEL, List.of(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("ping")))));
    }

    private static List<StreamEvent> collect(ChatApi api, StreamRequest request) {
        var events = new ArrayList<StreamEvent>();
        try (var iterator = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iterator.hasNext()) {
                events.add(iterator.next());
            }
        }
        return events;
    }

    private static void assertHas(List<StreamEvent> events, Class<? extends StreamEvent> type) {
        if (events.stream().noneMatch(type::isInstance)) {
            throw new AssertionError("missing " + type.getSimpleName());
        }
    }

    private static StreamEvent.StreamDone requireDone(List<StreamEvent> events) {
        return events.stream()
            .filter(e -> e instanceof StreamEvent.StreamDone)
            .map(e -> (StreamEvent.StreamDone) e)
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing StreamDone"));
    }

    private static EvalCase evalCase(String name, Consumer<EvalContext> body) {
        return new EvalCase() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public EvalResult run(EvalContext ctx) {
                var started = System.nanoTime();
                try {
                    body.accept(ctx);
                    return EvalResult.passed(name, Duration.ofNanos(System.nanoTime() - started));
                } catch (Exception | AssertionError e) {
                    var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    return EvalResult.failed(name, message, Duration.ofNanos(System.nanoTime() - started));
                }
            }
        };
    }
}

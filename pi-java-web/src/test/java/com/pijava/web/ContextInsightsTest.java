package com.pijava.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Context insights composition / trend / event computation. */
class ContextInsightsTest {

    private static final Instant T0 = Instant.parse("2026-11-11T00:00:00Z");

    @Test
    void buildsCompositionTrendAndEvents() {
        var user = new Entry.Message("e1", 1, null, T0,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello")), T0), null);
        var usage = new Usage(100, 20, 30, 0, null, null, 150,
            new Usage.Cost(0.1, 0.2, 0.05, 0, 0.35));
        var assistant = new Entry.Message("e2", 2, "e1", T0.plusSeconds(1),
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("hi"),
                    new ContentBlock.ToolUseContent("call-1", "read", Map.of("path", "a.txt"))),
                StopReason.TOOL_USE, null, "api", "provider", "model",
                usage, T0.plusSeconds(1), null, null),
            null);
        var result = new Entry.Message("e3", 3, "e2", T0.plusSeconds(2),
            new Message.ToolResultMessage("call-1", "read",
                List.of(new ContentBlock.TextContent("file body")), false),
            null);
        var modelChange = new Entry.ModelChange("e4", 4, "e3", T0.plusSeconds(3),
            "provider", "other-model");

        var report = ContextInsights.build(
            "s1", "provider/model", 1000, 8000, 16384,
            "You are helpful.",
            List.of(new ContextInsights.ToolView("read", "Read a file", "{\"type\":\"object\"}")),
            List.of(user, assistant, result, modelChange));

        assertThat(report.contextWindow()).isEqualTo(1000);
        assertThat(report.contextTokens()).isEqualTo(153.0);
        assertThat(report.percent()).isCloseTo(15.3, within(0.001));
        assertThat(report.categories()).extracting(ContextReport.Category::key)
            .containsExactly("system", "tools", "user", "injected", "answers",
                "reasoning", "toolCalls", "toolResults");

        var tokens = report.categories().stream()
            .collect(Collectors.toMap(ContextReport.Category::key, ContextReport.Category::tokens));
        assertThat(tokens.get("system")).isEqualTo(4.0);
        assertThat(tokens.get("user")).isEqualTo(2.0);
        assertThat(tokens.get("answers")).isEqualTo(1.0);
        assertThat(tokens.get("toolResults")).isEqualTo(3.0);
        assertThat(tokens.get("toolCalls")).isGreaterThan(0);

        assertThat(report.totals().turns()).isEqualTo(1);
        assertThat(report.totals().requests()).isEqualTo(1);
        assertThat(report.totals().toolCalls()).isEqualTo(1);
        assertThat(report.totals().cacheHitRate()).isCloseTo(30.0 / 130.0, within(0.0001));

        assertThat(report.steps()).hasSize(1);
        var step = report.steps().get(0);
        assertThat(step.reported()).isTrue();
        assertThat(step.promptTokens()).isEqualTo(130.0);
        assertThat(step.outputTokens()).isEqualTo(20.0);
        assertThat(step.categories().get("system")).isEqualTo(4.0);

        assertThat(report.events()).extracting(ContextReport.Event::type)
            .containsExactly("model");
    }
}

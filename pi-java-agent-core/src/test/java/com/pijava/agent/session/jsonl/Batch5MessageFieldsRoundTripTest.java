package com.pijava.agent.session.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.pijava.agent.session.SessionJson;
import com.pijava.ai.Usage;
import com.pijava.ai.message.AssistantMessageDiagnostic;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import org.junit.jupiter.api.Test;

/**
 * 批 5（B148/B234/消息级 thinkingLevel，docs/14 §3.9）：五个新持久化键的
 * 落线/回读/旧盘兼容：
 * assistant.{@code responseModel, thinkingLevel, diagnostics, durationMs} 与
 * toolResult.{@code durationMs}。缺键即 null（NON_NULL 不写出）。
 */
class Batch5MessageFieldsRoundTripTest {

    private static Message.AssistantMessage assistant() {
        var details = JsonNodeFactory.instance.objectNode()
            .put("status", 401).put("model", "gateway-model");
        var error = new AssistantMessageDiagnostic.DiagnosticErrorInfo(
            "PiMessagesResponseError", "401 Unauthorized: nope", null,
            JsonNodeFactory.instance.textNode("unauthorized"));
        return new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("hi")),
            StopReason.ERROR, null,
            "pi-messages", "radius", "gateway-model",
            "upstream-model", "high", "medium",
            List.of(new AssistantMessageDiagnostic(
                "pi_messages_response_failure", 1_700_000_000_000L, error, details)),
            new Usage(3, 2, 0, 0, null, null, 5, Usage.Cost.zero()),
            Instant.ofEpochMilli(1_700_000_000_100L),
            "401 Unauthorized: nope", null, 77L);
    }

    private static Message.ToolResultMessage toolResult(Long durationMs) {
        return new Message.ToolResultMessage("tc1", "bash",
            List.of(new ContentBlock.TextContent("ok")), null, null, List.of(),
            false, Instant.ofEpochMilli(1_700_000_000_200L), durationMs);
    }

    @Test
    void assistantKeysWriteInPiOrder() {
        var node = SessionJson.messageNode(assistant());
        var names = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);

        assertThat(node.path("responseModel").asText()).isEqualTo("upstream-model");
        assertThat(node.path("providerThinkingLevel").asText()).isEqualTo("high");
        assertThat(node.path("thinkingLevel").asText()).isEqualTo("medium");
        assertThat(node.path("durationMs").asLong()).isEqualTo(77L);
        // pi types.ts 声明序：model, responseModel, providerThinkingLevel, thinkingLevel, diagnostics
        assertThat(names.indexOf("model") + 1).isEqualTo(names.indexOf("responseModel"));
        assertThat(names.indexOf("responseModel") + 1)
            .isEqualTo(names.indexOf("providerThinkingLevel"));
        assertThat(names.indexOf("providerThinkingLevel") + 1)
            .isEqualTo(names.indexOf("thinkingLevel"));
        assertThat(names.indexOf("thinkingLevel") + 1)
            .isEqualTo(names.indexOf("diagnostics"));
        // 诊断逐值（type/error.code/details.status）。
        var diagnostic = node.path("diagnostics").get(0);
        assertThat(diagnostic.path("type").asText())
            .isEqualTo("pi_messages_response_failure");
        assertThat(diagnostic.path("error").path("name").asText())
            .isEqualTo("PiMessagesResponseError");
        assertThat(diagnostic.path("error").path("code").asText())
            .isEqualTo("unauthorized");
        assertThat(diagnostic.path("details").path("status").asInt()).isEqualTo(401);
        assertThat(diagnostic.path("timestamp").asLong()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void assistantRoundTripsAllKeys() {
        var restored = (Message.AssistantMessage) MessageJsonCodec.decode(
            SessionJson.messageNode(assistant()));

        assertThat(restored.responseModel()).isEqualTo("upstream-model");
        assertThat(restored.providerThinkingLevel()).isEqualTo("high");
        assertThat(restored.thinkingLevel()).isEqualTo("medium");
        assertThat(restored.durationMs()).isEqualTo(77L);
        assertThat(restored.diagnostics()).hasSize(1);
        assertThat(restored.diagnostics().get(0).details().path("status").asInt())
            .isEqualTo(401);
        assertThat(restored.diagnostics().get(0).error().code().asText())
            .isEqualTo("unauthorized");
    }

    @Test
    void legacyAssistantSessionDecodesNewKeysToNull() throws Exception {
        var node = SessionJson.mapper().readTree(
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"api\":\"anthropic-messages\",\"provider\":\"anthropic\","
                + "\"model\":\"claude-opus-4-8\"}");

        var restored = (Message.AssistantMessage) MessageJsonCodec.decode(node);
        assertThat(restored.responseModel()).isNull();
        assertThat(restored.thinkingLevel()).isNull();
        assertThat(restored.diagnostics()).isNull();
        assertThat(restored.durationMs()).isNull();
    }

    @Test
    void toolDurationWritesAndRoundTrips() {
        var node = SessionJson.messageNode(toolResult(42L));
        assertThat(node.path("durationMs").asLong()).isEqualTo(42L);

        var restored = (Message.ToolResultMessage) MessageJsonCodec.decode(node);
        assertThat(restored.durationMs()).isEqualTo(42L);
    }

    @Test
    void toolWithoutDurationOmitsKeyAndDecodesNull() {
        var node = SessionJson.messageNode(toolResult(null));
        assertThat(node.has("durationMs")).isFalse();

        var restored = (Message.ToolResultMessage) MessageJsonCodec.decode(node);
        assertThat(restored.durationMs()).isNull();
    }

    @Test
    void legacyToolSessionWithoutDurationDecodes() throws Exception {
        var node = SessionJson.mapper().readTree(
            "{\"role\":\"toolResult\",\"toolCallId\":\"tc1\",\"toolName\":\"bash\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}");

        var restored = (Message.ToolResultMessage) MessageJsonCodec.decode(node);
        assertThat(restored.durationMs()).isNull();
    }
}

package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.session.SessionMutation;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSONL roundtrip for the observability record fields: tool_finished,
 * StepAttempt LLM summary fields, OperationFinished durationMs — plus
 * back-compat with records written before these fields existed.
 */
class RecordObservabilityCodecTest {

    private SessionMutation.Record encodeThenParse(SessionMutation.Record mutation) {
        String line = JsonlCodec.encodeMutation(mutation);
        var result = JsonlCodec.parseMutation(line);
        assertThat(result.ok()).as("parse should succeed: %s", result.error()).isTrue();
        return (SessionMutation.Record) result.value();
    }

    private Entry encodeThenParseEntry(Entry entry) {
        String line = JsonlCodec.encodeMutation(new SessionMutation.Entry(null, entry));
        var result = JsonlCodec.parseMutation(line);
        assertThat(result.ok()).as("parse should succeed: %s", result.error()).isTrue();
        return ((SessionMutation.Entry) result.value()).entry();
    }

    @Test
    void assistantEntryRoundTripsItsStopReason() {
        var entry = new Entry.Message("e-1", 11L, null, Instant.now(),
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("done")), "tool_use", null), null);

        var parsed = (Entry.Message) encodeThenParseEntry(entry);

        assertThat(parsed.message()).isInstanceOf(Message.AssistantMessage.class);
        assertThat(((Message.AssistantMessage) parsed.message()).stopReason())
            .isEqualTo("tool_use");
    }

    @Test
    void assistantEntryWithoutStopReasonDecodesAsNull() {
        // 旧文件只有 {role, content}（Task 2 之前的格式）。
        var node = new ObjectMapper().createObjectNode();
        node.put("role", "assistant");
        node.putArray("content").addObject().put("type", "text").put("text", "old");

        var decoded = MessageJsonCodec.decode(node);

        assertThat(((Message.AssistantMessage) decoded).stopReason()).isNull();
        assertThat(((Message.AssistantMessage) decoded).deferred()).isNull();
    }

    @Test
    void toolFinishedRoundtrips() {
        var rec = new LaneRecord.ToolFinished("rec-7", 12L, "main", Instant.now(),
            "run-1", "call-1", "bash", true, false, "entry-9", 456L);
        var parsed = encodeThenParse(new SessionMutation.Record(rec)).record();

        assertThat(parsed).isInstanceOf(LaneRecord.ToolFinished.class);
        var finished = (LaneRecord.ToolFinished) parsed;
        assertThat(finished.id()).isEqualTo("rec-7");
        assertThat(finished.seq()).isEqualTo(12L);
        assertThat(finished.lane()).isEqualTo("main");
        assertThat(finished.runId()).isEqualTo("run-1");
        assertThat(finished.toolCallId()).isEqualTo("call-1");
        assertThat(finished.toolName()).isEqualTo("bash");
        assertThat(finished.isError()).isTrue();
        assertThat(finished.terminate()).isFalse();
        assertThat(finished.resultEntryId()).isEqualTo("entry-9");
        assertThat(finished.durationMs()).isEqualTo(456L);
    }

    @Test
    void stepAttemptWithSummaryFieldsRoundtrips() {
        var rec = new LaneRecord.StepAttempt("rec-8", 13L, "main", Instant.now(),
            "run-1", StepKind.ASSISTANT, 1, "entry-10", null,
            "anthropic/claude-sonnet-4-6", 14, 7, "budget=8000", 2314L);
        var parsed = encodeThenParse(new SessionMutation.Record(rec)).record();

        var attempt = (LaneRecord.StepAttempt) parsed;
        assertThat(attempt.model()).isEqualTo("anthropic/claude-sonnet-4-6");
        assertThat(attempt.messageCount()).isEqualTo(14);
        assertThat(attempt.toolCount()).isEqualTo(7);
        assertThat(attempt.thinking()).isEqualTo("budget=8000");
        assertThat(attempt.durationMs()).isEqualTo(2314L);
    }

    @Test
    void stepAttemptWithoutSummaryFieldsDecodesWithNulls() {
        // Record written by an older binary: no model/messageCount/toolCount/thinking/durationMs.
        String legacyLine = """
            {"kind":"record","type":"step_attempt","id":"rec-9","seq":14,"lane":"main",\
            "timestamp":1798713600000,"runId":"run-1","step":"assistant",\
            "attempt":0,"resultEntryId":"entry-9","compactionReason":null}\
            """;
        var parsed = JsonlCodec.parseMutation(legacyLine);
        assertThat(parsed.ok()).as("parse should succeed: %s", parsed.error()).isTrue();

        var attempt = (LaneRecord.StepAttempt) ((SessionMutation.Record) parsed.value()).record();
        assertThat(attempt.model()).isNull();
        assertThat(attempt.messageCount()).isNull();
        assertThat(attempt.toolCount()).isNull();
        assertThat(attempt.thinking()).isNull();
        assertThat(attempt.durationMs()).isNull();
    }

    @Test
    void operationFinishedWithDurationRoundtrips() {
        var rec = new LaneRecord.OperationFinished("rec-10", 15L, "main", Instant.now(),
            "run-1", OperationOutcome.COMPLETED, null, 5234L);
        var parsed = encodeThenParse(new SessionMutation.Record(rec)).record();

        var finished = (LaneRecord.OperationFinished) parsed;
        assertThat(finished.outcome()).isEqualTo(OperationOutcome.COMPLETED);
        assertThat(finished.durationMs()).isEqualTo(5234L);
    }

    @Test
    void operationFinishedWithoutDurationDecodesNull() {
        String legacyLine = """
            {"kind":"record","type":"operation_finished","id":"rec-11","seq":16,"lane":"main",\
            "timestamp":1798713600000,"runId":"run-1","outcome":"completed","error":null}\
            """;
        var parsed = JsonlCodec.parseMutation(legacyLine);
        assertThat(parsed.ok()).as("parse should succeed: %s", parsed.error()).isTrue();

        var finished = (LaneRecord.OperationFinished) ((SessionMutation.Record) parsed.value()).record();
        assertThat(finished.durationMs()).isNull();
    }

    @Test
    void stepAttemptSummaryOmittedFieldsAreNotSerialized() {
        var rec = new LaneRecord.StepAttempt("rec-12", 17L, "main", Instant.now(),
            "run-1", StepKind.ASSISTANT, 0, "entry-9", null,
            null, null, null, null, null);
        String line = JsonlCodec.encodeMutation(new SessionMutation.Record(rec));

        assertThat(line).doesNotContain("\"model\"");
        assertThat(line).doesNotContain("\"messageCount\"");
        assertThat(line).doesNotContain("\"toolCount\"");
        assertThat(line).doesNotContain("\"thinking\"");
        assertThat(line).doesNotContain("\"durationMs\"");
    }

    @Test
    void toolFinishedArgsAreMapCopySafe() {
        var rec = new LaneRecord.ToolStarted("rec-13", 18L, "main", Instant.now(),
            "run-1", "asst-1", 0, "call-2", "read",
            Map.of("path", "a.txt"), "entry-9", null);
        assertThat(rec.type()).isEqualTo("tool_started");
    }
}

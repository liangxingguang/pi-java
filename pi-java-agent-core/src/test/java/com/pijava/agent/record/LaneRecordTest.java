package com.pijava.agent.record;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LaneRecordTest {

    @Test
    void recordHeaderFields() {
        var now = Instant.now();
        var header = new RecordHeader(1L, now);

        assertThat(header.seq()).isEqualTo(1L);
        assertThat(header.timestamp()).isEqualTo(now);
    }

    @Test
    void newHeaderCreatesTimestamp() {
        var header = LaneRecord.newHeader(5L);
        assertThat(header.seq()).isEqualTo(5L);
        assertThat(header.timestamp()).isNotNull();
    }

    @Test
    void operationStarted() {
        var header = LaneRecord.newHeader(0L);
        var rec = new LaneRecord.OperationStarted(header, "run-1", "test intent");

        assertThat(rec.header()).isSameAs(header);
        assertThat(rec.runId()).isEqualTo("run-1");
        assertThat(rec.intent()).isEqualTo("test intent");
    }

    @Test
    void abortRequested() {
        var header = LaneRecord.newHeader(1L);
        var rec = new LaneRecord.AbortRequested(header, "user cancelled");

        assertThat(rec.reason()).isEqualTo("user cancelled");
    }

    @Test
    void operationFinished() {
        var header = LaneRecord.newHeader(2L);
        var rec = new LaneRecord.OperationFinished(header, "run-1", "completed");

        assertThat(rec.status()).isEqualTo("completed");
    }

    @Test
    void stepAttempt() {
        var header = LaneRecord.newHeader(3L);
        var rec = new LaneRecord.StepAttempt(header, 0, 100, 50);

        assertThat(rec.stepIndex()).isEqualTo(0);
        assertThat(rec.inputTokens()).isEqualTo(100);
        assertThat(rec.outputTokens()).isEqualTo(50);
    }

    @Test
    void usageRecord() {
        var header = LaneRecord.newHeader(4L);
        var rec = new LaneRecord.UsageRecord(header, 200, 100, "claude-sonnet");

        assertThat(rec.modelId()).isEqualTo("claude-sonnet");
    }

    @Test
    void toolStartedDefensiveCopy() {
        var header = LaneRecord.newHeader(5L);
        var args = new java.util.HashMap<String, Object>(Map.of("path", "/test"));
        var rec = new LaneRecord.ToolStarted(header, "toolu_1", "read", args);

        args.put("path", "/modified");
        assertThat(rec.arguments()).containsEntry("path", "/test");
    }

    @Test
    void queueEnqueued() {
        var header = LaneRecord.newHeader(6L);
        var rec = new LaneRecord.QueueEnqueued(header, "steer", "content");

        assertThat(rec.queueType()).isEqualTo("steer");
        assertThat(rec.content()).isEqualTo("content");
    }

    @Test
    void queueCancelled() {
        var header = LaneRecord.newHeader(7L);
        var rec = new LaneRecord.QueueCancelled(header, "followUp");

        assertThat(rec.queueType()).isEqualTo("followUp");
    }

    @Test
    void writeDeferred() {
        var header = LaneRecord.newHeader(8L);
        var rec = new LaneRecord.WriteDeferred(header, "entry-1");

        assertThat(rec.entryId()).isEqualTo("entry-1");
    }
}

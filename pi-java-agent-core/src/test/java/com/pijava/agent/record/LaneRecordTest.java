package com.pijava.agent.record;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LaneRecordTest {

    @Test
    void operationStartedWithRunIntent() {
        var now = Instant.now();
        var intent = new LaneRecord.OperationStarted.Run(
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), null, null);
        var rec = new LaneRecord.OperationStarted("rec-1", 1L, "main", now, null, intent);

        assertThat(rec.id()).isEqualTo("rec-1");
        assertThat(rec.seq()).isEqualTo(1L);
        assertThat(rec.lane()).isEqualTo("main");
        assertThat(rec.timestamp()).isEqualTo(now);
        assertThat(rec.intent()).isSameAs(intent);
    }

    @Test
    void abortRequested() {
        var rec = new LaneRecord.AbortRequested("rec-1", 1L, "main", Instant.now(), "run-1");
        assertThat(rec.runId()).isEqualTo("run-1");
        assertThat(rec.type()).isEqualTo("abort_requested");
    }

    @Test
    void operationFinished() {
        var rec = new LaneRecord.OperationFinished("rec-1", 1L, "main", Instant.now(),
            "run-1", OperationOutcome.COMPLETED, null);
        assertThat(rec.outcome()).isEqualTo(OperationOutcome.COMPLETED);
        assertThat(rec.error()).isNull();
    }

    @Test
    void stepAttempt() {
        var rec = new LaneRecord.StepAttempt("rec-1", 1L, "main", Instant.now(),
            "run-1", StepKind.ASSISTANT, 0, "entry-9", null);
        assertThat(rec.step()).isEqualTo(StepKind.ASSISTANT);
        assertThat(rec.attempt()).isEqualTo(0);
        assertThat(rec.resultEntryId()).isEqualTo("entry-9");
    }

    @Test
    void toolStarted() {
        var rec = new LaneRecord.ToolStarted("rec-1", 1L, "main", Instant.now(),
            "run-1", "asst-1", 0, "call-1", "bash",
            Map.of("cmd", "ls"), "entry-9", ReplayKind.NEVER);
        assertThat(rec.toolName()).isEqualTo("bash");
        assertThat(rec.replay()).isEqualTo(ReplayKind.NEVER);
        assertThat(rec.effectiveArgs()).containsEntry("cmd", "ls");
    }

    @Test
    void queueEnqueued() {
        var target = new com.pijava.agent.entry.ProvisionedEntry<>(
            new com.pijava.agent.entry.Entry.Message("e-1", 0, null, null,
                new Message.UserMessage(List.of()), null));
        var rec = new LaneRecord.QueueEnqueued("rec-1", 1L, "main", Instant.now(),
            QueueKind.STEER, "run-1", target);
        assertThat(rec.queue()).isEqualTo(QueueKind.STEER);
        assertThat(rec.target()).isSameAs(target);
    }

    @Test
    void usageRecordAccumulatesUsage() {
        var usage = new Usage(100, 50, 10, 5, null, null, 165,
            new Usage.Cost(0.1, 0.2, 0.01, 0.02, 0.33));
        var rec = new LaneRecord.UsageRecord("rec-1", 1L, "main", Instant.now(),
            usage, UsageCause.ASSISTANT, "run-1", "entry-9", null, 0, "stop");
        assertThat(rec.usage()).isEqualTo(usage);
        assertThat(rec.cause()).isEqualTo(UsageCause.ASSISTANT);
    }
}
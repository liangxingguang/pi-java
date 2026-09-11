package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 23 step 6: an error entry counts as the operation's terminal failure
 * only when a step attempt or a deferred fetch produced it, and never when it
 * is the target of an applied deferred write (pi {@code reducer.ts:614-640}).
 *
 * <p>Pure fold tests: no harness, no driving — the lane's bounded slice is
 * handed to {@link LaneStateFolder#fold} directly.</p>
 */
class TerminalFailureFoldTest {

    private static LaneStateFolder.FoldedState fold(List<LaneRecord> records,
                                                    List<Entry> ownEntries) {
        return LaneStateFolder.fold("default", records, ownEntries, List.of());
    }

    /** An assistant entry that ended in an error, with no provider handle. */
    private static Entry errorAssistant(String id) {
        return new Entry.Message(id, 0, null, null,
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("boom")), "error", null), null);
    }

    /** An assistant entry deferred to the provider, carrying its handle. */
    private static Entry deferredAssistant(String id, String handleId) {
        var handle = new DeferredHandle("faux", "test-model", "faux-api", handleId,
            null, null, Map.of());
        return new Entry.Message(id, 0, null, null,
            new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("pending")), "deferred", handle), null);
    }

    /** The operation start every other record must reference. */
    private static LaneRecord started(String runId) {
        return new LaneRecord.OperationStarted(runId, 0, "default", null, null,
            new LaneRecord.OperationStarted.Run(List.of(), List.of(), null, null));
    }

    private static LaneRecord stepAttempt(String runId, String resultEntryId) {
        return new LaneRecord.StepAttempt("s-" + runId, 0, "default", null, runId,
            StepKind.ASSISTANT, 0, resultEntryId, null, null, null, null, null, null);
    }

    private static LaneRecord deferredFetchUsage(String runId, String entryId) {
        return new LaneRecord.UsageRecord("u-1", 0, "default", null, Usage.of(1, 1),
            UsageCause.DEFERRED_FETCH, runId, entryId, null, null, null);
    }

    @Test
    void errorEntryProducedByAStepIsAttributedToStep() {
        var assistant = errorAssistant("a-1");
        var record = new LaneRecord.StepAttempt("s-1", 0, "default", null, "run-1",
            StepKind.ASSISTANT, 0, "a-1", null, null, null, null, null, null);

        var folded = fold(List.of(started("run-1"), record), List.of(assistant));

        assertThat(folded.terminalFailure()).isNotNull();
        assertThat(folded.terminalFailure().entryId()).isEqualTo("a-1");
        assertThat(folded.terminalFailure().source()).isEqualTo("step");
    }

    @Test
    void errorEntryPrecededByADeferredEntryIsAttributedToDeferredFetch() {
        var deferred = deferredAssistant("a-0", "handle-1");
        var error = errorAssistant("a-1");

        var folded = fold(List.of(), List.of(deferred, error));

        assertThat(folded.terminalFailure().source()).isEqualTo("deferred_fetch");
        assertThat(folded.terminalFailure().entryId()).isEqualTo("a-1");
    }

    @Test
    void errorEntryWithNoProvenanceYieldsNoTerminalFailure() {
        var folded = fold(List.of(), List.of(errorAssistant("a-1")));

        assertThat(folded.terminalFailure()).isNull();
    }

    @Test
    void deferredWriteErrorEntryIsNotATerminalFailure() {
        var error = errorAssistant("a-1");
        var write = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(error));

        var folded = fold(List.of(write), List.of(error));

        assertThat(folded.terminalFailure()).isNull();
    }

    /** A deferred-fetch usage record is provenance too (pi reducer.ts:614-640). */
    @Test
    void errorEntryWithDeferredFetchUsageIsAttributedToDeferredFetch() {
        var error = errorAssistant("a-1");
        var usage = deferredFetchUsage("run-1", "a-1");

        var folded = fold(List.of(started("run-1"), usage), List.of(error));

        assertThat(folded.terminalFailure()).isNotNull();
        assertThat(folded.terminalFailure().source()).isEqualTo("deferred_fetch");
    }

    /** The newest entry is not an error: nothing failed. */
    @Test
    void nonErrorNewestEntryYieldsNoTerminalFailure() {
        var error = errorAssistant("a-1");
        var recovered = deferredAssistant("a-2", "handle-1");
        var step = stepAttempt("run-1", "a-1");

        var folded = fold(List.of(started("run-1"), step), List.of(error, recovered));

        assertThat(folded.terminalFailure()).isNull();
    }

    /** The failure carries the message the error entry wrapped. */
    @Test
    void terminalFailureCarriesTheErrorMessage() {
        var error = errorAssistant("a-1");

        var folded = fold(List.of(started("run-1"), stepAttempt("run-1", "a-1")),
            List.of(error));

        assertThat(folded.terminalFailure().message())
            .isInstanceOf(Message.AssistantMessage.class);
        assertThat(folded.terminalFailure().message().content())
            .containsExactly(new ContentBlock.TextContent("boom"));
    }
}

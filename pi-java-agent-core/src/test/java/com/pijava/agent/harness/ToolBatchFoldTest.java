package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/22 step 6: the folded tool batch pairs the newest assistant entry's
 * tool calls with their results by {@code toolCallId}, and reports each call
 * whose result never landed (pi {@code deriveToolBatch},
 * {@code reducer.ts:479-486}).
 *
 * <p>Pure fold tests: no harness, no driving — the lane's bounded slice is
 * handed to {@link LaneStateFolder#fold} directly.</p>
 */
class ToolBatchFoldTest {

    private static LaneStateFolder.FoldedState fold(List<LaneRecord> records,
                                                    List<Entry> ownEntries) {
        return LaneStateFolder.fold("default", records, ownEntries, List.of());
    }

    /** An assistant entry whose content is one {@code ToolUseContent} per id. */
    private static Entry assistantWithToolCalls(String id, List<String> callIds) {
        var content = callIds.stream()
            .map(callId -> (ContentBlock) new ContentBlock.ToolUseContent(callId, "echo", Map.of()))
            .toList();
        return new Entry.Message(id, 0, null, null,
            new Message.AssistantMessage(content, "tool_use", null), null);
    }

    /** A tool result entry answering {@code toolUseId}. */
    private static Entry toolResult(String id, String toolUseId, String text) {
        return new Entry.Message(id, 0, null, null,
            new Message.ToolResultMessage(toolUseId, "echo",
                List.of(new ContentBlock.TextContent(text)), false), null);
    }

    @Test
    void eachToolCallIsMatchedToItsResultByToolCallId() {
        // assistant entry 含 2 个 toolCall；两条 toolResult 均有对应 toolUseId。
        var assistant = assistantWithToolCalls("a-1", List.of("call-1", "call-2"));
        var results = List.<Entry>of(
            toolResult("r-1", "call-1", "ok"),
            toolResult("r-2", "call-2", "ok"));
        var own = new ArrayList<Entry>();
        own.add(assistant);
        own.addAll(results);

        var folded = fold(List.of(), own);

        assertThat(folded.toolBatch().assistantEntryId()).isEqualTo("a-1");
        assertThat(folded.toolBatch().calls())
            .extracting(LaneOperationFold.ToolBatchCall::toolCallId)
            .containsExactly("call-1", "call-2");
        assertThat(folded.toolBatch().calls())
            .extracting(LaneOperationFold.ToolBatchCall::resultEntryId)
            .containsExactly("r-1", "r-2");
        assertThat(folded.toolBatch().calls())
            .noneMatch(LaneOperationFold.ToolBatchCall::missing);
    }

    @Test
    void unmatchedToolCallIsMarkedMissing() {
        var assistant = assistantWithToolCalls("a-1", List.of("call-1", "call-2"));
        var own = List.<Entry>of(assistant, toolResult("r-1", "call-1", "ok"));

        var folded = fold(List.of(), own);

        assertThat(folded.toolBatch().calls())
            .filteredOn(LaneOperationFold.ToolBatchCall::missing)
            .extracting(LaneOperationFold.ToolBatchCall::toolCallId)
            .containsExactly("call-2");
    }

    @Test
    void deferredWriteIsNotMistakenForAToolResult() {
        // 延迟写入的 target id 与被排除的 toolResult 同 id：必须不被当成结果。
        var assistant = assistantWithToolCalls("a-1", List.of("call-1"));
        var ghostResult = toolResult("r-ghost", "call-1", "should not count");
        var write = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(ghostResult));
        var own = List.<Entry>of(assistant, ghostResult);

        var folded = fold(List.of(write), own);

        assertThat(folded.toolBatch().calls().get(0).missing()).isTrue();
        assertThat(folded.toolBatch().calls().get(0).resultEntryId()).isNull();
    }

    /** No assistant entry requested a call: there is no batch to report. */
    @Test
    void noToolBatchWithoutAnAssistantToolCall() {
        var own = List.<Entry>of(
            new Entry.Message("a-1", 0, null, null,
                new Message.AssistantMessage(
                    List.of(new ContentBlock.TextContent("done")), "stop", null), null));

        assertThat(fold(List.of(), own).toolBatch()).isNull();
    }

    /** Only the newest assistant entry with calls is the batch's owner. */
    @Test
    void batchIsOwnedByTheNewestAssistantEntryWithToolCalls() {
        var older = assistantWithToolCalls("a-1", List.of("call-1"));
        var newer = assistantWithToolCalls("a-2", List.of("call-2"));
        var own = List.<Entry>of(older, toolResult("r-1", "call-1", "ok"), newer);

        var folded = fold(List.of(), own);

        assertThat(folded.toolBatch().assistantEntryId()).isEqualTo("a-2");
        assertThat(folded.toolBatch().calls())
            .extracting(LaneOperationFold.ToolBatchCall::toolCallId)
            .containsExactly("call-2");
    }

    /** Position, not {@code seq}: live entries all carry {@code seq == 0}. */
    @Test
    void resultIsMatchedByPositionNotSeq() {
        var assistant = assistantWithToolCalls("a-1", List.of("call-1"));
        var own = List.of(
            toolResult("r-1", "call-1", "before"),
            assistant,
            toolResult("r-2", "call-1", "after"));

        var folded = fold(List.of(), own);

        // 只有 assistant 之后的条目才算结果。
        assertThat(folded.toolBatch().calls().get(0).resultEntryId()).isEqualTo("r-2");
    }

    /** The deferred-write exclusion is id-based: an unrelated write is inert. */
    @Test
    void aResultIsNotExcludedByAnUnrelatedDeferredWrite() {
        var assistant = assistantWithToolCalls("a-1", List.of("call-1"));
        var result = toolResult("r-1", "call-1", "ok");
        var unrelated = new LaneRecord.WriteDeferred("w-1", 0, "default", null, "",
            new ProvisionedEntry<>(new Entry.Message("never-persisted", 0, null, null,
                new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))), null)));
        var own = List.<Entry>of(assistant, result);

        var folded = fold(List.of(unrelated), own);

        assertThat(folded.toolBatch().calls().get(0).missing()).isFalse();
        assertThat(folded.toolBatch().calls().get(0).resultEntryId()).isEqualTo("r-1");
    }
}

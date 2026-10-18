package com.pijava.coding.agent.compaction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pijava.agent.compaction.LlmSummaryGenerator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1（map #24）：pi {@code branch-summarization.ts} 对位夹具 —— 收集、预算两遍扫、
 * 生成三态（摘要/中止/失败）。
 */
class BranchSummarizationTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "branch");
    private static final String LANE = "main";

    // ── 夹具 ────────────────────────────────────────────────────────

    private static Entry.Message entry(String id, String parentId, Message message) {
        return new Entry.Message(id, 0, parentId, Instant.now(), message, null);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistant(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistantCalling(String tool, String path) {
        return new Message.AssistantMessage(List.of(new ContentBlock.ToolUseContent(
            "c-" + UUID.randomUUID(), tool, Map.of("path", path))));
    }

    /** A → B → C（main），再把叶拨回 B 追加 D —— 造出分叉树。 */
    private static Session<?> branchedSession(Path root) throws Exception {
        var repo = JsonlSessionRepository.over(root);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        // create 已带出默认车道 main（再 createLane 会撞 "Lane already exists"）。
        session.appendEntry(new ProvisionedEntry<>(entry("A", null, user("root"))), LANE);
        session.appendEntry(new ProvisionedEntry<>(entry("B", "A", assistant("mid"))), LANE);
        session.appendEntry(new ProvisionedEntry<>(entry("C", "B", user("old tip"))), LANE);
        session.moveLane(LANE, "B");
        session.appendEntry(new ProvisionedEntry<>(entry("D", "B", user("new tip"))), LANE);
        return session;
    }

    private static StreamIterator okStream(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done)));
    }

    private static BranchSummarization summarizerReturning(StreamIterator stream) {
        return new BranchSummarization(new LlmSummaryGenerator(
            (model, context, options) -> stream, () -> MODEL));
    }

    private static BranchSummarization.Options options(int contextWindow) {
        return new BranchSummarization.Options(MODEL, contextWindow, 0,
            BranchSummarization.DEFAULT_RESERVE_TOKENS, null, false);
    }

    // ── collectEntries（pi :108-146）──────────────────────────────

    @Test
    void collectWalksFromOldLeafToTheDeepestCommonAncestor() throws Exception {
        var session = branchedSession(Files.createTempDirectory("branch-collect"));
        var collected = BranchSummarization.collectEntries("C", "D", session);
        // 公共祖先是 B；收集的是 C 自己（时间序）。
        assertThat(collected.commonAncestorId()).isEqualTo("B");
        assertThat(collected.entries()).extracting(Entry::id).containsExactly("C");
    }

    @Test
    void collectWithNullOldLeafYieldsNothing() throws Exception {
        var session = branchedSession(Files.createTempDirectory("branch-collect-null"));
        var collected = BranchSummarization.collectEntries(null, "D", session);
        assertThat(collected.entries()).isEmpty();
        assertThat(collected.commonAncestorId()).isNull();
    }

    // ── prepareBranchEntries（pi :195-247）────────────────────────

    @Test
    void firstPassAccumulatesDetailsOnlyFromPiGeneratedBranchSummaries() {
        // branch-summarization.ts:204：fromHook=true 的 details 不累计（守卫同 compaction.ts:70）。
        var piGenerated = new Entry.BranchSummary("bs1", 0, "A", Instant.now(), "A", "s",
            Map.of("readFiles", List.of("kept.ts"), "modifiedFiles", List.of("touched.ts")),
            null, null);
        var hookGenerated = new Entry.BranchSummary("bs2", 0, "bs1", Instant.now(), "bs1", "s",
            Map.of("readFiles", List.of("hooked.ts")), null, Boolean.TRUE);
        var prepared = BranchSummarization.prepareBranchEntries(
            List.of(piGenerated, hookGenerated), 0);
        var lists = prepared.fileOps().compute();
        assertThat(lists.readFiles()).containsExactly("kept.ts");
        assertThat(lists.modifiedFiles()).containsExactly("touched.ts");
    }

    @Test
    void secondPassKeepsNewestWithinBudget() {
        // 预算 = 恰一条消息的估算（estimateTokens 同源，不硬编码公式）⇒
        // 从尾装只装得下最新一条。
        var entries = List.<Entry>of(
            entry("e1", null, user("first message")),
            entry("e2", "e1", user("second message")),
            entry("e3", "e2", user("third message")));
        int oneMessage = com.pijava.agent.context.ContextUsageEstimator
            .estimateMessagesTokens(List.of(user("third message")));
        var prepared = BranchSummarization.prepareBranchEntries(entries, oneMessage);
        assertThat(prepared.messages()).hasSize(1);
        assertThat(((Message.UserMessage) prepared.messages().getFirst()).content().getFirst())
            .isInstanceOfSatisfying(ContentBlock.TextContent.class,
                t -> assertThat(t.text()).isEqualTo("third message"));
    }

    @Test
    void toolResultMessagesAreSkipped() {
        // pi :158-160：toolResult 不进摘要对象（上下文在 assistant 的 toolCall 里）。
        var toolResult = new Message.ToolResultMessage("c1", "read",
            List.of(new ContentBlock.TextContent("file body")), null, null, null, false,
            Instant.now(), 0L);
        var prepared = BranchSummarization.prepareBranchEntries(
            List.of(entry("e1", null, user("q")), entry("e2", "e1", toolResult)), 0);
        assertThat(prepared.messages()).hasSize(1);
    }

    // ── generate（pi :293-381）────────────────────────────────────

    @Test
    void successPrependsPreambleAndAppendsFileLists() {
        var summarizer = summarizerReturning(okStream("## Goal\nexplore"));
        var entries = List.<Entry>of(
            entry("e1", null, user("look at a.ts")),
            entry("e2", "e1", assistantCalling("read", "a.ts")));
        var outcome = summarizer.generate(entries, options(200_000));

        var summary = (BranchSummarization.Outcome.Summary) outcome;
        assertThat(summary.text()).startsWith(
            "The user explored a different conversation branch before returning here.\n"
                + "Summary of that exploration:\n\n");
        assertThat(summary.text()).contains("## Goal\nexplore");
        assertThat(summary.text()).endsWith("\n\n<read-files>\na.ts\n</read-files>");
        assertThat(summary.readFiles()).containsExactly("a.ts");
    }

    @Test
    void emptyPreparationYieldsTheNoContentVerdict() {
        var outcome = summarizerReturning(okStream("unused"))
            .generate(List.of(), options(200_000));
        assertThat(((BranchSummarization.Outcome.Summary) outcome).text())
            .isEqualTo("No content to summarize");
    }

    @Test
    void abortedStreamYieldsTheAbortedOutcome() {
        var stream = StreamIterator.from(List.<StreamEvent>of(
            new StreamEvent.StreamError(StopReason.ABORTED, new RuntimeException("x"), null)));
        var outcome = summarizerReturning(stream)
            .generate(List.of(entry("e1", null, user("q"))), options(200_000));
        assertThat(outcome).isInstanceOf(BranchSummarization.Outcome.Aborted.class);
    }

    @Test
    void errorStreamYieldsTheFailedOutcome() {
        var stream = StreamIterator.from(List.<StreamEvent>of(
            new StreamEvent.StreamError(StopReason.ERROR, new RuntimeException("boom"), null)));
        var outcome = summarizerReturning(stream)
            .generate(List.of(entry("e1", null, user("q"))), options(200_000));
        var failed = (BranchSummarization.Outcome.Failed) outcome;
        assertThat(failed.error()).startsWith("Branch summarization failed:");
    }

    @Test
    void toolCallInResponseIsRejected() {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("c1", "read", Map.of()))).withStopReason(StopReason.STOP);
        var stream = StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.ToolCallEnd(0, "c1", "read", Map.of(), done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done)));
        var outcome = summarizerReturning(stream)
            .generate(List.of(entry("e1", null, user("q"))), options(200_000));
        assertThat(((BranchSummarization.Outcome.Failed) outcome).error())
            .isEqualTo("Branch summarization attempted to call a tool");
    }
}

package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.CompactionPlan;
import com.pijava.agent.hook.SuppliedCompaction;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/15 T1 引擎扩形测试：{@code message_end} 替换钩（裁决①）、{@code before_settle}
 * 钩（裁决②）、{@code before_compaction} 的 cancel/supplied 两臂与 compactionEntry
 * 上交（裁决⑥）。语义锚：pi runner.ts:1144-1181 / agent-session.ts:1839,1893-1919 /
 * :2763-2900。
 */
class Batch15EngineHooksTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static StreamFn stream(String text) {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason(StopReason.STOP);
        return (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, text, partial),
                new StreamEvent.StreamDone(StopReason.STOP, null, partial)));
    }

    private static AgentHarness harness() {
        return AgentHarness.create(HarnessConfig.builder()
                .streamFn(stream("ok")).model(MODEL).build());
    }

    private static String textOf(Message message) {
        return ((ContentBlock.TextContent) message.content().get(0)).text();
    }

    // ── message_end 替换钩（裁决①，pi runner.ts:1144-1181）──

    @Test
    void messageEndReplacementReachesTranscriptAndLane() {
        var h = harness();
        h.hookSystem().onMessageEnd("default", ctx ->
            ctx.message() instanceof Message.AssistantMessage
                ? new Message.AssistantMessage(
                    List.of(new ContentBlock.TextContent("replaced")))
                : null);
        h.prompt("hello");

        var payloads = h.snapshot("default").transcript().stream()
                .filter(e -> e instanceof Entry.Message)
                .map(e -> ((Entry.Message) e).message())
                .toList();
        // 替换对助手消息生效：transcript 里是替换后引用（pi _replaceMessageInPlace 同效）。
        assertThat(payloads.stream().filter(Message.AssistantMessage.class::isInstance)
                .map(Batch15EngineHooksTest::textOf)).containsExactly("replaced");
        // 用户消息未被触碰。
        assertThat(payloads.stream().filter(Message.UserMessage.class::isInstance)
                .map(Batch15EngineHooksTest::textOf)).containsExactly("hello");
    }

    @Test
    void messageEndChainsReplacementsAndToleratesErrors() {
        var h = harness();
        var seenBySecond = new ArrayList<String>();
        h.hookSystem().onMessageEnd("default", ctx ->
            ctx.message() instanceof Message.AssistantMessage
                ? new Message.AssistantMessage(List.of(new ContentBlock.TextContent("first")))
                : null);
        h.hookSystem().onMessageEnd("default", ctx -> {
            // pi 的链式：后钩看到前钩的替换结果（currentMessage）。
            if (ctx.message() instanceof Message.AssistantMessage) {
                seenBySecond.add(textOf(ctx.message()));
                throw new IllegalStateException("boom"); // 记账并弃权，不影响已生效替换
            }
            return null;
        });
        h.prompt("hello");

        assertThat(seenBySecond).containsExactly("first");
        // 末条消息 entry 的载荷＝「first」（钩子错误审计是 Entry.Usage，不在筛选内）。
        var lastMessage = h.snapshot("default").transcript().stream()
                .filter(e -> e instanceof Entry.Message)
                .map(e -> ((Entry.Message) e).message())
                .reduce((a, b) -> b).orElseThrow();
        assertThat(textOf(lastMessage)).isEqualTo("first");
    }

    @Test
    void messageEndAbstainKeepsOriginalReference() {
        var h = harness();
        var fired = new ArrayList<Message>();
        h.hookSystem().onMessageEnd("default", ctx -> {
            fired.add(ctx.message());
            return null;
        });
        h.prompt("hello");

        // 每个定案消息恰火一次（用户 prompt + 助手），且原引用原样入 transcript。
        assertThat(fired).hasSize(2);
        var payloads = h.snapshot("default").transcript().stream()
                .filter(e -> e instanceof Entry.Message)
                .map(e -> ((Entry.Message) e).message())
                .toList();
        assertThat(payloads).containsExactlyElementsOf(fired);
    }

    // ── before_settle 钩（裁决②，pi agent-session.ts:1839）──

    @Test
    void beforeSettleContinueForcesAnotherPass() {
        var streamCalls = new ArrayList<Integer>();
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason(StopReason.STOP);
        StreamFn sf = (model, context, options) -> {
            streamCalls.add(context.messages().size());
            return StreamIterator.from(List.of(
                    new StreamEvent.Start(AssistantMessage.empty()),
                    new StreamEvent.TextEnd(0, "ok", partial),
                    new StreamEvent.StreamDone(StopReason.STOP, null, partial)));
        };
        var h = AgentHarness.create(HarnessConfig.builder()
                .streamFn(sf).model(MODEL).build());
        var settleCalls = new int[1];
        h.hookSystem().onBeforeSettle("default", ctx -> {
            settleCalls[0]++;
            if (settleCalls[0] == 1) {
                // pi：boundary 处理器入队的消息驱动 continue（:1908 的
                // result.continue || hasQueuedMessages() 同构）。
                h.followUp("default", "again");
                return true;
            }
            return null;
        });
        h.prompt("hello");

        assertThat(streamCalls).hasSize(2);          // continue 的第二轮请求
        assertThat(settleCalls[0]).isEqualTo(2);     // 第二轮收口前再火一次
        assertThat(h.snapshot("default").transcript().stream()
                .filter(e -> e instanceof Entry.Message)
                .map(e -> ((Entry.Message) e).message())
                .filter(Message.UserMessage.class::isInstance)
                .map(Batch15EngineHooksTest::textOf)).contains("again");
    }

    @Test
    void beforeSettleAbstainOrFalseSettlesImmediately() {
        for (Boolean decision : new Boolean[] {null, false}) {
            var h = harness();
            var fired = new int[1];
            h.hookSystem().onBeforeSettle("default", ctx -> {
                fired[0]++;
                return decision;
            });
            h.prompt("hello");
            assertThat(fired[0]).isEqualTo(1);
        }
    }

    // ── before_compaction cancel / supplied（裁决⑥，pi :2792-2840）──

    @Test
    void beforeCompactionCancelAbortsManualCompaction() {
        var h = harness();
        h.prompt("hello");
        int before = h.snapshot("default").transcript().size();
        h.hookSystem().onBeforeCompaction("default", ctx -> {
            // docs/15：钩子时点 pi 的 preparation 载荷必须可见（Plan 非空、reason 透传）。
            assertThat(ctx.plan()).isNotNull();
            assertThat(ctx.reason()).isEqualTo("manual");
            assertThat(ctx.transcript()).isNotEmpty();
            return CompactionPlan.cancelled();
        });

        // keep=1 强制早出切点（默认设置下两条消息的 prepare 可能为空——无可摘要段）。
        assertThatThrownBy(() -> h.compact(new CompactionSettings(true, 16384, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Compaction cancelled");
        // pi cancelledByExtension：转录未动。
        assertThat(h.snapshot("default").transcript()).hasSize(before);
    }

    @Test
    void beforeCompactionSuppliedSkipsGenerationAndStampsFromHook() {
        var observed = new ArrayList<Entry.Compaction>();
        var observer = new com.pijava.agent.compaction.CompactionObserver() {
            @Override
            public void onStart(String reason) { }

            @Override
            public void onEnd(String reason, CompactionResult result, boolean aborted,
                              boolean willRetry, String errorMessage,
                              Entry.Compaction compactionEntry) {
                if (compactionEntry != null) {
                    observed.add(compactionEntry);
                }
            }
        };
        var h = AgentHarness.create(HarnessConfig.builder()
                .streamFn(stream("ok")).model(MODEL).compactionObserver(observer).build());
        h.prompt("hello");
        var transcriptBefore = h.snapshot("default").transcript();
        var firstKept = transcriptBefore.get(transcriptBefore.size() - 1).id();

        h.hookSystem().onBeforeCompaction("default", ctx ->
            CompactionPlan.supplied(new SuppliedCompaction(
                "ext-summary", firstKept, 4242, null, Map.of("origin", "test"))));
        var result = h.compact(CompactionSettings.defaults());

        var transcript = h.snapshot("default").transcript();
        var head = (Entry.Compaction) transcript.get(0);
        // 供应臂：条目载荷全等于供应值（pi :2818-2824 逐字段），fromHook=true。
        assertThat(head.summary()).isEqualTo("ext-summary");
        assertThat(head.firstKeptEntryId()).isEqualTo(firstKept);
        assertThat(head.tokensBefore()).isEqualTo(4242);
        assertThat(head.details()).containsEntry("origin", "test");
        assertThat(head.fromHook()).isTrue();
        assertThat(transcript.subList(1, transcript.size()))
                .containsExactlyElementsOf(transcriptBefore.subList(
                    transcriptBefore.size() - 1, transcriptBefore.size()));
        // 结果对象同样是供应值（estimatedTokensAfter 由引擎重建后补算，pi :2383 同位）。
        assertThat(result.summary()).isEqualTo("ext-summary");
        assertThat(result.tokensBefore()).isEqualTo(4242);
        assertThat(result.estimatedTokensAfter()).isNotNull();
        // compactionEntry 随 onEnd 上交（pi session_compact 载荷链）——同一条目。
        assertThat(observed).containsExactly(head);
    }

    @Test
    void defaultCompactionEndCarriesPersistedEntry() {
        var observed = new ArrayList<Entry.Compaction>();
        var observer = new com.pijava.agent.compaction.CompactionObserver() {
            @Override
            public void onStart(String reason) { }

            @Override
            public void onEnd(String reason, CompactionResult result, boolean aborted,
                              boolean willRetry, String errorMessage,
                              Entry.Compaction compactionEntry) {
                if (compactionEntry != null) {
                    observed.add(compactionEntry);
                }
            }
        };
        var h = AgentHarness.create(HarnessConfig.builder()
                .streamFn(stream("ok")).model(MODEL).compactionObserver(observer).build());
        h.prompt("hello");
        h.compact(new CompactionSettings(true, 16384, 1));

        assertThat(observed).hasSize(1);
        var head = h.snapshot("default").transcript().get(0);
        assertThat(observed.get(0)).isSameAs(head);
        assertThat(((Entry.Compaction) head).fromHook()).isNull(); // pi-generated ≙ undefined
    }
}

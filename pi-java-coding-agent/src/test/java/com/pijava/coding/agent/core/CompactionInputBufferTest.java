package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B175（docs/26 §3.2/§4）：压缩窗口缓冲器纯逻辑用例 ——
 * pi {@code queueCompactionMessage}/{@code flushCompactionQueue}
 * （interactive-mode.ts:4688-4783）。
 *
 * <p>无扩展命令分支（pi-java 未移植扩展子系统）：非重试路第一条即 prompt。</p>
 */
class CompactionInputBufferTest {

    /** Recording replayer: ordered calls + configurable failure + cancellation log. */
    static final class Recording implements CompactionInputBuffer.Replayer {

        record Call(String method, String text, StreamingBehavior mode) {}

        final List<Call> calls = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("boom");
        /** Throw after this many delivered calls (-1 = never). */
        int failAfter = -1;

        @Override
        public void prompt(String text, StreamingBehavior mode) {
            record("prompt", text, mode);
        }

        @Override
        public void steer(String text) {
            record("steer", text, null);
        }

        @Override
        public void followUp(String text) {
            record("followUp", text, null);
        }

        @Override
        public void cancelQueued(String queueType) {
            cancelled.add(queueType);
        }

        private void record(String method, String text, StreamingBehavior mode) {
            calls.add(new Call(method, text, mode));
            if (failAfter >= 0 && calls.size() > failAfter) {
                throw failure;
            }
        }
    }

    @Test
    void emptyFlushIsNoOp() {
        var buffer = new CompactionInputBuffer();
        var sink = new Recording();

        buffer.flush(true, sink);
        buffer.flush(false, sink);

        assertThat(sink.calls).isEmpty();
    }

    @Test
    void willRetryQueuesEveryMessageByModeWithoutPrompt() {
        // pi flushCompactionQueue willRetry 分支（:4723-4731）：
        // 重试回合在跑，消息逐条进引擎队列，不起新 prompt。
        var buffer = new CompactionInputBuffer();
        buffer.add("steer this", StreamingBehavior.STEER);
        buffer.add("do later", StreamingBehavior.FOLLOW_UP);
        var sink = new Recording();

        buffer.flush(true, sink);

        assertThat(sink.calls).containsExactly(
            new Recording.Call("steer", "steer this", null),
            new Recording.Call("followUp", "do later", null));
        assertThat(buffer.isEmpty()).isTrue();
    }

    @Test
    void nonRetryDeliversFirstViaPromptAndQueuesTheRest() {
        // pi :4741-4760：第一条经 prompt(behavior)（空闲则起新 run），
        // 其余按 mode 排队。
        var buffer = new CompactionInputBuffer();
        buffer.add("first", StreamingBehavior.FOLLOW_UP);
        buffer.add("second", StreamingBehavior.STEER);
        buffer.add("third", StreamingBehavior.FOLLOW_UP);
        var sink = new Recording();

        buffer.flush(false, sink);

        assertThat(sink.calls).containsExactly(
            new Recording.Call("prompt", "first", StreamingBehavior.FOLLOW_UP),
            new Recording.Call("steer", "second", null),
            new Recording.Call("followUp", "third", null));
        assertThat(buffer.isEmpty()).isTrue();
    }

    @Test
    void failureOnSecondDeliveryRestoresBufferAndCancelsBothQueues() {
        // pi restoreQueue（:4710-4719）：clearQueue 撤两队列 ⇒ 缓冲原样还原 ⇒ 再抛。
        var buffer = new CompactionInputBuffer();
        buffer.add("first", StreamingBehavior.STEER);
        buffer.add("second", StreamingBehavior.FOLLOW_UP);
        var sink = new Recording();
        sink.failAfter = 1;  // first delivered, second throws

        assertThatThrownBy(() -> buffer.flush(false, sink))
            .isSameAs(sink.failure);

        assertThat(buffer.contents()).containsExactly(
            new CompactionInputBuffer.Queued("first", StreamingBehavior.STEER),
            new CompactionInputBuffer.Queued("second", StreamingBehavior.FOLLOW_UP));
        assertThat(sink.cancelled).contains("steer", "followUp");
    }

    @Test
    void promptFailureRestoresTheWholeBufferAndCancelsQueues() {
        // 第一条 prompt 就在调用线程抛（压缩门/运行门）⇒ 无投递，同样全量还原。
        var buffer = new CompactionInputBuffer();
        buffer.add("first", StreamingBehavior.STEER);
        var sink = new Recording();
        sink.failAfter = 0;

        assertThatThrownBy(() -> buffer.flush(false, sink))
            .isSameAs(sink.failure);

        assertThat(buffer.contents()).containsExactly(
            new CompactionInputBuffer.Queued("first", StreamingBehavior.STEER));
        assertThat(sink.cancelled).contains("steer", "followUp");
    }

    @Test
    void clearEmptiesTheBuffer() {
        var buffer = new CompactionInputBuffer();
        buffer.add("x", StreamingBehavior.STEER);

        buffer.clear();

        assertThat(buffer.isEmpty()).isTrue();
        assertThat(buffer.contents()).isEmpty();
    }

    @Test
    void addPreservesOrderAndModes() {
        var buffer = new CompactionInputBuffer();
        buffer.add("a", StreamingBehavior.STEER);
        buffer.add("b", StreamingBehavior.FOLLOW_UP);
        buffer.add("c", StreamingBehavior.STEER);

        assertThat(buffer.contents()).containsExactly(
            new CompactionInputBuffer.Queued("a", StreamingBehavior.STEER),
            new CompactionInputBuffer.Queued("b", StreamingBehavior.FOLLOW_UP),
            new CompactionInputBuffer.Queued("c", StreamingBehavior.STEER));
    }
}

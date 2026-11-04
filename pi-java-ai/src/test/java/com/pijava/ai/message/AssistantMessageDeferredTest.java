package com.pijava.ai.message;

import java.time.Instant;
import java.util.List;

import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/20 §6.2：流式 {@link AssistantMessage} 必须携带 pi 的
 * {@code deferred?: DeferredHandle}（{@code ai/src/types.ts:600}）。
 *
 * <p>record 加组件最容易「构造点改了、wither 漏了」—— 漏传的 wither 会把 handle
 * 静默变 null。本类逐条钉住投影与全部 wither 的传递。</p>
 */
class AssistantMessageDeferredTest {

    private static final DeferredHandle HANDLE = new DeferredHandle(
        "faux", "faux-1", "faux", "deferred:1:abc", null, 250L, null);

    private static AssistantMessage withHandle() {
        return new AssistantMessage(
            "msg", List.of(new ContentBlock.TextContent("hi")),
            new StreamEvent.UsageInfo(1, 2, null), StopReason.DEFERRED,
            "faux", "faux", "faux-1", null, null, null,
            Instant.ofEpochMilli(1_700_000_000_000L), null, null, null, "response", HANDLE);
    }

    @Test
    void canonicalConstructorCarriesTheHandle() {
        assertThat(withHandle().deferred()).isEqualTo(HANDLE);
    }

    @Test
    void everyWitherPreservesTheHandle() {
        var base = withHandle();

        assertThat(base.withId("other").deferred()).isEqualTo(HANDLE);
        assertThat(base.withContent(List.of()).deferred()).isEqualTo(HANDLE);
        assertThat(base.withUsage(null).deferred()).isEqualTo(HANDLE);
        assertThat(base.withStopReason(StopReason.STOP).deferred()).isEqualTo(HANDLE);
        assertThat(base.withIdentity("a", "b", "c", Instant.now()).deferred()).isEqualTo(HANDLE);
        assertThat(base.withProviderThinkingLevel("high").deferred()).isEqualTo(HANDLE);
        assertThat(base.withResponseModel("other-model").deferred()).isEqualTo(HANDLE);
        assertThat(base.withDiagnostics(List.of()).deferred()).isEqualTo(HANDLE);
        assertThat(base.withErrorMessage("boom").deferred()).isEqualTo(HANDLE);
        assertThat(base.withRawStopReason("max_tokens").deferred()).isEqualTo(HANDLE);
        assertThat(base.withDurationMs(5L).deferred()).isEqualTo(HANDLE);
        assertThat(base.withDeferred(null).deferred()).isNull();
    }

    @Test
    void emptyAndCompatibilityShapesStartWithoutAHandle() {
        assertThat(AssistantMessage.empty().deferred()).isNull();

        var legacy = new AssistantMessage(
            "msg", List.of(), null, StopReason.STOP,
            "faux", "faux", "faux-1", Instant.now(), null, null);
        assertThat(legacy.deferred()).isNull();
    }

    @Test
    void persistedProjectionCopiesTheHandle() {
        var persisted = Message.AssistantMessage.fromPartial(withHandle());

        assertThat(persisted.deferred()).isEqualTo(HANDLE);
        assertThat(persisted.stopReason()).isEqualTo(StopReason.DEFERRED);
    }
}

package com.pijava.coding.agent.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5（docs/25）：{@link SessionResult} 的 disposition 与 {@code queued()}
 * 工厂 —— pi prompt 预检 disposition（{@code started | queued}，
 * {@code agent-session.ts:294-295}）。
 */
class SessionResultQueuedTest {

    @Test
    void queuedIsAlreadySettledWithQueuedDisposition() {
        var result = SessionResult.queued();

        assertThat(result.disposition()).isEqualTo("queued");
        assertThat(result.status())
            .as("排队结果已落定，调用方 join 不阻塞")
            .isEqualTo(new RunStatus(0, "queued"));
        assertThat(result.entries()).isEmpty();
        assertThat(result.stream().count()).isZero();
        assertThat(result.statusFuture().isDone()).isTrue();
    }

    @Test
    void startedResultCarriesStartedDisposition() {
        var result = new SessionResult(
            Stream.<StreamEvent>empty(),
            CompletableFuture.completedFuture(List.of()),
            CompletableFuture.completedFuture(new RunStatus(0, "completed")),
            "started");

        assertThat(result.disposition()).isEqualTo("started");
    }
}

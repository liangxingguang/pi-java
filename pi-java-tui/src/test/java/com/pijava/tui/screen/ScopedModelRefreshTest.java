package com.pijava.tui.screen;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import com.pijava.coding.agent.core.ModelCatalogRefresh;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedModelRefreshTest {

    @Test
    void timeoutUsesPiWarningText() throws Exception {
        var outcomes = new LinkedBlockingQueue<ScopedModelRefresh.Outcome>();
        var refresh = new ScopedModelRefresh(Duration.ofMillis(20));

        refresh.start(() -> {
            sleep(250);
            return new ModelCatalogRefresh.Result(Map.of());
        }, List::of, outcomes::add);

        var outcome = outcomes.poll(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(outcome).isNotNull();
        assertThat(outcome.status()).isEqualTo("Model refresh timed out; showing cached models.");
        refresh.close();
    }

    @Test
    void closeDropsLateResult() throws Exception {
        var outcomes = new LinkedBlockingQueue<ScopedModelRefresh.Outcome>();
        var refresh = new ScopedModelRefresh(Duration.ofSeconds(1));
        refresh.start(() -> {
            sleep(150);
            return new ModelCatalogRefresh.Result(Map.of());
        }, List::of, outcomes::add);

        refresh.close();
        Thread.sleep(250);

        assertThat(outcomes).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

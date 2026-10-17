package com.pijava.agent.harness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #69：pi {@code agent/src/stream-fn.ts:1-20} 对位 —— 进程级缺省 StreamFn。
 *
 * <p>静态持有器跨用例有状态，每个用例后卸载（{@code set(null)} ≙ pi 的 undefined）。</p>
 */
class DefaultStreamFnTest {

    @AfterEach
    void uninstall() {
        DefaultStreamFn.set(null);
    }

    @Test
    void getThrowsVerbatimMessageWhenUnset() {
        // pi :15-19 错文逐字。
        assertThatThrownBy(DefaultStreamFn::get)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("No default stream function configured. "
                + "Pass streamFn explicitly or call setDefaultStreamFn().");
    }

    @Test
    void setThenGetReturnsTheInstalledFunction() {
        StreamFn fn = (model, context, options) -> {
            throw new UnsupportedOperationException("stub");
        };
        DefaultStreamFn.set(fn);
        assertThat(DefaultStreamFn.get()).isSameAs(fn);
    }

    @Test
    void harnessConfigBuildFallsBackToTheDefault() {
        // pi agent.ts:236 `?? getDefaultStreamFn()`：build() 缺省走进程级兜底。
        StreamFn fn = (model, context, options) -> {
            throw new UnsupportedOperationException("stub");
        };
        DefaultStreamFn.set(fn);
        var config = HarnessConfig.builder()
            .model(com.pijava.ai.model.ModelId.of("faux", "gate-model"))
            .build();
        assertThat(config.streamFn()).isSameAs(fn);
    }

    @Test
    void harnessConfigBuildStillThrowsWhenNothingInstalled() {
        assertThatThrownBy(() -> HarnessConfig.builder()
            .model(com.pijava.ai.model.ModelId.of("faux", "gate-model"))
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No default stream function configured");
    }
}

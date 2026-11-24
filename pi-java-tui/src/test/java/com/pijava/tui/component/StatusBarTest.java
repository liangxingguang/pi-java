package com.pijava.tui.component;

import java.util.List;

import com.pijava.agent.context.ContextUsage;
import com.pijava.agent.harness.SessionSnapshot;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B293：pi footer {@code formatTokens}/{@code context} 文案的逐字用例
 * （{@code footer.ts:25-31,193-212}）。
 */
class StatusBarTest {

    private static SessionSnapshot snapshot(ContextUsage usage, boolean autoCompact) {
        return new SessionSnapshot("demo", "faux/test", "idle", 0L, 0,
            List.of(), List.of(), usage, autoCompact);
    }

    @Test
    void formatTokensFollowsPiThresholds() {
        assertThat(StatusBar.formatTokens(999)).isEqualTo("999");
        assertThat(StatusBar.formatTokens(1500)).isEqualTo("1.5k");
        assertThat(StatusBar.formatTokens(272_000)).isEqualTo("272k");
        assertThat(StatusBar.formatTokens(9_999)).isEqualTo("10.0k");
        assertThat(StatusBar.formatTokens(1_500_000)).isEqualTo("1.5M");
        assertThat(StatusBar.formatTokens(12_000_000)).isEqualTo("12M");
    }

    @Test
    void contextTextMatchesThePiFooterOracle() {
        var text = StatusBar.contextText(
            snapshot(new ContextUsage(115_840.0, 272_000, 42.2), true));

        assertThat(text).isEqualTo("42.2%/272k (auto)");
        assertThat(text).containsPattern("\\d+(?:\\.\\d+)?%\\/272k(?: \\(auto\\))?");
    }

    @Test
    void unknownTokensAfterCompactionRenderAsQuestionMark() {
        assertThat(StatusBar.contextText(snapshot(new ContextUsage(null, 272_000, null), false)))
            .isEqualTo("?/272k");
    }

    @Test
    void missingUsageFollowsThePiZeroBranch() {
        assertThat(StatusBar.contextText(snapshot(null, false))).isEqualTo("0.0%/0");
    }
}

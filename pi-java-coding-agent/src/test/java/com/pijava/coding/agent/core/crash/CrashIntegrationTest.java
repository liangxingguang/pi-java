package com.pijava.coding.agent.core.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** pi interactive-mode.ts :2160-2162 / :304-319 文案对拍（【用户可见】逐字）。 */
class CrashIntegrationTest {

    @Test
    void instructionsWithSessionFileOfferResume() {
        assertThat(CrashIntegration.crashReportInstructions("/tmp/s.jsonl"))
            .isEqualTo("To report this crash: run `pi-java -r` to resume the session,"
                + " then run /bug. The crash details are attached automatically.");
    }

    @Test
    void instructionsWithoutSessionFileSayStart() {
        assertThat(CrashIntegration.crashReportInstructions(null))
            .isEqualTo("To report this crash: start pi-java and run /bug."
                + " The crash details are attached automatically.");
    }

    @Test
    void hintEmptyOrBlankMatchesReturnNull() {
        assertThat(CrashIntegration.formatCrashExtensionHint(null)).isNull();
        assertThat(CrashIntegration.formatCrashExtensionHint(List.of())).isNull();
        assertThat(CrashIntegration.formatCrashExtensionHint(List.of("", " ")))
            .as("空串被滤；空白串 pi 不过滤——保留参与成形")
            .isNotNull();
    }

    @Test
    void hintSingleMatch() {
        assertThat(CrashIntegration.formatCrashExtensionHint(List.of("acme-tools")))
            .isEqualTo("A stack frame came from loaded extension `acme-tools`, which may"
                + " be involved. Try disabling it with `pi-java config`, or run"
                + " `pi-java -ne` to confirm.");
    }

    @Test
    void hintTwoMatches() {
        assertThat(CrashIntegration.formatCrashExtensionHint(List.of("a", "b")))
            .isEqualTo("A stack frame came from loaded extensions `a` and `b`, which may"
                + " be involved. Try disabling them with `pi-java config`, or run"
                + " `pi-java -ne` to confirm.");
    }

    @Test
    void hintThreePlusMatches() {
        assertThat(CrashIntegration.formatCrashExtensionHint(List.of("a", "b", "c")))
            .isEqualTo("A stack frame came from loaded extensions `a`, `b`, and `c`,"
                + " which may be involved. Try disabling them with `pi-java config`,"
                + " or run `pi-java -ne` to confirm.");
    }
}

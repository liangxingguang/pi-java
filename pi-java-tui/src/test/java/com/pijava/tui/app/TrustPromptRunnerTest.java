package com.pijava.tui.app;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 23（docs/34 T3）：启动信任弹窗的提示文案逐字（pi project-trust.ts:29-33
 * {@code formatProjectTrustPrompt}，APP_NAME＝pi-java、目录名＝.pi-java）。
 */
class TrustPromptRunnerTest {

    @Test
    void promptTextIsVerbatim() {
        assertThat(TrustPromptRunner.promptText(Path.of("/proj/x")))
            .isEqualTo("Trust project folder?\n" + Path.of("/proj/x") + "\n\n"
                + "This allows pi-java to load .pi-java settings and resources,"
                + " install missing project packages, and execute project extensions.");
    }
}

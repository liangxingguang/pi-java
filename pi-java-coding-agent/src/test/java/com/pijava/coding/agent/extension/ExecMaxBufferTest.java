package com.pijava.coding.agent.extension;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L5 W3（B277a）：pi {@code execCommand} 的 {@code maxBuffer} 语义（core/exec.ts:41-44,
 * 76-104, 140-147）。
 */
class ExecMaxBufferTest {

    @Test
    void zeroMaxBufferTruncatesImmediately() {
        var result = ExtensionExec.execCommand(ExecTestSupport.javaCommand(),
            ExecTestSupport.probeArgs("ascii", "100"), null,
            new ExtensionExec.ExecOptions(null, null, null, 0L));
        assertThat(result.truncated()).isTrue();
        assertThat(result.killed()).isTrue();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.code()).isNotZero();
    }

    @Test
    void outputOverCapIsTruncatedAndNonZero() {
        var result = ExtensionExec.execCommand(ExecTestSupport.javaCommand(),
            ExecTestSupport.probeArgs("ascii", "1000"), null,
            new ExtensionExec.ExecOptions(null, null, null, 100L));
        assertThat(result.truncated()).isTrue();
        assertThat(result.killed()).isTrue();
        assertThat(result.stdout()).hasSize(100);
        assertThat(result.code()).isEqualTo(1);
    }

    @Test
    void stdoutAndStderrShareTheCombinedCap() {
        var result = ExtensionExec.execCommand(ExecTestSupport.javaCommand(),
            ExecTestSupport.probeArgs("both", "10", "10"), null,
            new ExtensionExec.ExecOptions(null, null, null, 15L));
        assertThat(result.truncated()).isTrue();
        assertThat(result.stdout().length() + result.stderr().length()).isEqualTo(15);
        assertThat(result.code()).isEqualTo(1);
    }

    @Test
    void normalExitKeepsZeroAndNotTruncated() {
        var result = ExtensionExec.execCommand(ExecTestSupport.javaCommand(),
            ExecTestSupport.probeArgs("ascii", "10"), null,
            new ExtensionExec.ExecOptions(null, null, null, null));
        assertThat(result.truncated()).isFalse();
        assertThat(result.killed()).isFalse();
        assertThat(result.code()).isZero();
        assertThat(result.stdout()).hasSize(10);
    }

    @Test
    void invalidMaxBufferIsRejected() {
        assertThatThrownBy(() -> new ExtensionExec.ExecOptions(null, null, null, -1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxBuffer must be an integer between 0 and "
                + ExtensionExec.DEFAULT_EXEC_MAX_BUFFER + " bytes");
        assertThatThrownBy(() -> new ExtensionExec.ExecOptions(null, null, null,
            ExtensionExec.DEFAULT_EXEC_MAX_BUFFER + 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

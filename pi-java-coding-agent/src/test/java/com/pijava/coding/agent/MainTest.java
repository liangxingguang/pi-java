package com.pijava.coding.agent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Top-level dispatch: help/version/mode/export/print diagnostics.
 */
class MainTest {

    @Test
    void helpPrintsUsage() {
        var out = captureStdout(() -> Main.run(new String[] {"--help"}));
        assertThat(out).contains("Usage:", "--provider", "--print");
    }

    @Test
    void versionPrintsVersion() {
        var out = captureStdout(() -> Main.run(new String[] {"-v"}));
        assertThat(out).isNotBlank();
    }

    @Test
    void jsonModeWithoutPromptIsError() {
        // --mode json 已实现（P6-5e）：无 prompt 消息时报错。
        int code = Main.run(new String[] {"--mode", "json"});
        assertThat(code).isEqualTo(1);
    }

    @Test
    void exportIsRejected() {
        int code = Main.run(new String[] {"--export", "out.html"});
        assertThat(code).isEqualTo(2);
    }

    @Test
    void printWithoutPromptIsError() {
        int code = Main.run(new String[] {"-p"});
        assertThat(code).isEqualTo(1);
    }

    @Test
    void invalidModeArgumentFailsParsing() {
        int code = Main.run(new String[] {"--mode", "bogus"});
        assertThat(code).isEqualTo(2);
    }

    private static String captureStdout(Runnable action) {
        var out = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}

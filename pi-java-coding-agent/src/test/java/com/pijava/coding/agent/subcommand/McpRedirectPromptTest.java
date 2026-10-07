package com.pijava.coding.agent.subcommand;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.pijava.ai.AbortSignal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code waitForRedirectUrl}（{@code cli.ts:579-614}）。
 */
class McpRedirectPromptTest {

    private final List<String> prompts = new ArrayList<>();

    private McpRedirectPrompt.Options options(long timeoutMs, boolean interactive,
                                              InputStream input) {
        return new McpRedirectPrompt.Options(timeoutMs, interactive, input, prompts::add);
    }

    @Test
    void withoutATerminalOnlyTheCallbackOrTheTimeoutEndsIt() {
        var started = System.nanoTime();
        var result = McpRedirectPrompt.waitForRedirectUrl(AbortSignal.create(),
                options(150, false, InputStream.nullInputStream()));

        assertThat(result).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(100);
        // Nothing is read without a terminal.
        assertThat(prompts).isEmpty();
    }

    @Test
    void anAlreadyAbortedSignalEndsItAtOnce() {
        var signal = AbortSignal.create();
        signal.abort();

        var started = System.nanoTime();
        var result = McpRedirectPrompt.waitForRedirectUrl(signal,
                options(10_000, false, InputStream.nullInputStream()));

        assertThat(result).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000);
    }

    @Test
    void anAbortDuringTheWaitEndsIt() throws Exception {
        var signal = AbortSignal.create();
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            signal.abort();
        });

        var started = System.nanoTime();
        assertThat(McpRedirectPrompt.waitForRedirectUrl(signal,
                options(10_000, false, InputStream.nullInputStream()))).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);
    }

    @Test
    void aTerminalReadsThePastedUrl() {
        var pasted = "http://127.0.0.1:1234/callback?code=abc&state=s1";
        var result = McpRedirectPrompt.waitForRedirectUrl(AbortSignal.create(),
                options(10_000, true,
                        new ByteArrayInputStream((pasted + "\n").getBytes(StandardCharsets.UTF_8))));

        assertThat(result).isEqualTo(pasted);
        assertThat(prompts).containsExactly(McpRedirectPrompt.PROMPT);
    }

    /** A stream that never produces a line, like a terminal nobody types into. */
    private static InputStream neverAnswers() {
        return new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(interrupted);
                }
                return -1;
            }
        };
    }

    @Test
    void aTerminalThatNeverAnswersTimesOut() {
        var started = System.nanoTime();
        var result = McpRedirectPrompt.waitForRedirectUrl(AbortSignal.create(),
                options(150, true, neverAnswers()));

        assertThat(result).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(100);
    }

    @Test
    void aTerminalAtEndOfInputAnswersWithNothing() {
        // stdin closed: the read returns at once, and that cancels the sign-in just as pi's does.
        var started = System.nanoTime();
        var result = McpRedirectPrompt.waitForRedirectUrl(AbortSignal.create(),
                options(10_000, true, InputStream.nullInputStream()));

        assertThat(result).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);
    }

    @Test
    void thePromptGoesToTheErrorStream() {
        // pi writes the prompt with `output: process.stderr`.
        McpRedirectPrompt.waitForRedirectUrl(AbortSignal.create(),
                options(10_000, true,
                        new ByteArrayInputStream("x\n".getBytes(StandardCharsets.UTF_8))));
        assertThat(prompts).containsExactly(
                "If the browser cannot reach this machine, paste the URL it was redirected to: ");
    }
}

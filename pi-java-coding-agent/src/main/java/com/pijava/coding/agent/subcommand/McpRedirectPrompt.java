package com.pijava.coding.agent.subcommand;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;

/**
 * Waits for the pasted redirect URL in a terminal (pi {@code waitForRedirectUrl},
 * {@code cli.ts:579-614}).
 *
 * <p>Without a terminal, only the browser callback can finish the sign-in, so this waits for the
 * abort and returns {@code null}. Resolves to {@code null} after the timeout too.</p>
 */
final class McpRedirectPrompt {

    /** The prompt pi writes to stderr ({@code cli.ts:602}). */
    static final String PROMPT =
            "If the browser cannot reach this machine, paste the URL it was redirected to: ";

    /**
     * How long to wait and how to talk to the terminal.
     *
     * @param timeoutMs how long the sign-in may take in total
     * @param interactive whether a terminal is attached and no {@code openUrl} was injected
     * @param input where to read the pasted line from
     * @param promptOut where to write the prompt
     */
    record Options(long timeoutMs, boolean interactive, InputStream input,
                   Consumer<String> promptOut) {
    }

    private McpRedirectPrompt() {
    }

    /**
     * Wait for the callback, the pasted URL, or the timeout (pi {@code waitForRedirectUrl}).
     *
     * @param signal aborted when the browser reached the callback
     * @param options how long to wait and where to read a pasted URL
     * @return the pasted URL, or {@code null} when cancelled, timed out, or non-interactive
     */
    static @Nullable String waitForRedirectUrl(AbortSignal signal, Options options) {
        var controller = AbortSignal.create();
        // The answer is null whenever the callback or the timeout wins.
        var answer = new CompletableFuture<@Nullable String>();
        controller.onAbort(() -> answer.complete(null));
        var unsubscribe = signal.onAbort(controller::abort);
        var timer = Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(options.timeoutMs());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            controller.abort();
        });
        try {
            if (!options.interactive()) {
                // Only the callback or the timeout can finish this.
                return answer.join();
            }
            // Whichever comes first: the pasted line, the callback, or the timeout.
            var line = new CompletableFuture<String>();
            Thread.startVirtualThread(() -> {
                try (var reader = new BufferedReader(
                        new InputStreamReader(options.input(), StandardCharsets.UTF_8))) {
                    line.complete(reader.readLine());
                } catch (Throwable failure) {
                    line.completeExceptionally(failure);
                }
            });
            line.whenComplete((value, failure) -> answer.complete(value));
            options.promptOut().accept(PROMPT);
            return answer.join();
        } finally {
            timer.interrupt();
            unsubscribe.run();
        }
    }
}

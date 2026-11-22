package com.pijava.mcp.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;

/**
 * Opens the authorization URL the way a browser would: once the sign-in waits for
 * the callback, it follows the redirect to the loopback callback server and also
 * remembers the redirect URL, which is what a user pastes when the browser cannot
 * reach loopback.
 */
final class BrowserPrompt implements McpSignInPrompt {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private final CountDownLatch waiting = new CountDownLatch(1);
    private final CompletableFuture<String> redirect = new CompletableFuture<>();
    private volatile boolean reachedCallback;

    @Override
    public void showAuthorizationUrl(URI url) {
        Thread.startVirtualThread(() -> {
            try {
                // waitForAuthorizationResponse registers the callback before asking for
                // a pasted URL, so following the redirect now always reaches it.
                if (!waiting.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The sign-in never waited for the redirect URL");
                }
                var authorized = CLIENT.send(HttpRequest.newBuilder(url).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                var location = authorized.headers().firstValue("location").orElseThrow(() ->
                        new IllegalStateException("The authorization endpoint did not redirect"));
                // The user's browser follows the redirect to loopback.
                CLIENT.send(HttpRequest.newBuilder(URI.create(location)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                reachedCallback = true;
                redirect.complete(location);
            } catch (Throwable failure) {
                redirect.completeExceptionally(failure);
            }
        });
    }

    @Override
    public @Nullable String promptForRedirectUrl(AbortSignal signal) throws Exception {
        waiting.countDown();
        return redirect.get(10, TimeUnit.SECONDS);
    }

    /**
     * Whether the browser asked the loopback callback server. Waiting for the
     * redirect also makes sure the browser thread finished before it is read.
     */
    boolean awaitCallback() throws Exception {
        redirect.get(10, TimeUnit.SECONDS);
        return reachedCallback;
    }
}

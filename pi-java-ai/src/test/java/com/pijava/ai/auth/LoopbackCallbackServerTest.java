package com.pijava.ai.auth;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 对应 pi {@code test/oauth-callback-server.test.ts}：分派/页面/claim/取消/超时/
 * 端口占用，以及 waitForCallbackOrManualInput 竞速四例。
 */
class LoopbackCallbackServerTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private LoopbackCallbackServer.Options<String> options(
            LoopbackCallbackServer.Options.Completer<String> completer) {
        return new LoopbackCallbackServer.Options<>(
            "Example", "127.0.0.1", 0, "/callback", null, "expected-state",
            null, completer);
    }

    private HttpResponse<String> get(LoopbackCallbackServer<?> server, String rawPathAndQuery)
            throws Exception {
        return http.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + serverPort(server) + rawPathAndQuery)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(LoopbackCallbackServer<?> server, String method,
                                         String rawPathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + serverPort(server) + rawPathAndQuery))
            .method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static int serverPort(LoopbackCallbackServer<?> server) {
        return URI.create(server.redirectUri().replace("localhost", "127.0.0.1")).getPort();
    }

    // ── 分派 ─────────────────────────────────────────────────

    @Test
    void ignoresStrayRequestsAndResolvesWithCompletedCode() throws Exception {
        var server = LoopbackCallbackServer.start(options((code, query) -> "completed:" + code));
        try {
            assertThat(server.redirectUri()).matches("http://127\\.0\\.0\\.1:\\d+/callback");

            assertThat(get(server, "/other").statusCode()).isEqualTo(404);

            var wrongState = get(server, "/callback?code=c&state=other");
            assertThat(wrongState.statusCode()).isEqualTo(400);
            assertThat(wrongState.body()).contains("State mismatch.");

            var post = request(server, "POST", "/callback?code=c&state=expected-state");
            assertThat(post.statusCode()).isEqualTo(404);

            var missingCode = get(server, "/callback?state=expected-state");
            assertThat(missingCode.statusCode()).isEqualTo(400);

            var success = get(server, "/callback?code=the-code&state=expected-state");
            assertThat(success.statusCode()).isEqualTo(200);
            assertThat(success.headers().firstValue("content-type"))
                .hasValue("text/html; charset=utf-8");
            assertThat(success.body()).contains("Authentication successful");
            assertThat(success.body()).contains("Signed in to Example.");
            assertThat(success.body()).contains("fill=\"#F09082\"", "fill=\"#4D9ABF\"",
                "fill=\"#F1BE58\"");

            assertThat(server.outcome().get(5, TimeUnit.SECONDS)).isEqualTo("completed:the-code");
        } finally {
            server.close();
        }
    }

    @Test
    void usesRedirectHostAndSkipsStateCheckWhenNoneExpected() throws Exception {
        var server = LoopbackCallbackServer.start(new LoopbackCallbackServer.Options<>(
            "Example", "127.0.0.1", 0, "/callback", "localhost", null, null,
            (code, query) -> "completed:" + code));
        try {
            assertThat(server.redirectUri()).matches("http://localhost:\\d+/callback");
            var response = get(server, "/callback?code=no-state");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(server.outcome().get(5, TimeUnit.SECONDS)).isEqualTo("completed:no-state");
        } finally {
            server.close();
        }
    }

    @Test
    void showsCompletionFailuresOnPageAndRejectsWait() throws Exception {
        var server = LoopbackCallbackServer.start(options((code, query) -> {
            throw new Exception("token exchange failed");
        }));
        try {
            var failure = get(server, "/callback?code=c&state=expected-state");
            assertThat(failure.statusCode()).isEqualTo(502);
            assertThat(failure.body()).contains("Example sign-in failed.", "token exchange failed");

            var thrown = catchThrowable(() -> server.outcome().get(5, TimeUnit.SECONDS));
            assertThat(thrown).hasMessageContaining("token exchange failed");
        } finally {
            server.close();
        }
    }

    @Test
    void rejectsWaitWhenProviderRedirectsWithError() throws Exception {
        var server = LoopbackCallbackServer.start(options((code, query) -> code));
        try {
            var failure = get(server,
                "/callback?error=access_denied&error_description=User+denied+access&state=expected-state");
            assertThat(failure.statusCode()).isEqualTo(400);
            assertThat(failure.body()).contains("User denied access");
            var thrown = catchThrowable(() -> server.outcome().get(5, TimeUnit.SECONDS));
            assertThat(thrown).hasMessageContaining(
                "Example authorization failed: User denied access");
        } finally {
            server.close();
        }
    }

    @Test
    void completesOnlyFirstCallbackAndClaimSurvivesCancel() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = LoopbackCallbackServer.start(options((code, query) -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "done";
        }));
        try {
            var first = CompletableFuture.supplyAsync(() -> {
                try {
                    return get(server, "/callback?code=c&state=expected-state");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var second = get(server, "/callback?code=c2&state=expected-state");
            assertThat(second.statusCode()).isEqualTo(409);

            // 已 claim 的回调不被 cancel 抢走，仍正常完成。
            server.cancel();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(server.outcome().get(5, TimeUnit.SECONDS)).isEqualTo("done");
        } finally {
            server.close();
        }
    }

    @Test
    void resolvesNullAfterCancelAndLateCallbackGets409() throws Exception {
        var server = LoopbackCallbackServer.start(options((code, query) -> code));
        try {
            server.cancel();
            assertThat(server.outcome().get(5, TimeUnit.SECONDS)).isNull();
            var late = get(server, "/callback?code=c&state=expected-state");
            assertThat(late.statusCode()).isEqualTo(409);
        } finally {
            server.close();
        }
    }

    @Test
    void rejectsWaitOnTimeout() throws Exception {
        var server = LoopbackCallbackServer.start(new LoopbackCallbackServer.Options<>(
            "Example", "127.0.0.1", 0, "/callback", null, "expected-state",
            Duration.ofMillis(10), (code, query) -> code));
        try {
            var thrown = catchThrowable(() -> server.outcome().get(5, TimeUnit.SECONDS));
            assertThat(thrown).hasMessageContaining("Example sign-in timed out");
        } finally {
            server.close();
        }
    }

    @Test
    void failsInsteadOfPickingAnotherPortWhenRequestedPortTaken() throws Exception {
        var blocker = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        blocker.start();
        try {
            var taken = blocker.getAddress().getPort();
            var thrown = catchThrowable(() -> LoopbackCallbackServer.start(
                new LoopbackCallbackServer.Options<>("Example", "127.0.0.1", taken,
                    "/callback", null, "state", null, (code, query) -> code)));
            assertThat(thrown).isInstanceOf(java.net.BindException.class);
        } finally {
            blocker.stop(0);
        }
    }

    // ── 竞速 waitForCallbackOrManualInput ─────────────────────

    @Test
    void raceReturnsBrowserCallbackAndInterruptsManualPrompt() throws Exception {
        var promptInterrupted = new AtomicBoolean();
        var server = LoopbackCallbackServer.start(new LoopbackCallbackServer.Options<>(
            "Example", "127.0.0.1", 0, "/callback", null, null, null,
            (code, query) -> "from-browser"));
        OAuthInteraction interaction = new OAuthInteraction() {
            @Override public void notify(String message) { }
            @Override public String prompt(String message) {
                try {
                    Thread.sleep(30_000); // 占住手工提示，直到被中断。
                } catch (InterruptedException e) {
                    promptInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return "";
            }
        };
        try {
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    return LoopbackCallbackServer.awaitCallbackOrManual(interaction, server, "paste");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(200); // 等 prompt 线程起跑。
            assertThat(get(server, "/callback?code=from-browser").statusCode()).isEqualTo(200);
            var outcome = future.get(5, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(LoopbackCallbackServer.Outcome.Callback.class);
            assertThat(((LoopbackCallbackServer.Outcome.Callback<?>) outcome).value())
                .isEqualTo("from-browser");
            // 中断在 finally 发出，prompt 线程异步落标志，轮候一下。
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!promptInterrupted.get() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(promptInterrupted.get()).isTrue();
        } finally {
            server.close();
        }
    }

    @Test
    void raceReturnsPastedInputAndStopsWaitingForBrowser() throws Exception {
        var server = LoopbackCallbackServer.start(new LoopbackCallbackServer.Options<>(
            "Example", "127.0.0.1", 0, "/callback", null, null, null,
            (code, query) -> code));
        OAuthInteraction interaction = interactionWithPrompt("pasted");
        try {
            var outcome = LoopbackCallbackServer.awaitCallbackOrManual(interaction, server, "paste");
            assertThat(outcome).isInstanceOf(LoopbackCallbackServer.Outcome.Manual.class);
            assertThat(((LoopbackCallbackServer.Outcome.Manual<?>) outcome).input()).isEqualTo("pasted");
            // 服务器已被 cancel：迟到的回调 409。
            var late = get(server, "/callback?code=c");
            assertThat(late.statusCode()).isEqualTo(409);
        } finally {
            server.close();
        }
    }

    @Test
    void raceUsesOnlyManualPromptWithoutServer() throws Exception {
        var outcome = LoopbackCallbackServer.awaitCallbackOrManual(
            interactionWithPrompt("pasted"), null, "paste");
        assertThat(outcome).isInstanceOf(LoopbackCallbackServer.Outcome.Manual.class);
        assertThat(((LoopbackCallbackServer.Outcome.Manual<?>) outcome).input()).isEqualTo("pasted");
    }

    @Test
    void racePropagatesManualPromptFailures() throws Exception {
        var server = LoopbackCallbackServer.start(new LoopbackCallbackServer.Options<>(
            "Example", "127.0.0.1", 0, "/callback", null, null, null,
            (code, query) -> code));
        OAuthInteraction failing = new OAuthInteraction() {
            @Override public void notify(String message) { }
            @Override public String prompt(String message) {
                throw new RuntimeException("prompt cancelled");
            }
        };
        try {
            var thrown = catchThrowable(() ->
                LoopbackCallbackServer.awaitCallbackOrManual(failing, server, "paste"));
            assertThat(thrown).hasMessageContaining("prompt cancelled");
        } finally {
            server.close();
        }
    }

    @Test
    void blankPromptDoesNotWinRaceBeforeCallback() throws Exception {
        // 无控制台时 prompt 立即返回空串：不得抢在浏览器回调之前结束登录。
        var server = LoopbackCallbackServer.start(new LoopbackCallbackServer.Options<>(
            "Example", "127.0.0.1", 0, "/callback", null, null, null,
            (code, query) -> code));
        OAuthInteraction blank = new OAuthInteraction() {
            @Override public void notify(String message) { }
            @Override public String prompt(String message) {
                return "";
            }
        };
        try {
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    return LoopbackCallbackServer.awaitCallbackOrManual(blank, server, "paste");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(200);
            assertThat(future).isNotDone();
            assertThat(get(server, "/callback?code=late-code").statusCode()).isEqualTo(200);
            var outcome = future.get(5, TimeUnit.SECONDS);
            assertThat(((LoopbackCallbackServer.Outcome.Callback<?>) outcome).value())
                .isEqualTo("late-code");
        } finally {
            server.close();
        }
    }

    private static OAuthInteraction interactionWithPrompt(String input) {
        return new OAuthInteraction() {
            @Override public void notify(String message) { }
            @Override public String prompt(String message) {
                return input;
            }
        };
    }
}

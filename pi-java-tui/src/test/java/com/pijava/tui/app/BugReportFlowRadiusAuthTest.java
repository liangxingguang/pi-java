package com.pijava.tui.app;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.bugreport.BugReportUpload;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.BugReportChoiceScreen;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TuiEventDispatcher;
import com.sun.net.httpserver.HttpServer;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Radius credential behavior at the /bug upload boundary. */
class BugReportFlowRadiusAuthTest {

    private static final KeyEvent ENTER = KeyEvent.ofKey(KeyCode.ENTER);

    @TempDir
    Path tmp;

    @Test
    void bugUploadUsesRadiusTokenWhenAvailable() throws Exception {
        try (var rig = new Rig(tmp)) {
            rig.beginUpload(() -> Optional.of("radius-secret"));

            rig.awaitText("Bug report uploaded. Report ID: br-42");
            assertThat(rig.authorization.get()).isEqualTo("Bearer radius-secret");
        }
    }

    @Test
    void bugUploadWithoutRadiusTokenRemainsAnonymous() throws Exception {
        try (var rig = new Rig(tmp)) {
            rig.beginUpload(Optional::empty);

            rig.awaitText("Bug report uploaded. Report ID: br-42");
            assertThat(rig.calls.get()).isEqualTo(1);
            assertThat(rig.authorization.get()).isNull();
        }
    }

    @Test
    void credentialRefreshFailureDoesNotDowngradeToAnonymous() throws Exception {
        try (var rig = new Rig(tmp)) {
            rig.beginUpload(() -> {
                throw new IllegalStateException("expired");
            });

            rig.awaitChoice();
            assertThat(rig.calls.get()).isZero();
        }
    }

    private static final class Rig implements AutoCloseable {
        private final String oldHome;
        private final AgentSession session;
        private final ChatScreen chatScreen = new ChatScreen();
        private final TuiEventDispatcher dispatcher = new TuiEventDispatcher();
        private final AtomicReference<ScreenOverlay> overlay = new AtomicReference<>();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final HttpServer server;

        private Rig(Path tmp) throws Exception {
            oldHome = System.getProperty("user.home");
            System.setProperty("user.home", tmp.toString());
            var providers = ProviderRegistry.create();
            providers.register(FauxProvider.text("ok"));
            session = AgentSession.create(ArgsParser.parse(new String[] {
                "--provider", "faux", "--model", "hello", "--no-session"}), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(null), new DefaultFileSystem()));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/bug-reports", exchange -> {
                calls.incrementAndGet();
                authorization.set(exchange.getRequestHeaders().getFirst("authorization"));
                exchange.getRequestBody().readAllBytes();
                var body = "{\"ok\":true,\"bug_report\":{\"id\":\"br-42\"}}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        void beginUpload(Supplier<Optional<String>> credentials) {
            var upload = new BugReportUpload(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort());
            new BugReportFlow(session, chatScreen, dispatcher, overlay::set,
                () -> upload, () -> false, () -> sessionRoot(), credentials).begin(null);
            drive();
            drive();
            drive();
        }

        private Path sessionRoot() {
            return Path.of(System.getProperty("user.home"));
        }

        private void drive() {
            var current = overlay.get();
            assertThat(current).isNotNull();
            current.onKeyEvent(ENTER);
            assertThat(current.isDone()).isTrue();
            overlay.set(null);
            current.apply(session, ignored -> {});
            dispatcher.drain();
        }

        void awaitText(String expected) {
            await(() -> chatScreen.messages().stream().anyMatch(message -> switch (message) {
                case ChatMessage.System system -> system.text().contains(expected);
                case ChatMessage.Error error -> error.message().contains(expected);
                default -> false;
            }), "chat text: " + expected);
        }

        void awaitChoice() {
            await(() -> overlay.get() instanceof BugReportChoiceScreen, "upload failure choice");
        }

        private void await(java.util.function.BooleanSupplier condition, String description) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                dispatcher.drain();
                if (condition.getAsBoolean()) {
                    return;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            throw new AssertionError("Timed out waiting for " + description);
        }

        @Override
        public void close() {
            server.stop(0);
            session.close();
            if (oldHome != null) {
                System.setProperty("user.home", oldHome);
            }
        }
    }
}

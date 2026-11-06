package com.pijava.coding.agent.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SessionShareServiceTest {

    @TempDir
    static Path tempRoot;

    /** L5 W4：destination=gist 跳过 Radius（即使有 token）。 */
    @Test
    void gistDestinationSkipsRadiusEvenWithToken() throws Exception {
        try (var rig = SessionShareServiceTestRig.create().withRadiusToken("token")
                .withDestination("gist")
                .withGhAuth(0).withGistResult(0, "https://gist.github.com/u/abc1\n", "")) {
            var result = rig.service().share(ShareCancellation.never());
            assertThat(result.kind()).isEqualTo(ShareOutcome.Kind.SUCCESS);
            assertThat(rig.radiusCalls()).isZero();
            assertThat(rig.ghCalls()).isPositive();
        }
    }

    /** L5 W4：destination=radius 未认证 ⇒ 逐字错误且不回落 gist。 */
    @Test
    void radiusDestinationWithoutAuthDoesNotFallBack() throws Exception {
        try (var rig = SessionShareServiceTestRig.create().withoutRadiusToken()
                .withDestination("radius")) {
            var result = rig.service().share(ShareCancellation.never());
            assertThat(result.kind()).isEqualTo(ShareOutcome.Kind.ERROR);
            assertThat(result.message())
                .isEqualTo("Radius authentication is not configured. Run '/login radius' first.");
            assertThat(rig.ghCalls()).isZero();
        }
    }

    /** L5 W4：auto 保持旧行为（有 token ⇒ Radius）。 */
    @Test
    void autoDestinationUsesRadiusWhenAuthenticated() throws Exception {
        try (var rig = SessionShareServiceTestRig.create().withRadiusToken("token")) {
            var result = rig.service().share(ShareCancellation.never());
            assertThat(result.kind()).isEqualTo(ShareOutcome.Kind.SUCCESS);
            assertThat(rig.radiusCalls()).isEqualTo(1);
        }
    }

    @Test
    void radiusFailureNeverFallsBackToGist() throws Exception {
        try (var rig = SessionShareServiceTestRig.create()
                .withRadiusToken("token").withRadiusResponse(500, "{\"error\":\"denied\"}")) {
            var result = rig.service().share(ShareCancellation.never());

            assertThat(result.kind()).isEqualTo(ShareOutcome.Kind.ERROR);
            assertThat(result.message()).isEqualTo("Failed to upload Radius artifact: denied");
            assertThat(rig.ghCalls()).isZero();
        }
    }

    @Test
    void gistUsesHtmlAndReturnsViewerAndGistUrls() throws Exception {
        try (var rig = SessionShareServiceTestRig.create().withoutRadiusToken()
                .withGhAuth(0).withGistResult(0, "https://gist.github.com/u/abc123\n", "")) {
            var result = rig.service().share(ShareCancellation.never());

            assertThat(result.kind()).isEqualTo(ShareOutcome.Kind.SUCCESS);
            assertThat(result.message()).isEqualTo(
                "Share URL: https://pi.dev/session/#abc123\nGist: https://gist.github.com/u/abc123");
            assertThat(rig.ghCommands().getLast()).startsWith("gist", "create", "--public=false");
            assertThat(rig.htmlAtGist).contains("<!DOCTYPE html>");
            assertThat(rig.tempChildren()).isEmpty();
        }
    }

    @Test
    void missingGhHasExactInstallMessage() throws Exception {
        try (var rig = SessionShareServiceTestRig.create().withoutRadiusToken()
                .withGhLaunchError(new IOException("CreateProcess error=2"))) {
            var result = rig.service().share(ShareCancellation.never());

            assertThat(result.message()).isEqualTo(
                "GitHub CLI (gh) is not installed. Install it from https://cli.github.com/");
            assertThat(rig.gistCreateCalls()).isZero();
        }
    }

    @Test
    void loggedOutGhStopsBeforeHtmlOrGistCreation() throws Exception {
        try (var rig = SessionShareServiceTestRig.create().withoutRadiusToken().withGhAuth(1)) {
            var result = rig.service().share(ShareCancellation.never());

            assertThat(result.message()).isEqualTo(
                "GitHub CLI is not logged in. Run 'gh auth login' first.");
            assertThat(rig.gistCreateCalls()).isZero();
        }
    }

    @Test
    void gistFailureUsesTrimmedStderr() throws Exception {
        try (var rig = SessionShareServiceTestRig.create().withoutRadiusToken()
                .withGhAuth(0).withGistResult(1, "", "  denied\n")) {
            var result = rig.service().share(ShareCancellation.never());

            assertThat(result.message()).isEqualTo("Failed to create gist: denied");
        }
    }

    @Test
    void blankGistOutputHasExactParseError() throws Exception {
        try (var rig = SessionShareServiceTestRig.create().withoutRadiusToken()
                .withGhAuth(0).withGistResult(0, "\n", "")) {
            var result = rig.service().share(ShareCancellation.never());

            assertThat(result.message()).isEqualTo("Failed to parse gist ID from gh output");
        }
    }

    private static final class SessionShareServiceTestRig implements AutoCloseable {
        private static final String NDJSON = """
            {"type":"session","version":3,"id":"s","timestamp":"2026-01-01T00:00:00Z","cwd":"/tmp"}
            {"type":"custom","customType":"pi.share","id":"x","parentId":null,"timestamp":"2026-01-01T00:00:00Z","data":{"systemPrompt":"","tools":[]}}
            """;
        private final Path tempParent;
        private final HttpServer server;
        private final List<List<String>> commands = new ArrayList<>();
        private volatile boolean radiusProvider = true;
        private volatile Optional<String> token = Optional.empty();
        private volatile int radiusStatus = 200;
        private volatile String radiusBody =
            "{\"artifact\":{\"canonical_url\":\"https://radius.example/a/1\"}}";
        private volatile int ghAuth = 0;
        private volatile GhCli.Result gist = new GhCli.Result(0, "", "");
        private volatile IOException ghLaunchError;
        private volatile String htmlAtGist;
        /** L5 W4：{@code sessionShareDestination}（auto|gist|radius）。 */
        private volatile String destination = "auto";
        /** L5 W4：Radius 端点被调次数的观测计数。 */
        private final java.util.concurrent.atomic.AtomicInteger radiusCalls =
            new java.util.concurrent.atomic.AtomicInteger();

        private SessionShareServiceTestRig(Path tempParent) throws IOException {
            this.tempParent = tempParent;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/artifacts", exchange -> {
                radiusCalls.incrementAndGet();
                var body = radiusBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(radiusStatus, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        static SessionShareServiceTestRig create() {
            try {
                return new SessionShareServiceTestRig(
                    Files.createTempDirectory(tempRoot, "share-service"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        SessionShareServiceTestRig withRadiusToken(String value) {
            token = Optional.of(value);
            return this;
        }

        SessionShareServiceTestRig withoutRadiusToken() {
            token = Optional.empty();
            return this;
        }

        SessionShareServiceTestRig withDestination(String value) {
            destination = value;
            return this;
        }

        SessionShareServiceTestRig withRadiusResponse(int status, String body) {
            radiusStatus = status;
            radiusBody = body;
            return this;
        }

        SessionShareServiceTestRig withGhAuth(int status) {
            ghAuth = status;
            return this;
        }

        SessionShareServiceTestRig withGistResult(int status, String stdout, String stderr) {
            gist = new GhCli.Result(status, stdout, stderr);
            return this;
        }

        SessionShareServiceTestRig withGhLaunchError(IOException error) {
            ghLaunchError = error;
            return this;
        }

        SessionShareService service() {
            Supplier<String> serialized = () -> NDJSON;
            BooleanSupplier provider = () -> radiusProvider;
            Supplier<Optional<String>> credentials = () -> token;
            return new SessionShareService(serialized, provider, credentials,
                new RadiusArtifactUploader("http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newHttpClient()),
                this::runGh, () -> tempParent, "https://pi.dev/session/",
                () -> destination);
        }

        private GhCli.Result runGh(List<String> arguments) throws IOException {
            commands.add(List.copyOf(arguments));
            if (arguments.equals(List.of("auth", "status"))) {
                if (ghLaunchError != null) {
                    throw ghLaunchError;
                }
                return new GhCli.Result(ghAuth, "", "");
            }
            if (arguments.size() == 4 && arguments.get(0).equals("gist")) {
                try {
                    htmlAtGist = Files.readString(Path.of(arguments.get(3)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return gist;
            }
            throw new AssertionError("unexpected gh command: " + arguments);
        }

        int ghCalls() {
            return commands.size();
        }

        /** L5 W4：Radius 上传次数（destination 门的观测点）。 */
        int radiusCalls() {
            return radiusCalls.get();
        }

        int gistCreateCalls() {
            return (int) commands.stream().filter(c -> c.getFirst().equals("gist")).count();
        }

        List<List<String>> ghCommands() {
            return commands;
        }

        List<Path> tempChildren() throws IOException {
            try (var children = Files.list(tempParent)) {
                return children.toList();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

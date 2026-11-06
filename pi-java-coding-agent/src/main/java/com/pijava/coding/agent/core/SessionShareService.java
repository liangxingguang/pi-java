package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.ai.auth.Credentials;
import com.pijava.coding.agent.export.HtmlExporter;

/** Shares the current branch through Radius, or a private gh gist when Radius has no token. */
public final class SessionShareService {

    /** Loader-visible sharing stage. */
    public enum Stage { RADIUS_UPLOAD, GIST_CREATE }

    private final Supplier<String> sessionJsonl;
    private final BooleanSupplier radiusProviderPresent;
    private final Supplier<Optional<String>> radiusToken;
    private final RadiusArtifactUploader uploader;
    private final GhCli.Runner gh;
    private final Supplier<Path> tempParent;
    private final String viewerBase;
    /** L5 W4：{@code sessionShareDestination}（auto|gist|radius）现读口。 */
    private final Supplier<String> destination;

    /** Create the production service for one session. */
    public SessionShareService(AgentSession session) {
        this(() -> SessionBranchSerializer.serialize(session),
            () -> session.services().providers().get("radius").isPresent(),
            () -> Credentials.resolveApiKey("radius"), new RadiusArtifactUploader(),
            GhCli.processRunner(), () -> Path.of(System.getProperty("java.io.tmpdir")), viewerBase(),
            () -> session.services().settings().accessors().getSessionShareDestination());
    }

    /** 七参旧形状（destination 缺省 auto）。 */
    SessionShareService(Supplier<String> sessionJsonl, BooleanSupplier radiusProviderPresent,
                        Supplier<Optional<String>> radiusToken, RadiusArtifactUploader uploader,
                        GhCli.Runner gh, Supplier<Path> tempParent, String viewerBase) {
        this(sessionJsonl, radiusProviderPresent, radiusToken, uploader, gh, tempParent,
            viewerBase, () -> "auto");
    }

    SessionShareService(Supplier<String> sessionJsonl, BooleanSupplier radiusProviderPresent,
                        Supplier<Optional<String>> radiusToken, RadiusArtifactUploader uploader,
                        GhCli.Runner gh, Supplier<Path> tempParent, String viewerBase,
                        Supplier<String> destination) {
        this.sessionJsonl = sessionJsonl;
        this.radiusProviderPresent = radiusProviderPresent;
        this.radiusToken = radiusToken;
        this.uploader = uploader;
        this.gh = gh;
        this.tempParent = tempParent;
        this.viewerBase = viewerBase;
        this.destination = destination;
    }

    /** Share without loader notifications. */
    public ShareOutcome share(ShareCancellation cancellation) {
        return share(cancellation, ignored -> {});
    }

    /** Share and report the two upload stages without performing UI effects in the domain service. */
    public ShareOutcome share(ShareCancellation cancellation, Consumer<Stage> stages) {
        Path tempDir = null;
        try {
            Path jsonl;
            try {
                tempDir = Files.createTempDirectory(tempParent.get(), "pi-share-");
                jsonl = tempDir.resolve("session.jsonl");
                Files.writeString(jsonl, sessionJsonl.get(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return error("Failed to export session: " + messageOf(e));
            }
            if (cancellation.isCancelled()) {
                return cancelled();
            }
            // L5 W4（pi session-share.ts:66-82）：destination 门。
            // gist ⇒ 跳过 Radius；radius 未认证 ⇒ 逐字错误且不回落；auto ⇒ 旧行为。
            var target = destination.get();
            if (!"gist".equals(target)) {
                if (radiusProviderPresent.getAsBoolean()) {
                    Optional<String> token;
                    try {
                        token = radiusToken.get();
                    } catch (RuntimeException e) {
                        return error("Failed to upload Radius artifact: " + messageOf(e));
                    }
                    if (token.isPresent() && !token.get().isBlank()) {
                        stages.accept(Stage.RADIUS_UPLOAD);
                        return uploader.upload(jsonl, token.get(), cancellation);
                    }
                }
                if ("radius".equals(target)) {
                    return error("Radius authentication is not configured."
                        + " Run '/login radius' first.");
                }
            }
            if (cancellation.isCancelled()) {
                return cancelled();
            }
            try {
                var auth = gh.run(List.of("auth", "status"));
                if (auth.exitCode() != 0) {
                    return error("GitHub CLI is not logged in. Run 'gh auth login' first.");
                }
            } catch (IOException e) {
                return error("GitHub CLI (gh) is not installed. Install it from https://cli.github.com/");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cancelled();
            }
            var html = tempDir.resolve("session.html");
            try {
                new HtmlExporter().export(jsonl, html);
            } catch (Exception e) {
                return error("Failed to export session: " + messageOf(e));
            }
            if (cancellation.isCancelled()) {
                return cancelled();
            }
            stages.accept(Stage.GIST_CREATE);
            GhCli.Result gist;
            try {
                gist = gh.run(List.of("gist", "create", "--public=false", html.toString()));
            } catch (IOException e) {
                return error("Failed to create gist: " + messageOf(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cancelled();
            }
            if (cancellation.isCancelled()) {
                return cancelled();
            }
            if (gist.exitCode() != 0) {
                var detail = gist.stderr().trim();
                return error("Failed to create gist: " + (detail.isEmpty() ? "Unknown error" : detail));
            }
            var gistUrl = gist.stdout().trim();
            var gistId = gistUrl.substring(gistUrl.lastIndexOf('/') + 1);
            if (gistId.isEmpty()) {
                return error("Failed to parse gist ID from gh output");
            }
            return new ShareOutcome(ShareOutcome.Kind.SUCCESS,
                "Share URL: " + viewerBase + "#" + gistId + "\nGist: " + gistUrl);
        } finally {
            if (tempDir != null) {
                deleteTree(tempDir);
            }
        }
    }

    private static String viewerBase() {
        var configured = System.getenv("PI_SHARE_VIEWER_URL");
        return configured == null || configured.isBlank() ? "https://pi.dev/session/" : configured;
    }

    private static ShareOutcome cancelled() {
        return new ShareOutcome(ShareOutcome.Kind.CANCELLED, "Share cancelled");
    }

    private static ShareOutcome error(String message) {
        return new ShareOutcome(ShareOutcome.Kind.ERROR, message);
    }

    private static String messageOf(Exception error) {
        return error.getMessage() == null ? "Unknown error" : error.getMessage();
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup, matching pi's force/rm catch.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}

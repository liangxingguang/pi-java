package com.pijava.tui.screen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.core.LoginAuthType;
import com.pijava.coding.agent.core.LoginExecutor;
import com.pijava.coding.agent.core.LoginProviderOption;
import com.pijava.coding.agent.core.ProviderLoginService;
import com.pijava.mcp.config.McpConfig;
import com.pijava.mcp.config.McpServerConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LoginFlowTest {

    @TempDir
    static Path tempRoot;

    @Test
    void cancellingLoginReturnsToProviderSelectorWithoutWritingCredential() {
        var rig = LoginFlowTestRig.create();
        rig.blockLogins();
        rig.flow.openProviderOptions();
        rig.flow.startProviderLogin(rig.radiusOauth);

        rig.flow.cancelDialog();

        assertThat(rig.flow.currentScreen()).isEqualTo(LoginFlow.Screen.PROVIDER);
        assertThat(rig.credentialsWrites()).isZero();
    }

    @Test
    void topLevelMenuPlacesRadiusAfterAuthenticationTypes() {
        var rig = LoginFlowTestRig.create();

        rig.flow.begin(null);

        assertThat(rig.flow.currentScreen()).isEqualTo(LoginFlow.Screen.AUTH_TYPE);
        assertThat(rig.flow.authTypeOptions()).containsExactly(
            "Sign in with an account", "Sign in with an API key", "Sign in with Radius");
    }

    @Test
    void directProviderReferenceStartsLoginWithoutProviderSelector() {
        var rig = LoginFlowTestRig.createSingleMethod();
        rig.blockLogins();

        rig.flow.begin("  rAdIuS ");

        assertThat(rig.flow.currentScreen()).isEqualTo(LoginFlow.Screen.DIALOG);
        rig.flow.cancelDialog();
    }

    @Test
    void successfulRadiusLoginReloadsOnlyAfterMcpOfferIsAccepted() throws Exception {
        var rig = LoginFlowTestRig.create();

        rig.flow.startProviderLogin(rig.radiusOauth);
        rig.awaitScreen(LoginFlow.Screen.MCP_OFFER);
        assertThat(rig.host.reloads.get()).isZero();
        rig.confirmCurrentOverlay();

        rig.awaitScreen(LoginFlow.Screen.CLOSED);
        assertThat(rig.host.reloads.get()).isEqualTo(1);
        assertThat(McpConfig.load(rig.agentDir, rig.agentDir, false).servers())
            .extracting(McpServerEntryView::name)
            .containsExactly("radius");
    }

    @Test
    void alreadyConfiguredRadiusMcpClosesWithoutOfferOrReload() throws Exception {
        var rig = LoginFlowTestRig.create();
        McpConfig.add(rig.agentDir.resolve("mcp.json"), "radius",
            new McpServerConfig.Http(null, null, null, null, null, null,
                "https://radius.pi.dev/mcp", null, null,
                new McpServerConfig.Auth("radius")));

        rig.flow.startProviderLogin(rig.radiusOauth);

        rig.awaitScreen(LoginFlow.Screen.CLOSED);
        assertThat(rig.host.overlay).isNull();
        assertThat(rig.host.reloads.get()).isZero();
    }

    @Test
    void secretInputIsMaskedButSubmittedVerbatim() {
        var option = new LoginProviderOption("radius", "Radius", LoginAuthType.API_KEY, null, false);
        var dialog = new LoginDialogScreen(option, Runnable::run, () -> {});
        var secret = CompletableFuture.supplyAsync(() -> dialog.secret("Enter Radius API key"),
            Thread::startVirtualThread);
        await(dialog::awaitingInput);

        dialog.onKeyEvent(KeyEvent.ofChar('k'));
        dialog.onKeyEvent(KeyEvent.ofChar('e'));
        dialog.onKeyEvent(KeyEvent.ofChar('y'));

        assertThat(dialog.inputDisplay()).isEqualTo("***").doesNotContain("key");
        dialog.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(secret.join()).isEqualTo("key");
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(condition.getAsBoolean()).as("condition before timeout").isTrue();
    }

    /** View helper keeps the extraction lambda independent of MCP implementation details. */
    private interface McpServerEntryView {
        static String name(com.pijava.mcp.config.McpServerEntry entry) {
            return entry.name();
        }
    }

    private static final class LoginFlowTestRig {
        private final Path agentDir;
        private final LoginProviderOption radiusOauth =
            new LoginProviderOption("radius", "Radius", LoginAuthType.OAUTH, null, false);
        private final LoginProviderOption radiusApi =
            new LoginProviderOption("radius", "Radius", LoginAuthType.API_KEY, null, false);
        private final RecordingHost host = new RecordingHost();
        private final AtomicInteger credentialWrites = new AtomicInteger();
        private final CountDownLatch loginStarted = new CountDownLatch(1);
        private final CountDownLatch loginRelease = new CountDownLatch(1);
        private volatile boolean blocked;
        private final LoginFlow flow;

        private LoginFlowTestRig(Path agentDir, boolean singleMethod) {
            this.agentDir = agentDir;
            LoginExecutor executor = (option, interaction) -> {
                loginStarted.countDown();
                if (blocked) {
                    try {
                        if (!loginRelease.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("login timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Login cancelled", e);
                    }
                }
                credentialWrites.incrementAndGet();
                return new ProviderLoginService.LoginSuccess(option.id(), option.name(),
                    option.authType(), "Logged in to Radius. Credentials saved to auth.json",
                    null, null);
            };
            flow = new LoginFlow(singleMethod ? List.of(radiusOauth) : List.of(radiusApi, radiusOauth),
                executor, agentDir, agentDir, host, Runnable::run);
        }

        static LoginFlowTestRig create() {
            return create(false);
        }

        static LoginFlowTestRig createSingleMethod() {
            return create(true);
        }

        private static LoginFlowTestRig create(boolean singleMethod) {
            try {
                return new LoginFlowTestRig(Files.createTempDirectory(tempRoot, "login-flow"), singleMethod);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void blockLogins() {
            blocked = true;
        }

        int credentialsWrites() {
            return credentialWrites.get();
        }

        void awaitScreen(LoginFlow.Screen expected) {
            LoginFlowTest.await(() -> flow.currentScreen() == expected);
        }

        void confirmCurrentOverlay() {
            var overlay = host.overlay;
            overlay.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
            assertThat(overlay.isDone()).isTrue();
            host.overlay = null;
            overlay.apply(null, ignored -> {});
        }
    }

    private static final class RecordingHost implements LoginFlow.Host {
        private final List<String> statuses = new ArrayList<>();
        private final AtomicInteger reloads = new AtomicInteger();
        private ScreenOverlay overlay;

        @Override public void open(ScreenOverlay next) { overlay = next; }

        @Override
        public void dismiss(ScreenOverlay target) {
            if (overlay == target) {
                overlay = null;
                target.apply(null, ignored -> {});
            }
        }

        @Override public void status(String message) { statuses.add(message); }
        @Override public void warning(String message) {}
        @Override public void error(String message) { throw new AssertionError(message); }
        @Override public void reload() { reloads.incrementAndGet(); }
    }
}

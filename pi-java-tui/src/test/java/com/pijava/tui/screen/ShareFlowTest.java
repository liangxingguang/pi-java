package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.pijava.coding.agent.core.SessionShareService;
import com.pijava.coding.agent.core.ShareOutcome;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShareFlowTest {

    @Test
    void radiusFailureShowsOnlyRadiusLoaderAndNeverGistLoader() {
        var rig = new Rig((cancellation, stages) -> {
            stages.accept(SessionShareService.Stage.RADIUS_UPLOAD);
            return new ShareOutcome(ShareOutcome.Kind.ERROR, "Failed to upload Radius artifact: denied");
        });

        rig.flow.begin();
        rig.awaitError();

        assertThat(rig.host.lastLoader.message()).isEqualTo("Uploading to Radius...");
        assertThat(rig.host.errors).containsExactly("Failed to upload Radius artifact: denied");
    }

    @Test
    void gistFallbackShowsGistLoaderAndFinalUrls() {
        var rig = new Rig((cancellation, stages) -> {
            stages.accept(SessionShareService.Stage.GIST_CREATE);
            return new ShareOutcome(ShareOutcome.Kind.SUCCESS,
                "Share URL: https://pi.dev/session/#abc\nGist: https://gist.github.com/u/abc");
        });

        rig.flow.begin();
        rig.awaitStatus();

        assertThat(rig.host.lastLoader.message()).isEqualTo("Creating gist...");
        assertThat(rig.host.statuses).containsExactly(
            "Share URL: https://pi.dev/session/#abc\nGist: https://gist.github.com/u/abc");
    }

    @Test
    void escapeCancelsWorkerAndReportsShareCancelled() {
        var started = new CountDownLatch(1);
        var rig = new Rig((cancellation, stages) -> {
            stages.accept(SessionShareService.Stage.RADIUS_UPLOAD);
            started.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ShareOutcome(ShareOutcome.Kind.CANCELLED, "Share cancelled");
            }
            return new ShareOutcome(ShareOutcome.Kind.SUCCESS, "unexpected");
        });
        rig.flow.begin();
        await(started);
        var loader = rig.host.lastLoader;

        loader.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        rig.host.overlay = null;
        loader.apply(null, ignored -> {});
        rig.awaitStatus();

        assertThat(rig.host.statuses).containsExactly("Share cancelled");
        assertThat(rig.host.errors).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class Rig {
        private final RecordingHost host = new RecordingHost();
        private final ShareFlow flow;

        private Rig(ShareFlow.ShareExecutor executor) {
            flow = new ShareFlow(executor, host, Runnable::run);
        }

        void awaitError() {
            awaitCondition(() -> !host.errors.isEmpty());
        }

        void awaitStatus() {
            awaitCondition(() -> !host.statuses.isEmpty());
        }

        private static void awaitCondition(java.util.function.BooleanSupplier condition) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            assertThat(condition.getAsBoolean()).isTrue();
        }
    }

    private static final class RecordingHost implements ShareFlow.Host {
        private final List<String> statuses = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private ScreenOverlay overlay;
        private ShareFlow.Loader lastLoader;

        @Override
        public void open(ScreenOverlay next) {
            overlay = next;
            lastLoader = (ShareFlow.Loader) next;
        }

        @Override
        public void dismiss(ScreenOverlay target) {
            if (overlay == target) {
                overlay = null;
                target.apply(null, ignored -> {});
            }
        }

        @Override public void status(String message) { statuses.add(message); }
        @Override public void error(String message) { errors.add(message); }
    }
}

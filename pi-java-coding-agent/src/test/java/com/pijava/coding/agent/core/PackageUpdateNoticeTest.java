package com.pijava.coding.agent.core;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.pkg.PackageUpdate;
import com.pijava.coding.agent.pkg.SourceScope;

/**
 * 批 19 T7（docs/30 §7）：启动包更新通知——文案逐字、异常/空表静默、
 * fire-and-forget 不阻塞调用线程。离线臂：环境变量进程全局不可注入，由
 * {@code PackageEnvironment} 单测覆盖判定本身（批 18 惯例）。
 */
class PackageUpdateNoticeTest {

    @Test
    void notificationTextVerbatim() {
        var text = PackageUpdateNotifier.notificationText(List.of(
            new PackageUpdate("npm:foo", "foo", PackageUpdate.Type.NPM, SourceScope.USER),
            new PackageUpdate("git:github.com/u/r", "github.com/u/r", PackageUpdate.Type.GIT,
                SourceScope.PROJECT)));

        assertThat(text).isEqualTo("""
            Package Updates Available
            Package updates are available. Run pi-java update --extensions
            Packages:
            - foo
            - github.com/u/r""");
    }

    @Test
    void nonEmptyUpdatesAreDelivered() throws InterruptedException {
        var delivered = new AtomicReference<List<PackageUpdate>>();
        var latch = new CountDownLatch(1);
        var updates = List.of(
            new PackageUpdate("npm:foo", "foo", PackageUpdate.Type.NPM, SourceScope.USER));

        PackageUpdateNotifier.checkAsync(() -> updates, list -> {
            delivered.set(list);
            latch.countDown();
        });

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered.get()).isEqualTo(updates);
    }

    @Test
    void emptyUpdatesAreSilent() throws InterruptedException {
        var latch = new CountDownLatch(1);

        PackageUpdateNotifier.checkAsync(List::of, list -> latch.countDown());

        assertThat(latch.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void callbackExceptionsAreSwallowedToo() throws InterruptedException {
        // 评审 I-3：UI 编排回调抛错（会话已关等）同样静默——不裸奔到虚拟线程。
        var updates = List.of(
            new PackageUpdate("npm:foo", "foo", PackageUpdate.Type.NPM, SourceScope.USER));
        var ran = new CountDownLatch(1);

        PackageUpdateNotifier.checkAsync(() -> updates, list -> {
            ran.countDown();
            throw new IllegalStateException("dispatcher closed");
        });

        // 回调确实跑过；虚拟线程无未捕获异常（不崩测试 JVM 的 uncaught 面）。
        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void exceptionsAreSwallowed() throws InterruptedException {
        // pi :1273-1278 吞错对位。
        var latch = new CountDownLatch(1);

        PackageUpdateNotifier.checkAsync(() -> {
            throw new IllegalStateException("registry down");
        }, list -> latch.countDown());

        assertThat(latch.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }
}

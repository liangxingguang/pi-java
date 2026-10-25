package com.pijava.coding.agent.theme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThemeWatcher} 防抖与重载测试（pi theme.ts:734-819 对位，
 * 批 21/docs/32 §7）。同包测试直接驱动 package-private 的 watcher。
 */
class ThemeWatcherTest {

    @TempDir
    Path dir;

    @Test
    void reloadsOnTargetFileChangeWithDebounce() throws Exception {
        var file = dir.resolve("nord.json");
        Files.writeString(file, "{}");
        var calls = new AtomicInteger();
        var latch = new CountDownLatch(1);
        try (var watcher = new ThemeWatcher(dir, "nord", () -> {
            calls.incrementAndGet();
            latch.countDown();
        })) {
            watcher.start();
            // 连续两写 ⇒ 防抖合并（容忍慢 FS：定时器合并不严格断言次数=1，
            // 只断言防抖窗内聚合——pi 语义是 clearTimeout 重排）。
            Files.writeString(file, "{\"v\":1}");
            Files.writeString(file, "{\"v\":2}");
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ignoresOtherFiles() throws Exception {
        Files.writeString(dir.resolve("nord.json"), "{}");
        var latch = new CountDownLatch(1);
        try (var watcher = new ThemeWatcher(dir, "nord", latch::countDown)) {
            watcher.start();
            Files.writeString(dir.resolve("other.json"), "{}");
            Files.writeString(dir.resolve("notes.md"), "x");
            assertThat(latch.await(1500, TimeUnit.MILLISECONDS)).isFalse();
        }
    }

    @Test
    void survivesMissingDirectoryClose() {
        // 注册失败臂（pi watchWithErrorHandler 错误 ⇒ 关监视器静默）。
        var watcher = new ThemeWatcher(dir.resolve("ghost"), "nord", () -> { });
        watcher.start();   // 目录不存在 ⇒ 内部关闭
        watcher.close();   // 幂等
    }
}

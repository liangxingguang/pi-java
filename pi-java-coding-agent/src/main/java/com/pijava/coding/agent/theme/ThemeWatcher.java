package com.pijava.coding.agent.theme;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 活跃自定义主题的文件监视热重载（pi {@code theme.ts:734-819} 的
 * {@code startThemeWatcher} 对位，批 21/docs/32 T7）。
 *
 * <p>逐字语义：只监视 {@code <agentDir>/themes/<name>.json} 一个文件；100ms
 * 防抖（合并编辑器连续写）；监视目录 itself 的 WatchService 错误 ⇒ 监视器关闭
 * （pi {@code watchWithErrorHandler} 的错误臂）。陈旧定时器/文件暂失/解析失败
 * 三臂在 {@link Themes} 的重载回调里（pi 同——都在防抖定时器体内）。</p>
 */
final class ThemeWatcher implements AutoCloseable {

    /** pi {@code setTimeout(..., 100)} 防抖窗。 */
    private static final long DEBOUNCE_MS = 100;

    private final Path themeFileName;
    private final Path watchedDir;
    private final Runnable reloadAction;
    private final WatchService watchService;
    private final ScheduledExecutorService scheduler;
    private final Thread watchThread;
    private volatile ScheduledFuture<?> pendingReload;
    private volatile boolean closed;

    ThemeWatcher(Path watchedDir, String themeName, Runnable reloadAction) {
        this.watchedDir = watchedDir;
        this.themeFileName = Path.of(themeName + ".json");
        this.reloadAction = reloadAction;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot watch themes directory " + watchedDir, e);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "pi-theme-watcher-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.watchThread = new Thread(this::pollLoop, "pi-theme-watcher");
        this.watchThread.setDaemon(true);
    }

    /** 注册目录监视并启动轮询线程。 */
    void start() {
        try {
            watchedDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            // pi watchWithErrorHandler 错误臂 ⇒ 监视器关闭（不起监视等同静默）
            close();
            return;
        }
        watchThread.start();
    }

    private void pollLoop() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                var context = event.context();
                // pi：filename 缺失 ⇒ 也重载（:796-798）；只认目标文件名（:800-802）
                if (context instanceof Path changed
                        && (changed.getFileName() == null
                            || !changed.getFileName().equals(themeFileName))) {
                    continue;
                }
                scheduleReload();
            }
            if (!key.reset()) {
                // 目录不可再监视（被删等）⇒ pi 错误臂等价：关监视器
                close();
                return;
            }
        }
    }

    /** pi {@code scheduleReload}（:757-787）：100ms 防抖，新事件清旧定时器。 */
    private synchronized void scheduleReload() {
        if (pendingReload != null) {
            pendingReload.cancel(false);
        }
        pendingReload = scheduler.schedule(reloadAction, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pendingReload != null) {
            pendingReload.cancel(false);
            pendingReload = null;
        }
        scheduler.shutdownNow();
        try {
            watchService.close();
        } catch (IOException e) {
            // 关闭失败无善后（pi closeWatcher 同静默）
        }
    }
}

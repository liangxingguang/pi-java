package com.pijava.mcp.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Process launch and process-tree termination for the stdio transport
 * (pi {@code transports/stdio.ts:46-205}).
 *
 * <p>pi 用**进程组**解决「父进程退出后子进程仍活着」：POSIX 下 {@code detached:true} ＋ {@code kill(-pid)}，
 * Windows 下 {@code taskkill /pid <pid> /T /F}。Java 的 {@link ProcessBuilder} 没有进程组，且
 * {@link ProcessHandle#descendants()} 只看得见**当前**的后代 —— 父进程一退，子进程被重新挂到别的父下，
 * 快照就找不到了。故这里改用「**关 stdin 之前**抓快照，之后按快照逐个杀」，可观察结果与 pi 等价：
 * 服务器及其后代都不会残留。</p>
 */
final class StdioProcess {

    /** 存活中的服务器进程树（pi 的 {@code liveProcessGroups}），JVM 退出时兜底清理。 */
    private static final Set<ProcessHandle> LIVE = ConcurrentHashMap.newKeySet();

    private static volatile boolean exitHookInstalled;

    /** A started process with its stdin. */
    record Launched(Process process, OutputStream stdin) {
    }

    private StdioProcess() {
    }

    /** Launch a child per the options. */
    static Launched start(StdioTransportOptions options) throws IOException {
        var pb = new ProcessBuilder();
        var command = new ArrayList<String>();
        command.add(options.command());
        command.addAll(options.args());
        pb.command(command);
        if (options.cwd() != null) {
            pb.directory(new java.io.File(options.cwd()));
        }

        var environment = pb.environment();
        if (!options.inheritEnv()) {
            environment.clear();
        }
        if (options.env() != null) {
            for (Map.Entry<String, String> entry : options.env().entrySet()) {
                environment.put(entry.getKey(), entry.getValue());
            }
        }

        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(stderrRedirect(options.stderr()));
        var process = pb.start();
        return new Launched(process, process.getOutputStream());
    }

    private static ProcessBuilder.Redirect stderrRedirect(StdioTransportOptions.Stderr routing) {
        return switch (routing) {
            case PIPE -> ProcessBuilder.Redirect.PIPE;
            case INHERIT -> ProcessBuilder.Redirect.INHERIT;
            case IGNORE -> ProcessBuilder.Redirect.DISCARD;
        };
    }

    /**
     * 关 stdin **之前**的进程树快照：后代在前、服务器本身在最后（先杀后代，免得它们被重新挂走）。
     *
     * <p>⚠️ 顺序不能颠倒：父进程先死会让 {@code descendants()} 立即返回空集。</p>
     */
    static List<ProcessHandle> snapshotTree(Process child) {
        var tree = new ArrayList<ProcessHandle>();
        child.toHandle().descendants().forEach(tree::add);
        tree.add(child.toHandle());
        return List.copyOf(tree);
    }

    /** 把一棵树登记进「JVM 退出兜底」（幂等）。 */
    static void register(List<ProcessHandle> tree) {
        installExitHook();
        LIVE.addAll(tree);
    }

    /** 服务器退出后撤销登记（幂等）。 */
    static void unregister(ProcessHandle handle) {
        LIVE.remove(handle);
    }

    /**
     * SIGTERM 等价：对快照里的每个句柄 {@link ProcessHandle#destroy()}，最多等 {@code timeoutMs}。
     *
     * @return 全体都已退出
     */
    static boolean terminate(List<ProcessHandle> tree, long timeoutMs) {
        tree.forEach(ProcessHandle::destroy);
        return awaitAll(tree, timeoutMs);
    }

    /**
     * SIGKILL 等价：{@link ProcessHandle#destroyForcibly()}，最多等 {@code timeoutMs}。
     *
     * @return 全体都已退出
     */
    static boolean kill(List<ProcessHandle> tree, long timeoutMs) {
        tree.forEach(ProcessHandle::destroyForcibly);
        return awaitAll(tree, timeoutMs);
    }

    private static boolean awaitAll(List<ProcessHandle> tree, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        for (var handle : tree) {
            while (handle.isAlive()) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * pi {@code installExitHook}：宿主进程意外退出时，`on exit` 兜底把还活着的服务器树杀掉。
     *
     * <p>Java 没有 {@code process.once("exit")} 的等价物，用 shutdown hook；只装一次，且只做
     * 「SIGTERM 等价」这一步（退出路径上不值得等 SIGKILL）。</p>
     */
    private static void installExitHook() {
        if (exitHookInstalled) {
            return;
        }
        synchronized (StdioProcess.class) {
            if (exitHookInstalled) {
                return;
            }
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    for (var handle : LIVE) {
                        try {
                            handle.descendants().forEach(ProcessHandle::destroy);
                            handle.destroy();
                        } catch (RuntimeException ignored) {
                            // 退出路径：单个进程失败不影响其余清理。
                        }
                    }
                }, "mcp-stdio-exit-hook"));
            } catch (IllegalStateException alreadyShuttingDown) {
                // JVM 已在关闭：无需再装。
            }
            exitHookInstalled = true;
        }
    }
}

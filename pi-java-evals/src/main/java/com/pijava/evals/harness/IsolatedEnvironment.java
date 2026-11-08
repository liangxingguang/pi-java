package com.pijava.evals.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * 批 32（docs/44 §3.1）：一次 eval arm 的隔离环境 —— pi {@code harness.ts:283-287}
 * 的 {@code mkdtemp} 临时根、隔离 home/workspace/sessionDir 对位。
 *
 * <p>覆盖 {@code user.home}/{@code user.dir} 是<b>进程级</b>状态：本类用静态锁
 * 串行化所有臂（禁止并行），{@link #close()} 在 finally 中还原属性并递归删除临时根。
 * 部分构造失败也走同一条还原路径。</p>
 */
public final class IsolatedEnvironment implements AutoCloseable {

    /**
     * agent 目录覆盖属性（{@code FileSettingsStorage#AGENT_DIR_PROPERTY}）。
     * 复述常量值以免 harness 直接依赖 coding-agent 的内部类路径。
     */
    static final String AGENT_DIR_PROPERTY =
        com.pijava.coding.agent.core.FileSettingsStorage.AGENT_DIR_PROPERTY;

    /** 单许可信号量贯穿环境生命周期；{@link #close()} 释放。 */
    private static final Semaphore LOCK = new Semaphore(1);

    private final Path root;
    private final Path home;
    private final Path workspace;
    private final Path sessionDir;
    private final String previousUserHome;
    private final String previousUserDir;
    private final String previousAgentDir;
    private boolean closed;

    private IsolatedEnvironment(Path root, Path home, Path workspace, Path sessionDir,
                                String previousUserHome, String previousUserDir,
                                String previousAgentDir) {
        this.root = root;
        this.home = home;
        this.workspace = workspace;
        this.sessionDir = sessionDir;
        this.previousUserHome = previousUserHome;
        this.previousUserDir = previousUserDir;
        this.previousAgentDir = previousAgentDir;
    }

    /**
     * 建立隔离根并覆盖进程属性。调用方必须在 finally 关闭。
     *
     * @return open environment (holds the process-wide isolation lock)
     * @throws IOException if the temporary tree cannot be created
     */
    public static IsolatedEnvironment open() throws IOException {
        LOCK.acquireUninterruptibly();
        var previousHome = System.getProperty("user.home");
        var previousDir = System.getProperty("user.dir");
        var previousAgentDir = System.getProperty(AGENT_DIR_PROPERTY);
        Path root = null;
        try {
            root = Files.createTempDirectory("pi-java-eval-");
            var home = Files.createDirectories(root.resolve("home"));
            var workspace = Files.createDirectories(root.resolve("workspace"));
            var sessions = Files.createDirectories(root.resolve("sessions"));
            var agentDir = Files.createDirectories(home.resolve(".pi-java").resolve("agent"));
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", workspace.toString());
            // L6 W4（docs/26 §5.2）：本仓实际读 ~/.pi-java/agent，靠属性注入指到隔离根。
            System.setProperty(AGENT_DIR_PROPERTY, agentDir.toString());
            return new IsolatedEnvironment(root, home, workspace, sessions,
                previousHome, previousDir, previousAgentDir);
        } catch (IOException | RuntimeException e) {
            restore(previousHome, previousDir, previousAgentDir);
            if (root != null) {
                deleteTree(root);
            }
            LOCK.release();
            throw e;
        }
    }

    /** @return 临时根目录 */
    public Path root() {
        return root;
    }

    /** @return 隔离 home */
    public Path home() {
        return home;
    }

    /** @return {@code home/.pi-java/agent}（本仓 agent 目录；pi 侧是 {@code home/.pi/agent}） */
    public Path agentDir() {
        return home.resolve(".pi-java").resolve("agent");
    }

    /** @return 隔离 workspace（也是 {@code user.dir}） */
    public Path workspace() {
        return workspace;
    }

    /** @return 隔离 sessionDir */
    public Path sessionDir() {
        return sessionDir;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            restore(previousUserHome, previousUserDir, previousAgentDir);
        } finally {
            try {
                deleteTree(root);
            } finally {
                LOCK.release();
            }
        }
    }

    private static void restore(String home, String dir, String agentDir) {
        if (home == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", home);
        }
        if (dir == null) {
            System.clearProperty("user.dir");
        } else {
            System.setProperty("user.dir", dir);
        }
        if (agentDir == null) {
            System.clearProperty(AGENT_DIR_PROPERTY);
        } else {
            System.setProperty(AGENT_DIR_PROPERTY, agentDir);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // pi 同旨：清理失败忽略。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }
}

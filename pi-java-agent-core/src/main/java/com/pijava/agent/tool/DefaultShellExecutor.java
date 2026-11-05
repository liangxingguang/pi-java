package com.pijava.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Default shell executor using ProcessBuilder + Virtual Threads.
 *
 * <p>Aligned with pi's {@code getShellConfig} (utils/shell.ts): commands
 * always run in a real bash. When bash is not on PATH the solution is the
 * same as pi's — the user sets {@code shellPath} in settings.json. The
 * resolution order is the configured {@code shellPath}, then Git Bash in its
 * known install locations, then {@code bash.exe} on PATH; if none is found
 * an actionable error pointing at Git for Windows is thrown (pi behavior —
 * no silent cmd fallback, no magic auto-detection). The legacy WSL
 * {@code C:\Windows\System32\bash.exe} is special cased to receive the
 * command on stdin instead of {@code -c}.</p>
 *
 * <p>Git Bash on Windows is launched as a login shell ({@code --login -c})
 * because its non-login shells inherit only the Windows PATH — without the
 * login profile, coreutils like {@code ls} (in {@code /usr/bin}) are not on
 * PATH and every command fails.</p>
 */
public class DefaultShellExecutor implements ShellExecutor {

    private final String customShellPath;

    /** Create a default shell executor with no custom shell path. */
    public DefaultShellExecutor() {
        this(null);
    }

    /** @param customShellPath optional {@code shellPath} from settings.json */
    public DefaultShellExecutor(String customShellPath) {
        this.customShellPath = customShellPath;
    }

    @Override
    public ShellResult execute(String command, ShellOptions options) throws Exception {
        ShellConfig config = resolveShell();
        return ShellProcessEngine.run(command, options, config.args(),
            config.transport() == ShellTransport.STDIN);
    }

    // ── Shell resolution (mirrors pi's getShellConfig) ────────────

    private ShellConfig resolveShell() throws IOException {
        if (customShellPath != null && !customShellPath.isBlank()) {
            String expanded = expandHome(customShellPath.trim());
            if (!shellPathExists(expanded)) {
                throw new IOException("Custom shell path not found: " + expanded);
            }
            return configFor(expanded);
        }

        if (isWindows()) {
            List<String> candidates = gitBashCandidates();
            for (String candidate : candidates) {
                if (shellPathExists(candidate)) {
                    return configFor(candidate);
                }
            }
            String onPath = firstOnPath("bash.exe");
            if (onPath != null) {
                return configFor(onPath);
            }
            throw new IOException(
                "No bash shell found. Options:\n"
                    + "  1. Install Git for Windows: https://git-scm.com/download/win\n"
                    + "  2. Add your bash to PATH (Cygwin, MSYS2, etc.)\n"
                    + "  3. Set shellPath in settings.json\n\n"
                    + "Searched Git Bash in:\n" + String.join("\n", candidates));
        }

        if (Files.exists(Path.of("/bin/bash"))) {
            return configFor("/bin/bash");
        }
        String onPath = firstOnPath("bash");
        if (onPath != null) {
            return configFor(onPath);
        }
        return new ShellConfig(List.of("sh", "-c"), ShellTransport.ARGV);
    }

    private static ShellConfig configFor(String shell) {
        if (isLegacyWslBashPath(shell)) {
            return new ShellConfig(List.of(shell, "-s"), ShellTransport.STDIN);
        }
        if (isWindows()) {
            // Git Bash / MSYS: receive the command over stdin. Passing it as a
            // `-c` argv goes through Windows command-line parsing and MSYS
            // backslash conversion, which corrupts escapes like `\n` inside
            // `echo -e "5\n3"` (observed as `5n3`).
            return new ShellConfig(List.of(shell, "--login", "-s"), ShellTransport.STDIN);
        }
        return new ShellConfig(List.of(shell, "-c"), ShellTransport.ARGV);
    }

    /**
     * pi {@code isLegacyWslBashPath}（shell.ts:13-25）：优先按 {@code SystemRoot}
     * 判（任意盘符的 Windows 目录）；根不可知时才用 {@code C:\Windows} 常规路径。
     */
    private static boolean isLegacyWslBashPath(String path) {
        var normalized = Path.of(path).normalize().toString()
            .replace('/', '\\').toLowerCase(Locale.ROOT);
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            var root = Path.of(systemRoot).normalize().toString()
                .replace('/', '\\').toLowerCase(Locale.ROOT);
            if (!root.matches("^[a-z]:[\\\\/].*")) {
                return false;
            }
            for (String directory : List.of("System32", "Sysnative")) {
                if (normalized.equals((root + "\\" + directory + "\\bash.exe")
                        .replace('/', '\\').toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
        // Fall back to conventional launcher paths only when the Windows directory is unknown.
        return normalized.matches("^[a-z]:\\\\windows\\\\(?:system32|sysnative)\\\\bash\\.exe$");
    }

    /**
     * pi {@code shellPathExists}（shell.ts:33-42）：Unix 等同 {@code access(F_OK)}；
     * Windows 刻意不用 Node {@code existsSync()} 的额外 stat（Store 执行别名会被它拒掉）。
     * Java 侧 {@link Files#exists} 是平台可达性判定，不复刻该额外 stat。
     */
    static boolean shellPathExists(String path) {
        try {
            return Files.exists(Path.of(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> gitBashCandidates() throws IOException {
        var candidates = new ArrayList<String>();
        addCandidate(candidates, System.getenv("ProgramFiles"), "Git\\bin\\bash.exe");
        addCandidate(candidates, System.getenv("ProgramFiles(x86)"), "Git\\bin\\bash.exe");
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String base, String relative) {
        if (base != null && !base.isBlank()) {
            addPath(candidates, Path.of(base, relative));
        }
    }

    private static void addPath(List<String> candidates, Path path) {
        var normalized = path.normalize().toString();
        if (!candidates.contains(normalized)) {
            candidates.add(normalized);
        }
    }

    static String firstOnPath(String executable) throws IOException {
        try {
            var pb = isWindows()
                ? new ProcessBuilder("where", executable)
                : new ProcessBuilder("which", executable);
            pb.redirectErrorStream(true);
            var process = pb.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            var first = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).lines().findFirst().orElse("").trim();
            // pi shell.ts:45-83：Windows 用 where 并校验可达；Unix 信任 which 输出
            //（Termux/特殊文件系统）。
            if (first.isEmpty()) {
                return null;
            }
            return isWindows() && !shellPathExists(first) ? null : first;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Mirrors pi's normalizePath: leading {@code ~} expands to the home dir. */
    private static String expandHome(String path) {
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private enum ShellTransport {
        ARGV,
        STDIN
    }

    private record ShellConfig(List<String> args, ShellTransport transport) {}
}

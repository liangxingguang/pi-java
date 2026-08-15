package com.pijava.agent.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        var pb = new ProcessBuilder();
        if (config.transport() == ShellTransport.STDIN) {
            pb.command(List.copyOf(config.args()));
        } else {
            pb.command(concat(config.args(), command));
        }
        pb.directory(Path.of(options.cwd()).toFile());
        if (!options.inheritEnv()) {
            pb.environment().clear();
        }
        pb.environment().putAll(options.env());
        pb.redirectErrorStream(true);

        var process = pb.start();
        if (config.transport() == ShellTransport.STDIN) {
            process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
        }
        var output = new ByteArrayOutputStream();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                try (var is = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        if (options.signal() != null && options.signal().isAborted()) {
                            process.destroyForcibly();
                            break;
                        }
                        output.write(buf, 0, n);
                    }
                }
                return process.waitFor();
            });

            int exitCode;
            boolean timedOut = false;
            try {
                if (options.timeoutSeconds().isPresent()) {
                    exitCode = future.get(options.timeoutSeconds().getAsLong(), TimeUnit.SECONDS);
                } else {
                    exitCode = future.get();
                }
            } catch (TimeoutException e) {
                timedOut = true;
                process.destroyForcibly();
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException ie) {
                    exitCode = -1;
                }
            }

            String outputStr = output.toString(StandardCharsets.UTF_8);
            long outputLines = outputStr.isEmpty() ? 0 : outputStr.lines().count();

            return new ShellResult(
                outputStr,
                exitCode,
                timedOut,
                false,  // truncation handled by BashTool
                outputLines,
                outputStr.getBytes(StandardCharsets.UTF_8).length
            );
        }
    }

    // ── Shell resolution (mirrors pi's getShellConfig) ────────────

    private ShellConfig resolveShell() throws IOException {
        if (customShellPath != null && !customShellPath.isBlank()) {
            String expanded = expandHome(customShellPath.trim());
            if (!Files.exists(Path.of(expanded))) {
                throw new IOException("Custom shell path not found: " + expanded);
            }
            return configFor(expanded);
        }

        if (isWindows()) {
            List<String> candidates = gitBashCandidates();
            for (String candidate : candidates) {
                if (Files.exists(Path.of(candidate))) {
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
        return isWindows()
            ? new ShellConfig(List.of(shell, "--login", "-c"), ShellTransport.ARGV)
            : new ShellConfig(List.of(shell, "-c"), ShellTransport.ARGV);
    }

    /** Mirrors pi's isLegacyWslBashPath. */
    private static boolean isLegacyWslBashPath(String path) {
        var normalized = path.replace('/', '\\').toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-z]:\\\\windows\\\\(?:system32|sysnative)\\\\bash\\.exe$");
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

    private static String firstOnPath(String executable) throws IOException {
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
            return first.isEmpty() || !Files.exists(Path.of(first)) ? null : first;
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

    private static List<String> concat(List<String> args, String command) {
        var all = new ArrayList<String>(args);
        all.add(command);
        return all;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private enum ShellTransport {
        ARGV,
        STDIN
    }

    private record ShellConfig(List<String> args, ShellTransport transport) {}
}

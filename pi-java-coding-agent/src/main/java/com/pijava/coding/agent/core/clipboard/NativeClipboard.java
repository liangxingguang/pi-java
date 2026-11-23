package com.pijava.coding.agent.core.clipboard;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 原生剪贴板命令行链（docs/26 §4.2，K34）：pi {@code native-platform.ts}
 * ＋{@code native-module-path.ts} 的 java 对位——pi 用预编译 {@code .node}
 * helper；java 改用各平台命令行（Termux、pbcopy、PowerShell/clip、
 * wl-copy/xclip/xsel），最后回退 AWT Toolkit。
 *
 * <p>候选链顺序即回退序，首个成功即停；每条命令 2 s 超时，非 0 退出或 IO
 * 异常切下一候选。空候选 {@code List.of()} 表示 AWT 兜底（测试可整体注入）。</p>
 */
public final class NativeClipboard {

    /** 命令执行器（测试注入点）：{@code stdin} 写入子进程标准输入。 */
    @FunctionalInterface
    public interface CommandRunner {
        /**
         * @param command 命令与参数；空列表表示 AWT 兜底
         * @param stdin   待写入标准输入的文本
         * @throws IOException 命令不存在、超时、非 0 退出或剪贴板不可用
         */
        void run(List<String> command, String stdin) throws IOException;
    }

    private static final long TIMEOUT_SECONDS = 2;

    private static volatile CommandRunner runner = NativeClipboard::runCommand;

    private NativeClipboard() {}

    /**
     * 复制文本：按平台候选链依次尝试，全部失败抛最后一个异常。
     *
     * @param text 待复制文本
     * @throws IOException 所有通道均失败
     */
    public static void copy(String text) throws IOException {
        IOException last = null;
        for (List<String> command : commandCandidates(System.getenv(), System.getProperty("os.name", ""))) {
            try {
                runner.run(command, text);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Clipboard unavailable");
    }

    /** 测试替换点：注入假执行器（{@code null} 恢复默认）。 */
    public static void overrideForTest(CommandRunner value) {
        runner = value == null ? NativeClipboard::runCommand : value;
    }

    /**
     * 平台候选链（包级测试面）。
     *
     * @param env    环境变量
     * @param osName {@code os.name}
     * @return 命令列表，末项恒为空列表（AWT 兜底）
     */
    static List<List<String>> commandCandidates(Map<String, String> env, String osName) {
        var candidates = new ArrayList<List<String>>();
        boolean windows = isWindows(osName);
        boolean mac = isMac(osName);
        if (isTermux(env)) {
            candidates.add(List.of("termux-clipboard-set"));
        }
        if (windows) {
            // PowerShell Set-Clipboard 走 UTF-8 标准输入，避免 clip.exe 的代码页问题。
            candidates.add(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "$ProgressPreference='SilentlyContinue'; Set-Clipboard -Value ([Console]::In.ReadToEnd())"));
            candidates.add(List.of("clip"));
        } else if (mac) {
            candidates.add(List.of("pbcopy"));
        } else if (!isTermux(env)) {
            candidates.add(List.of("wl-copy"));
            candidates.add(List.of("xclip", "-selection", "clipboard"));
            candidates.add(List.of("xsel", "--clipboard", "--input"));
        }
        candidates.add(List.of());
        return List.copyOf(candidates);
    }

    private static void runCommand(List<String> command, String stdin) throws IOException {
        if (command.isEmpty()) {
            awtCopy(stdin);
            return;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (var input = process.getOutputStream()) {
                input.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Clipboard command timed out: " + command.get(0));
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
                throw new IOException("Clipboard command failed (" + process.exitValue()
                    + "): " + command.get(0) + (output.isEmpty() ? "" : " — " + output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Clipboard command interrupted: " + command.get(0), e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void awtCopy(String text) throws IOException {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        } catch (Exception e) {
            throw new IOException("Clipboard unavailable: " + e.getMessage(), e);
        }
    }

    private static boolean isTermux(Map<String, String> env) {
        String prefix = env.get("PREFIX");
        return env.containsKey("TERMUX_VERSION") || (prefix != null && prefix.contains("com.termux"));
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }
}

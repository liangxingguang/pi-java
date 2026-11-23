package com.pijava.tui.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 终端能力探测（docs/26 §4.1，K35）：pi {@code terminal-image.ts:140
 * detectCapabilities} 的 java 对位——trueColor / hyperlinks / images 三档，
 * 按环境变量逐臂识别终端（tmux、screen、Herdr、Rex、Orca、Kitty、Ghostty、
 * WezTerm、Warp、iTerm2、Windows Terminal、Alacritty、VS Code、Zed、
 * JetBrains、Windows 控制台），并支持 {@code PI_TRUE_COLOR} /
 * {@code PI_HYPERLINKS} / {@code PI_IMAGE_PROTOCOL} 覆盖。
 *
 * <p>判定为纯函数 {@link #detect(Map, String)}（测试面）；{@link #get()} 缓存
 * 首次探测结果。hyperlinks 目前仅暴露能力（渲染仍只显文本，docs/26 §5 N2）。</p>
 *
 * @param images     图片协议（Kitty / iTerm2），不支持为 {@code null}
 * @param trueColor  是否 truecolor
 * @param hyperlinks 是否支持 OSC 8 超链接
 */
public record TerminalCapabilities(ImageProtocol images, boolean trueColor, boolean hyperlinks) {

    /** 终端图片协议。 */
    public enum ImageProtocol {
        /** Kitty graphics protocol. */
        KITTY,
        /** iTerm2 inline images. */
        ITERM2
    }

    private static volatile TerminalCapabilities cached;

    /** 进程级缓存探测（环境变化需显式 {@link #resetForTest()}）。 */
    public static TerminalCapabilities get() {
        var value = cached;
        if (value == null) {
            value = detect(System.getenv(), System.getProperty("os.name", ""));
            cached = value;
        }
        return value;
    }

    /** 复位缓存（测试）。 */
    public static void resetForTest() {
        cached = null;
    }

    /**
     * 纯函数探测：判定序与 pi {@code detectCapabilitiesFromEnvironment} 逐条对位。
     *
     * @param env    环境变量（键区分大小写，与进程环境一致）
     * @param osName {@code os.name}（仅用于 Windows 控制台臂）
     * @return 生效能力
     */
    static TerminalCapabilities detect(Map<String, String> env, String osName) {
        String termProgram = lower(env.get("TERM_PROGRAM"));
        String terminalEmulator = lower(env.get("TERMINAL_EMULATOR"));
        String term = lower(env.get("TERM"));
        String colorTerm = lower(env.get("COLORTERM"));
        boolean hasTrueColorHint = "truecolor".equals(colorTerm) || "24bit".equals(colorTerm)
            || term.endsWith("-direct");
        boolean isWindowsConsole = lower(osName).contains("win");

        TerminalCapabilities detected;
        if (env.containsKey("TMUX") || term.startsWith("tmux")) {
            // tmux 只在 client_termfeatures 含 hyperlinks 时才转发 OSC 8；图片不可靠。
            detected = new TerminalCapabilities(null, hasTrueColorHint, tmuxForwardsHyperlinks());
        } else if (term.startsWith("screen")) {
            detected = new TerminalCapabilities(null, hasTrueColorHint, false);
        } else if ("herdr".equals(termProgram)) {
            detected = new TerminalCapabilities(ImageProtocol.KITTY, hasTrueColorHint, true);
        } else if ("rex".equals(termProgram)) {
            detected = new TerminalCapabilities(ImageProtocol.KITTY, true, true);
        } else if ("orca".equals(termProgram) && "kitty".equalsIgnoreCase(env.get("ORCA_IMAGE_PROTOCOL"))) {
            detected = new TerminalCapabilities(ImageProtocol.KITTY, true, true);
        } else if (env.containsKey("KITTY_WINDOW_ID") || "kitty".equals(termProgram)) {
            detected = new TerminalCapabilities(ImageProtocol.KITTY, true, true);
        } else if ("ghostty".equals(termProgram) || term.contains("ghostty")
                || env.containsKey("GHOSTTY_RESOURCES_DIR")) {
            detected = new TerminalCapabilities(ImageProtocol.KITTY, true, true);
        } else if (env.containsKey("WEZTERM_PANE") || "wezterm".equals(termProgram)) {
            detected = new TerminalCapabilities(ImageProtocol.KITTY, true, true);
        } else if ("warpterminal".equals(termProgram) || env.containsKey("WARP_SESSION_ID")
                || env.containsKey("WARP_TERMINAL_SESSION_UUID")) {
            detected = new TerminalCapabilities(ImageProtocol.KITTY, true, true);
        } else if (env.containsKey("ITERM_SESSION_ID") || "iterm.app".equals(termProgram)) {
            detected = new TerminalCapabilities(ImageProtocol.ITERM2, true, true);
        } else if (env.containsKey("WT_SESSION")) {
            detected = new TerminalCapabilities(null, true, true);
        } else if (isAlacritty(termProgram, term, env)) {
            detected = new TerminalCapabilities(null, true, true);
        } else if ("vscode".equals(termProgram) || "zed".equals(termProgram)) {
            detected = new TerminalCapabilities(null, true, true);
        } else if ("jetbrains-jediterm".equals(terminalEmulator)) {
            detected = new TerminalCapabilities(null, true, false);
        } else if (isWindowsConsole) {
            detected = new TerminalCapabilities(null, true, false);
        } else {
            // 未知终端保守：OSC 8 在不支持的终端会「吞掉」链接文本，宁走 text (url)。
            detected = new TerminalCapabilities(null, hasTrueColorHint, false);
        }
        return applyOverrides(env, detected);
    }

    private static boolean isAlacritty(String termProgram, String term, Map<String, String> env) {
        // Alacritty 不设 TERM_PROGRAM：TERM=alacritty（terminfo 已装）或 xterm-256color，
        // Unix/macOS 上设 ALACRITTY_WINDOW_ID。
        return "alacritty".equals(termProgram) || term.startsWith("alacritty")
            || env.containsKey("ALACRITTY_WINDOW_ID");
    }

    private static TerminalCapabilities applyOverrides(Map<String, String> env,
                                                       TerminalCapabilities detected) {
        ImageProtocol images = detected.images();
        String imageProtocol = lower(env.get("PI_IMAGE_PROTOCOL"));
        if ("kitty".equals(imageProtocol)) {
            images = ImageProtocol.KITTY;
        } else if ("iterm2".equals(imageProtocol)) {
            images = ImageProtocol.ITERM2;
        } else if ("none".equals(imageProtocol) || "0".equals(imageProtocol)) {
            images = null;
        }
        Boolean trueColor = parseBoolean(env.get("PI_TRUE_COLOR"));
        Boolean hyperlinks = parseBoolean(env.get("PI_HYPERLINKS"));
        return new TerminalCapabilities(images,
            trueColor != null ? trueColor : detected.trueColor(),
            hyperlinks != null ? hyperlinks : detected.hyperlinks());
    }

    private static Boolean parseBoolean(String value) {
        if ("1".equals(value)) {
            return Boolean.TRUE;
        }
        if ("0".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * tmux 是否转发 OSC 8：{@code tmux display-message -p '#{client_termfeatures}'}
     * 含 {@code hyperlinks}；250 ms 超时或任何异常 ⇒ false。
     */
    static boolean tmuxForwardsHyperlinks() {
        Process process = null;
        try {
            process = new ProcessBuilder("tmux", "display-message", "-p", "#{client_termfeatures}")
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String feature : out.split(",")) {
                if ("hyperlinks".equals(feature.trim())) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

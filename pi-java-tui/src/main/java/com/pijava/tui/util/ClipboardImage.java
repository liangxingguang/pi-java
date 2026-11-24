package com.pijava.tui.util;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 剪贴板图片读取（L7 W7，R15/K14）：pi {@code utils/clipboard-image.ts} 的
 * java 对位子集——Windows 走 PowerShell {@code GetImage().Save(png)}，
 * Wayland 走 {@code wl-paste}，X11 走 {@code xclip}。MIME 白名单与扩展名
 * 映射逐字对位（{@code SUPPORTED_IMAGE_MIME_TYPES} /
 * {@code extensionForImageMimeType}）。任何后端失败/空剪贴板 ⇒
 * {@link Optional#empty()}，调用方回落文本粘贴。
 */
public final class ClipboardImage {

    /** 剪贴板图片（原始字节＋基础 MIME）。 */
    public record Image(byte[] bytes, String mimeType) {}

    private static final List<String> SUPPORTED =
        List.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private ClipboardImage() {}

    /** 读取剪贴板图片；不可用/为空 ⇒ empty。 */
    public static Optional<Image> read() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return readWindows();
        }
        if (isWayland()) {
            Optional<Image> wayland = readWayland();
            if (wayland.isPresent()) {
                return wayland;
            }
        }
        return readXclip();
    }

    /** 读取剪贴板文本（图片缺失时的兜底；headless/失败 ⇒ empty）。 */
    public static Optional<String> readText() {
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            if (data instanceof String text && !text.isEmpty()) {
                return Optional.of(text);
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** pi {@code extensionForImageMimeType}：不支持的 MIME ⇒ {@code null}。 */
    public static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        return switch (baseMimeType(mimeType)) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> null;
        };
    }

    /** pi {@code selectPreferredImageMimeType}：按白名单优先级选型。 */
    static String selectPreferred(List<String> types) {
        for (String preferred : SUPPORTED) {
            for (String type : types) {
                if (baseMimeType(type).equals(preferred)) {
                    return type;
                }
            }
        }
        for (String type : types) {
            if (baseMimeType(type).startsWith("image/")) {
                return type;
            }
        }
        return null;
    }

    // ── backends ────────────────────────────────────────────────────────

    private static Optional<Image> readWindows() {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("pi-java-clip-", ".png");
            String script = "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                + "$img=[System.Windows.Forms.Clipboard]::GetImage(); if ($img) { $img.Save('"
                + escapePowerShell(tmp.toString()) + "',[System.Drawing.Imaging.ImageFormat]::Png); 'ok' } "
                + "else { 'empty' }";
            var result = run(List.of("powershell.exe", "-NoProfile", "-STA", "-Command", script), 5000);
            if (result == null || !"ok".equals(new String(result, StandardCharsets.UTF_8).trim())) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(tmp);
            return bytes.length == 0 ? Optional.empty()
                : Optional.of(new Image(bytes, "image/png"));
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static Optional<Image> readWayland() {
        byte[] list = run(List.of("wl-paste", "--list-types"), 1000);
        if (list == null) {
            return Optional.empty();
        }
        String selected = selectPreferred(
            new String(list, StandardCharsets.UTF_8).lines().map(String::trim).toList());
        if (selected == null) {
            return Optional.empty();
        }
        byte[] data = run(List.of("wl-paste", "--type", selected, "--no-newline"), 5000);
        return data == null || data.length == 0 ? Optional.empty()
            : Optional.of(new Image(data, baseMimeType(selected)));
    }

    private static Optional<Image> readXclip() {
        byte[] targets = run(List.of("xclip", "-selection", "clipboard", "-t", "TARGETS", "-o"), 1000);
        if (targets == null) {
            return Optional.empty();
        }
        String selected = selectPreferred(
            new String(targets, StandardCharsets.UTF_8).lines().map(String::trim).toList());
        if (selected == null) {
            return Optional.empty();
        }
        byte[] data = run(List.of("xclip", "-selection", "clipboard", "-t", selected, "-o"), 5000);
        return data == null || data.length == 0 ? Optional.empty()
            : Optional.of(new Image(data, baseMimeType(selected)));
    }

    /** 跑命令并收集 stdout；非 0/超时/启动失败 ⇒ null。 */
    private static byte[] run(List<String> command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return process.getInputStream().readAllBytes();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static boolean isWayland() {
        return System.getenv("WAYLAND_DISPLAY") != null
            || "wayland".equals(System.getenv("XDG_SESSION_TYPE"));
    }

    private static String baseMimeType(String mimeType) {
        int semicolon = mimeType.indexOf(';');
        String base = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String escapePowerShell(String value) {
        return value.replace("'", "''");
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}

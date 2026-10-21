package com.pijava.tui.util;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;

/**
 * 系统剪贴板（docs/19 §3.12，裁决⑤）：仅 AWT 通道，不移植 pi 的
 * native/pbcopy/clip/wl-copy/OSC 52 降级链（窄差 D44）。headless／不可用
 * ⇒ {@link IOException}（调用方转 showError，pi :5605-5607 对位）。
 */
public final class ClipboardSupport {

    /** 复制实现（测试可替换）。 */
    @FunctionalInterface
    public interface CopyFn {
        /** Copy text to the system clipboard, throwing on failure. */
        void copy(String text) throws IOException;
    }

    private static volatile CopyFn impl = ClipboardSupport::awtCopy;

    private ClipboardSupport() {}

    /** Copy {@code text} to the system clipboard. */
    public static void copy(String text) throws IOException {
        impl.copy(text);
    }

    /**
     * 测试替换点：注入假实现（{@code null} 恢复 AWT 默认）。测试结束必须复位
     * —— 静态态跨用例泄漏会让真 AWT 臂静默失效。
     */
    public static void overrideForTest(CopyFn fn) {
        impl = fn == null ? ClipboardSupport::awtCopy : fn;
    }

    private static void awtCopy(String text) throws IOException {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        } catch (Exception e) {
            throw new IOException("Clipboard unavailable: " + e.getMessage(), e);
        }
    }
}

package com.pijava.tui.util;

import java.io.IOException;

import com.pijava.coding.agent.core.clipboard.NativeClipboard;

/**
 * 系统剪贴板入口（docs/26 §4.2，K34）：默认走
 * {@link NativeClipboard} 的平台命令行链（Termux/pbcopy/PowerShell/clip/
 * wl-copy/xclip/xsel），最后 AWT Toolkit 兜底。headless／不可用 ⇒
 * {@link IOException}（调用方转 showError，pi :5605-5607 对位）。
 */
public final class ClipboardSupport {

    /** 复制实现（测试可替换）。 */
    @FunctionalInterface
    public interface CopyFn {
        /** Copy text to the system clipboard, throwing on failure. */
        void copy(String text) throws IOException;
    }

    private static volatile CopyFn impl = NativeClipboard::copy;

    private ClipboardSupport() {}

    /** Copy {@code text} to the system clipboard. */
    public static void copy(String text) throws IOException {
        impl.copy(text);
    }

    /**
     * 测试替换点：注入假实现（{@code null} 恢复默认）。测试结束必须复位
     * —— 静态态跨用例泄漏会让真通道静默失效。
     */
    public static void overrideForTest(CopyFn fn) {
        impl = fn == null ? NativeClipboard::copy : fn;
    }
}

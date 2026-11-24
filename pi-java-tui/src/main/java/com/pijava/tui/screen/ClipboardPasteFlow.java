package com.pijava.tui.screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.tui.util.ClipboardImage;

/**
 * 剪贴板粘贴流程（L7 W7，R15/K14）：pi
 * {@code interactive-mode.ts:3344-3382 handleClipboardPaste} 的 java 对位。
 *
 * <p>顺序逐条对齐：先读剪贴板图片（写 {@code <tmp>/pi-java-clipboard-<uuid>.<ext>}
 * 后把路径插入编辑器）→ 否则读文本插入 → 都无则报错。异常统一
 * {@code "Failed to paste from clipboard: "} 前缀（pi 逐字）。</p>
 */
public final class ClipboardPasteFlow {

    private ClipboardPasteFlow() {}

    /** 生产入口：系统剪贴板。 */
    public static void paste(Consumer<String> insertText, Consumer<String> showError) {
        paste(insertText, showError, ClipboardImage::read, ClipboardImage::readText);
    }

    /** 可注入入口（测试面）。 */
    public static void paste(Consumer<String> insertText, Consumer<String> showError,
                             Supplier<Optional<ClipboardImage.Image>> images,
                             Supplier<Optional<String>> texts) {
        try {
            Optional<ClipboardImage.Image> image = images.get();
            if (image.isPresent()) {
                var value = image.get();
                String extension = ClipboardImage.extensionFor(value.mimeType());
                Path file = Files.createTempFile("pi-java-clipboard-",
                    "." + (extension == null ? "png" : extension));
                Files.write(file, value.bytes());
                insertText.accept(file.toString());
                return;
            }
            Optional<String> text = texts.get();
            if (text.isPresent()) {
                insertText.accept(text.get());
                return;
            }
            showError.accept("Failed to paste from clipboard: clipboard has no image or text");
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            showError.accept("Failed to paste from clipboard: " + reason);
        }
    }
}

package com.pijava.tui.component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.pijava.tui.util.TerminalCapabilities;
import com.pijava.tui.util.TerminalImage;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.widget.RawOutputCapable;
import dev.tamboui.widget.Widget;

/**
 * 终端内联图片元素（L7 W7，R14/S2d）：pi {@code components/image.ts} 的 java
 * 对位。占位由 {@link Widget#render} 填充空单元；实际图片序列走
 * {@link RawOutputCapable}（TamboUI 0.4 的原始输出通道），以绝对光标定位
 * 写到区域左上角，从而不经过单元格缓冲。
 */
public final class ImageElement implements Widget, RawOutputCapable {

    private final ImagePlacement placement;

    public ImageElement(ImagePlacement placement) {
        this.placement = placement;
    }

    @Override
    public void render(Rect area, Buffer buffer) {
        // 预留空单元；图片由原始输出覆盖。
        buffer.fill(area, Cell.EMPTY);
    }

    @Override
    public void render(Rect area, Buffer buffer, OutputStream output) {
        if (TerminalCapabilities.get().images() == null || area.width() <= 0 || area.height() <= 0) {
            return;
        }
        var size = new TerminalImage.CellSize(
            Math.min(placement.columns(), area.width()),
            Math.min(placement.rows(), area.height()));
        String sequence = TerminalImage.sequence(placement.base64(), placement.mimeType(), size,
            placement.imageId() > 0 ? placement.imageId() : null, placement.filename());
        if (sequence == null) {
            return;
        }
        try {
            output.write(("\u001b[" + (area.y() + 1) + ";" + (area.x() + 1) + "H")
                .getBytes(StandardCharsets.UTF_8));
            output.write(sequence.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {
            // 原始输出失败不影响文本帧。
        }
    }
}

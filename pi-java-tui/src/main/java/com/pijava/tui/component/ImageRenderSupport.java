package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;

/**
 * 视口内联图片装配（L7 W7，R14/S2d）：从本帧 {@link RenderRow} 收集图片锚点，
 * 并把落在视口内的锚点经 {@link ImageElement} 的原始输出通道绘制。拆出
 * ChatViewportElement 以维持 500 行上限。
 */
final class ImageRenderSupport {

    /** 绝对行号＋图片载荷。 */
    record Anchor(int row, ImagePlacement placement) {}

    private ImageRenderSupport() {}

    /** 收集本帧图片首行锚点。 */
    static List<Anchor> collect(List<RenderRow> rows) {
        var anchors = new ArrayList<Anchor>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).image() != null) {
                anchors.add(new Anchor(i, rows.get(i).image()));
            }
        }
        return List.copyOf(anchors);
    }

    /** 绘制视口内图片（首行需可见；部分滚出时按可见高度裁剪）。 */
    static void render(Frame frame, Rect area, int contentWidth, int offset,
                       List<Anchor> anchors) {
        for (var anchor : anchors) {
            int screenRow = anchor.row() - offset;
            if (screenRow < 0 || screenRow >= area.height()) {
                continue;
            }
            var image = anchor.placement();
            int width = Math.max(1, Math.min(image.columns(), contentWidth));
            int height = Math.max(1, Math.min(image.rows(), area.height() - screenRow));
            var fitted = new ImagePlacement(image.base64(), image.mimeType(), image.filename(),
                width, height, image.imageId());
            frame.renderWidget(new ImageElement(fitted),
                new Rect(area.x(), area.y() + screenRow, width, height));
        }
    }
}

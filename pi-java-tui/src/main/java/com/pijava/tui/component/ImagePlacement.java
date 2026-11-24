package com.pijava.tui.component;

/**
 * 一条可渲染图片的载荷（L7 W7，R14/S2d）：base64 数据、MIME、文件名与
 * 已换算的单元格尺寸。图片行在 {@link LogicalLine}/{@link RenderRow} 上
 * 携带本记录，由 {@link ChatViewportElement} 在视口内交给
 * {@link ImageElement} 直写终端。
 *
 * @param base64   base64 图片数据
 * @param mimeType 图片 MIME
 * @param filename 原文件名（可空，降级链接用）
 * @param columns  占用列数
 * @param rows     占用行数
 * @param imageId  Kitty 图片 ID（非 Kitty／0 ≙ 无）
 */
public record ImagePlacement(String base64, String mimeType, String filename,
                             int columns, int rows, int imageId) {

    /** 钳到更窄的可用宽度：列变窄、行按比例缩放（至少 1 行）。 */
    public ImagePlacement fitWidth(int maxColumns) {
        if (maxColumns <= 0 || maxColumns >= columns) {
            return this;
        }
        int fittedColumns = Math.max(1, maxColumns);
        int fittedRows = Math.max(1, (int) Math.round(rows * (fittedColumns / (double) columns)));
        return new ImagePlacement(base64, mimeType, filename, fittedColumns, fittedRows, imageId);
    }
}

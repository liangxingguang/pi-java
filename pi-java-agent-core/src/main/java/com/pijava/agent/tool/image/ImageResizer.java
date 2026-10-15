package com.pijava.agent.tool.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.pijava.ai.catalog.InputLimits;

/**
 * 图片缩放核心（对应 pi {@code utils/image-resize-core.ts} 的 {@code resizeImageInProcess}，
 * 批 9/B85）。pi 用 Photon（Rust/WASM）；Java 用 JDK 自带 ImageIO——
 * 算法形状逐字对应，像素不逐字节对应（裁决 D2，夹具只断言宽高/体积/文案）。
 *
 * <ol>
 *   <li>限内（宽高 ≤ max 且 base64 体积 &lt; maxBytes）⇒ 原字节直通；</li>
 *   <li>按 maxWidth/maxHeight 等比收目标边；</li>
 *   <li>每档尺寸：PNG 候选先，再 JPEG 质量阶梯（默认 80,85,70,55,40 去重保序），
 *       取首个低于 maxBytes 的；</li>
 *   <li>仍超 ⇒ 宽高各 floor(*0.75)（≥1）直到 1×1；不可推进/解码失败 ⇒ null。</li>
 * </ol>
 *
 * <p>裁决 D1：JDK 无 WebP 解码器，限内 webp 靠 {@link WebpDimensions} 解析 RIFF 头取
 * 画布宽高后原字节直通（与 pi 限内输出一致）；超限 webp 无法重编码 ⇒ 返回 null，
 * 上层给 pi 同款 "could not be resized" 省略文本。</p>
 */
final class ImageResizer {

    /** 4.5MB of base64 payload（pi image-resize-core.ts:24，Anthropic 5MB 限下留头）。 */
    static final int DEFAULT_MAX_BYTES = (int) (4.5 * 1024 * 1024);
    static final int DEFAULT_MAX_WIDTH = 2000;
    static final int DEFAULT_MAX_HEIGHT = 2000;
    static final int DEFAULT_JPEG_QUALITY = 80;

    /** 缩放结果（pi {@code ResizedImage}）。 */
    record Result(String data, String mimeType, int originalWidth, int originalHeight,
                  int width, int height, boolean wasResized) {}

    private record Effective(int maxWidth, int maxHeight, int maxBytes, int jpegQuality) {}

    private ImageResizer() {}

    /**
     * @param bytes   归一后的图片字节（mime 已归一，BMP 已转 PNG）
     * @param mime    归一 MIME（image/png｜image/jpeg｜image/gif｜image/webp）
     * @param options 模型档位，可 {@code null}；逐字段缺席回落默认（pi 展开合并同效）
     * @return 缩放结果；解码/压到 1×1 仍超限时 {@code null}
     */
    static Result resize(byte[] bytes, String mime, InputLimits.ResizeOptions options) {
        var opt = new Effective(
            options != null && options.maxWidth() != null ? options.maxWidth() : DEFAULT_MAX_WIDTH,
            options != null && options.maxHeight() != null ? options.maxHeight() : DEFAULT_MAX_HEIGHT,
            options != null && options.maxBytes() != null ? options.maxBytes() : DEFAULT_MAX_BYTES,
            options != null && options.jpegQuality() != null
                ? options.jpegQuality() : DEFAULT_JPEG_QUALITY);
        int inputBase64Size = (bytes.length + 2) / 3 * 4;

        // pi 在限内判定前就 photon.new_from_byteslice 全量解码（截断/畸形图直接 catch⇒null），
        // 故 png/jpeg/gif 也必须全量栅格化，不能只看 IHDR 头。WebP 无 JDK 解码器（裁决 D1），
        // 限内靠 RIFF 头尺寸直通，超限直接 null。
        BufferedImage oriented;
        int originalWidth;
        int originalHeight;
        if ("image/webp".equals(mime)) {
            int[] webp = WebpDimensions.dimensions(bytes);
            if (webp == null) return null;
            originalWidth = webp[0];
            originalHeight = webp[1];
            if (originalWidth <= opt.maxWidth && originalHeight <= opt.maxHeight
                && inputBase64Size < opt.maxBytes) {
                return new Result(java.util.Base64.getEncoder().encodeToString(bytes),
                    mime, originalWidth, originalHeight, originalWidth, originalHeight, false);
            }
            return null;
        }
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) return null;
            oriented = ExifOrientation.apply(decoded, bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        originalWidth = oriented.getWidth();
        originalHeight = oriented.getHeight();

        if (originalWidth <= opt.maxWidth && originalHeight <= opt.maxHeight
            && inputBase64Size < opt.maxBytes) {
            // 限内直通：pi 同样在此分支丢回原字节（EXIF 变换结果不输出）。
            return new Result(java.util.Base64.getEncoder().encodeToString(bytes),
                mime, originalWidth, originalHeight, originalWidth, originalHeight, false);
        }


        int targetWidth = originalWidth;
        int targetHeight = originalHeight;
        if (targetWidth > opt.maxWidth) {
            targetHeight = Math.round((float) targetHeight * opt.maxWidth / targetWidth);
            targetWidth = opt.maxWidth;
        }
        if (targetHeight > opt.maxHeight) {
            targetWidth = Math.round((float) targetWidth * opt.maxHeight / targetHeight);
            targetHeight = opt.maxHeight;
        }

        var qualitySteps = new ArrayList<Integer>(new LinkedHashSet<>(
            List.of(opt.jpegQuality, 85, 70, 55, 40)));
        int currentWidth = targetWidth;
        int currentHeight = targetHeight;

        while (true) {
            var resized = scale(oriented, currentWidth, currentHeight);
            try {
                // pi：PNG 候选恒第一。
                String pngBase64 = encodePng(resized);
                if (base64Length(pngBase64) < opt.maxBytes) {
                    return new Result(pngBase64, "image/png", originalWidth, originalHeight,
                        currentWidth, currentHeight, true);
                }
                for (int quality : qualitySteps) {
                    String jpegBase64 = encodeJpeg(resized, quality);
                    if (base64Length(jpegBase64) < opt.maxBytes) {
                        return new Result(jpegBase64, "image/jpeg", originalWidth, originalHeight,
                            currentWidth, currentHeight, true);
                    }
                }
            } catch (IOException e) {
                return null;
            }

            if (currentWidth == 1 && currentHeight == 1) return null;
            int nextWidth = currentWidth == 1 ? 1 : Math.max(1, (int) Math.floor(currentWidth * 0.75));
            int nextHeight = currentHeight == 1 ? 1 : Math.max(1, (int) Math.floor(currentHeight * 0.75));
            if (nextWidth == currentWidth && nextHeight == currentHeight) return null;
            currentWidth = nextWidth;
            currentHeight = nextHeight;
        }
    }

    /** pi {@code formatDimensionNote}（image-resize.ts:110-121）。 */
    static String dimensionNote(Result result) {
        if (!result.wasResized) return null;
        double scale = (double) result.originalWidth() / result.width();
        return String.format(java.util.Locale.ROOT,
            "[Image: original %dx%d, displayed at %dx%d. Multiply coordinates by %.2f to map to original image.]",
            result.originalWidth(), result.originalHeight(), result.width(), result.height(), scale);
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private static int base64Length(String base64) {
        // pi 用 Buffer.byteLength(data,"utf-8")；base64 字符全 ASCII ⇒ 字符串长度即字节数。
        return base64.length();
    }

    private static BufferedImage scale(BufferedImage src, int width, int height) {
        boolean alpha = src.getColorModel().hasAlpha();
        var dst = new BufferedImage(width, height,
            alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
            RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dst;
    }

    private static String encodePng(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer");
        }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** JPEG 无 alpha 通道：先 flatten 白底（裁决 D3），再按质量编码。 */
    private static String encodeJpeg(BufferedImage image, int quality) throws IOException {
        BufferedImage rgb = image;
        if (image.getColorModel().hasAlpha()) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        var writers = ImageIO.getImageWritersByMIMEType("image/jpeg");
        if (!writers.hasNext()) throw new IOException("no JPEG writer");
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100.0f);
            var out = new ByteArrayOutputStream();
            try (var output = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(rgb, null, null), param);
            }
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } finally {
            writer.dispose();
        }
    }
}

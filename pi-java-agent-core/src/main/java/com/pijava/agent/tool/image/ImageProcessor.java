package com.pijava.agent.tool.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.pijava.agent.tool.ImagePolicy;
import com.pijava.ai.catalog.InputLimits;

/**
 * 图片入模处理（对应 pi {@code utils/image-process.ts} 的 {@code processImage}，
 * 批 9/B85）。顺序：MIME 归一 → 异类转 PNG（BMP 等，<b>不受 autoResize 门控</b>）→
 * 限内直通或按模型档位缩放 → 组装转换/尺寸 hints。
 */
public final class ImageProcessor {

    /** 转换失败（pi image-process.ts:70-74）。 */
    static final String CONVERT_FAILURE =
        "[Image omitted: could not be converted to a supported inline image format.]";
    /** 缩放失败（pi image-process.ts:90-94）。 */
    static final String RESIZE_FAILURE =
        "[Image omitted: could not be resized below the inline image size limit.]";

    private ImageProcessor() {}

    /** processImage 的判别联合（pi {@code ProcessImageResult}）；嵌套 record 隐式许可。 */
    public sealed interface Processed {
        record Ok(String data, String mimeType, List<String> hints) implements Processed {}
        record Failure(String message) implements Processed {}
    }

    static Processed.Ok ok(String data, String mimeType, List<String> hints) {
        return new Processed.Ok(data, mimeType, List.copyOf(hints));
    }

    static Processed.Failure failure(String message) {
        return new Processed.Failure(message);
    }

    /**
     * @param bytes      原始图片字节
     * @param mime       原始 MIME（可带 {@code ;charset=} 参数，取分号前）
     * @param autoResize 是否缩放（pi {@code images.autoResize}，默认 true）
     * @param resize     模型档位，{@code null} 走 {@link ImageResizer} 默认档
     */
    public static Processed process(byte[] bytes, String mime, boolean autoResize,
                                    InputLimits.ResizeOptions resize) {
        String base = baseMimeType(mime);
        String canonical = canonicalSupported(base);
        byte[] normalizedBytes;
        String normalizedMime;
        String convertedFrom;
        if (canonical != null) {
            normalizedBytes = bytes;
            normalizedMime = canonical;
            convertedFrom = null;
        } else {
            byte[] png = convertToPng(bytes);
            if (png == null) {
                return failure(CONVERT_FAILURE);
            }
            normalizedBytes = png;
            normalizedMime = "image/png";
            convertedFrom = base;
        }

        if (!autoResize) {
            var hints = new ArrayList<String>();
            String hint = conversionHint(convertedFrom, normalizedMime);
            if (hint != null) hints.add(hint);
            return ok(java.util.Base64.getEncoder().encodeToString(normalizedBytes),
                normalizedMime, hints);
        }

        var resized = ImageResizer.resize(normalizedBytes, normalizedMime, resize);
        if (resized == null) {
            return failure(RESIZE_FAILURE);
        }
        var hints = new ArrayList<String>();
        String hint = conversionHint(convertedFrom, resized.mimeType());
        if (hint != null) hints.add(hint);
        String note = ImageResizer.dimensionNote(resized);
        if (note != null) hints.add(note);
        return ok(resized.data(), resized.mimeType(), hints);
    }

    /** 策略对象便捷入口（read 与工具结果收口共用）。 */
    public static Processed process(byte[] bytes, String mime, ImagePolicy policy) {
        return process(bytes, mime, policy.autoResize(), policy.resize());
    }

    /** pi {@code baseMimeType}：分号前 trim 小写。 */
    static String baseMimeType(String mimeType) {
        if (mimeType == null) return "";
        int semi = mimeType.indexOf(';');
        String base = semi >= 0 ? mimeType.substring(0, semi) : mimeType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /** pi {@code normalizeSupportedImageMimeType}；不在四元白名单返回 null。 */
    private static String canonicalSupported(String base) {
        return switch (base) {
            case "image/png" -> "image/png";
            case "image/jpeg", "image/jpg" -> "image/jpeg";
            case "image/gif" -> "image/gif";
            case "image/webp" -> "image/webp";
            default -> null;
        };
    }

    /** pi {@code conversionHint}：from 缺席或与目标相同 ⇒ 无 hint。 */
    private static String conversionHint(String from, String to) {
        if (from == null || from.equals(to)) return null;
        return "[Image converted from " + from + " to " + to + ".]";
    }

    /**
     * 任意支持解码的格式 → PNG（对应 pi {@code convertImageBytesToPng}，同样应用
     * EXIF 朝向）。ImageIO 解不出 ⇒ {@code null}（photon 不可用/解码失败同语义）。
     */
    static byte[] convertToPng(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return null;
            image = ExifOrientation.apply(image, bytes);
            var out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) return null;
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}

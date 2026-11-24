package com.pijava.tui.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 终端图片协议工具（L7 W7，R14/S2d）：pi {@code tui/src/terminal-image.ts} 的
 * java 对位子集——Kitty/iTerm2 编码、尺寸头解析、单元格换算与文本降级。
 *
 * <p>能力判定复用 {@link TerminalCapabilities}（K35 已对位）；本类只做纯
 * 变换，便于单测。降级文案逐字对位 pi {@code imageFallback}：有文件名时
 * {@code [Image: <path> [mime] WxH]}，hyperlinks 能力下包 OSC 8
 * {@code file://} 链接。</p>
 */
public final class TerminalImage {

    /** 像素尺寸。 */
    public record Dimensions(int widthPx, int heightPx) {}

    /** 单元格尺寸（列×行）。 */
    public record CellSize(int columns, int rows) {}

    private static final int KITTY_CHUNK = 4096;
    private static final int DEFAULT_CELL_W = 9;
    private static final int DEFAULT_CELL_H = 18;

    private TerminalImage() {}

    /** 当前终端图片协议（null ⇒ 不支持）。 */
    public static TerminalCapabilities.ImageProtocol protocol() {
        return TerminalCapabilities.get().images();
    }

    /** Kitty 图形协议编码（>4096 字节 base64 自动分块）。 */
    public static String encodeKitty(String base64, int columns, int rows,
                                     Integer imageId, boolean moveCursor) {
        var params = new StringBuilder("a=T,f=100,q=2");
        if (!moveCursor) {
            params.append(",C=1");
        }
        if (columns > 0) {
            params.append(",c=").append(columns);
        }
        if (rows > 0) {
            params.append(",r=").append(rows);
        }
        if (imageId != null && imageId > 0) {
            params.append(",i=").append(imageId);
        }
        if (base64.length() <= KITTY_CHUNK) {
            return "\u001b_G" + params + ";" + base64 + "\u001b\\";
        }
        var out = new StringBuilder();
        for (int offset = 0; offset < base64.length(); offset += KITTY_CHUNK) {
            String chunk = base64.substring(offset, Math.min(base64.length(), offset + KITTY_CHUNK));
            boolean first = offset == 0;
            boolean last = offset + KITTY_CHUNK >= base64.length();
            if (first) {
                out.append("\u001b_G").append(params).append(",m=1;").append(chunk).append("\u001b\\");
            } else if (last) {
                out.append("\u001b_Gm=0;").append(chunk).append("\u001b\\");
            } else {
                out.append("\u001b_Gm=1;").append(chunk).append("\u001b\\");
            }
        }
        return out.toString();
    }

    /** iTerm2 内联图片编码（宽度按列，高度 auto，保持比例）。 */
    public static String encodeITerm2(String base64, int columns, String name) {
        int bytes = Base64.getDecoder().decode(base64).length;
        var params = new StringBuilder("inline=1;size=").append(bytes)
            .append(";width=").append(columns).append(";height=auto");
        if (name != null && !name.isEmpty()) {
            params.append(";name=").append(Base64.getEncoder()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8)));
        }
        return "\u001b]1337;File=" + params + ":" + base64 + "\u0007";
    }

    /** 删除指定 Kitty 图片（含数据）。 */
    public static String deleteKittyImage(int imageId) {
        return "\u001b_Ga=d,d=I,i=" + imageId + ",q=2\u001b\\";
    }

    /** 是否图片行（Kitty/iTerm2 序列出现在行内）。 */
    public static boolean isImageLine(String line) {
        return line != null && (line.contains("\u001b_G") || line.contains("\u001b]1337;File="));
    }

    /** 剥离图片序列（含 iTerm2 的光标上移前缀），保留文本。 */
    public static String stripImageSequences(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceAll("\u001b_G[^\u001b]*\u001b\\\\", "")
            .replaceAll("(?:\\u001b\\[\\d+A)?\\u001b\\]1337;File=[^\u0007]*\u0007", "");
    }

    /** 随机 Kitty 图片 ID（1..0xfffffffe）。 */
    public static int allocateImageId() {
        return ThreadLocalRandom.current().nextInt(1, 0x7fffffff);
    }

    /** 生成当前协议下的图片序列；不支持/解码失败 ⇒ null。 */
    public static String sequence(String base64, String mimeType, CellSize size,
                                  Integer imageId, String filename) {
        var protocol = protocol();
        if (protocol == null) {
            return null;
        }
        if (protocol == TerminalCapabilities.ImageProtocol.KITTY) {
            return encodeKitty(base64, size.columns(), size.rows(), imageId, false);
        }
        return encodeITerm2(base64, size.columns(), filename);
    }

    /** 图片像素尺寸（PNG/JPEG/GIF/WebP 头）；未知 ⇒ null。 */
    public static Dimensions dimensions(String base64, String mimeType) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if ("image/png".equals(mimeType)) {
            return png(bytes);
        }
        if ("image/jpeg".equals(mimeType)) {
            return jpeg(bytes);
        }
        if ("image/gif".equals(mimeType)) {
            return gif(bytes);
        }
        if ("image/webp".equals(mimeType)) {
            return webp(bytes);
        }
        return null;
    }

    /**
     * 单元格换算（pi {@code calculateImageCellSize}）：保持比例，按宽/高上限
     * 取缩放；{@code optimize} 时按 Kitty 失真优化（选畸变更小的相邻格数）。
     */
    public static CellSize cellSize(Dimensions image, int maxWidthCells, int maxHeightCells,
                                    boolean optimize) {
        return cellSize(image, maxWidthCells, maxHeightCells,
            DEFAULT_CELL_W, DEFAULT_CELL_H, optimize);
    }

    /** 单元格换算（单元格像素可注入，测试面）。 */
    public static CellSize cellSize(Dimensions image, int maxWidthCells, int maxHeightCells,
                                    int cellW, int cellH, boolean optimize) {
        int maxWidth = Math.max(1, maxWidthCells);
        int imageWidth = Math.max(1, image.widthPx());
        int imageHeight = Math.max(1, image.heightPx());
        double widthScale = (maxWidth * (double) cellW) / imageWidth;
        double heightScale = maxHeightCells <= 0
            ? widthScale : (maxHeightCells * (double) cellH) / imageHeight;
        double scale = Math.min(widthScale, heightScale);

        int columns = Math.max(1, Math.min(maxWidth,
            (int) Math.ceil(imageWidth * scale / cellW)));
        int rows = Math.max(1, (int) Math.ceil(imageHeight * scale / cellH));
        if (maxHeightCells > 0) {
            rows = Math.min(maxHeightCells, rows);
        }
        if (optimize) {
            if (widthScale <= heightScale) {
                double idealRows = columns * (double) cellW * imageHeight
                    / (imageWidth * (double) cellH);
                rows = lessDistorted(rows, idealRows);
            } else {
                double idealColumns = rows * (double) cellH * imageWidth
                    / (imageHeight * (double) cellW);
                columns = lessDistorted(columns, idealColumns);
            }
        }
        return new CellSize(columns, rows);
    }

    /** 文本降级（pi {@code imageFallback} 逐字形状）。 */
    public static String fallback(String mimeType, Dimensions dimensions, String filename) {
        var parts = new StringBuilder();
        if (filename != null && !filename.isEmpty()) {
            if (TerminalCapabilities.get().hyperlinks() && isAbsolute(filename)) {
                parts.append("\u001b]8;;").append(fileUrl(filename))
                    .append("\u001b\\").append(shorten(filename)).append("\u001b]8;;\u001b\\");
            } else {
                parts.append(shorten(filename));
            }
            parts.append(' ');
        }
        parts.append('[').append(mimeType == null ? "unknown" : mimeType).append(']');
        if (dimensions != null) {
            parts.append(' ').append(dimensions.widthPx()).append('x').append(dimensions.heightPx());
        }
        return "[Image: " + parts + "]";
    }

    // ── dimension readers ─────────────────────────────────────────────

    private static Dimensions png(byte[] b) {
        if (b.length < 24 || (b[0] & 0xff) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
            return null;
        }
        return new Dimensions(readInt(b, 16), readInt(b, 20));
    }

    private static Dimensions jpeg(byte[] b) {
        if (b.length < 2 || (b[0] & 0xff) != 0xff || (b[1] & 0xff) != 0xd8) {
            return null;
        }
        int offset = 2;
        while (offset < b.length - 9) {
            if ((b[offset] & 0xff) != 0xff) {
                offset++;
                continue;
            }
            int marker = b[offset + 1] & 0xff;
            if (marker >= 0xc0 && marker <= 0xc2) {
                return new Dimensions(readShort(b, offset + 7), readShort(b, offset + 5));
            }
            if (offset + 3 >= b.length) {
                return null;
            }
            int length = readShort(b, offset + 2);
            if (length < 2) {
                return null;
            }
            offset += 2 + length;
        }
        return null;
    }

    private static Dimensions gif(byte[] b) {
        if (b.length < 10) {
            return null;
        }
        String sig = new String(b, 0, 6, StandardCharsets.US_ASCII);
        if (!"GIF87a".equals(sig) && !"GIF89a".equals(sig)) {
            return null;
        }
        return new Dimensions(readShortLE(b, 6), readShortLE(b, 8));
    }

    private static Dimensions webp(byte[] b) {
        if (b.length < 30) {
            return null;
        }
        String riff = new String(b, 0, 4, StandardCharsets.US_ASCII);
        String webp = new String(b, 8, 4, StandardCharsets.US_ASCII);
        if (!"RIFF".equals(riff) || !"WEBP".equals(webp)) {
            return null;
        }
        String chunk = new String(b, 12, 4, StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8 " -> new Dimensions(readShortLE(b, 26) & 0x3fff, readShortLE(b, 28) & 0x3fff);
            case "VP8L" -> {
                int bits = readIntLE(b, 21);
                yield new Dimensions((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1);
            }
            case "VP8X" -> new Dimensions(
                ((b[24] & 0xff) | ((b[25] & 0xff) << 8) | ((b[26] & 0xff) << 16)) + 1,
                ((b[27] & 0xff) | ((b[28] & 0xff) << 8) | ((b[29] & 0xff) << 16)) + 1);
            default -> null;
        };
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xff) << 24) | ((b[offset + 1] & 0xff) << 16)
            | ((b[offset + 2] & 0xff) << 8) | (b[offset + 3] & 0xff);
    }

    private static int readShort(byte[] b, int offset) {
        return ((b[offset] & 0xff) << 8) | (b[offset + 1] & 0xff);
    }

    private static int readShortLE(byte[] b, int offset) {
        return ((b[offset + 1] & 0xff) << 8) | (b[offset] & 0xff);
    }

    private static int readIntLE(byte[] b, int offset) {
        return (b[offset] & 0xff) | ((b[offset + 1] & 0xff) << 8)
            | ((b[offset + 2] & 0xff) << 16) | ((b[offset + 3] & 0xff) << 24);
    }

    private static int lessDistorted(int upper, double ideal) {
        if (upper <= 1 || ideal <= 0) {
            return upper;
        }
        int lower = upper - 1;
        double upperDistortion = Math.max(upper / ideal, ideal / upper);
        double lowerDistortion = Math.max(lower / ideal, ideal / lower);
        return lowerDistortion < upperDistortion ? lower : upper;
    }

    private static boolean isAbsolute(String filename) {
        return filename.startsWith("/") || filename.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static String shorten(String filename) {
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty() && (filename.equals(home) || filename.startsWith(home + "/")
                || filename.startsWith(home + "\\"))) {
            return "~" + filename.substring(home.length());
        }
        return filename;
    }

    private static String fileUrl(String filename) {
        String normalized = filename.replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return "file://" + normalized.replace(" ", "%20");
    }
}

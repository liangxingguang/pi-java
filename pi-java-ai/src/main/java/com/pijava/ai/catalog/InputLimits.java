package com.pijava.ai.catalog;

/**
 * pi {@code ModelInputLimits}（{@code model-schema.ts:47-79}）：单请求输入上限。
 *
 * <p>两层全 {@code null} ⇒ 该层缺席（不造空壳）。消费链（图片缩放/注记管线，B85）
 * 不在批 3a 范围，本类型只承载数据。</p>
 *
 * @param maxRequestBytes 整请求体字节上限（{@code maxRequestBytes}，min 1），或 {@code null}
 * @param images          图片输入张数/缩放限制，或 {@code null}
 */
public record InputLimits(Integer maxRequestBytes, ImageLimits images) {

    /**
     * pi {@code ModelImageInputLimits}。
     *
     * @param resize          图片缩放参数，或 {@code null}
     * @param maxPerMessage   单条消息图片张数上限（min 1），或 {@code null}
     * @param maxPerRequest   整请求图片张数上限（min 1），或 {@code null}
     */
    public record ImageLimits(ResizeOptions resize, Integer maxPerMessage,
                               Integer maxPerRequest) {
    }

    /**
     * pi {@code modelImageResizeOptions}；{@code maxBytes} 计 base64 字节，
     * {@code jpegQuality} 取 1..100。
     */
    public record ResizeOptions(Integer maxWidth, Integer maxHeight, Integer maxBytes,
                                 Integer jpegQuality) {
    }
}

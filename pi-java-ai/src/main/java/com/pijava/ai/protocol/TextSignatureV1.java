package com.pijava.ai.protocol;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * pi {@code TextSignatureV1}（{@code ai/src/types.ts:358-365}）的编解码 —— 逐行照
 * {@code ai/src/api/openai-responses-shared.ts:52-76}。
 *
 * <p>它是文本块回执签名的两种形态之一：新形 {@code {"v":1,"id":…,"phase":…}} ，
 * 或 legacy 的**裸 id 字符串**（Google 的 thoughtSignature 也占
 * {@code TextContent.textSignature} 这同一个槽位，见 {@code types.ts:367}）。</p>
 *
 * <p>Responses 车道两个方向都用它：响应侧 {@code output_item.done} 写回执
 * （pi {@code :701}），请求侧回放时取回**原** {@code msg_xxx} 与 {@code phase}
 * （pi {@code :267-287}）。</p>
 */
public final class TextSignatureV1 {

    /** pi 的 {@code v: 1}。 */
    static final int VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private TextSignatureV1() {}

    /** pi {@code encodeTextSignatureV1}：{@code phase} 为空 ⇒ **无键**（不是 null）。 */
    public static String encode(String id, String phase) {
        var node = JSON.createObjectNode();
        node.put("v", VERSION);
        node.put("id", id);
        if (phase != null && !phase.isEmpty()) {
            node.put("phase", phase);
        }
        return node.toString();
    }

    /**
     * pi {@code parseTextSignature}：只有 {@code "{"} 开头**且**能解出
     * {@code v === 1}（数字）＋字符串 {@code id} 时才认新形；其余情形（坏 JSON、
     * {@code v} 不是 1、{@code id} 不是字符串）一律**整串当 legacy id**。
     *
     * @return 缺席（null/空串）⇒ empty
     */
    public static Optional<Parsed> parse(String signature) {
        if (signature == null || signature.isEmpty()) {
            return Optional.empty();
        }
        if (signature.startsWith("{")) {
            try {
                var node = JSON.readTree(signature);
                if (node != null
                        && node.get("v") != null && node.get("v").isNumber()
                        && node.get("v").asInt() == VERSION
                        && node.hasNonNull("id") && node.get("id").isTextual()) {
                    var phase = node.path("phase").asText(null);
                    // pi 只认两个合法值；其余（含缺键）⇒ 回 {id}，phase 不上线。
                    var valid = "commentary".equals(phase) || "final_answer".equals(phase);
                    return Optional.of(new Parsed(node.get("id").asText(), valid ? phase : null));
                }
            } catch (Exception ignored) {
                // 坏 JSON：落到 legacy 支（pi 的 catch 同义）
            }
        }
        return Optional.of(new Parsed(signature, null));
    }

    /** 解出的回执 id ＋ 可选 phase（{@code null} ≙ pi 的 undefined）。 */
    public record Parsed(String id, String phase) {}
}

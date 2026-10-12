package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mistral 请求头组装（B233）—— pi {@code mistral-conversations.ts:355-384}
 * 的 {@code buildMistralHeaders}。
 *
 * <p>次序与 pi 一致：基底头（Authorization/Accept；{@code content-type} 与
 * {@code User-Agent} 由 {@code PiHttpClient} 传输默认给，对应 pi :357/:360）
 * → model.headers 覆盖（pi :362）→ options.headers 覆盖（pi :363，
 * 值 {@code null} ⇒ 删除）→ 缓存门自动补 {@code x-affinity}（pi :365-369）。</p>
 *
 * <p>覆盖/删除都是 <b>大小写不敏感</b>的（pi 的 {@code Headers.set/delete} 语义）；
 * 显式亲和判定看两层<b>原始 map 的键存在性</b>（pi :365-366 ＋ :382-384，
 * 值不管 —— {@code {x-affinity:null}} 既压制自动值又把 model 层值删净，
 * 结果头缺席，见 pi {@code mistral-http-transport.test.ts:421-445}）。</p>
 */
final class MistralHeaders {

    private MistralHeaders() {}

    /**
     * @param apiKey        凭证（pi :359 的 Bearer 头）
     * @param modelHeaders  {@code Model.headers}（models.json/目录层），非 null 字符串值
     * @param optionHeaders per-request {@code options.headers} 原始 map（值可能为 {@code null}）
     * @param sessionId     过了 cacheRetention=none 门的会话 id（门由调用方算）
     * @param useCaching    pi {@code shouldUsePromptCaching} 的结果
     */
    static LinkedHashMap<String, String> build(String apiKey,
                                                Map<String, String> modelHeaders,
                                                Map<?, ?> optionHeaders,
                                                String sessionId,
                                                boolean useCaching) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "text/event-stream"); // pi :358
        headers.put("Authorization", "Bearer " + apiKey); // pi :359
        if (modelHeaders != null) {
            modelHeaders.forEach((name, value) -> putCi(headers, name, value));
        }
        applyOptionOverrides(headers, optionHeaders);

        boolean explicitAffinity = hasKeyCi(modelHeaders, "x-affinity")
                || hasRawKeyCi(optionHeaders, "x-affinity");
        if (useCaching && !explicitAffinity) {
            headers.put("x-affinity", sessionId); // pi :368
        }
        return headers;
    }

    /** pi {@code :374-380} applyMistralHeaderOverrides：null 删除，非 null 替换。 */
    private static void applyOptionOverrides(Map<String, String> headers, Map<?, ?> overrides) {
        if (overrides == null) {
            return;
        }
        overrides.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            var name = key.toString();
            if (value == null) {
                removeCi(headers, name);
            } else {
                putCi(headers, name, value.toString());
            }
        });
    }

    /** pi {@code Headers.set}：大小写不敏感替换（先删异写键再 put）。 */
    private static void putCi(Map<String, String> headers, String name, String value) {
        removeCi(headers, name);
        headers.put(name, value);
    }

    /** pi {@code Headers.delete}：大小写不敏感删除。 */
    private static void removeCi(Map<String, String> headers, String name) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
    }

    private static boolean hasKeyCi(Map<String, String> headers, String key) {
        return headers != null
                && headers.keySet().stream().anyMatch(key::equalsIgnoreCase);
    }

    /** 选项头是 raw map（值可能为 null）；只看键存在性（pi :382-384 的 Object.keys().some）。 */
    private static boolean hasRawKeyCi(Map<?, ?> headers, String key) {
        return headers != null
                && headers.keySet().stream()
                        .anyMatch(k -> k != null && key.equalsIgnoreCase(k.toString()));
    }
}

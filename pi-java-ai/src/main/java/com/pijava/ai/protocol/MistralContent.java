package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;

/**
 * docs/66：mistral-conversations 车道的**内容块**出站 helper（从
 * {@code MistralConversationsApi} 抽出，拆分步骤 7）。
 *
 * <p>一个内容块在线上是 {@code {type, …}}：text ⇒ {@code {type:"text", text}}；
 * image ⇒ {@code {type:"image_url", image_url}}（pi {@code mistral-conversations.ts}
 * 的 chunk 构造）。</p>
 */
final class MistralContent {

    private MistralContent() {}

    /** pi 的图片判据是 {@code type === "image"}；java 的 URL 图片同等对待（docs/44 D4）。 */
    static boolean isImageBlock(ContentBlock block) {
        return block instanceof ContentBlock.ImageContent
                || block instanceof ContentBlock.UrlImageContent;
    }

    private static Map<String, Object> chunk(String type, String key, String value) {
        var chunk = new LinkedHashMap<String, Object>();
        chunk.put("type", type);
        chunk.put(key, value);
        return chunk;
    }

    static Map<String, Object> textChunk(String text) {
        return chunk("text", "text", text);
    }

    static Map<String, Object> imageChunk(String url) {
        return chunk("image_url", "image_url", url);
    }
}

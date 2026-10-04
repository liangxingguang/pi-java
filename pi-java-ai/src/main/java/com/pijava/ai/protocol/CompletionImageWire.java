package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import com.pijava.ai.message.ContentBlock;

/**
 * 原 docs/66：openai-completions 车道的**图片内容**出站 helper（从
 * {@code OpenAICompletionsMessageConverter} 抽出，拆分步骤 7）。
 */
final class CompletionImageWire {

    private CompletionImageWire() {}

    /** pi 的图片判据是 {@code type === "image"}；java 的 URL 图片同等对待（原 docs/44 D4）。 */
    static boolean isImageBlock(ContentBlock block) {
        return block instanceof ContentBlock.ImageContent
                || block instanceof ContentBlock.UrlImageContent;
    }

    /** 收集图片块（能力门在调用点，pi {@code openai-completions.ts:1424}）。 */
    static void collectImageParts(List<ContentBlock> content,
                                    List<ChatCompletionContentPart> out) {
        for (var block : content) {
            if (block instanceof ContentBlock.ImageContent img) {
                out.add(imagePart(
                    "data:" + img.mediaType() + ";base64," + img.data()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                out.add(imagePart(url.url()));
            }
        }
    }

    /**
     * pi {@code :1448-1456} 的合成 user 消息：固定文案 ＋ 图片块。文案纯 ASCII，
     * pi 也不净化。
     */
    static ChatCompletionUserMessageParam syntheticToolImageMessage(
            List<ChatCompletionContentPart> imageParts) {
        var parts = new ArrayList<ChatCompletionContentPart>(imageParts.size() + 1);
        parts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                .text("Attached image(s) from tool result:").build()));
        parts.addAll(imageParts);
        return ChatCompletionUserMessageParam.builder()
                .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
                .build();
    }

    /** One image content part (data URL or remote URL). */
    static ChatCompletionContentPart imagePart(String url) {
        return ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(url).build())
                .build());
    }
}

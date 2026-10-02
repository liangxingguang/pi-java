package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * docs/66：openai-responses 车道的**用户/指令输入项**构建（从
 * {@code ResponsesMessageConverter} 抽出，拆分步骤 7）。
 */
final class ResponseInputWire {

    private ResponseInputWire() {}

    /** A single-text easy input item (system updates and text-only users). */
    static ResponseInputItem inputMessage(EasyInputMessage.Role role, String text) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
            .role(role)
            .content(EasyInputMessage.Content.ofTextInput(text))
            .build());
    }

    /** One user message → ResponseInputItem（pi :231 串形态／:238 有图块形态）。 */
    static ResponseInputItem toUserItem(List<ContentBlock> content) {
        var hasImage = content.stream().anyMatch(b -> b instanceof ContentBlock.ImageContent
            || b instanceof ContentBlock.UrlImageContent);
        if (!hasImage) {
            // pi :231 —— user 串形态：整串净化。
            return inputMessage(EasyInputMessage.Role.USER,
                SanitizeUnicode.surrogates(extractText(content)));
        }
        var parts = new ArrayList<ResponseInputContent>();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent tc && !tc.text().isEmpty()) {
                // pi :238 —— user 有图分支：**逐项**净化。
                parts.add(ResponseInputContent.ofInputText(
                    ResponseInputText.builder()
                        .text(SanitizeUnicode.surrogates(tc.text())).build()));
            } else if (block instanceof ContentBlock.ImageContent img) {
                parts.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl("data:" + img.mediaType() + ";base64," + img.data())
                    .build()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                parts.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl(url.url())
                    .build()));
            }
        }
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
            .role(EasyInputMessage.Role.USER)
            .content(EasyInputMessage.Content.ofResponseInputMessageContentList(parts))
            .build());
    }

    private static String extractText(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(c -> c instanceof ContentBlock.TextContent)
                .map(c -> ((ContentBlock.TextContent) c).text())
                .reduce("", String::concat);
    }
}

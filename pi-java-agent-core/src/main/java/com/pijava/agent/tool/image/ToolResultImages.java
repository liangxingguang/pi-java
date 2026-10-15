package com.pijava.agent.tool.image;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.tool.ImagePolicy;
import com.pijava.ai.message.ContentBlock;

/**
 * 工具结果图片统一收口（对应 pi {@code utils/tool-result-images.ts} 的
 * {@code normalizeToolResultImages}，批 9/B85）。
 *
 * <p>{@code read} 工具与 @file 附件自己跑过 {@link ImageProcessor}，但扩展、MCP 桥、
 * 截图等工具会直接把任意 base64 图片塞进结果——超限图进历史后每次请求都被 provider
 * 整单拒绝，故在 after_tool 钩子之后（扩展改写的图也覆盖）统一归一一次。</p>
 *
 * <p>与 read 的关键差异：处理失败时<b>保留原块</b>（工具已产出图，后端不可用不该静默
 * 删输出）；转换/缩放产生的 hints 合并成一块文本追加在该图之后；无变化时返回原列表
 * 同一引用，让调用方跳过重写。</p>
 */
public final class ToolResultImages {

    private ToolResultImages() {}

    /** 无 image 块或归一未改变任何块时，返回与入参同一引用。 */
    public static List<ContentBlock> normalize(List<ContentBlock> content, ImagePolicy policy) {
        if (content == null || content.isEmpty()) {
            return content == null ? List.of() : content;
        }
        boolean hasImage = content.stream().anyMatch(ContentBlock.ImageContent.class::isInstance);
        if (!hasImage) {
            return content;
        }

        List<ContentBlock> normalized = new ArrayList<>(content.size() + 2);
        boolean changed = false;
        for (ContentBlock block : content) {
            if (!(block instanceof ContentBlock.ImageContent image)) {
                normalized.add(block);
                continue;
            }
            byte[] bytes;
            try {
                bytes = java.util.Base64.getDecoder().decode(image.data());
            } catch (IllegalArgumentException e) {
                // 非 base64：原样保留（与处理失败同口径）。
                normalized.add(block);
                continue;
            }
            var processed = ImageProcessor.process(bytes, image.mediaType(), policy);
            if (processed instanceof ImageProcessor.Processed.Failure) {
                normalized.add(block);
                continue;
            }
            var ok = (ImageProcessor.Processed.Ok) processed;
            if (ok.data().equals(image.data()) && ok.mimeType().equals(image.mediaType())
                && ok.hints().isEmpty()) {
                normalized.add(block);
                continue;
            }
            normalized.add(new ContentBlock.ImageContent(ok.mimeType(), ok.data()));
            if (!ok.hints().isEmpty()) {
                normalized.add(new ContentBlock.TextContent(String.join("\n", ok.hints())));
            }
            changed = true;
        }
        return changed ? List.copyOf(normalized) : content;
    }
}

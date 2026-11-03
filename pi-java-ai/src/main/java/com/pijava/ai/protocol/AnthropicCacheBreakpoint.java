package com.pijava.ai.protocol;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * #101 结构拆分：Anthropic 会话断点的「给单个块挂 {@code cache_control}」逻辑
 * （pi {@code anthropic-messages.ts:1406-1422} 的白名单分支）。
 *
 * <p>从 {@link AnthropicRequestBuilder} 移出，只为实现 ≤500 行规则；行为零变化。</p>
 */
final class AnthropicCacheBreakpoint {

    private AnthropicCacheBreakpoint() {}

    /**
     * 给一个块挂断点（pi {@code :1406-1422} 的白名单分支）。
     *
     * <p>白名单是 {@code text | image | tool_result | tool_addition | tool_removal}。
     * 前三个走 SDK 的 {@code toBuilder()} 重建；后两个是 beta 形状的块，java 侧经**原始 JSON
     * 直通**承载（{@code 原 docs/51 §12.4}），故取 {@code _json()} 加键后回读 ——
     * 这条路径产出的键序与 pi **逐字节相同**（探针 J9）。</p>
     *
     * <p>⚠️ 返回 {@code null} 表示**这个块不在白名单里**（pi 的 {@code else} ⇒ 静默不挂）。
     * 按可达输入算这个分支取不到（user 消息的块只能是 text/image/tool_result，system 消息的
     * 只能是 text/tool_removal/tool_addition，五种全在名单内，见 {@code 原 docs/54 §4.6}）——
     * <b>照抄保留，且这不是夹具没牙</b>：白名单本身就是 pi 的行为，删掉它会在 pi 未来新增
     * 块类型时静默改变行为。</p>
     */
    static ContentBlockParam withCacheControl(ContentBlockParam block,
                                              CacheControlEphemeral cacheControl) {
        if (block.isText()) {
            return ContentBlockParam.ofText(
                block.asText().toBuilder().cacheControl(cacheControl).build());
        }
        if (block.isImage()) {
            return ContentBlockParam.ofImage(
                block.asImage().toBuilder().cacheControl(cacheControl).build());
        }
        if (block.isToolResult()) {
            return ContentBlockParam.ofToolResult(
                block.asToolResult().toBuilder().cacheControl(cacheControl).build());
        }
        var raw = block._json().orElse(null);
        if (raw == null) {
            return null;
        }
        var mapper = ObjectMappers.jsonMapper();
        var node = mapper.valueToTree(raw);
        if (!(node instanceof ObjectNode object)) {
            return null;
        }
        var type = object.path("type").asText("");
        if (!"tool_addition".equals(type) && !"tool_removal".equals(type)) {
            return null;
        }
        object.set("cache_control", mapper.valueToTree(cacheControl));
        try {
            return mapper.treeToValue(object, ContentBlockParam.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot attach a cache breakpoint to a raw block", e);
        }
    }
}

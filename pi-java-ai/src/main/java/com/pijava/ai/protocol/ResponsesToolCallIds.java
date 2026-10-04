package com.pijava.ai.protocol;

import com.pijava.ai.api.ToolCallIdNormalizer;
import com.pijava.ai.utils.ShortHash;

import java.util.Objects;
import java.util.Set;

/**
 * OpenAI Responses 车道的 toolCall id 归一器（包 B14 步2，pi P21）。
 *
 * <p>pi {@code openai-responses-shared.ts:155-176} ＋ {@code openai-responses.ts:31}。</p>
 *
 * <p>D3（{@code 原 docs/62}）起接收侧构造复合 id（{@code call_id|item.id}）⇒
 * 竖线分支<b>生产可达</b>（P33 已结案）。</p>
 */
public final class ResponsesToolCallIds {

    /** pi {@code openai-responses.ts:31} 的 {@code OPENAI_TOOL_CALL_PROVIDERS}。 */
    private static final Set<String> ALLOWED_TOOL_CALL_PROVIDERS =
            Set.of("openai", "openai-codex", "opencode");

    private ResponsesToolCallIds() {}

    /**
     * D3：pi shared :288/:331 的出站拆分 —— 复合 id 取 call_id 段
     * （无 {@code |} 的旧 id 原样返回）。
     */
    public static String callIdOf(String id) {
        int sep = id.indexOf('|');
        return sep < 0 ? id : id.substring(0, sep);
    }

    /**
     * D3：pi shared :289 的 item.id 段（{@code split("|")[1]}）；无第二段 ⇒ {@code null}。
     */
    public static String itemIdOf(String id) {
        int sep = id.indexOf('|');
        return sep < 0 || sep + 1 >= id.length() ? null : id.substring(sep + 1);
    }

    /**
     * pi 的 {@code normalizeToolCallId} 闭包捕获了本车道的 api 名
     * （pi 里是 {@code model.api}；java 侧 api 是适配器属性，故显式传入）。
     *
     * @param apiName 本车道的 api 名，例如 {@code "openai-responses"}
     */
    public static ToolCallIdNormalizer create(String apiName) {
        return (id, target, source) -> {
            if (!ALLOWED_TOOL_CALL_PROVIDERS.contains(target.provider())) {
                return normalizeIdPart(id);
            }
            if (!id.contains("|")) {
                return normalizeIdPart(id);
            }
            // pi 的 split("|") 解构取前两段，多余段丢弃 —— Java 同形（D3 起生产可达）。
            String[] parts = id.split("\\|", -1);
            var callId = parts[0];
            var itemId = parts.length > 1 ? parts[1] : "";
            var normalizedCallId = normalizeIdPart(callId);
            boolean isForeignToolCall = !Objects.equals(source.provider(), target.provider())
                    || !Objects.equals(source.api(), apiName);
            var normalizedItemId = isForeignToolCall
                    ? buildForeignResponsesItemId(itemId)
                    : normalizeIdPart(itemId);
            if (!normalizedItemId.startsWith("fc_")) {
                normalizedItemId = normalizeIdPart("fc_" + normalizedItemId);
            }
            return normalizedCallId + "|" + normalizedItemId;
        };
    }

    /**
     * pi {@code openai-responses-shared.ts:144-148} 的 {@code normalizeIdPart}：
     * 非法字符换 {@code _}、截 64、剥尾部下划线。
     */
    private static String normalizeIdPart(String part) {
        var sanitized = part.replaceAll("[^a-zA-Z0-9_-]", "_");
        var normalized = sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
        return normalized.replaceAll("_+$", "");
    }

    /** pi {@code :150-153} 的 {@code buildForeignResponsesItemId}：{@code fc_} + shortHash，超 64 截 64。 */
    private static String buildForeignResponsesItemId(String itemId) {
        var normalized = "fc_" + ShortHash.of(itemId);
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }}

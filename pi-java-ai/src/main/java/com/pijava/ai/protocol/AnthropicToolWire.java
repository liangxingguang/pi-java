package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.pijava.ai.api.StrictJsonSchema;
import com.pijava.ai.api.StrictSampling;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.Transcripts;

/**
 * Anthropic 车道的**工具线格**：顶层工具表（pi {@code anthropic-messages.ts:1203-1232} 的两支）
 * 与消息侧的两个工具块（{@code tool_removal} / {@code tool_addition}，pi {@code :1339-1351}）。
 *
 * <p>从 {@link AnthropicRequestBuilder} 拆出，只为守住「文件 ≤ 500 行」。两者是同一条车道的
 * 同一层关注点（工具怎么落到出站 JSON 上），拆缝按「工具」划，不按调用顺序划。</p>
 *
 * <p>包 B165（{@code 原 docs/45}）：形状换成 pi 的**定义内联**（{@code b271b0a52}，beta
 * {@code inline-tools-2026-09-15}）。顶层表**就此定死**（初始 ＋ 占位符），后续工具改为在
 * {@code tool_addition} 块里带**完整定义**（含 {@code input_schema}）。</p>
 */
final class AnthropicToolWire {

    private AnthropicToolWire() {
    }

    /**
     * pi {@code :196-208} 的 {@code DEFERRED_TOOL_PLACEHOLDER} 的名字。
     *
     * <p>只要请求里**任何**工具有 {@code defer_loading}，Anthropic 就会加隐藏的提示脚手架；
     * 从第一个请求起就声明这个占位符能把那段脚手架留在缓存前缀里，于是第一个真正的
     * 迟到工具不会击穿缓存（pi 注释：实测不这么做会**全量 miss**）。
     * 它永不被激活，模型也看不见。</p>
     */
    private static final String DEFERRED_TOOL_PLACEHOLDER_NAME = "__pi_deferred_placeholder__";

    /**
     * 工具表（pi {@code :1203-1232} 的两支）。
     *
     * <p>原生支的形状是 pi 注释写死的那句「请求级列表**就此定死**」：初始工具保持活跃、
     * 缓存断点挂最后一个上，随后是占位符 —— 顶层表**到此为止**。后续工具不再出现在这里，
     * 而是在 {@code tool_addition} 块里**按值内联**（{@code tool_definition}）；被删的
     * 由 {@code tool_removal} 撤回。于是这段缓存前缀跨工具变更完整。</p>
     *
     * <p>⚠️ 与 {@code b271b0a52} 之前的关键差异：旧形状把「历史上声明过的后续工具」也摊进顶层表
     * （{@code defer_loading:true}），靠 {@code tool_addition} 的按名引用来激活。新形状**不摊**
     * —— 这正是尾部那段 {@code getDeclaredTools} 循环被删的原因。</p>
     *
     * <p>包 A-01：断点挂在本支**最后一个工具**上（pi {@code :1489} 的
     * {@code index === tools.length - 1}）。⚠️ 原生支挂的是 <b>{@code initialTools} 的末项</b>
     * —— 占位符**不挂**（pi 对它们另调一次不带 {@code cacheControl} 的 {@code convertTools}），
     * 这样缓存前缀跨工具变更完整（探针 P7/P10 实测）。</p>
     */
    static void addTools(MessageCreateParams.Builder builder,
                         com.pijava.ai.api.TranscriptContext transcript,
                         List<ToolDefinition> initialTools, boolean nativeToolChanges,
                         CacheControlEphemeral toolCacheControl,
                         boolean supportsStrictTools,
                         boolean eagerToolInputStreaming,
                         boolean oauth) {
        if (!nativeToolChanges) {
            var tools = Transcripts.getCurrentTools(transcript.messages());
            for (int i = 0; i < tools.size(); i++) {
                builder.addTool(ToolUnion.ofTool(
                    toAnthropicTool(tools.get(i),
                        atLast(i, tools.size(), toolCacheControl), supportsStrictTools,
                        eagerToolInputStreaming, oauth)));
            }
            return;
        }
        for (int i = 0; i < initialTools.size(); i++) {
            builder.addTool(ToolUnion.ofTool(
                toAnthropicTool(initialTools.get(i),
                    atLast(i, initialTools.size(), toolCacheControl), supportsStrictTools,
                    eagerToolInputStreaming, oauth)));
        }
        // B81：占位符不经过 toClaudeCodeName（pi 里它独立于 convertTools 构造）。
        builder.addTool(ToolUnion.ofTool(deferredToolPlaceholder()));
    }

    /** pi {@code :1489}：只有末项拿断点。 */
    private static CacheControlEphemeral atLast(int index, int size, CacheControlEphemeral cc) {
        return index == size - 1 ? cc : null;
    }

    /**
     * 工具定义 → 与 pi {@code convertTools}（{@code :1576-1614}）**同形**的普通 JSON：
     * {@code {name, description?, eager_input_streaming?, strict?, input_schema}}。
     *
     * <p><b>单一真源</b>：顶层表的 SDK {@link Tool} 由本函数的结果**回读**得到
     * （{@link #toAnthropicTool}），内联的 {@code tool_definition.definition} 直接用它 ——
     * pi 两侧走的本来就是同一个 {@code convertTools}，所以这里也只有一份构造。</p>
     *
     * <p>⚠️ <b>键序与 pi 相同</b>（探针实测）：{@code cache_control} 由
     * {@link #toAnthropicTool} 事后挂上（pi 的 {@code convertTools} 里它是**最后一个**键，
     * 且只挂末项）；内联定义**没有** {@code cache_control}（pi 的回调实参缺省第 5 参）。</p>
     */
    private static Map<String, Object> toDefinitionJson(ToolDefinition td, boolean supportsStrictTools,
                                                        boolean eagerToolInputStreaming,
                                                        boolean oauth) {
        // 批1/#101：探测遍带 Anthropic 专有谓词（minimum/format 等命中 ⇒ prefer 回落非 strict、
        // require 抛错）；strict=true 时的实际转换走无谓词通用版（pi 同形两遍，
        // anthropic-messages.ts:1586-1590）。
        var strict = Boolean.TRUE.equals(
            StrictSampling.resolveStrict(td, supportsStrictTools, AnthropicStrictKeywords.PREDICATE));
        var schema = strict
            ? StrictJsonSchema.convert(td.inputSchema()) : td.inputSchema();
        var definition = new LinkedHashMap<String, Object>();
        // B81（pi convertTools :1603）：OAuth 车道工具名走 CC 规范名映射。
        definition.put("name", ClaudeCodeNames.toWire(td.name(), oauth));
        if (td.description() != null && !td.description().isBlank()) {
            definition.put("description", td.description());
        }
        // 包 09（B90）：pi `:1486` 的 `...(cond ? {eager_input_streaming: true} : {})` ——
        // **缺席**而非 false。
        if (eagerToolInputStreaming) {
            definition.put("eager_input_streaming", true);
        }
        if (strict) {
            definition.put("strict", true);
        }
        definition.put("input_schema", schema);
        return definition;
    }

    /**
     * 工具定义 → Anthropic 的 SDK {@link Tool}；{@code cacheControl} 非空时挂断点
     * （pi {@code :1611} —— 只有**末项**拿得到，由 {@link #atLast} 决定）。
     */
    private static Tool toAnthropicTool(ToolDefinition td, CacheControlEphemeral cacheControl,
                                        boolean supportsStrictTools,
                                        boolean eagerToolInputStreaming,
                                        boolean oauth) {
        var tool = rawTool(toDefinitionJson(td, supportsStrictTools,
            eagerToolInputStreaming, oauth));
        return cacheControl == null ? tool : tool.toBuilder().cacheControl(cacheControl).build();
    }

    /**
     * 普通 JSON → SDK {@link Tool}。
     *
     * <p>⚠️ <b>不走 {@code Tool.builder().inputSchema(putAllAdditionalProperties(...))}</b>：
     * SDK 的 {@code InputSchema} 把 {@code type} 声明成自己的字段，与 additionalProperties
     * **重叠** ⇒ 那条路径会把 {@code "type"} 键**写两遍**（{@code "type":"object","type":"object"}），
     * 与 pi 的线格不符。回读路径把 {@code type} 收进声明字段，只写一次（探针实测，
     * {@code 原 docs/45 §10}）。</p>
     */
    private static Tool rawTool(Map<String, Object> definition) {
        var mapper = ObjectMappers.jsonMapper();
        try {
            return mapper.treeToValue(mapper.valueToTree(definition), Tool.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot build an Anthropic tool definition", e);
        }
    }

    /**
     * pi {@code :203-208} 的 {@code DEFERRED_TOOL_PLACEHOLDER}，键序逐字对齐
     * （{@code name, description, input_schema, defer_loading}）。
     *
     * <p>它的 schema 是 {@code {type:"object", properties:{}, required:[]}}，同样走
     * {@link #rawTool} —— 见那里的重复键说明。</p>
     */
    private static Tool deferredToolPlaceholder() {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());
        var definition = new LinkedHashMap<String, Object>();
        definition.put("name", DEFERRED_TOOL_PLACEHOLDER_NAME);
        definition.put("description", "Reserved placeholder. Never available. Never call this.");
        definition.put("input_schema", schema);
        definition.put("defer_loading", true);
        return rawTool(definition);
    }

    /**
     * {@code {type:"tool_removal", tool:{type:"tool_reference", name}}}（pi {@code :1345-1348}）。
     *
     * <p>⚠️ {@code tool_removal} 的载荷形状**没变**：仍是按名引用。变的只是
     * {@code tool_addition}（见 {@link #toolDefinitionBlock}）。</p>
     */
    static ContentBlockParam toolReferenceBlock(String type, String toolName, boolean oauth) {
        var tool = new LinkedHashMap<String, Object>();
        tool.put("type", "tool_reference");
        // B81（pi :1347）：OAuth 下被撤回的工具按 CC 规范名引用。
        tool.put("name", ClaudeCodeNames.toWire(toolName, oauth));
        var block = new LinkedHashMap<String, Object>();
        block.put("type", type);
        block.put("tool", tool);
        return rawContentBlock(block);
    }

    /**
     * {@code {type:"tool_addition", tool:{type:"tool_definition", definition:{…}}}}
     * （pi {@code :1351}）。
     *
     * <p>载荷是 {@code convertTools(...)} 的返回值 —— 与顶层表**同一个**定义构造器
     * （strict／eager／input_schema 三项全走），但**不带** {@code cache_control}
     * （pi 的回调实参缺省第 5 参）。见 {@link #toDefinitionJson}。</p>
     */
    static ContentBlockParam toolDefinitionBlock(ToolDefinition td,
                                                 boolean supportsStrictTools,
                                                 boolean eagerToolInputStreaming,
                                                 boolean oauth) {
        var tool = new LinkedHashMap<String, Object>();
        tool.put("type", "tool_definition");
        tool.put("definition", toDefinitionJson(td, supportsStrictTools,
            eagerToolInputStreaming, oauth));
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "tool_addition");
        block.put("tool", tool);
        return rawContentBlock(block);
    }

    /**
     * 普通 JSON → {@link ContentBlockParam}（{@code tool_removal} 与 {@code tool_addition}
     * 共用）。
     *
     * <p>⚠️ 这两个块**只存在于** {@code com.anthropic.models.beta.messages.*} —— 非 beta 的
     * {@link ContentBlockParam} 没有对应变体。这里用 SDK 的**未知变体直通**（原始 JSON 反序列化，
     * 序列化时原样写出）把 beta 形状装进非 beta 参数，绕开整条车道的类型族迁移。
     * 通路与逐字节往返证据见 {@code SdkJsonEscapeHatchTest}（{@code 原 docs/51 §12.4}）。</p>
     */
    private static ContentBlockParam rawContentBlock(Map<String, Object> block) {
        var mapper = ObjectMappers.jsonMapper();
        try {
            return mapper.treeToValue(mapper.valueToTree(block), ContentBlockParam.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot build a raw Anthropic content block", e);
        }
    }
}

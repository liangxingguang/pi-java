package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.StrictJsonSchema;
import com.pijava.ai.api.StrictSampling;
import com.pijava.ai.api.ToolDefinition;

/**
 * 原 docs/66：mistral-conversations 车道的 function-tool 出站构建
 * （pi {@code mistral-conversations.ts:752-765}）。从
 * {@code MistralConversationsApi} 抽出（拆分步骤 7）。
 *
 * <p>v1.6.0（pi {@code mistral-conversations.ts:531-534}）：strict tool 是**显式 opt-in** ——
 * 门取 {@code model.compat?.supportsStrictMode ?? false}（Mistral Large 4 带
 * {@code strict:true} 会沉默数分钟才发工具调用，#12148）。旧实现恒传 true 是默认偏差。
 * 每工具 {@code strict = resolveStrict}，true ⇒ parameters 走 strict schema 转换；
 * 恒发 {@code function.strict} 键（缺省 false）。</p>
 */
final class MistralTools {

    private MistralTools() {}

    /** Build Mistral {@code [{type:"function", function:{…}}]} tools. */
    static List<Map<String, Object>> toTools(List<ToolDefinition> definitions,
                                             boolean supportsStrictMode) {
        return definitions.stream().<Map<String, Object>>map(def -> {
            var strict = Boolean.TRUE.equals(StrictSampling.resolveStrict(def,
                supportsStrictMode));
            var schema = strict ? StrictJsonSchema.convert(def.inputSchema())
                : def.inputSchema();
            var function = new LinkedHashMap<String, Object>();
            function.put("name", def.name());
            function.put("description", def.description());
            function.put("parameters", schema);
            function.put("strict", strict);
            var tool = new LinkedHashMap<String, Object>();
            tool.put("type", "function");
            tool.put("function", function);
            return tool;
        }).toList();
    }
}

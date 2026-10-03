package com.pijava.ai.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * docs/69：pi {@code constrained-sampling.ts:189-277} 的 grammar 解析 ——
 * 请求起点一次算出 {@code toolName → inputProperty} 表（pi
 * {@code createGrammarToolInputProperties}），两车道的出站声明与历史回放都读它。
 *
 * <p>语义照 pi：能力门 false ⇒ 静默回落（返回 null）；门开但两变体皆空 ⇒ 硬错；
 * 变体 lark 优先于 regex；schema 必须是 object 且恰一个必填 string 属性。</p>
 */
public final class GrammarInputProperties {

    /**
     * pi {@code GrammarConstrainedSampling}（{@code constrained-sampling.ts:133-137}）。
     *
     * @param format         {@code "lark"} 或 {@code "regex"}
     * @param definition     文法定义
     * @param inputProperty  参数对象里承载输入串的属性名
     */
    public record ResolvedGrammar(String format, String definition, String inputProperty) {}

    /**
     * pi {@code resolveGrammarConstrainedSampling:230-263}。
     *
     * @return 解析结果；非 grammar 配置或门关闭 ⇒ {@code null}
     */
    public static ResolvedGrammar resolve(ToolDefinition tool,
                                          boolean supportsOpenAIGrammarTools) {
        var config = tool.constrainedSampling();
        if (!(config instanceof GrammarSampling grammarConfig)
                || !supportsOpenAIGrammarTools) {
            return null;
        }
        var variants = grammarConfig.variants();
        var lark = variants.get("openai_lark");
        var regex = variants.get("openai_regex");
        var hasLark = lark != null && !lark.isBlank();
        var hasRegex = regex != null && !regex.isBlank();
        if (!hasLark && !hasRegex) {
            throw new IllegalStateException(
                "Tool \"" + tool.name() + "\" cannot use grammar constrained sampling: "
                    + "no supported grammar variant was provided.");
        }
        try {
            var property = inferInputProperty(tool);
            return new ResolvedGrammar(
                hasLark ? "lark" : "regex",
                hasLark ? lark : regex,
                property);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                "Tool \"" + tool.name() + "\" cannot use grammar constrained sampling: "
                    + error.getMessage() + ".");
        }
    }

    /** pi {@code createGrammarToolInputProperties:265-277}。 */
    public static Map<String, String> create(List<ToolDefinition> tools,
                                             boolean supportsOpenAIGrammarTools) {
        var properties = new LinkedHashMap<String, String>();
        if (tools != null) {
            for (var tool : tools) {
                var grammar = resolve(tool, supportsOpenAIGrammarTools);
                if (grammar != null) {
                    properties.put(tool.name(), grammar.inputProperty());
                }
            }
        }
        return Map.copyOf(properties);
    }

    /** pi {@code inferGrammarInputProperty:189-206}：object ＋ 恰一个必填 string 属性。 */
    private static String inferInputProperty(ToolDefinition tool) {
        var schema = tool.inputSchema();
        if (!"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException(
                "grammar constrained sampling requires an object parameter schema");
        }
        if (!(schema.get("required") instanceof List<?> required)
                || required.size() != 1
                || !(required.get(0) instanceof String property)) {
            throw new IllegalArgumentException(
                "grammar constrained sampling requires exactly one required string property");
        }
        if (!(schema.get("properties") instanceof Map<?, ?> properties)
                || !(properties.get(property) instanceof Map<?, ?> inputProperty)) {
            throw new IllegalArgumentException(
                "grammar constrained sampling requires a properties entry for " + property);
        }
        if (!"string".equals(inputProperty.get("type"))) {
            throw new IllegalArgumentException(
                "grammar constrained sampling property " + property
                    + " must have type string");
        }
        return property;
    }

    private GrammarInputProperties() {}
}

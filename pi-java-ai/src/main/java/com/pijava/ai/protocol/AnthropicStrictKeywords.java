package com.pijava.ai.protocol;

import java.util.Set;

import com.pijava.ai.api.UnsupportedStrictKeyword;

/**
 * Anthropic strict 工具的关键字拒绝谓词，逐字对位
 * {@code anthropic-messages.ts:1542-1575} 的 {@code isAnthropicStrictUnsupportedKeyword}
 * （来源：https://platform.claude.com/docs/en/build-with-claude/structured-outputs#json-schema-limitations）。
 *
 * <p>命中这些键值时整个请求会被 Anthropic 400（不是降级），故探测遍带本谓词：
 * prefer 工具回落非 strict，require 工具抛错。strict=true 的实际发送遍仍走无 Probe 谓词的
 * 通用转换（pi 的 {@code getJsonSchemaToolParameters} 同样不带谓词）。其余五车道不使用本类
 * （mistral 恒 strict 且无专有拒绝集）。</p>
 */
final class AnthropicStrictKeywords {

    /** pi {@code ANTHROPIC_STRICT_UNSUPPORTED_KEYWORDS}（{@code :1544-1556}），逐字同序。 */
    private static final Set<String> UNSUPPORTED = Set.of(
        "minimum",
        "maximum",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "multipleOf",
        "maxItems",
        "uniqueItems",
        "minContains",
        "maxContains",
        "minProperties",
        "maxProperties");

    /** pi {@code ANTHROPIC_STRICT_STRING_FORMATS}（{@code :1557-1568}）。 */
    private static final Set<String> ALLOWED_FORMATS = Set.of(
        "date-time",
        "time",
        "date",
        "duration",
        "email",
        "hostname",
        "uri",
        "ipv4",
        "ipv6",
        "uuid");

    /** 唯一实例：只在 {@link AnthropicToolWire} 探测遍传入。 */
    static final UnsupportedStrictKeyword PREDICATE = AnthropicStrictKeywords::test;

    private AnthropicStrictKeywords() {
    }

    private static boolean test(String key, Object value) {
        if (UNSUPPORTED.contains(key)) {
            return true;
        }
        if ("minItems".equals(key)) {
            // pi `value !== 0 && value !== 1`：仅 0/1 放行；非数值同拒。
            return !(value instanceof Number number
                && (number.doubleValue() == 0.0d || number.doubleValue() == 1.0d));
        }
        if ("format".equals(key)) {
            // pi `typeof value !== "string" || !FORMATS.has(value)`。
            return !(value instanceof String format && ALLOWED_FORMATS.contains(format));
        }
        return false;
    }
}

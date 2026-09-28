package com.pijava.ai.catalog;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * OpenAI 兼容车道上「思考开关」的十一种线格形状（pi
 * {@code OpenAICompletionsCompat.thinkingFormat}，{@code types.ts:696-707}）。
 *
 * <p>pi 的值是十一取值的字符串联合；本仓按 {@code CLAUDE.md} 的约定落成枚举
 * （纯判别字面量、无附带数据），{@link #wireName()} 给出线格字面量。取值含义见
 * {@code ThinkingFormatWriter}（pi {@code openai-completions.ts:873-970} 的形态链条）。</p>
 *
 * <p>⚠️ 与 {@link MaxTokensField} 同形的一点：它是**端点属性**，探测按 provider/baseUrl
 * （{@code detectCompat:1646-1656}），目录与 models.json 可显式覆盖 ⇒
 * {@link ModelCompat#thinkingFormat()} 三态里 {@code null} 是「按车道探测」。</p>
 *
 * <p>⚠️ 线格名带连字符的四个（{@code chat-template}/{@code qwen-chat-template}/
 * {@code string-thinking}/{@code ant-ling}）不等于枚举名的小写 ⇒ 不能用
 * {@code name().toLowerCase()} 推，必须逐个携带。</p>
 *
 * @see com.pijava.ai.catalog.CompatResolver
 */
public enum ThinkingFormat {
    /** pi {@code "openai"} —— 缺省形状：{@code reasoning_effort}（探测的回落值）。 */
    OPENAI("openai"),

    /** pi {@code "openrouter"} —— {@code reasoning:{effort}} 嵌套对象。 */
    OPENROUTER("openrouter"),

    /** pi {@code "deepseek"} —— {@code thinking:{type}} ＋ 支持时的 {@code reasoning_effort}。 */
    DEEPSEEK("deepseek"),

    /** pi {@code "together"} —— {@code reasoning:{enabled}} ＋ 支持时的 {@code reasoning_effort}。 */
    TOGETHER("together"),

    /** pi {@code "baseten"} —— 可配置的 {@code chat_template_args} ＋ {@code reasoning_effort}。 */
    BASETEN("baseten"),

    /** pi {@code "zai"} —— {@code thinking:{type, clear_thinking}}。 */
    ZAI("zai"),

    /** pi {@code "qwen"} —— 顶层 {@code enable_thinking} 布尔。 */
    QWEN("qwen"),

    /** pi {@code "chat-template"} —— 可配置的 {@code chat_template_kwargs}（{@code $var} 解析）。 */
    CHAT_TEMPLATE("chat-template"),

    /** pi {@code "qwen-chat-template"} —— 固定的 {@code enable_thinking}/{@code preserve_thinking}。 */
    QWEN_CHAT_TEMPLATE("qwen-chat-template"),

    /** pi {@code "string-thinking"} —— 顶层 {@code thinking} 字符串。 */
    STRING_THINKING("string-thinking"),

    /** pi {@code "ant-ling"} —— {@code reasoning:{effort}}，且**仅当**映射值是字符串。 */
    ANT_LING("ant-ling");

    private final String wireName;

    ThinkingFormat(String wireName) {
        this.wireName = wireName;
    }

    /** 线格字面量（pi 的那个字符串）。 */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * pi 字面量 ⇒ 枚举。未知取值返回空 —— 「响亮抛错」由 models.json 的解析层决定
     * （{@code ModelsJsonConfig.thinkingFormatOf}，与 {@code maxTokensField} 同口径：
     * 静默降级会静默改变线格）。
     *
     * <p>⚠️ **大小写敏感的精确匹配**：pi 的 zod 联合是逐字面量比对，
     * {@code "OpenAI"} 在 pi 是校验失败 ⇒ 这里不做小写化归一。</p>
     */
    public static Optional<ThinkingFormat> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (var format : values()) {
            if (format.wireName.equals(raw)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}

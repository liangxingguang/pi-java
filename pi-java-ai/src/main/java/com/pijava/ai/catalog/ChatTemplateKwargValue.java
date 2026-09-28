package com.pijava.ai.catalog;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * {@code chat_template_kwargs}/{@code chat_template_args} 的一个声明值（pi
 * {@code ChatTemplateKwargValue}，{@code types.ts:87-95}）：
 *
 * <pre>{@code string | number | boolean | null | {$var, omitWhenOff?}}</pre>
 *
 * <p>变体**携带不同字段**（字面量是一个标量；{@code $var} 对象有取值名与省略门）⇒
 * 按 {@code CLAUDE.md} 的判据用 sealed interface ＋ record，而不是给一个字面量类型
 * 硬塞可选字段。消费者是 {@code ThinkingFormatWriter}（pi
 * {@code resolveChatTemplateKwargValue:1044-1067}）。</p>
 *
 * <p>⚠️ {@link Literal} 的载荷是裸 {@code Object} 而不是 SDK 的 {@code JsonValue}：
 * catalog 包**不依赖** openai SDK（分层纪律），标量性由 models.json 的解析层校验
 * （{@code ModelsJsonConfig.kwargValueOf}）。</p>
 *
 * <p>⚠️ <b>{@code Literal(null)} 是合法值、语义是「写这个键，值是 null」</b> ——
 * pi 的 {@code :1050} 早返回让字面量 {@code null} 原样上线（{@code "key": null}），
 * 它**不是**「这个键不写」。两态之别由消费侧守住（{@code docs/58 §2.3}/R11）。</p>
 */
public sealed interface ChatTemplateKwargValue {

    /**
     * pi 的 {@code string | number | boolean | null}：原样上线。
     *
     * @param value {@code String}／{@code Number}／{@code Boolean}／{@code null} 之一
     *              （解析层保证；null 是**值**，见类 javadoc）
     */
    record Literal(Object value) implements ChatTemplateKwargValue {}

    /**
     * pi 的 {@code {$var, omitWhenOff?}}：由请求期的思考状态解析。
     *
     * @param var         三个 pi 控制的取值之一
     * @param omitWhenOff pi 的 {@code omitWhenOff}：无思考级别时**整个键不写**
     *                    （缺省 {@code false} ≙ pi 的键缺席）
     */
    record Var(ThinkingVar var, boolean omitWhenOff) implements ChatTemplateKwargValue {

        /** 便捷构造：无 {@code omitWhenOff}（≙ pi 的键缺席）。 */
        public Var(ThinkingVar var) {
            this(var, false);
        }
    }

    /**
     * 三个 pi 控制的取值（{@code types.ts:93}）—— 纯判别字面量 ⇒ 枚举。
     *
     * <p>⚠️ 线格名带点号与连字符语义（{@code thinking.enabled} 等），逐个携带。</p>
     */
    enum ThinkingVar {
        /** pi {@code "thinking.enabled"} ⇒ {@code !!reasoningEffort} 布尔。 */
        ENABLED("thinking.enabled"),

        /** pi {@code "thinking.effort"} ⇒ 级别映射（两派 null 语义见消费侧）。 */
        EFFORT("thinking.effort"),

        /** pi {@code "thinking.budget"} ⇒ 夹取后的预算；无预算时**键被删**。 */
        BUDGET("thinking.budget");

        private final String wireName;

        ThinkingVar(String wireName) {
            this.wireName = wireName;
        }

        /** 线格字面量（pi 的那个字符串）。 */
        @JsonValue
        public String wireName() {
            return wireName;
        }

        /**
         * pi 字面量 ⇒ 枚举；未知取值返回空（models.json 的解析层响亮抛错，
         * 与 {@link ThinkingFormat#parse} 同口径）。大小写敏感的精确匹配。
         */
        public static Optional<ThinkingVar> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            for (var var : values()) {
                if (var.wireName.equals(raw)) {
                    return Optional.of(var);
                }
            }
            return Optional.empty();
        }
    }
}

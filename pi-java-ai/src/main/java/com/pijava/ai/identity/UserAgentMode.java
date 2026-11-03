package com.pijava.ai.identity;

/**
 * How the {@code User-Agent} is rendered for a request.
 *
 * <p>pi {@code UserAgentMode}（{@code packages/ai/src/utils/identity.ts:5-11}）：
 * {@code "short"} ⇒ {@code "pi"}；{@code "detailed"} ⇒ 带版本／平台／运行时／架构；
 * {@code { custom }} ⇒ 原样字符串。</p>
 */
public sealed interface UserAgentMode permits UserAgentMode.Short, UserAgentMode.Detailed,
        UserAgentMode.Custom {

    /** {@code "short"}：只发 {@code pi}。 */
    UserAgentMode SHORT = new Short();

    /** {@code "detailed"}（缺省）：{@code pi-java/<version> (<platform>; <runtime>; <arch>)}。 */
    UserAgentMode DETAILED = new Detailed();

    /** {@code "short"} 的单例记录。 */
    record Short() implements UserAgentMode {}

    /** {@code "detailed"} 的单例记录。 */
    record Detailed() implements UserAgentMode {}

    /** {@code { custom }}：调用方给定的完整 UA。 */
    record Custom(String value) implements UserAgentMode {
        public Custom {
            java.util.Objects.requireNonNull(value, "value");
        }
    }
}

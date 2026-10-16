package com.pijava.ai.auth;

import java.util.Map;

import com.pijava.ai.api.AuthKind;

/**
 * 解析出来的凭证：**值 ＋ 形态 ＋ 出处**（包 A0，{@code 原 docs/43 D5}）。
 *
 * <p>pi 的凭证解析结果同样带出处（{@code providers/anthropic.ts:18-39} 返回
 * {@code source: "stored credential"} ／ 环境变量名），java 之前把这三样压成了一个
 * 裸字符串（{@code Credentials.resolveApiKey}）⇒ 车道无从知道该放哪个头。</p>
 *
 * @param kind  凭证形态（决定车道的头形状）
 * @param value 凭证值
 * @param source 出处（{@code env:<VAR>} ／ {@code stored:<provider>[::<profile>]}），
 *               供诊断与测试断言；pi 的 {@code source} 只用于展示
 * @param env   #98：federation 的 provider env —— pi 的 resolve 返回
 *              {@code {auth, env, source}} 三件套里的 {@code env}（federation 五变量，
 *              「ids 是 provider 配置而非 auth，故走 env」是 pi 注释原意）。非 federation
 *              恒为空表（缺席 ≙ pi 的 {@code env} 键不在）。
 */
public record RecordedCredential(AuthKind kind, String value, String source, Map<String, String> env) {

    /** Canonical：env 防御性拷贝；{@code null} ⇒ 空表。 */
    public RecordedCredential {
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    /** 三参便利构造（存量调用点零改签）：无 provider env。 */
    public RecordedCredential(AuthKind kind, String value, String source) {
        this(kind, value, source, Map.of());
    }
}

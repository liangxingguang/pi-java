package com.pijava.ai.auth;

import com.pijava.ai.api.AuthKind;

/**
 * 解析出来的凭证：**值 ＋ 形态 ＋ 出处**（包 A0，{@code docs/43 D5}）。
 *
 * <p>pi 的凭证解析结果同样带出处（{@code providers/anthropic.ts:18-39} 返回
 * {@code source: "stored credential"} ／ 环境变量名），java 之前把这三样压成了一个
 * 裸字符串（{@code Credentials.resolveApiKey}）⇒ 车道无从知道该放哪个头。</p>
 *
 * @param kind  凭证形态（决定车道的头形状）
 * @param value 凭证值
 * @param source 出处（{@code env:<VAR>} ／ {@code stored:<provider>[::<profile>]}），
 *               供诊断与测试断言；pi 的 {@code source} 只用于展示
 */
public record RecordedCredential(AuthKind kind, String value, String source) {
}
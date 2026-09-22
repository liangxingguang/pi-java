package com.pijava.ai.api;

/**
 * 凭证的**形态**（包 A0，{@code docs/43 D5}）—— pi 的三种凭证形态在 java 的载体。
 *
 * <p>pi 的凭证不是一个裸字符串而是一个带形态的值：</p>
 * <ul>
 *   <li>{@code anthropic.ts:18-39} 的解析结果落在 {@code auth.apiKey} 或
 *       {@code auth.headers.Authorization} 上；</li>
 *   <li>{@code api/anthropic-messages.ts:906-989} 再按值分派（Bearer /
 *       OAuth＋身份头 / x-api-key）。</li>
 * </ul>
 *
 * <p>pi-java 在<b>解析时</b>就把形态定下来（{@code auth.Credentials} 返回
 * {@code RecordedCredential}），车道只按 kind 分派，不再对值做字符串嗅探。</p>
 */
public enum AuthKind {

    /** 默认形态：{@code x-api-key} 头（Anthropic）／{@code Authorization: Bearer}（OpenAI 一家）。 */
    API_KEY,

    /** {@code ANTHROPIC_AUTH_TOKEN} 型：{@code Authorization: Bearer}，不带身份头。 */
    BEARER,

    /** {@code ANTHROPIC_OAUTH_TOKEN} 型：{@code Authorization: Bearer} ＋ claude-cli 身份头。 */
    OAUTH
}
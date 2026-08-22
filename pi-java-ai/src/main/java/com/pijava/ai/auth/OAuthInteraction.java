package com.pijava.ai.auth;

/**
 * OAuth 登录交互回调（PKCE 与 device-code 流程共用）。
 *
 * <p>{@code notify} 提示进度；{@code prompt} 请求手动输入（无控制台/远程场景
 * 返回空串或 null，调用方据此走自动回调等待）。</p>
 */
public interface OAuthInteraction {

    void notify(String message);

    String prompt(String message);
}

package com.pijava.ai.auth;

import java.util.List;

/**
 * 结构化 OAuth 事件（pi {@code auth/types.ts:137-151} 的 {@code AuthEvent}）。
 *
 * <p>docs/19 #45：保留字符串提示作为降级通道；理解结构化事件的 UI（TUI 登录
 * 对话框）覆写 {@link OAuthInteraction#notify(OAuthEvent)}，其余实现继续走
 * {@link OAuthInteraction#notify(String)}。</p>
 */
public sealed interface OAuthEvent {

    /** 信息事件里的一条链接（pi {@code AuthInfoLink}）。 */
    record Link(String url, String label) {
    }

    /** 一般信息（可带链接）。 */
    record Info(String message, List<Link> links) implements OAuthEvent {
        public Info {
            links = links == null ? List.of() : List.copyOf(links);
        }

        public Info(String message) {
            this(message, List.of());
        }
    }

    /** 浏览器授权 URL（可带说明）。 */
    record AuthUrl(String url, String instructions) implements OAuthEvent {
    }

    /**
     * 设备码登录：展示 {@code verificationUri} 与 {@code userCode}。
     *
     * <p>两个开关默认 {@code true}（pi {@code login-dialog.ts:171-172}）：
     * 用户确认后打开浏览器／复制用户码。</p>
     */
    record DeviceCode(String userCode, String verificationUri,
                      boolean openBrowserOnConfirm, boolean copyCodeOnConfirm,
                      Long intervalSeconds, Long expiresInSeconds) implements OAuthEvent {

        /** pi 默认：两个确认动作都开启。 */
        public DeviceCode(String userCode, String verificationUri,
                          Long intervalSeconds, Long expiresInSeconds) {
            this(userCode, verificationUri, true, true, intervalSeconds, expiresInSeconds);
        }
    }

    /** 进度提示。 */
    record Progress(String message) implements OAuthEvent {
    }

    /** 没有结构化消费者的实现沿用旧字符串提示。 */
    default String legacyText() {
        return switch (this) {
            case Info info -> info.message();
            case Progress progress -> progress.message();
            case AuthUrl url -> url.instructions() == null || url.instructions().isBlank()
                ? url.url() : url.url() + "\n" + url.instructions();
            case DeviceCode device -> "Open this URL in your browser and enter the code:\n  "
                + device.verificationUri() + "\nUser code: " + device.userCode();
        };
    }
}

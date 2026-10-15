package com.pijava.ai.auth;

import java.io.IOException;
import java.util.List;

/**
 * OAuth 登录交互回调（PKCE 与 device-code 流程共用）。
 *
 * <p>{@code notify} 提示进度；{@code prompt} 请求手动输入（无控制台/远程场景
 * 返回空串，调用方据此只等自动回调）；{@code select} 是 pi {@code prompt({type:"select"})}
 * 的对应面，默认实现拼编号菜单后回落 {@link #prompt}（pi CLI 同样原样读输入，
 * 非法选项由登录流自己判）。</p>
 */
public interface OAuthInteraction {

    void notify(String message);

    String prompt(String message);

    /** 登录法选项（id 为流内判别值，label 为展示文案）。 */
    record LoginOption(String id, String label) {}

    /**
     * 让用户在若干登录法中选一个，返回所选 {@link LoginOption#id()}。
     * 默认编号菜单实现；TUI 可覆写成自己的选择组件。
     */
    default String select(String message, List<LoginOption> options) throws IOException {
        var sb = new StringBuilder(message).append(System.lineSeparator());
        for (int i = 0; i < options.size(); i++) {
            sb.append("  ").append(i + 1).append(". ")
                .append(options.get(i).label()).append(System.lineSeparator());
        }
        var answer = prompt(sb.toString());
        if (answer == null || answer.isBlank()) {
            return options.isEmpty() ? answer : options.getFirst().id();
        }
        var trimmed = answer.trim();
        try {
            var index = Integer.parseInt(trimmed) - 1;
            if (index >= 0 && index < options.size()) {
                return options.get(index).id();
            }
        } catch (NumberFormatException ignored) {
            // 非编号即按 id 原文返回。
        }
        return trimmed;
    }
}

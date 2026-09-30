package com.pijava.ai.catalog;

import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * pi {@code types.ts:123} 的 {@code SessionAffinityFormat}：
 * {@code "openai" | "openai-nosession" | "openrouter"}。
 *
 * <p>会话亲和头的线格形状，挂在 {@code compat.sessionAffinityFormat} 上（包 B103）。
 * 纯常量闭集 ⇒ 用 {@code enum}（见项目编码规范）。</p>
 *
 * <p>各车道按此值产出的头（详见 {@code docs/61 §1.3}）：</p>
 * <ul>
 *   <li>{@link #OPENROUTER}：{@code x-session-id}（三车道一致）。</li>
 *   <li>{@link #OPENAI}：completions 发 {@code session_id}＋{@code x-client-request-id}
 *       ＋{@code x-session-affinity}；responses 只发前两个（无 x-session-affinity）。</li>
 *   <li>{@link #OPENAI_NOSESSION}：不发 {@code session_id}；completions 发
 *       {@code x-client-request-id}＋{@code x-session-affinity}，responses 只发前者。</li>
 * </ul>
 */
public enum SessionAffinityFormat {
    OPENAI,
    OPENAI_NOSESSION,
    OPENROUTER;

    /** pi 的线格字面量（snake/连字符形）。 */
    @JsonValue
    public String wireName() {
        return switch (this) {
            case OPENAI -> "openai";
            case OPENAI_NOSESSION -> "openai-nosession";
            case OPENROUTER -> "openrouter";
        };
    }

    /**
     * 解析 compat 侧的线格值。未知/空 ⇒ {@link Optional#empty()}（≙ pi 的 {@code undefined}
     * ⇒ 交回车道探测缺省），与 {@link CacheRetention#parse} 同一口径。
     */
    public static Optional<SessionAffinityFormat> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "openai" -> Optional.of(OPENAI);
            case "openai-nosession" -> Optional.of(OPENAI_NOSESSION);
            case "openrouter" -> Optional.of(OPENROUTER);
            default -> Optional.empty();
        };
    }
}

package com.pijava.ai.identity;

import java.util.List;

/**
 * Who requests are attributed to.
 *
 * <p>pi {@code ClientAttribution}（{@code packages/ai/src/utils/identity.ts:13-21}）：
 * app 名、app URL、可选类别。字段逐字映射到各 provider 的归因头。</p>
 *
 * @param name       app 名（OpenRouter 标题、NVIDIA origin、OpenCode client）
 * @param url        app URL（OpenRouter {@code HTTP-Referer}）
 * @param categories app 类别；{@code null} 与空列表都省略 {@code X-OpenRouter-Categories}
 */
public record ClientAttribution(String name, String url,
                                @org.jspecify.annotations.Nullable List<String> categories) {

    /** pi 的 {@code PI_ATTRIBUTION}（{@code identity.ts:24}）逐字。 */
    public static final ClientAttribution PI = new ClientAttribution(
        "pi", "https://pi.dev", List.of("cli-agent"));

    public ClientAttribution {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(url, "url");
        categories = categories == null ? null : List.copyOf(categories);
    }

    /** 便捷构造：无类别。 */
    public ClientAttribution(String name, String url) {
        this(name, url, null);
    }
}

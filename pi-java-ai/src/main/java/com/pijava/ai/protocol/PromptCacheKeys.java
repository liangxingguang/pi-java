package com.pijava.ai.protocol;

/**
 * pi {@code clampOpenAIPromptCacheKey}（{@code api/openai-prompt-cache.ts:1-9}）的移植。
 *
 * <p>OpenAI 系 {@code prompt_cache_key} 的最大长度与截断：按 Unicode <b>码点</b>
 * （不是 UTF-16 {@code char}）取前 {@link #MAX_LENGTH} 个。用码点是为了与 pi 的
 * {@code Array.from(key)} 逐字一致 —— 一个 emoji（代理对）在两种口径下计数值不同。</p>
 */
public final class PromptCacheKeys {

    /** pi {@code OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH}（{@code openai-prompt-cache.ts:1}）。 */
    public static final int MAX_LENGTH = 64;

    private PromptCacheKeys() {}

    /**
     * @param key 原始键；{@code null} ⇒ {@code null}（pi 的 {@code undefined} 透传）
     * @return 不超限原样返回；否则前 64 个码点拼成的串
     */
    public static String clamp(String key) {
        if (key == null) {
            return null;
        }
        var sb = new StringBuilder();
        var count = new int[]{0};
        key.codePoints().takeWhile(cp -> count[0]++ < MAX_LENGTH).forEach(sb::appendCodePoint);
        return sb.toString();
    }
}

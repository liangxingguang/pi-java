package com.pijava.ai.protocol;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 B103：{@link PromptCacheKeys} 的钉子 —— 对齐 pi {@code clampOpenAIPromptCacheKey}
 * （{@code openai-prompt-cache.ts:1-9}），按 Unicode <b>码点</b>截前 64。
 */
class PromptCacheKeysTest {

    @Test
    void nullPassesThrough() {
        assertThat(PromptCacheKeys.clamp(null)).isNull();
    }

    @Test
    void shortKeysReturnedAsIs() {
        assertThat(PromptCacheKeys.clamp("abc")).isEqualTo("abc");
    }

    @Test
    void exactly64CodePointsReturnedAsIs() {
        var key = "a".repeat(64);
        assertThat(PromptCacheKeys.clamp(key)).isEqualTo(key);
    }

    @Test
    void truncatesTo64CodePoints() {
        var key = "a".repeat(70);
        var clamped = PromptCacheKeys.clamp(key);
        assertThat(clamped).hasSize(64);
        assertThat(clamped.codePoints().count()).isEqualTo(64);
    }

    @Test
    void countsCodePointsNotUtf16Chars() {
        // 70 个 emoji（每个是代理对＝2 char，但 1 码点）⇒ 截成 64 个 emoji，
        // 按码点应当 64 码点、128 char。若错按 char 截会在此变红。
        var emoji = "😀".repeat(70);
        var clamped = PromptCacheKeys.clamp(emoji);
        assertThat(clamped.codePoints().count()).isEqualTo(64);
        assertThat(clamped).hasSize(128);
    }
}

package com.pijava.tui.component;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L7 W3（R31）：Markdown 顶层块缓存语义——同源复用、流式仅末块重渲染、
 * 主题/默认样式失配、帧界裁剪与全量失效。
 */
class MarkdownRenderCacheTest {

    private static final MarkdownTheme THEME = theme(Color.hex("#FFFF00"));
    private static final MarkdownTheme OTHER = theme(Color.hex("#00FFFF"));

    private static MarkdownTheme theme(Color heading) {
        return new MarkdownTheme(
            heading,                    // heading
            Color.hex("#0000FF"),       // link
            Color.hex("#888888"),       // linkUrl
            Color.hex("#FF00FF"),       // code
            Color.hex("#00FF00"),       // codeBlock
            Color.hex("#777777"),       // codeBlockBorder
            Color.hex("#666666"),       // quote
            Color.hex("#555555"),       // quoteBorder
            Color.hex("#444444"),       // hr
            Color.hex("#FF00FF"),       // listBullet
            "  ");
    }

    private final MarkdownRenderCache cache = new MarkdownRenderCache();

    @Test
    void secondRenderReusesEveryBlock() {
        cache.beginFrame();
        var first = cache.render("a\n\nb", THEME, Style.EMPTY);
        cache.endFrame();
        assertThat(first).hasSize(3);   // "a" / "" / "b"
        int rendered = cache.renderedBlockCount();
        assertThat(rendered).isEqualTo(2);

        cache.beginFrame();
        var second = cache.render("a\n\nb", THEME, Style.EMPTY);
        cache.endFrame();

        assertThat(cache.renderedBlockCount()).isEqualTo(rendered);
        assertThat(cache.cacheHitCount()).isEqualTo(2);
        assertThat(second).isEqualTo(first);
        assertThat(cache.cachedSourceCount()).isEqualTo(1);
    }

    @Test
    void streamingAppendRendersOnlyTheChangedBlock() {
        cache.beginFrame();
        cache.render("a\n\nb", THEME, Style.EMPTY);
        cache.endFrame();
        int rendered = cache.renderedBlockCount();

        cache.beginFrame();
        cache.render("a\n\nb c", THEME, Style.EMPTY);
        cache.endFrame();

        // 首块命中，末块重渲染；旧 "b" 块被帧尾裁剪。
        assertThat(cache.cacheHitCount()).isEqualTo(1);
        assertThat(cache.renderedBlockCount()).isEqualTo(rendered + 1);
        assertThat(cache.cachedBlockCount()).isEqualTo(2);
    }

    @Test
    void themeChangeRerendersAllBlocks() {
        cache.beginFrame();
        cache.render("a\n\nb", THEME, Style.EMPTY);
        cache.endFrame();

        cache.beginFrame();
        cache.render("a\n\nb", OTHER, Style.EMPTY);
        cache.endFrame();

        assertThat(cache.renderedBlockCount()).isEqualTo(4);
        assertThat(cache.cacheHitCount()).isZero();
    }

    @Test
    void defaultStyleChangeRerendersAllBlocks() {
        cache.beginFrame();
        cache.render("a\n\nb", THEME, Style.EMPTY);
        cache.endFrame();

        cache.beginFrame();
        cache.render("a\n\nb", THEME, Style.EMPTY.italic());
        cache.endFrame();

        assertThat(cache.renderedBlockCount()).isEqualTo(4);
    }

    @Test
    void endFrameDropsBlocksAndSourcesNotUsed() {
        cache.beginFrame();
        cache.render("a\n\nb", THEME, Style.EMPTY);
        cache.endFrame();
        assertThat(cache.cachedBlockCount()).isEqualTo(2);

        cache.beginFrame();
        cache.endFrame();

        assertThat(cache.cachedBlockCount()).isZero();
        assertThat(cache.cachedSourceCount()).isZero();
    }

    @Test
    void invalidateClearsEverything() {
        cache.render("a", THEME, Style.EMPTY);
        cache.invalidate();

        assertThat(cache.cachedBlockCount()).isZero();
        assertThat(cache.cachedSourceCount()).isZero();
    }

    @Test
    void blankInputProducesNoLines() {
        assertThat(cache.render("", THEME, Style.EMPTY)).isEmpty();
        assertThat(cache.render(null, THEME, Style.EMPTY)).isEmpty();
    }

    @Test
    void commentOnlySourceReturnsOneBlankLine() {
        var out = cache.render("<!-- c -->", THEME, Style.EMPTY);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).markup()).isEmpty();
    }
}

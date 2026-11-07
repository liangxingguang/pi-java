package com.pijava.coding.agent.export;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W8（docs/25 §9；pi {@code test/export-html-cjk.test.ts}，issue #10154）：
 * 紧贴 CJK 标点的强调（{@code **中文。**}）必须渲染为 {@code <strong>}，全角标点不被吞。
 *
 * <p><b>D225</b>：Java 用自有 {@code MarkdownToHtml}，不引入 JS 的
 * {@code marked-cjk-friendly}；判定以渲染结果为准，不以字节对等为准。</p>
 */
class HtmlExportCjkTest {

    @Test
    void emphasisNextToCjkPunctuationRenders() {
        assertThat(MarkdownToHtml.render("**这是测试。**后面"))
            .contains("<strong>这是测试。</strong>").contains("后面");
        assertThat(MarkdownToHtml.render("**一句话说清：**一台机器"))
            .contains("<strong>一句话说清：</strong>").contains("一台机器");
        assertThat(MarkdownToHtml.render("**要点到此为止。**20 小时"))
            .contains("<strong>要点到此为止。</strong>").contains("20 小时");
        assertThat(MarkdownToHtml.render("字**「引用」**字"))
            .contains("<strong>「引用」</strong>");
        assertThat(MarkdownToHtml.render("*斜体。*后面"))
            .contains("<em>斜体。</em>").contains("后面");
    }

    @Test
    void fullWidthPunctuationIsNotSwallowed() {
        assertThat(MarkdownToHtml.render("中文，标点。结束！"))
            .contains("中文，标点。结束！");
    }

    /** pi 的非 CJK 臂保持 CommonMark flanking（`**end.**Then` 不算强调）。 */
    @Test
    void nonCjkFlankingRulesUnchanged() {
        assertThat(MarkdownToHtml.render("**end.**Then next"))
            .doesNotContain("<strong>");
    }
}

package com.pijava.tui.util.latex;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatexRendererTest {

    @Test
    void rendersGreekAndRelationsInline() {
        assertThat(LatexRenderer.render("\\alpha + \\beta = \\gamma", false))
            .isEqualTo("α + β = γ");
        assertThat(LatexRenderer.render("x \\le y", false)).isEqualTo("x ≤ y");
    }

    @Test
    void rendersFractionInlineWithPiParenthesisation() {
        assertThat(LatexRenderer.render("\\frac{a}{b}", false)).isEqualTo("a/b");
        assertThat(LatexRenderer.render("\\frac{a+b}{c}", false)).isEqualTo("(a+b)/c");
        assertThat(LatexRenderer.render("\\frac{x}{y+1}", false)).isEqualTo("x/(y+1)");
    }

    @Test
    void rendersRoots() {
        assertThat(LatexRenderer.render("\\sqrt{x}", false)).isEqualTo("√x");
        assertThat(LatexRenderer.render("\\sqrt{x+1}", false)).isEqualTo("√(x+1)");
        assertThat(LatexRenderer.render("\\sqrt[3]{x}", false)).isEqualTo("³√x");
    }

    @Test
    void rendersUnicodeScriptsWhenAvailable() {
        assertThat(LatexRenderer.render("x^2", false)).isEqualTo("x²");
        assertThat(LatexRenderer.render("x_1", false)).isEqualTo("x₁");
        assertThat(LatexRenderer.render("a_i", false)).isEqualTo("aᵢ");
    }

    @Test
    void fallsBackToBracedScriptsWhenUnmappable() {
        assertThat(LatexRenderer.render("x^{\\alpha}", false)).isEqualTo("x^{α}");
        assertThat(LatexRenderer.render("x^{n+1}", false)).isEqualTo("xⁿ⁺¹");
    }

    @Test
    void displayFractionsStackVertically() {
        String rendered = LatexRenderer.render("\\frac{a}{b}", true);
        assertThat(rendered).isNotNull();
        assertThat(rendered.split("\n")).hasSize(3);
        assertThat(rendered).contains("─");
    }

    @Test
    void rendersMatricesAsGrid() {
        String rendered = LatexRenderer.render(
            "\\begin{matrix} a & b \\\\ c & d \\end{matrix}", false);
        assertThat(rendered).isNotNull();
        assertThat(rendered.split("\n")).hasSize(2);
        assertThat(rendered).contains("a").contains("b").contains("c").contains("d");
    }

    @Test
    void unknownCommandIsUnsupported() {
        assertThat(LatexRenderer.render("\\notacommand{x}", false)).isNull();
    }

    @Test
    void inlineScannerSkipsEscapedDollarAndKeepsUnsupportedRaw() {
        assertThat(LatexInline.inline("cost \\$5 and $x^2$"))
            .isEqualTo("cost $5 and x²");
        assertThat(LatexInline.inline("$\\notacommand{x}$"))
            .isEqualTo("$\\notacommand{x}$");
    }

    @Test
    void blockScannerRendersOnlyFullDisplayMath() {
        assertThat(LatexInline.block("$$\\frac{1}{2}$$")).hasSize(3);
        assertThat(LatexInline.block("before $$x$$ after")).isEmpty();
    }

    @Test
    void textWrappersAndAccents() {
        assertThat(LatexRenderer.render("\\text{hello}", false)).isEqualTo("hello");
        assertThat(LatexRenderer.render("\\hat{x}", false)).isEqualTo("x\u0302");
    }

    @Test
    void spacingAndIgnoredCommandsDrop() {
        assertThat(LatexRenderer.render("a\\,b", false)).isEqualTo("a b");
        assertThat(LatexRenderer.render("\\left( x \\right)", false)).isEqualTo("( x )");
    }
}

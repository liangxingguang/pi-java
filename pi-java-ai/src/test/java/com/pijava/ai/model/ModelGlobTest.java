package com.pijava.ai.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * minimatch 10.2.6 子集边界夹具（docs/12 §3.2 清单逐条）。语义来源＝npm tarball
 * {@code dist/commonjs}（ast.js/brace-expressions.js/index.js）静态阅读。
 */
class ModelGlobTest {

    @Test
    void isGlobDetectsTriggerCharacters() {
        assertThat(ModelGlob.isGlob("a*")).isTrue();
        assertThat(ModelGlob.isGlob("a?b")).isTrue();
        assertThat(ModelGlob.isGlob("[ab]")).isTrue();
        assertThat(ModelGlob.isGlob("plain:high")).isFalse();
        assertThat(ModelGlob.isGlob("provider/model")).isFalse();
    }

    @Test
    void starDoesNotCrossSlashAndCoalesces() {
        assertThat(ModelGlob.matches("a/b", "a/*")).isTrue();
        assertThat(ModelGlob.matches("a/b/c", "a/*")).isFalse();
        assertThat(ModelGlob.matches("abc", "*")).isTrue();
        assertThat(ModelGlob.matches("a/b", "*")).isFalse();
        assertThat(ModelGlob.matches("", "*")).isFalse();           // noEmpty：整段纯星至少一字符
        assertThat(ModelGlob.matches("axxxb", "a***b")).isTrue();  // 连续星合并
        assertThat(ModelGlob.matches("a/b", "a***b")).isFalse();
    }

    @Test
    void questionMarkIsOneNonSlashCharacter() {
        assertThat(ModelGlob.matches("abc", "a?c")).isTrue();
        assertThat(ModelGlob.matches("ac", "a?c")).isFalse();
        assertThat(ModelGlob.matches("a/c", "a?c")).isFalse();
    }

    @Test
    void globstarOnlyAsWholeSegment() {
        assertThat(ModelGlob.matches("x", "**")).isTrue();
        assertThat(ModelGlob.matches("x/y/z", "**")).isTrue();
        assertThat(ModelGlob.matches("a/b", "a/**/b")).isTrue();
        assertThat(ModelGlob.matches("a/x/b", "a/**/b")).isTrue();
        assertThat(ModelGlob.matches("a/x/y/b", "a/**/b")).isTrue();
        assertThat(ModelGlob.matches("a/x/c", "a/**/b")).isFalse();
        assertThat(ModelGlob.matches("x/b", "**/b")).isTrue();
        assertThat(ModelGlob.matches("b", "**/b")).isTrue();
        assertThat(ModelGlob.matches("a/b/c", "a/**")).isTrue();
        assertThat(ModelGlob.matches("axxb", "a**b")).isTrue();    // 段内 ** 等同 a*b
        assertThat(ModelGlob.matches("a/b", "a**b")).isFalse();
    }

    @Test
    void globstarDoesNotSwallowLeadingDotSegments() {
        assertThat(ModelGlob.matches(".x", "**")).isFalse();
        assertThat(ModelGlob.matches("a/.x/b", "a/**/b")).isFalse();
        assertThat(ModelGlob.matches("a/x/.y", "a/**")).isFalse();
        assertThat(ModelGlob.matches(".b", "**/b")).isFalse();
    }

    @Test
    void characterClasses() {
        assertThat(ModelGlob.matches("1", "[1m]")).isTrue();
        assertThat(ModelGlob.matches("m", "[1m]")).isTrue();
        assertThat(ModelGlob.matches("x", "[1m]")).isFalse();
        assertThat(ModelGlob.matches("x", "[!1]")).isTrue();
        assertThat(ModelGlob.matches("1", "[!1]")).isFalse();
        assertThat(ModelGlob.matches("x", "[^1]")).isTrue();
        assertThat(ModelGlob.matches("b", "[a-c]")).isTrue();
        assertThat(ModelGlob.matches("d", "[a-c]")).isFalse();
        assertThat(ModelGlob.matches("]", "[]]")).isTrue();        // []] ＝字面 ]
        assertThat(ModelGlob.matches("-", "[-]")).isTrue();
        // [a-] 是单字符类（成员 a 与字面横线），不是 "a-" 两字符。
        assertThat(ModelGlob.matches("a", "[a-]")).isTrue();
        assertThat(ModelGlob.matches("-", "[a-]")).isTrue();
        assertThat(ModelGlob.matches("a-", "[a-]")).isFalse();
        assertThat(ModelGlob.matches("x", "[x]")).isTrue();        // 单字符类等义字面
    }

    @Test
    void unclosedBracketIsLiteral() {
        assertThat(ModelGlob.matches("model[1", "model[1")).isTrue();
        assertThat(ModelGlob.matches("model1", "model[1")).isFalse();
    }

    @Test
    void emptyOrReversedClassPoisonMatchesNothing() {
        // 逆序范围两端丢弃 ⇒ 空类毒化（pi parseClass 产 "$."）。
        assertThat(ModelGlob.matches("x", "x[a-1]y")).isFalse();
    }

    @Test
    void regexMetaCharactersAreLiteral() {
        assertThat(ModelGlob.matches("a.b", "a.b")).isTrue();
        assertThat(ModelGlob.matches("axb", "a.b")).isFalse();
        assertThat(ModelGlob.matches("(x)", "(x)")).isTrue();
        assertThat(ModelGlob.matches("xx", "(x)")).isFalse();
        assertThat(ModelGlob.matches("a+b", "a+b")).isTrue();
        assertThat(ModelGlob.matches("aaab", "a+b")).isFalse();
    }

    @Test
    void bracesAreLiteralNotExpansion() {
        // docs/12 §4③：不做 brace 展开 ⇒ 只匹配字面花括号（id 字符集外 ⇒ 实用上 no-match）。
        assertThat(ModelGlob.matches("{a,b}", "{a,b}")).isTrue();
        assertThat(ModelGlob.matches("a", "{a,b}")).isFalse();
        assertThat(ModelGlob.matches("b", "{a,b}")).isFalse();
    }

    @Test
    void escapes() {
        assertThat(ModelGlob.matches("*", "\\*")).isTrue();
        assertThat(ModelGlob.matches("a", "\\*")).isFalse();
        assertThat(ModelGlob.matches("a", "\\a")).isTrue();        // 非元字符：反斜杠丢弃
    }

    @Test
    void dotGateAndLiteralDots() {
        assertThat(ModelGlob.matches(".foo", "*")).isFalse();
        assertThat(ModelGlob.matches(".x", "?x")).isFalse();
        assertThat(ModelGlob.matches(".ab", "[ab]*")).isFalse();
        assertThat(ModelGlob.matches(".x", ".x")).isTrue();        // 显式字面点不拦
        assertThat(ModelGlob.matches("a/.b", "a/[!a]?*")).isFalse();
    }

    @Test
    void nocaseAndFullStringAnchoring() {
        assertThat(ModelGlob.matches("ABC", "abc")).isTrue();
        assertThat(ModelGlob.matches("AbC", "a?c")).isTrue();
        assertThat(ModelGlob.matches("x/y", "x")).isFalse();       // 全串锚定
        assertThat(ModelGlob.matches("ax", "x")).isFalse();
    }
}

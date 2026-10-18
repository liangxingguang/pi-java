package com.pijava.coding.agent.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B236（docs/11 §1.3/§4.1）：pi {@code createToolNameMatcher}/{@code toolPatternRegExp}/
 * {@code isMcpToolName}（core/mcp-servers.ts:11-42）对位 —— 只有 {@code *} 一条模式规则，
 * 全串锚定，其余字符字面化（不是 minimatch）。
 */
class ToolNameMatcherTest {

    @Test
    void exactNamesMatchExactly() {
        var matcher = ToolNameMatcher.create(List.of("read", "grep"));
        assertThat(matcher.test("read")).isTrue();
        assertThat(matcher.test("grep")).isTrue();
        assertThat(matcher.test("bash")).isFalse();
        // 全串锚定：前缀/子串不中。
        assertThat(matcher.test("gre")).isFalse();
        assertThat(matcher.test("grep2")).isFalse();
    }

    @Test
    void starPatternsMatchAnyCharacters() {
        var matcher = ToolNameMatcher.create(List.of("gr*", "*sh", "mcp__radius__*"));
        assertThat(matcher.test("grep")).isTrue();
        assertThat(matcher.test("bash")).isTrue();
        // `*sh` 只中以 sh 结尾的：bash 中，powershell（以 ell 结尾）不中。
        assertThat(matcher.test("powershell")).isFalse();
        assertThat(matcher.test("mcp__radius__search")).isTrue();
        assertThat(matcher.test("mcp__other__search")).isFalse();
        assertThat(matcher.test("read")).isFalse();
        // 锚定：模式 `gr*` 不吃中段命中。
        assertThat(matcher.test("xgrep")).isFalse();
    }

    @Test
    void loneStarAndDoubleStarMatchEverything() {
        var lone = ToolNameMatcher.create(List.of("*"));
        assertThat(lone.test("read")).isTrue();
        assertThat(lone.test("mcp__x__y")).isTrue();
        var doubleStar = ToolNameMatcher.create(List.of("**"));
        assertThat(doubleStar.test("grep")).isTrue();
    }

    @Test
    void regexMetacharactersAreLiteral() {
        // `a.c*` 只中字面 `a.c` 开头的名，`.` 不作任意字符。
        var matcher = ToolNameMatcher.create(List.of("a.c*"));
        assertThat(matcher.test("a.c")).isTrue();
        assertThat(matcher.test("a.config")).isTrue();
        assertThat(matcher.test("abc")).isFalse();
        assertThat(matcher.test("aXc")).isFalse();
    }

    @Test
    void mcpToolNamesIncludePrefixAndResourceTrio() {
        assertThat(ToolNameMatcher.isMcpToolName("mcp__radius__search")).isTrue();
        assertThat(ToolNameMatcher.isMcpToolName("mcp__")).isTrue();
        assertThat(ToolNameMatcher.isMcpToolName("list_mcp_resources")).isTrue();
        assertThat(ToolNameMatcher.isMcpToolName("list_mcp_resource_templates")).isTrue();
        assertThat(ToolNameMatcher.isMcpToolName("read_mcp_resource")).isTrue();
        // 反例：内建与「含 mcp__ 但不在开头」。
        assertThat(ToolNameMatcher.isMcpToolName("read")).isFalse();
        assertThat(ToolNameMatcher.isMcpToolName("bash")).isFalse();
        assertThat(ToolNameMatcher.isMcpToolName("xmcp__a")).isFalse();
    }
}

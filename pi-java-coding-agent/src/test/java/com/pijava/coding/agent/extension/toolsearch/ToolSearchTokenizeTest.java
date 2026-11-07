package com.pijava.coding.agent.extension.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ToolNamespace;
import com.pijava.coding.agent.extension.ExtensionToolInfo;

import org.junit.jupiter.api.Test;

/**
 * L5 W7（docs/25 §8；pi {@code tool-search/tool.ts:71-116}＋{@code test/tool-search.test.ts:18-52}）：
 * Unicode 分词（NFC、camel/缩写边界、非 ASCII 用分词器）与「名称不再进 text」
 * 的文档构造。
 */
class ToolSearchTokenizeTest {

    @Test
    void splitsCamelAndSnakeDropsStopWordsAndFoldsPlurals() {
        assertThat(ToolSearchTokenizer.tokenize("listIssues for the GitHub_repo"))
            .containsExactly("list", "issue", "git", "hub", "repo");
        assertThat(ToolSearchTokenizer.tokenize("searches queries HTTPServer"))
            .containsExactly("search", "query", "http", "server");
        assertThat(ToolSearchTokenizer.tokenize("repo.issues/search:queries"))
            .containsExactly("repo", "issue", "search", "query");
    }

    /**
     * pi test/tool-search.test.ts:33-42 的八条 Unicode 分词断言（JDK 口径）。
     *
     * <p><b>D224</b>：只有「无空格连写」的脚本是方言——浏览器 {@code Intl.Segmenter}
     * 用词典把「搜索仓库问题」/「ค้นหาเอกสาร」切成词，JDK 的 UAX#29 只给整段。
     * 此处钉 JDK 行为；空格/标点分隔的脚本（日文、韩文、西里尔、拉丁）逐词一致。</p>
     */
    @Test
    void segmentsUnicodeWords() {
        assertThat(ToolSearchTokenizer.tokenize("搜索仓库问题")).containsExactly("搜索仓库问题");
        assertThat(ToolSearchTokenizer.tokenize("搜索 仓库 问题")).containsExactly("搜索", "仓库", "问题");
        assertThat(ToolSearchTokenizer.tokenize("文書を検索")).containsExactly("文書", "を", "検索");
        assertThat(ToolSearchTokenizer.tokenize("문서 검색")).containsExactly("문서", "검색");
        assertThat(ToolSearchTokenizer.tokenize("ค้นหาเอกสาร")).containsExactly("ค้นหาเอกสาร");
        assertThat(ToolSearchTokenizer.tokenize("ค้นหา เอกสาร")).containsExactly("ค้นหา", "เอกสาร");
        assertThat(ToolSearchTokenizer.tokenize("Поиск документов"))
            .containsExactly("поиск", "документов");
        assertThat(ToolSearchTokenizer.tokenize("Éléments cafés"))
            .containsExactly("éléments", "cafés");
    }

    @Test
    void preservesNonAsciiWordsAndSplitsMixedIdentifiers() {
        assertThat(ToolSearchTokenizer.tokenize("搜索GitHub_repoIssues résumé cafés"))
            .containsExactly("搜索", "git", "hub", "repo", "issue", "résumé", "cafés");
    }

    @Test
    void normalizesCanonicallyEquivalentText() {
        // "CAFÉ" 与分解形 "café" 归一到同一 NFC 词。
        assertThat(ToolSearchTokenizer.tokenize("CAFÉ café"))
            .containsExactly("café", "café");
    }

    @Test
    void ignoresWhitespacePunctuationAndEmoji() {
        assertThat(ToolSearchTokenizer.tokenize(" \t，。!? 😀 ")).isEmpty();
    }

    @Test
    void mcpNamesSplitIntoIdentifierWords() {
        assertThat(ToolSearchTokenizer.tokenize("mcp__app__thread_read"))
            .containsExactly("mcp", "app", "thread", "read");
        // 连字符与下划线等价（pi normalizeName 的注释同旨）。
        assertThat(ToolSearchTokenizer.tokenize("dev-radius"))
            .isEqualTo(ToolSearchTokenizer.tokenize("dev_radius"));
    }

    @Test
    void documentKeepsNamespaceOutOfText() {
        var info = new ExtensionToolInfo("mcp__peekaboo__app", "Launch an app", List.of(),
            com.pijava.agent.tool.ToolExposure.DEFERRED,
            Map.of("type", "object"), null);
        var namespace = new ToolNamespace("mcp__peekaboo", null,
            "Peekaboo controls macOS apps", "Use app names.");

        var document = ToolSearchDocument.of(info, namespace);

        // pi createToolSearchDocument（:119-129）：text 只含描述＋schema；名称与
        // 命名空间各走独立字段。
        assertThat(document.text()).isEqualTo("Launch an app");
        assertThat(document.namespaceText()).isEqualTo("mcp__peekaboo Peekaboo controls macOS apps Use app names.");
    }

    @Test
    void documentWithoutNamespaceHasEmptyNamespaceText() {
        var info = new ExtensionToolInfo("read", "Read a file", List.of(),
            com.pijava.agent.tool.ToolExposure.DEFERRED, Map.of("type", "object"), null);

        assertThat(ToolSearchDocument.of(info, null).namespaceText()).isEmpty();
    }

    @Test
    void schemaDescriptionsAndPropertyNamesStayInText() {
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of(
            "state", Map.of("type", "string", "description", "open or closed")));
        var info = new ExtensionToolInfo("list_issues", "List issues", List.of(),
            com.pijava.agent.tool.ToolExposure.DEFERRED, schema, null);

        assertThat(ToolSearchDocument.of(info, null).text())
            .isEqualTo("List issues state open or closed");
    }
}

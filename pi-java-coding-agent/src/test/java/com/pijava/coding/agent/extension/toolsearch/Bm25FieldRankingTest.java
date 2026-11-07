package com.pijava.coding.agent.extension.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ToolExposure;
import com.pijava.agent.tool.ToolNamespace;
import com.pijava.coding.agent.extension.ExtensionToolInfo;

import org.junit.jupiter.api.Test;

/**
 * L5 W7（docs/25 §8；pi {@code tool.ts:136-256}＋{@code test/tool-search.test.ts:55-180}）：
 * 分字段 BM25——名称权重 3、命名空间权重 0.5、长度只归一描述；排序键依次
 * exact、covered、score；本地名全命中加 fullNameBonus。
 */
class Bm25FieldRankingTest {

    private static ExtensionToolInfo tool(String name, String description) {
        return tool(name, description, Map.of("type", "object"));
    }

    private static ExtensionToolInfo tool(String name, String description,
                                          Map<String, Object> parameters) {
        return new ExtensionToolInfo(name, description, List.of(), ToolExposure.DEFERRED,
            parameters, null);
    }

    private static ToolSearchDocument doc(String name, String description) {
        return ToolSearchDocument.of(tool(name, description), null);
    }

    private static List<String> names(List<Bm25Ranker.Match> matches) {
        return matches.stream().map(Bm25Ranker.Match::name).toList();
    }

    // ── 基础排序（pi :61-70）───────────────────────────────────────

    private static final List<ToolSearchDocument> GITHUB_DOCS = List.of(
        ToolSearchDocument.of(tool("mcp__github__list_issues", "List issues in a repository.",
            Map.of("type", "object", "properties", Map.of(
                "state", Map.of("type", "string", "description", "open or closed")))), null),
        doc("mcp__github__create_pull_request", "Open a pull request."),
        doc("mcp__linear__search_issues", "Search Linear issues by text."),
        doc("mcp__docs__search", "Search the documentation."));

    @Test
    void ranksByRelevanceAndRespectsTheLimit() {
        var ranker = new Bm25Ranker();

        assertThat(names(ranker.rank("issue", GITHUB_DOCS, 8)))
            .containsExactly("mcp__linear__search_issues", "mcp__github__list_issues");
        assertThat(names(ranker.rank("pull requests", GITHUB_DOCS, 8)).getFirst())
            .isEqualTo("mcp__github__create_pull_request");
        assertThat(ranker.rank("search", GITHUB_DOCS, 1)).hasSize(1);
        // 属性名与描述可检索。
        assertThat(names(ranker.rank("closed", GITHUB_DOCS, 8)))
            .containsExactly("mcp__github__list_issues");
    }

    @Test
    void unknownAndEmptyQueriesReturnNothing() {
        var ranker = new Bm25Ranker();

        assertThat(ranker.rank("kubernetes", GITHUB_DOCS, 8)).isEmpty();
        assertThat(ranker.rank("the", GITHUB_DOCS, 8)).isEmpty();
        // 无同义词："tickets" 找不到 "issues"。
        assertThat(ranker.rank("tickets", GITHUB_DOCS, 8)).isEmpty();
    }

    // ── issue #11331：整名查询、名称权重、覆盖数、本地名奖励 ──────────

    @Test
    void queryEqualToNameRanksThatToolFirst() {
        var named = List.of(
            doc("mcp__app__queue_read", "Read a queued message of this thread."),
            doc("mcp__dev-app__thread_read", "Read durable state and a paginated timeline "
                + "from any thread. The default view returns user messages, assistant "
                + "messages and plans. Continue with afterPosition until hasMore is false."));
        var ranker = new Bm25Ranker();

        for (var query : List.of("thread_read", "dev_app__thread_read",
                "mcp__dev-app__thread_read", "Thread_Read", "thread read")) {
            assertThat(names(ranker.rank(query, named, 1)))
                .as("query=%s", query)
                .containsExactly("mcp__dev-app__thread_read");
        }
        // 部分名仍按 BM25。
        assertThat(names(ranker.rank("read", named, 1))).containsExactly("mcp__app__queue_read");
    }

    @Test
    void nameMatchBeatsMentionsInDescription() {
        var documents = List.of(
            doc("mcp__ci__status", "Deploy status: last deploy, deploy log, deploy errors."),
            doc("mcp__ci__deploy_release", "Start a release of the current branch to an "
                + "environment, run the migrations, switch traffic over, and wait until the "
                + "new version is live and healthy."));

        assertThat(names(new Bm25Ranker().rank("deploy", documents, 1)))
            .containsExactly("mcp__ci__deploy_release");
    }

    @Test
    void queryContainingWholeLocalNameRanksFirst() {
        var peekaboo = new ToolNamespace("mcp__peekaboo", null,
            null, "Peekaboo controls macOS apps by app name.");
        var documents = List.of(
            peekabooDoc("mcp__peekaboo__see",
                "Capture the app window of the peekaboo app and describe the app.", peekaboo),
            peekabooDoc("mcp__peekaboo__menu", "Click a menu item of an app.", peekaboo),
            peekabooDoc("mcp__peekaboo__app", "Launch, quit, relaunch, focus, hide, or switch "
                + "applications. Targets an application by name, bundle identifier, or process "
                + "ID, and can wait until it is ready.", peekaboo),
            doc("mcp__app__queue_read", "Read a queued message of this thread."),
            doc("mcp__dev-app__thread_read", "Read durable state and a paginated timeline from "
                + "any thread. The default view returns user messages, assistant messages and plans."));
        var ranker = new Bm25Ranker();

        assertThat(names(ranker.rank("peekaboo app", documents, 1)))
            .containsExactly("mcp__peekaboo__app");
        assertThat(names(ranker.rank("open the peekaboo app tool", documents, 1)))
            .containsExactly("mcp__peekaboo__app");
        assertThat(names(ranker.rank("read thread_read state", documents, 1)))
            .containsExactly("mcp__dev-app__thread_read");
        // 没有整名命中时描述提及决定排序。
        assertThat(names(ranker.rank("describe window", documents, 1)))
            .containsExactly("mcp__peekaboo__see");
    }

    @Test
    void moreCoveredQueryTermsWinWhateverTheScore() {
        var documents = List.of(
            doc("mcp__peekaboo__app", "Launch, quit, focus, or switch applications by name or bundle identifier."),
            doc("mcp__peekaboo__see", "Capture the app window and describe the UI elements of the app."),
            doc("mcp__peekaboo__window", "Move, resize, or close a window of an app."),
            doc("mcp__peekaboo__image", "Take a screenshot of the screen, an app, or a window."));
        var ranker = new Bm25Ranker();

        assertThat(names(ranker.rank("screenshot window", documents, 1)))
            .containsExactly("mcp__peekaboo__image");
        assertThat(names(ranker.rank("describe app", documents, 1)))
            .containsExactly("mcp__peekaboo__see");
        // 整名仍排第一。
        assertThat(names(ranker.rank("window", documents, 1)))
            .containsExactly("mcp__peekaboo__window");
    }

    @Test
    void namespaceTextIsASeparateWeakerField() {
        var document = ToolSearchDocument.of(tool("mcp__x__run", "Run it."),
            new ToolNamespace("mcp__x", null, "Kubernetes cluster tools", null));

        assertThat(names(new Bm25Ranker().rank("kubernetes", List.of(document), 8)))
            .containsExactly("mcp__x__run");
    }

    @Test
    void unicodeQueryMatchesLocalizedDescription() {
        var localized = doc("localized", "Rechercher un café");

        assertThat(names(new Bm25Ranker().rank("café",
            List.of(localized), 8))).containsExactly("localized");
    }

    @Test
    void tiesKeepDocumentOrder() {
        var documents = List.of(
            doc("a__reader", "Read one thing."),
            doc("b__reader", "Read one thing."));

        assertThat(names(new Bm25Ranker().rank("read", documents, 8)))
            .containsExactly("a__reader", "b__reader");
    }

    private static ToolSearchDocument peekabooDoc(String name, String description,
                                                  ToolNamespace namespace) {
        return ToolSearchDocument.of(tool(name, description), namespace);
    }
}

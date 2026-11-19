package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** docs/54 §2.10：github-issue-autocomplete 验收——token/repo 解析、issue 过滤、错误通知、注册。 */
class GithubIssueAutocompleteTest {

    private static GithubIssueAutocompleteExtension.GitHubIssue issue(int n, String t, String s) {
        return new GithubIssueAutocompleteExtension.GitHubIssue(n, t, s);
    }

    @Test
    void extractIssueToken() {
        assertThat(GithubIssueAutocompleteExtension.extractIssueToken("fix #12")).isEqualTo("12");
        assertThat(GithubIssueAutocompleteExtension.extractIssueToken("see #abc")).isEqualTo("abc");
        assertThat(GithubIssueAutocompleteExtension.extractIssueToken("no token")).isNull();
        assertThat(GithubIssueAutocompleteExtension.extractIssueToken("#hi #there"))
            .isEqualTo("there");
    }

    @Test
    void parseGitHubRepoBothSchemes() {
        assertThat(GithubIssueAutocompleteExtension.parseGitHubRepo(
            "git@github.com:openai/pi.git")).isEqualTo("openai/pi");
        assertThat(GithubIssueAutocompleteExtension.parseGitHubRepo(
            "https://github.com/earendil-works/pi")).isEqualTo("earendil-works/pi");
        assertThat(GithubIssueAutocompleteExtension.parseGitHubRepo(
            "https://gitlab.com/a/b")).isNull();
    }

    @Test
    void resolveRepoFromRemoteV() {
        var remote = "origin\tgit@github.com:openai/pi.git (fetch)\n"
            + "origin\tgit@github.com:openai/pi.git (push)\n";
        assertThat(GithubIssueAutocompleteExtension.resolveGitHubRepo(remote))
            .isEqualTo("openai/pi");
        assertThat(GithubIssueAutocompleteExtension.resolveGitHubRepo("origin\thttps://gitlab.com/a/b (fetch)"))
            .isNull();
    }

    @Test
    void filterIssuesEmptyNumericAndFuzzy() {
        var issues = List.of(issue(12, "Fix parser", "OPEN"), issue(123, "Add feature", "CLOSED"),
            issue(5, "Parser docs", "OPEN"));
        assertThat(GithubIssueAutocompleteExtension.filterIssues(issues, "")).hasSize(3);
        assertThat(GithubIssueAutocompleteExtension.filterIssues(issues, "12"))
            .extracting(GithubIssueAutocompleteExtension.GitHubIssue::number)
            .containsExactly(12, 123);
        assertThat(GithubIssueAutocompleteExtension.filterIssues(issues, "parser"))
            .extracting(GithubIssueAutocompleteExtension.GitHubIssue::number)
            .contains(12, 5);
    }

    @Test
    void formatIssueItemAndParseIssues() {
        assertThat(GithubIssueAutocompleteExtension.formatIssueItem(issue(42, "Bug", "OPEN")))
            .isEqualTo("#42 [open] Bug");
        var json = "[{\"number\":42,\"title\":\"Bug\",\"state\":\"OPEN\"},"
            + "{\"number\":7,\"title\":\"Doc\",\"state\":\"CLOSED\"}]";
        var issues = GithubIssueAutocompleteExtension.parseIssues(json);
        assertThat(issues).hasSize(2);
        assertThat(issues.get(0).number()).isEqualTo(42);
        assertThat(issues.get(0).title()).isEqualTo("Bug");
    }

    @Test
    void registrationAndErrorNotice() {
        var w = new W3Context();
        var ext = new GithubIssueAutocompleteExtension();
        ext.register(w.ctx, new GithubIssueAutocompleteExtension.IssueSource() {
            public String gitRemote(String cwd) { return ""; }
            public List<GithubIssueAutocompleteExtension.GitHubIssue> issues(String repo) {
                return List.of();
            }
        });
        w.fire(com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START, null);

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("github-issue-autocomplete: cwd is not a GitHub repository");
        assertThat(w.ui.autocompleteCount).isZero(); // 错误臂不注册
    }
}

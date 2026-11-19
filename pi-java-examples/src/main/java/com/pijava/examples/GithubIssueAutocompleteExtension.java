package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/github-issue-autocomplete.ts:1-185} 移植（docs/54 §2.10）——
 * session_start 预热 GitHub issue 并注册补全 provider。补全 SPI 为空接口（D242）：
 * 只验注册形状与纯逻辑（token/repo 解析、issue 过滤），补全建议内容不可观测。
 */
public final class GithubIssueAutocompleteExtension implements PiExtension {

    static final int MAX_ISSUES = 100;
    static final int MAX_SUGGESTIONS = 20;

    static final Pattern ISSUE_TOKEN = Pattern.compile("(?:^|[ \\t])#([^\\s#]*)$");
    static final Pattern SSH_REPO = Pattern.compile("git@github\\.com:([^/]+/[^/]+?)(?:\\.git)?(?=\\s|$)");
    static final Pattern HTTPS_REPO = Pattern.compile("https?://github\\.com/([^/]+/[^/]+?)(?:\\.git)?(?=\\s|$)");

    record GitHubIssue(int number, String title, String state) {}

    /** 可注入的 `git remote -v` / `gh issue list` 后端（不联网）。 */
    interface IssueSource {
        String gitRemote(String cwd);
        List<GitHubIssue> issues(String repo);
    }

    @Override
    public String name() {
        return "github-issue-autocomplete";
    }

    @Override
    public void register(ExtensionContext ctx) {
        register(ctx, new IssueSource() {
            public String gitRemote(String cwd) {
                var r = ctx.exec("git", List.of("remote", "-v"),
                    new com.pijava.coding.agent.extension.ExtensionExec.ExecOptions(
                        null, 5000L, cwd, null));
                return r.stdout();
            }
            public List<GitHubIssue> issues(String repo) {
                var r = ctx.exec("gh", List.of("issue", "list", "--repo", repo, "--state", "open",
                        "--limit", "100", "--json", "number,title,state"),
                    new com.pijava.coding.agent.extension.ExtensionExec.ExecOptions(
                        null, 5000L, null, null));
                if (r.code() != 0) {
                    throw new IllegalStateException(r.stderr().trim().isEmpty()
                        ? "exit code " + r.code() : r.stderr().trim());
                }
                return parseIssues(r.stdout());
            }
        });
    }

    void register(ExtensionContext ctx, IssueSource source) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            var cwd = c.cwd();
            var repo = resolveGitHubRepo(source.gitRemote(cwd));
            if (repo == null) {
                c.ui().notify("github-issue-autocomplete: cwd is not a GitHub repository",
                    NotifyType.ERROR);
                return null;
            }
            try {
                source.issues(repo); // 预热
            } catch (RuntimeException e) {
                var details = e.getMessage() == null || e.getMessage().isBlank()
                    ? "exit code 1" : e.getMessage();
                c.ui().notify("github-issue-autocomplete: failed to load issues: " + details,
                    NotifyType.ERROR);
            }
            // D242：补全 SPI 为空接口，注册形状由 ScriptedUi 计数；无内容可断言
            c.ui().addAutocompleteProvider(current -> current);
            return null;
        });
    }

    /** pi L24：末尾 `#token` 提取。 */
    static String extractIssueToken(String input) {
        var m = ISSUE_TOKEN.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    /** pi L29/L34：SSH 与 HTTPS remote URL 解析 owner/repo。 */
    static String parseGitHubRepo(String url) {
        var ssh = SSH_REPO.matcher(url.trim());
        if (ssh.find()) {
            return ssh.group(1);
        }
        var https = HTTPS_REPO.matcher(url.trim());
        if (https.find()) {
            return https.group(1);
        }
        return null;
    }

    /** pi L42-61：从 `git remote -v` 输出解析首个 GitHub repo。 */
    static String resolveGitHubRepo(String remoteOutput) {
        if (remoteOutput == null || remoteOutput.isBlank()) {
            return null;
        }
        for (var line : remoteOutput.lines().toList()) {
            var parts = line.trim().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            var repo = parseGitHubRepo(parts[1]);
            if (repo != null) {
                return repo;
            }
        }
        return null;
    }

    /** pi L63-68：补全项文本（D242：只见 label/description 文本）。 */
    static String formatIssueItem(GitHubIssue issue) {
        return "#" + issue.number() + " [" + issue.state().toLowerCase() + "] " + issue.title();
    }

    /** pi L71-89：空 query 取前 20；纯数字按编号前缀优先；否则子序列模糊。 */
    static List<GitHubIssue> filterIssues(List<GitHubIssue> issues, String query) {
        if (query == null || query.isEmpty()) {
            return issues.subList(0, Math.min(MAX_SUGGESTIONS, issues.size()));
        }
        var result = new ArrayList<GitHubIssue>();
        if (query.chars().allMatch(Character::isDigit)) {
            for (var issue : issues) {
                if (String.valueOf(issue.number()).startsWith(query)) {
                    result.add(issue);
                }
            }
        }
        if (result.isEmpty()) {
            var lower = query.toLowerCase();
            for (var issue : issues) {
                if (subsequenceMatch((issue.number() + " " + issue.title()).toLowerCase(), lower)) {
                    result.add(issue);
                }
            }
        }
        return result.subList(0, Math.min(MAX_SUGGESTIONS, result.size()));
    }

    static boolean subsequenceMatch(String text, String query) {
        int j = 0;
        for (int i = 0; i < text.length() && j < query.length(); i++) {
            if (text.charAt(i) == query.charAt(j)) {
                j++;
            }
        }
        return j == query.length();
    }

    /** 最小 JSON 解析（gh --json 输出）：只取 number/title/state 三键。 */
    static List<GitHubIssue> parseIssues(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        var list = new ArrayList<GitHubIssue>();
        for (var obj : json.replace("[", "").replace("]", "").split("\\},\\s*\\{")) {
            var num = jsonValue(obj, "number");
            var title = jsonValue(obj, "title");
            var state = jsonValue(obj, "state");
            if (num != null && title != null && state != null) {
                try {
                    list.add(new GitHubIssue(Integer.parseInt(num), title, state));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return list;
    }

    private static String jsonValue(String obj, String key) {
        var marker = "\"" + key + "\"";
        var idx = obj.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        var colon = obj.indexOf(':', idx + marker.length());
        if (colon < 0) {
            return null;
        }
        var rest = obj.substring(colon + 1).trim();
        if (rest.startsWith("\"")) {
            var end = rest.indexOf('"', 1);
            return end < 0 ? null : rest.substring(1, end);
        }
        var end = 0;
        while (end < rest.length() && rest.charAt(end) != ',' && rest.charAt(end) != '}') {
            end++;
        }
        return rest.substring(0, end).trim();
    }
}

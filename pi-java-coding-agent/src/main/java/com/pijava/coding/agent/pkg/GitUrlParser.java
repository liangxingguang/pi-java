package com.pijava.coding.agent.pkg;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.pijava.coding.agent.pkg.ParsedSource.GitSource;

/**
 * pi {@code parseGitUrl}（utils/git.ts:172-226）移植。
 *
 * <p>规则（pi docstring 逐字）：</p>
 * <ul>
 *   <li>With git: prefix, accept all historical shorthand forms.</li>
 *   <li>Without git: prefix, only accept explicit protocol URLs.</li>
 * </ul>
 *
 * <p><b>窄差 D105</b>：pi 的 hosted-git-info 知名宿主表裁为通用 URL 解析
 * （{@code parseGenericGitUrl} 已覆盖同语义）；{@code bitbucket:}/{@code gist:}
 * 等冷僻简写不支持。JS {@code URL} 的 scheme/host 小写规范化在重建 repo 时
 * 对位（{@link #rebuildUrl}）。</p>
 */
public final class GitUrlParser {

    private static final Pattern SCP_LIKE = Pattern.compile("^git@([^:]+):(.+)$");
    private static final Pattern PROTOCOL_GATE =
        Pattern.compile("^(https?|ssh|git)://", Pattern.CASE_INSENSITIVE);

    private GitUrlParser() {}

    /** pi {@code parseGitUrl}（裁 hosted-git-info 层，D105）。 */
    public static GitSource parseGitUrl(String source) {
        var trimmed = source.trim();
        var hasGitPrefix = trimmed.startsWith("git:");
        var url = hasGitPrefix ? trimmed.substring(4).trim() : trimmed;
        if (!hasGitPrefix && !PROTOCOL_GATE.matcher(url).find()) {
            return null;
        }
        return parseGenericGitUrl(url);
    }

    /** pi {@code parseGenericGitUrl}（:140-169）。 */
    private static GitSource parseGenericGitUrl(String url) {
        var split = splitRef(url);
        var repo = split.repo();
        String host;
        String path;

        var scp = SCP_LIKE.matcher(split.repo());
        if (scp.matches()) {
            host = scp.group(1);
            path = scp.group(2);
        } else if (split.repo().startsWith("https://") || split.repo().startsWith("http://")
            || split.repo().startsWith("ssh://") || split.repo().startsWith("git://")) {
            try {
                var uri = new URI(split.repo());
                host = uri.getHost();
                if (host == null) {
                    return null;
                }
                path = stripLeadingSlashes(uri.getRawPath() == null ? "" : uri.getRawPath());
            } catch (URISyntaxException e) {
                return null;
            }
        } else {
            var slashIndex = split.repo().indexOf('/');
            if (slashIndex < 0) {
                return null;
            }
            host = split.repo().substring(0, slashIndex);
            path = split.repo().substring(slashIndex + 1);
            if (!host.contains(".") && !host.equals("localhost")) {
                return null;
            }
            repo = "https://" + split.repo();
        }
        return buildGitSource(repo, host, path, split.ref());
    }

    /** pi {@code splitRef}（:15-62）：{@code @<ref>} 后缀拆分（三形态）。 */
    private static RepoRef splitRef(String url) {
        var scp = SCP_LIKE.matcher(url);
        if (scp.matches()) {
            var pathWithMaybeRef = scp.group(2);
            var refSeparator = pathWithMaybeRef.indexOf('@');
            if (refSeparator < 0) {
                return new RepoRef(url, null);
            }
            var repoPath = pathWithMaybeRef.substring(0, refSeparator);
            var ref = pathWithMaybeRef.substring(refSeparator + 1);
            if (repoPath.isEmpty() || ref.isEmpty()) {
                return new RepoRef(url, null);
            }
            return new RepoRef("git@" + scp.group(1) + ":" + repoPath, ref);
        }

        if (url.contains("://")) {
            try {
                var uri = new URI(url);
                if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                    return new RepoRef(url, null);
                }
                var pathWithMaybeRef =
                    stripLeadingSlashes(uri.getRawPath() == null ? "" : uri.getRawPath());
                var refSeparator = pathWithMaybeRef.indexOf('@');
                if (refSeparator < 0) {
                    return new RepoRef(url, null);
                }
                var repoPath = pathWithMaybeRef.substring(0, refSeparator);
                var ref = pathWithMaybeRef.substring(refSeparator + 1);
                if (repoPath.isEmpty() || ref.isEmpty()) {
                    return new RepoRef(url, null);
                }
                return new RepoRef(rebuildUrl(uri, "/" + repoPath), ref);
            } catch (URISyntaxException e) {
                return new RepoRef(url, null);
            }
        }

        var slashIndex = url.indexOf('/');
        if (slashIndex < 0) {
            return new RepoRef(url, null);
        }
        var host = url.substring(0, slashIndex);
        var pathWithMaybeRef = url.substring(slashIndex + 1);
        var refSeparator = pathWithMaybeRef.indexOf('@');
        if (refSeparator < 0) {
            return new RepoRef(url, null);
        }
        var repoPath = pathWithMaybeRef.substring(0, refSeparator);
        var ref = pathWithMaybeRef.substring(refSeparator + 1);
        if (repoPath.isEmpty() || ref.isEmpty()) {
            return new RepoRef(url, null);
        }
        return new RepoRef(host + "/" + repoPath, ref);
    }

    /** pi {@code buildGitSource}（:104-124）校验面逐字。 */
    static GitSource buildGitSource(String repo, String host, String path, String ref) {
        if (path.startsWith("/")) {
            return null;
        }
        var normalizedPath = path.replaceAll("\\.git$", "").replaceAll("^/+", "");
        if (host.isEmpty() || normalizedPath.isEmpty()
            || normalizedPath.split("/").length < 2) {
            return null;
        }
        if (hasUnsafeGitInstallPart(host, false) || hasUnsafeGitInstallPart(normalizedPath, true)) {
            return null;
        }
        return new GitSource(repo, host, normalizedPath, ref, ref != null);
    }

    /** pi {@code hasUnsafeGitInstallPart}（:86-101）：原串与解码串双检
     *  （{@code \0}、{@code \}、前导 {@code /}、{@code ..} 段；host 禁斜杠）。 */
    private static boolean hasUnsafeGitInstallPart(String value, boolean allowSlash) {
        var decoded = decodeForValidation(value);
        if (decoded == null) {
            return true;
        }
        String[] candidates = {value, decoded};
        for (var candidate : candidates) {
            if (candidate.contains("\0") || candidate.contains("\\") || candidate.startsWith("/")) {
                return true;
            }
            if (!allowSlash && candidate.contains("/")) {
                return true;
            }
            if (List.of(candidate.split("/")).contains("..")) {
                return true;
            }
        }
        return false;
    }

    /** pi {@code decodeForValidation}（:78-84）：解码失败 ⇒ null（判不安全）。 */
    private static String decodeForValidation(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** JS {@code URL#toString()} 对位：scheme/host 小写，去默认端口外的 authority
     *  原样保留，query/fragment 保留。 */
    private static String rebuildUrl(URI uri, String newRawPath) {
        var authority = new StringBuilder();
        if (uri.getRawUserInfo() != null) {
            authority.append(uri.getRawUserInfo()).append('@');
        }
        authority.append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() >= 0) {
            authority.append(':').append(uri.getPort());
        }
        var out = new StringBuilder()
            .append(uri.getScheme().toLowerCase(Locale.ROOT))
            .append("://").append(authority).append(newRawPath);
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            out.append('#').append(uri.getRawFragment());
        }
        var result = out.toString();
        // pi: parsed.toString().replace(/\/$/, "")
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static String stripLeadingSlashes(String path) {
        var i = 0;
        while (i < path.length() && path.charAt(i) == '/') {
            i++;
        }
        return path.substring(i);
    }

    private record RepoRef(String repo, String ref) {}
}

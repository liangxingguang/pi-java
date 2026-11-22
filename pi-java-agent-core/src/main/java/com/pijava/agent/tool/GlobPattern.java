package com.pijava.agent.tool;

import java.util.regex.Pattern;

/**
 * Glob-to-regex matcher shared by the built-in {@code find} and {@code grep} tools.
 * Aligned with pi's fd/minimatch glob semantics（find.ts:209-252、grep.ts:167）.
 *
 * <p>翻译规则：{@code **&#47;} ＝ 零或多段、裸 {@code **} ＝ 跨段任意、
 * {@code *} ＝ 段内任意（含 dotfile）、{@code ?} ＝ 段内单字、
 * {@code [...]} 字符类（{@code !} 取反）、{@code {a,b}} 支选；
 * 其余字符按字面量。两侧都做全串匹配。调用方负责把候选路径归一成
 * posix 形式，并按 pattern 是否含 {@code /} 分派 basename／整路径。</p>
 */
public final class GlobPattern {
    private final Pattern regex;

    private GlobPattern(Pattern regex) {
        this.regex = regex;
    }

    /**
     * Compile a glob into a matcher.
     *
     * @param glob the glob pattern
     * @param ignoreCase whether matching is case-insensitive (fd's smart case)
     */
    public static GlobPattern compile(String glob, boolean ignoreCase) {
        var re = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        re.append("(?:[^/]+/)*");   // **/ ⇒ 零或多段
                        i += 3;
                    } else {
                        re.append(".*");            // 裸 ** ⇒ 跨段任意
                        i += 2;
                    }
                } else {
                    re.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                re.append("[^/]");
                i++;
            } else if (c == '[') {
                i = appendClass(re, glob, i);
            } else if (c == '{') {
                i = appendAlternation(re, glob, i);
            } else {
                if ("\\.^$|()+".indexOf(c) >= 0) {
                    re.append('\\');
                }
                re.append(c);
                i++;
            }
        }
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        return new GlobPattern(Pattern.compile(re.append('$').toString(), flags));
    }

    /** {@code [abc]}／{@code [!abc]}；未闭合时按字面 {@code [}。 */
    private static int appendClass(StringBuilder re, String glob, int start) {
        int end = glob.indexOf(']', start + 1);
        if (end < 0) {
            re.append("\\[");
            return start + 1;
        }
        String cls = glob.substring(start + 1, end);
        if (cls.startsWith("!")) {
            cls = "^" + cls.substring(1);
        }
        re.append('[').append(cls).append(']');
        return end + 1;
    }

    /** {@code {a,b}}：各支按字面量（不支持嵌套），未闭合时按字面开括号。 */
    private static int appendAlternation(StringBuilder re, String glob, int start) {
        int end = glob.indexOf('}', start + 1);
        if (end < 0) {
            re.append("\\{");
            return start + 1;
        }
        String[] alts = glob.substring(start + 1, end).split(",", -1);
        re.append("(?:");
        for (int k = 0; k < alts.length; k++) {
            if (k > 0) {
                re.append('|');
            }
            re.append(Pattern.quote(alts[k]));
        }
        re.append(')');
        return end + 1;
    }

    /** Match a posix-normalized path (或 basename) against the glob, whole-string. */
    public boolean matches(String posixPath) {
        return regex.matcher(posixPath).matches();
    }
}

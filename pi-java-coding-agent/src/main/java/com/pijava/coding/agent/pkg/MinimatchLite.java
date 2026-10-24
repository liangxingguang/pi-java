package com.pijava.coding.agent.pkg;

import java.util.regex.Pattern;

/**
 * 路径向 minimatch 子集（批 18 裁决⑧）：翻译到 regex。
 *
 * <p>支持：{@code *}（不跨 {@code /}）、{@code **}（跨段）、{@code ?}、
 * {@code {a,b}} 展开、dot:false 默认（{@code .} 开头段不被通配开头匹配——pi
 * minimatch 默认）。</p>
 *
 * <p><b>窄差 D110</b>：extglob（{@code @(...)}/{@code +(...)} 等）、POSIX 字符类
 * （{@code [[:alpha:]]}）、括号嵌套等冷僻形不支持（按字面转义）。</p>
 */
public final class MinimatchLite {

    private MinimatchLite() {}

    /** 全串匹配（pi {@code minimatch(path, pattern)} 默认选项对位）。 */
    public static boolean match(String pattern, String input) {
        return toRegex(pattern).matcher(input).matches();
    }

    /** glob → regex 翻译（段为单位处理 {@code **} 与 dot 规则）。 */
    static Pattern toRegex(String pattern) {
        var segments = pattern.split("/", -1);
        var out = new StringBuilder("^");
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            var isFirst = i == 0;
            var isLast = i == segments.length - 1;
            if (segment.equals("**")) {
                // dot:false：** 不跨 . 开头段（逐段 (?!\.) 负先行，评审 M1 回补）；
                // 尾位 ** 至少一段（pi minimatch：「a/**」不中「a」自身）。
                if (isFirst && isLast) {
                    out.append("(?![.])[^/]+(?:/(?![.])[^/]+)*");
                } else if (isFirst) {
                    // "**/b"：零段也能接 b（尾部斜杠由本段自带）。
                    out.append("(?:(?![.])[^/]+/)*");
                } else if (isLast) {
                    out.append("(?:/(?![.])[^/]+)+");
                } else {
                    // "a/**/b"：零段时 "a/b" 也中（斜杠本段自带）。
                    out.append("(?:/(?![.])[^/]+)*/");
                }
                continue;
            }
            if (!isFirst) {
                var previous = segments[i - 1];
                // ** 段已自带分隔斜杠（首/中形态），其后不补 "/"。
                if (!previous.equals("**")) {
                    out.append("/");
                }
            }
            appendSegment(out, segment);
        }
        out.append("$");
        return Pattern.compile(out.toString());
    }

    /** 单段翻译：通配开头 ⇒ 负先行禁 {@code .} 首字符（dot:false）。 */
    private static void appendSegment(StringBuilder out, String segment) {
        if (segment.isEmpty()) {
            return;
        }
        var first = segment.charAt(0);
        if (first == '*' || first == '?' || first == '{') {
            out.append("(?![.])");
        }
        for (var i = 0; i < segment.length(); i++) {
            var c = segment.charAt(i);
            switch (c) {
                case '*' -> out.append("[^/]*");
                case '?' -> out.append("[^/]");
                case '{' -> {
                    var close = segment.indexOf('}', i);
                    if (close < 0) {
                        out.append("\\{");
                        break;
                    }
                    out.append("(?:");
                    var alternatives = segment.substring(i + 1, close).split(",", -1);
                    for (var j = 0; j < alternatives.length; j++) {
                        if (j > 0) {
                            out.append("|");
                        }
                        appendSegment(out, alternatives[j]);
                    }
                    out.append(")");
                    i = close;
                }
                default -> {
                    if ("\\.^$|()[]+-".indexOf(c) >= 0) {
                        out.append("\\");
                    }
                    out.append(c);
                }
            }
        }
    }
}

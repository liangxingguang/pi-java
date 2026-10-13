package com.pijava.ai.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * minimatch 10.2.6 的子集匹配器（pi {@code minimatch(fullId, globPattern, { nocase: true })}，
 * model-resolver.ts:315-316）。
 *
 * <p>语义来自 npm {@code minimatch@10.2.6} tarball 的<b>静态阅读</b>（pi package.json 钉死
 * 该版本；取证未执行其代码），实现 {@code dist/commonjs/ast.js} 的 glob→正则翻译：</p>
 * <ul>
 *   <li>{@code ?} → {@code [^/]}（不跨斜杠）；</li>
 *   <li>{@code *} → {@code [^/]*?}（连续星合并、不跨斜杠）；整段纯星 ⇒ 至少一字符
 *       {@code [^/]+?}（ast.js {@code noEmpty}）；</li>
 *   <li>{@code **} 仅在<b>整段</b>时是 globstar（可跨斜杠、可零段；dot:false 下不吃
 *       leading-dot 段）；段内 {@code a**b} 等同 {@code a*b}；</li>
 *   <li>{@code [...]} 字符类：首位 {@code !}/{@code ^} 取反、{@code a-z} 码点范围（逆序
 *       范围丢弃）、{@code -]} 字面横线、{@code []]} 字面 {@code ]}；未闭合 {@code [}
 *       按字面；单字符类 {@code [x]} 非 magic、等义字面 {@code x}；空类毒化（恒不匹配）；</li>
 *   <li>{@code \\} 转义下一字符（目标非正则元字符时反斜杠丢弃）；行尾孤 {@code \\} 按字面；</li>
 *   <li>其余正则元字符全部按字面转义；</li>
 *   <li>点文件门 {@code dot:false}：段首 token 是通配符或 magic 字符类时加 {@code (?!\\.)}；</li>
 *   <li>{@code nocase}（{@link Pattern#CASE_INSENSITIVE}），全串锚定。</li>
 * </ul>
 *
 * <p><b>不做</b>（docs/12 §4 裁决③）：brace 展开 {@code {a,b}}、extglob、posix class、
 * {@code #} 注释、{@code !} 否定模式。花括号按字面匹配 ⇒ id 字符集外的写法得 no-match。</p>
 */
public final class ModelGlob {

    private static final ConcurrentHashMap<String, Pattern> CACHE = new ConcurrentHashMap<>();

    /** {@code [^/]}：ast.js:127 {@code qmark}。 */
    private static final String QMARK = "[^/]";
    /** {@code [^/]*?}：ast.js:129 {@code star}（非贪婪不影响全串布尔判定）。 */
    private static final String STAR = "[^/]*?";
    /** {@code [^/]+?}：整段纯星时的 noEmpty 形态。 */
    private static final String STAR_NO_EMPTY = "[^/]+?";

    private ModelGlob() {}

    /** 模式是否含 glob 触发字符（pi model-resolver.ts:289：{@code *}、{@code ?}、{@code [}）。 */
    public static boolean isGlob(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0 || pattern.indexOf('[') >= 0;
    }

    /** minimatch(text, glob, &#123;nocase:true&#125;) 的等价判定（全串锚定）。 */
    public static boolean matches(String text, String glob) {
        return CACHE.computeIfAbsent(glob, ModelGlob::compile).matcher(text).matches();
    }

    private static Pattern compile(String glob) {
        var segments = glob.split("/", -1);
        var out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0 && !"**".equals(segments[i - 1])) {
                out.append('/');
            }
            out.append(compileSegment(segments[i], i, segments.length));
        }
        return Pattern.compile("^" + out + "$", Pattern.CASE_INSENSITIVE);
    }

    /** 单段翻译；{@code **} 整段翻成 globstar 形态（按所在位置决定是否吞斜杠）。 */
    private static String compileSegment(String seg, int index, int count) {
        if ("**".equals(seg)) {
            // 一个段的点门：(?!\.)；跨段时每个被吞入的段都加门（index.js matchGlobstar
            // 对 leading-dot 段恒判 false）。
            var body = "(?!\\.)[^/]*(?:/(?!\\.)[^/]*)*";
            if (count == 1) {
                return body;
            }
            if (index == count - 1) {
                // 结尾 **：前面已有字面 '/'，零段或多段。
                return "(?:" + body + ")?";
            }
            // 开头/中段 **：自身吞掉尾斜杠（零段时整组缺席）。
            return "(?:" + body + "/)?";
        }

        var re = new StringBuilder();
        boolean gate = false;
        boolean started = false;
        boolean inStar = false;
        boolean escaping = false;
        boolean allStars = !seg.isEmpty() && seg.chars().allMatch(c -> c == '*');

        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (escaping) {
                escaping = false;
                // ast.js:810-813：被转义字符不是正则元字符 ⇒ 反斜杠丢弃。
                re.append(literal(c, reSpecials(c)));
                started = true;
                continue;
            }
            if (c == '*') {
                if (!inStar) {
                    re.append(allStars ? STAR_NO_EMPTY : STAR);
                    if (!started) {
                        gate = true;
                    }
                }
                inStar = true;
                started = true;
                continue;
            }
            inStar = false;
            if (c == '\\') {
                if (i == seg.length() - 1) {
                    re.append(literal('\\', true));
                } else {
                    escaping = true;
                }
                started = true;
                continue;
            }
            if (c == '[') {
                var part = parseClass(seg, i);
                if (part.consumed() > 0) {
                    re.append(part.src());
                    if (!started && part.magic()) {
                        gate = true;
                    }
                    i += part.consumed() - 1;
                    started = true;
                    continue;
                }
            }
            if (c == '?') {
                re.append(QMARK);
                if (!started) {
                    gate = true;
                }
                started = true;
                continue;
            }
            re.append(literal(c, false));
            started = true;
        }
        return (gate ? "(?!\\.)" : "") + re;
    }

    /** ast.js:122 {@code reSpecials}（不含 '-'，普通字面走全转义）。 */
    private static boolean reSpecials(char c) {
        return "().*{}+?[]^$\\!".indexOf(c) >= 0;
    }

    /** 正则字面转义（字符类外）：非字母数字统一加反斜杠。 */
    private static String literal(char c, boolean alreadySpecial) {
        if (alreadySpecial || (!Character.isLetterOrDigit(c) && c != '/')) {
            return "\\" + c;
        }
        return String.valueOf(c);
    }

    /** 字符类解析结果（brace-expressions.js:36-149 parseClass 的子集）。 */
    private record ClassPart(String src, int consumed, boolean magic) {}

    /**
     * 解析 {@code [...]}；未闭合 ⇒ {@code consumed()==0}（调用方按字面 {@code [} 处理）。
     * 逆序码点范围两端丢弃（pi 同形）；无成员的合法类 ⇒ 毒化 {@code $.}（锚定下恒不匹配）。
     */
    private static ClassPart parseClass(String glob, int pos) {
        List<String> members = new ArrayList<>();
        boolean negate = false;
        boolean sawStart = false;
        boolean escaping = false;
        int end = -1;
        int i = pos + 1;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if ((c == '!' || c == '^') && i == pos + 1) {
                negate = true;
                i++;
                continue;
            }
            if (c == ']' && sawStart && !escaping) {
                end = i + 1;
                break;
            }
            sawStart = true;
            if (c == '\\' && !escaping) {
                // 步进交给循环 ++：下一轮读到被转义字符（escaping 仍为 true）。
                escaping = true;
                continue;
            }
            escaping = false;
            if (i + 1 < glob.length() && glob.charAt(i + 1) == '-' && i + 2 < glob.length()
                    && glob.charAt(i + 2) == ']') {
                // c-]：横线作字面成员；步进 1，循环 ++ 落在 ']' 上正常收类。
                members.add(classEscape(c));
                members.add("\\-");
                i += 1;
                continue;
            }
            if (i + 1 < glob.length() && glob.charAt(i + 1) == '-' && i + 2 < glob.length()) {
                char hi = glob.charAt(i + 2);
                if (hi > c) {
                    members.add(classEscape(c) + "-" + classEscape(hi));
                } else if (hi == c) {
                    members.add(classEscape(c));
                }
                // hi < c：逆序范围，两端整体丢弃（pi 同形）。
                // 步进 2，循环 ++ 越过 hi 落在 ']' 上。
                i += 2;
                continue;
            }
            members.add(classEscape(c));
            i++;
        }
        if (end < 0) {
            return new ClassPart("", 0, false);
        }
        if (members.isEmpty()) {
            return new ClassPart("$.", end - pos, true);
        }
        if (!negate && members.size() == 1 && members.getFirst().length() <= 2) {
            // 单字符类 [x] 非 magic，等义字面 x（去转义反斜杠后交外层字面判定）。
            var single = members.getFirst();
            return new ClassPart(single, end - pos, false);
        }
        var src = "[" + (negate ? "^" : "") + String.join("", members) + "]";
        return new ClassPart(src, end - pos, true);
    }

    /** 字符类内成员转义：只需转义类边界与范围相关字符（外加 {@code &} 避开 Java 交集语法）。 */
    private static String classEscape(char c) {
        if ("\\][^-&".indexOf(c) >= 0) {
            return "\\" + c;
        }
        return String.valueOf(c);
    }
}

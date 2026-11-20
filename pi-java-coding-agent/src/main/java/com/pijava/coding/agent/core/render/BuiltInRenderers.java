package com.pijava.coding.agent.core.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.core.ReadLineRange;
import com.pijava.coding.agent.extension.ToolCallRenderer;

/**
 * docs/60（L7 W1b）：pi {@code core/tools/renderers/*} 的默认工具渲染器。
 *
 * <p>与 examples 的渲染器（docs/54）不同，本类是 <b>CLI 默认</b>：TUI 在扩展链
 * 未命中时以它为 {@code base}（{@code ToolRenderers.resolve}），因此不加载任何
 * 扩展也生效；扩展仍优先（pi 的 {@code tool_override} 语义）。</p>
 *
 * <p>输出是<b>已转义</b>的纯文本行（D92 文本级方言）：颜色/语法高亮/keyHint 的
 * 差异见 docs/60 §4（D260–D263）。两个方法都可"无意见"（{@code null} ⇒ 调用方
 * 回落默认渲染），例如 {@code edit} 只接标题行，diff 仍走 {@code DiffView}。</p>
 */
public final class BuiltInRenderers {

    /** pi {@code keyHint("app.tools.expand", "to expand")} 的文本级硬编码（D220）。 */
    static final String EXPAND_HINT = "ctrl+o to expand";
    /** pi {@code bash.ts:18}。 */
    private static final int SHELL_PREVIEW_LINES = 5;
    /** pi {@code read.ts:127}/{@code write.ts:117}。 */
    private static final int TEXT_PREVIEW_LINES = 10;
    /** pi {@code grep.ts:49}。 */
    private static final int GREP_PREVIEW_LINES = 15;
    /** pi {@code find.ts:46}/{@code ls.ts:39}。 */
    private static final int LIST_PREVIEW_LINES = 20;

    private static final ToolCallRenderer READ = new ReadRenderer();
    private static final ToolCallRenderer BASH = new ShellRenderer("$");
    private static final ToolCallRenderer POWERSHELL = new ShellRenderer("PS>");
    private static final ToolCallRenderer EDIT = new EditRenderer();
    private static final ToolCallRenderer WRITE = new WriteRenderer();
    private static final ToolCallRenderer GREP = new GrepRenderer();
    private static final ToolCallRenderer FIND = new FindRenderer();
    private static final ToolCallRenderer LS = new LsRenderer();

    private BuiltInRenderers() {}

    /**
     * 内置渲染器（pi {@code createAllToolRenderers}，{@code renderers/index.ts:32-43}）。
     *
     * @param toolName 工具名
     * @return 渲染器；未内置 ⇒ {@code null}（调用方继续回落）
     */
    public static ToolCallRenderer resolve(String toolName) {
        if (toolName == null) {
            return null;
        }
        return switch (toolName) {
            case "read" -> READ;
            case "bash" -> BASH;
            case "powershell" -> POWERSHELL;
            case "edit" -> EDIT;
            case "write" -> WRITE;
            case "grep" -> GREP;
            case "find" -> FIND;
            case "ls" -> LS;
            default -> null;
        };
    }

    // ── 共享工具 ─────────────────────────────────────────────

    /** pi {@code str(value)}：字符串原样；null/undefined ⇒ {@code ""}；其他类型 ⇒ {@code null}。 */
    static String str(Object value) {
        if (value instanceof String text) {
            return text;
        }
        return value == null ? "" : null;
    }

    /** 参数取值（{@code args} 可空 ≙ pi 的 undefined）。 */
    private static Object value(Map<String, Object> args, String key) {
        return args == null ? null : args.get(key);
    }

    /** pi {@code args?.x ?? args?.y} 的键回退（仅 nullish 才回退）。 */
    private static Object firstPresent(Map<String, Object> args, String primary, String fallback) {
        var primaryValue = value(args, primary);
        return primaryValue != null ? primaryValue : value(args, fallback);
    }

    /** 头部折叠：前 {@code max} 行 ＋ 省略行（pi {@code read/grep/find/ls} 共用）。 */
    private static List<String> foldHead(List<String> lines, int max) {
        if (lines.size() <= max) {
            return RenderText.escapeAll(lines);
        }
        var out = new ArrayList<String>(max + 1);
        out.addAll(lines.subList(0, max));
        out.add("... (" + (lines.size() - max) + " more lines, " + EXPAND_HINT + ")");
        return RenderText.escapeAll(out);
    }

    private static List<String> foldHead(List<String> lines, boolean expanded, int collapsedMax) {
        return foldHead(lines, expanded ? lines.size() : collapsedMax);
    }

    /** 参数里的数值（{@code limit}／{@code timeout}）按 JS 形态回显。 */
    private static String number(Object value) {
        return value instanceof Number n ? RenderText.jsNumber(n.doubleValue())
            : value == null ? null : String.valueOf(value);
    }

    // ── read ────────────────────────────────────────────────

    /** pi {@code renderers/read.ts:28-148}。 */
    static final class ReadRenderer implements ToolCallRenderer {

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            var path = DisplayPath.display(str(firstPresent(args, "file_path", "path")), "...");
            var range = ReadLineRange.format(value(args, "offset"), value(args, "limit"));
            return List.of(RenderText.escape("read " + path + range));
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            // pi :118-120：折叠态且非错误 ⇒ 结果完全不显示。
            if (!expanded && !isError) {
                return List.of();
            }
            var lines = RenderText.trimTrailingEmpty(RenderText.lines(textOutput));
            return foldHead(lines, expanded, TEXT_PREVIEW_LINES);
        }
    }

    // ── bash / powershell ───────────────────────────────────

    /** pi {@code renderers/bash.ts:18-93}（bash 与 powershell 仅提示符不同）。 */
    static final class ShellRenderer implements ToolCallRenderer {

        private final String prompt;

        ShellRenderer(String prompt) {
            this.prompt = prompt;
        }

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            var command = str(value(args, "command"));
            var display = command == null ? "[invalid arg]" : command.isEmpty() ? "..." : command;
            var text = prompt + " " + display;
            var timeout = number(value(args, "timeout"));
            if (timeout != null && !timeout.isEmpty() && !"0".equals(timeout)) {
                text += " (timeout " + timeout + "s)";
            }
            return List.of(RenderText.escape(text));
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            var lines = RenderText.lines(textOutput);
            if (expanded || lines.size() <= SHELL_PREVIEW_LINES) {
                return RenderText.escapeAll(lines);
            }
            // pi :58-68：尾部 N 行 ＋ "N earlier lines" 前缀（D261：按逻辑行）。
            int hidden = lines.size() - SHELL_PREVIEW_LINES;
            var out = new ArrayList<String>(SHELL_PREVIEW_LINES + 1);
            out.add("... (" + hidden + " earlier lines, " + EXPAND_HINT + ")");
            out.addAll(lines.subList(hidden, lines.size()));
            return RenderText.escapeAll(out);
        }
    }

    // ── write ───────────────────────────────────────────────

    /** pi {@code renderers/write.ts:96-143}。 */
    static final class WriteRenderer implements ToolCallRenderer {

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            var path = DisplayPath.display(str(firstPresent(args, "file_path", "path")), "...");
            var content = str(value(args, "content"));
            var out = new ArrayList<String>();
            out.add("write " + path);
            if (content == null) {
                out.add("");
                out.add("[invalid content arg - expected string]");
                return RenderText.escapeAll(out);
            }
            if (content.isEmpty()) {
                return RenderText.escapeAll(out);
            }
            var lines = RenderText.trimTrailingEmpty(RenderText.lines(content));
            out.add("");
            int max = expanded ? lines.size() : TEXT_PREVIEW_LINES;
            out.addAll(lines.subList(0, Math.min(max, lines.size())));
            if (lines.size() > max) {
                out.add("... (" + (lines.size() - max) + " more lines, " + lines.size()
                    + " total, " + EXPAND_HINT + ")");
            }
            return RenderText.escapeAll(out);
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            // pi :132-142：成功不重复正文；错误才回显。
            if (!isError) {
                return List.of();
            }
            var lines = RenderText.lines(textOutput);
            return lines.isEmpty() ? List.of() : RenderText.escapeAll(lines);
        }
    }

    // ── grep / find / ls ────────────────────────────────────

    /** pi {@code renderers/grep.ts:17-69}。 */
    static final class GrepRenderer implements ToolCallRenderer {

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            var pattern = str(value(args, "pattern"));
            var rawPath = str(value(args, "path"));
            var path = DisplayPath.display(rawPath, ".");
            var text = "grep " + (pattern == null ? "[invalid arg]" : "/" + pattern + "/")
                + " in " + path;
            var glob = str(value(args, "glob"));
            if (glob != null && !glob.isEmpty()) {
                text += " (" + glob + ")";
            }
            var limit = number(value(args, "limit"));
            if (limit != null) {
                text += " limit " + limit;
            }
            return List.of(RenderText.escape(text));
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            var trimmed = textOutput == null ? "" : textOutput.trim();
            if (trimmed.isEmpty()) {
                return List.of();
            }
            return foldHead(RenderText.lines(trimmed), expanded, GREP_PREVIEW_LINES);
        }
    }

    /** pi {@code renderers/find.ts:17-63}。 */
    static final class FindRenderer implements ToolCallRenderer {

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            var pattern = str(value(args, "pattern"));
            var path = DisplayPath.display(str(value(args, "path")), ".");
            var text = "find " + (pattern == null ? "[invalid arg]" : pattern) + " in " + path;
            var limit = number(value(args, "limit"));
            if (limit != null) {
                text += " (limit " + limit + ")";
            }
            return List.of(RenderText.escape(text));
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            var trimmed = textOutput == null ? "" : textOutput.trim();
            if (trimmed.isEmpty()) {
                return List.of();
            }
            return foldHead(RenderText.lines(trimmed), expanded, LIST_PREVIEW_LINES);
        }
    }

    /** pi {@code renderers/ls.ts:17-57}。 */
    static final class LsRenderer implements ToolCallRenderer {

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            var path = DisplayPath.display(str(value(args, "path")), ".");
            var text = "ls " + path;
            var limit = number(value(args, "limit"));
            if (limit != null) {
                text += " (limit " + limit + ")";
            }
            return List.of(RenderText.escape(text));
        }

        @Override
        public List<String> renderResult(String toolName, String textOutput, boolean isError,
                                         boolean expanded) {
            var lines = RenderText.trimTrailingEmpty(RenderText.lines(textOutput));
            return foldHead(lines, expanded, LIST_PREVIEW_LINES);
        }
    }

    // ── edit ────────────────────────────────────────────────

    /**
     * pi {@code renderers/edit.ts:83-86}：只接标题行。
     *
     * <p>{@code renderResult} 保持"无意见"——Java 的 edit 结果带
     * {@code ContentBlock.DiffContent}（{@code EditTool:163-165}），默认气泡经
     * {@code DiffView} 上色，比文本 diff 更接近 pi 的彩色渲染；读盘 preview
     * 依赖异步 {@code computeEditsDiff}（D263），不移植。</p>
     */
    static final class EditRenderer implements ToolCallRenderer {

        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            var path = DisplayPath.display(str(firstPresent(args, "file_path", "path")), "...");
            return List.of(RenderText.escape("edit " + path));
        }
    }
}

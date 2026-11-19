package com.pijava.examples;

import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ToolCallRenderer;
import com.pijava.coding.agent.extension.ToolRendererResolver;
import com.pijava.coding.agent.core.ReadLineRange;

/**
 * pi {@code examples/extensions/minimal-mode.ts:1-353} 移植（docs/54 §2.1）——
 * 同名覆盖 7 内置工具的"渲染"（执行沿用原工具）；read 显示 `:start-end` 行区间（改动点）。
 */
public final class MinimalModeExtension implements PiExtension {

    @Override
    public String name() {
        return "minimal-mode";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ToolRendererResolver resolver = (toolName, next) ->
            List.of("read", "bash", "write", "edit", "find", "grep", "ls").contains(toolName)
                ? new MinimalRenderer(toolName) : null;
        ctx.registerToolRenderer(resolver);
    }

    /** pi shortenPath：home 前缀→~。 */
    static String shortenPath(String path) {
        var home = System.getProperty("user.home");
        if (home != null && path.startsWith(home)) {
            return "~" + path.substring(home.length()).replace('\\', '/');
        }
        return path.replace('\\', '/');
    }

    /** pi 改动点：read 行区间后缀。 */
    static String readLineSuffix(Number offset, Number limit) {
        if (offset == null && limit == null) {
            return "";
        }
        return ReadLineRange.format(offset, limit);
    }

    static final class MinimalRenderer implements ToolCallRenderer {
        private final String toolName;

        MinimalRenderer(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public List<String> renderCall(String name, Map<String, Object> args, boolean expanded) {
            var path = args.get("path") instanceof String s ? shortenPath(s) : "";
            var cmd = args.get("command") instanceof String s ? s : "";
            var pattern = args.get("pattern") instanceof String s ? s : "";
            return switch (toolName) {
                case "read" -> List.of("read " + path + readLineSuffix(
                    number(args.get("offset")), number(args.get("limit"))));
                case "bash" -> List.of("$ " + cmd
                    + (args.get("timeout") != null ? " (timeout " + args.get("timeout") + "s)" : ""));
                case "write" -> List.of("write " + path + " ("
                    + (args.get("content") instanceof String s ? s.lines().count() : 0) + " lines)");
                case "edit" -> List.of("edit " + path);
                case "find" -> List.of("find " + pattern + " in " + path
                    + (args.get("limit") != null ? " (limit " + args.get("limit") + ")" : ""));
                case "grep" -> List.of("grep /" + pattern + "/ in " + path
                    + (args.get("glob") != null ? " (" + args.get("glob") + ")" : ""));
                case "ls" -> List.of("ls " + path
                    + (args.get("limit") != null ? " (limit " + args.get("limit") + ")" : ""));
                default -> List.of(toolName);
            };
        }

        @Override
        public List<String> renderResult(String name, String textOutput, boolean isError,
                boolean expanded) {
            var text = textOutput == null ? "" : textOutput;
            var count = text.lines().count();
            var suffix = switch (toolName) {
                case "read" -> " → " + count + " lines";
                case "find" -> " → " + count + " files";
                case "grep" -> " → " + count + " matches";
                case "ls" -> " → " + count + " entries";
                default -> "";
            };
            return expanded ? List.of(text) : List.of(suffix.isEmpty() ? text : suffix.trim());
        }

        private static Number number(Object o) {
            return o instanceof Number n ? n : null;
        }
    }
}

package com.pijava.examples;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ToolCallRenderer;
import com.pijava.coding.agent.extension.ToolRendererResolver;

/**
 * pi {@code examples/extensions/built-in-tool-renderer.ts:1-225} 移植（docs/54 §2.5）——
 * 覆盖 read/bash/edit/write 四工具渲染。D241：pi 的富 Component/isPartial 状态文本降级为纯文本行。
 */
public final class BuiltInToolRendererExtension implements PiExtension {

    static final Pattern EXIT_CODE = Pattern.compile("exit code: (\\d+)");

    @Override
    public String name() {
        return "built-in-tool-renderer";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ToolRendererResolver resolver = (toolName, next) -> switch (toolName) {
            case "read", "bash", "edit", "write" -> new PlainRenderer(toolName);
            default -> null;
        };
        ctx.registerToolRenderer(resolver);
    }

    /** pi：命令超 80 字符截断（slice(0,77)+"..."）。 */
    static String truncateCommand(String command) {
        return command.length() > 80 ? command.substring(0, 77) + "..." : command;
    }

    /** pi：diff 增删计数（排除 +++/--- 头）。 */
    static String diffCounts(String diff) {
        int added = 0;
        int removed = 0;
        for (var line : diff.split("\n", -1)) {
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+")) {
                added++;
            } else if (line.startsWith("-")) {
                removed++;
            }
        }
        return "+" + added + " / -" + removed;
    }

    /** pi：exit code 解析（0/无匹配→done，否则 exit N）。 */
    static String exitStatus(String text) {
        var m = EXIT_CODE.matcher(text == null ? "" : text);
        if (!m.find()) {
            return "done";
        }
        var n = Integer.parseInt(m.group(1));
        return n == 0 ? "done" : "exit " + n;
    }

    static final class PlainRenderer implements ToolCallRenderer {
        private final String toolName;

        PlainRenderer(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public List<String> renderCall(String name, Map<String, Object> args, boolean expanded) {
            var path = args.get("path") instanceof String s ? s : "";
            var cmd = args.get("command") instanceof String s ? s : "";
            return switch (toolName) {
                case "read" -> List.of("read " + path);
                case "bash" -> List.of("$ " + truncateCommand(cmd));
                case "edit" -> List.of("edit " + path);
                case "write" -> List.of("write " + path + " (" + lines(args.get("content")) + " lines)");
                default -> List.of(toolName);
            };
        }

        @Override
        public List<String> renderResult(String name, String textOutput, boolean isError,
                boolean expanded) {
            var text = textOutput == null ? "" : textOutput;
            return switch (toolName) {
                case "bash" -> List.of(exitStatus(text), truncateCommand(text.trim()));
                case "edit" -> List.of(diffCounts(text));
                case "read", "write" -> List.of("(" + text.lines().count() + " lines)"
                    + (text.length() > 10000 ? " [truncated]" : ""));
                default -> List.of(text);
            };
        }

        private static int lines(Object content) {
            return content instanceof String s ? (int) s.lines().count() : 0;
        }
    }
}

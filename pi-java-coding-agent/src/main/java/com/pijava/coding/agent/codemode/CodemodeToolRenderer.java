package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.pijava.coding.agent.extension.ToolCallRenderer;
import com.pijava.coding.agent.extension.ToolRendererResolver;

/**
 * Text-level renderer for codemode calls, aligned with {@code renderer.ts}.
 * Colors and animations are intentionally omitted in the Java text projection.
 */
public final class CodemodeToolRenderer implements ToolRendererResolver {
    private static final int CALL_PREVIEW_COUNT = 8;
    private static final int COLLAPSED_ARGS_CHARS = 80;

    @Override
    public ToolCallRenderer resolve(String toolName, Supplier<ToolCallRenderer> next) {
        if (!CodemodeTool.NAME.equals(toolName)) {
            return next.get();
        }
        return new ToolCallRenderer() {
            @Override
            public List<String> renderCall(String name, Map<String, Object> args, boolean expanded) {
                List<String> lines = new ArrayList<>();
                lines.add("codemode");
                Object code = args == null ? null : args.get("code");
                if (code == null) {
                    lines.add("[invalid arg]");
                    return lines;
                }
                String source = String.valueOf(code).replace("\r", "").stripTrailing();
                String[] all = source.split("\n", -1);
                int limit = expanded ? all.length : Math.min(10, all.length);
                for (int i = 0; i < limit; i++) {
                    lines.add(all[i]);
                }
                if (limit < all.length) {
                    lines.add("... (" + (all.length - limit) + " more lines, to expand)");
                }
                return lines;
            }

            @Override
            public List<String> renderResult(String name, String textOutput, boolean isError, boolean expanded) {
                List<String> lines = new ArrayList<>();
                if (textOutput == null || textOutput.isEmpty()) {
                    return lines;
                }
                String output = stripHeader(textOutput);
                String[] all = output.split("\n", -1);
                int limit = expanded ? all.length : Math.min(5, all.length);
                for (int i = 0; i < limit; i++) {
                    lines.add(all[i]);
                }
                if (limit < all.length) {
                    lines.add("... (" + (all.length - limit) + " more lines, to expand)");
                }
                return lines;
            }
        };
    }

    /** Renders nested call lines and the elapsed/finished label. */
    public static List<String> renderNestedCalls(List<CodemodeDetails.NestedCall> calls, boolean expanded) {
        List<String> lines = new ArrayList<>();
        if (calls.isEmpty()) {
            return lines;
        }
        List<CodemodeDetails.NestedCall> shown = expanded
            ? calls : calls.subList(Math.max(0, calls.size() - CALL_PREVIEW_COUNT), calls.size());
        if (shown.size() < calls.size()) {
            lines.add("... (" + (calls.size() - shown.size()) + " earlier calls, to expand)");
        }
        for (CodemodeDetails.NestedCall call : shown) {
            lines.add(formatCall(call, expanded));
        }
        return lines;
    }

    /** Returns the timer label shown for a running or finished call. */
    public static String timerLabel(long elapsedMs, boolean finished) {
        String duration = elapsedMs < 1000
            ? elapsedMs + "ms"
            : String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0) + "s";
        return (finished ? "Took " : "Elapsed ") + duration;
    }

    private static String formatCall(CodemodeDetails.NestedCall call, boolean expanded) {
        String args = call.args() == null ? "" : call.args();
        if (!expanded && args.length() > COLLAPSED_ARGS_CHARS) {
            args = args.substring(0, COLLAPSED_ARGS_CHARS - 3) + "...";
        }
        StringBuilder line = new StringBuilder(statusIcon(call.status())).append(' ').append(call.name());
        if (!args.isEmpty()) {
            line.append(' ').append(args);
        }
        if (call.durationMs() != null) {
            line.append(' ').append(timerLabel(call.durationMs().longValue(), true));
        }
        if (expanded && call.error() != null) {
            line.append('\n').append(call.error().replace("\n", "\n    "));
        }
        return line.toString();
    }

    private static String statusIcon(String status) {
        return switch (status) {
            case "running" -> "...";
            case "ok" -> "OK";
            case "error" -> "X";
            case "cancelled" -> "-";
            default -> "?";
        };
    }

    private static String stripHeader(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || !lines[0].matches("Script (completed|failed)")) {
            return text;
        }
        if (!lines[1].matches("Wall time [0-9.]+ seconds") || !lines[2].equals("Output:")) {
            return text;
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, 3, lines.length));
    }
}

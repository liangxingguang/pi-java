package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.message.ContentBlock;
import com.pijava.codemode.api.CodeModeData;

/** Output formatting helpers for {@link CodemodeExecutor}. */
final class CodemodeExecutorSupport {
    private CodemodeExecutorSupport() {
    }

    /** Formats script output items into text blocks, matching pi's {@code formatOutput}. */
    static List<CodeModeData.OutputItem> formatOutput(List<CodeModeData.OutputItem> output) {
        int total = 0;
        for (CodeModeData.OutputItem item : output) {
            if (item instanceof CodeModeData.Text text && !text.console()) {
                total++;
            }
        }
        List<CodeModeData.OutputItem> items = new ArrayList<>();
        List<String> consoleLines = new ArrayList<>();
        int index = 0;
        for (CodeModeData.OutputItem item : output) {
            if (item instanceof CodeModeData.Text text) {
                if (text.console()) {
                    consoleLines.add(text.text());
                } else {
                    index++;
                    String prefix = total > 1 ? "==> text " + index + "/" + total + " <==\n" : "";
                    items.add(new CodeModeData.Text(prefix + text.text(), false));
                }
            } else {
                items.add(item);
            }
        }
        if (!consoleLines.isEmpty()) {
            items.add(new CodeModeData.Text(
                "<console_output>\n" + String.join("\n", consoleLines) + "\n</console_output>", false));
        }
        return items;
    }

    /** Joins adjacent text blocks with a newline separator, like pi's {@code joinAdjacentText}. */
    static List<CodeModeData.OutputItem> joinAdjacentText(List<CodeModeData.OutputItem> items) {
        List<CodeModeData.OutputItem> joined = new ArrayList<>();
        StringBuilder parts = new StringBuilder();
        String lastPart = "";
        for (CodeModeData.OutputItem item : items) {
            if (item instanceof CodeModeData.Image) {
                flush(joined, parts);
                lastPart = "";
                joined.add(item);
                continue;
            }
            String text = ((CodeModeData.Text) item).text();
            boolean console = ((CodeModeData.Text) item).console();
            if (!lastPart.isEmpty() && !lastPart.endsWith("\n")) {
                parts.append('\n');
                lastPart = "\n";
            }
            parts.append(text);
            if (!text.isEmpty()) {
                lastPart = text;
            }
            if (console && parts.length() > 0) {
                flush(joined, parts);
                lastPart = "";
            }
        }
        flush(joined, parts);
        return joined;
    }

    private static void flush(List<CodeModeData.OutputItem> joined, StringBuilder parts) {
        if (parts.length() > 0) {
            joined.add(new CodeModeData.Text(parts.toString(), false));
            parts.setLength(0);
        }
    }

    /** Returns the text of a nested tool result. */
    static String textOf(List<ContentBlock> content) {
        List<String> texts = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.TextContent text) {
                texts.add(text.text());
            }
        }
        return String.join("\n", texts);
    }

    /** Turns a text block into an output item. */
    static CodeModeData.OutputItem text(String value) {
        return new CodeModeData.Text(value, false);
    }

    /** Returns the value a script sees after a nested tool call. */
    static Object toScriptValue(AgentTool<?, ?> tool, com.pijava.agent.harness.PiLoop.ToolOutcome outcome) {
        Object structured = outcome.result() == null ? null : outcome.result().structuredContent();
        if (tool.outputSchema() != null && structured != null) {
            return structured;
        }
        String text = outcome.result() == null ? "" : textOf(outcome.result().content());
        if (outcome.isError()) {
            throw new IllegalStateException(text.isEmpty() ? "Tool \"" + tool.name() + "\" failed" : text);
        }
        return text;
    }

    /** Truncates text to a preview length, appending {@code ...}. */
    static String truncateText(String text, int maxChars) {
        return text.length() > maxChars ? text.substring(0, maxChars - 3) + "..." : text;
    }

    /** Compact JSON preview of nested call arguments. */
    static String previewArgs(Object args) {
        if (args == null) {
            return "";
        }
        return truncateText(CodemodeToolSchemas.stringify(args), 200);
    }

    /** Status of a running call that was cut off by the script ending. */
    static List<CodemodeDetails.NestedCall> cancelRunning(List<CodemodeDetails.NestedCall> calls) {
        List<CodemodeDetails.NestedCall> result = new ArrayList<>();
        for (CodemodeDetails.NestedCall call : calls) {
            result.add("running".equals(call.status())
                ? new CodemodeDetails.NestedCall(call.id(), call.name(), call.args(), "cancelled",
                    call.durationMs(), call.error(), call.cost())
                : call);
        }
        return result;
    }

    /** Formats the call summary line on a failed script. */
    static String formatCallSummary(List<CodemodeDetails.NestedCall> calls) {
        if (calls.isEmpty()) {
            return "No tool calls were made.";
        }
        List<String> names = new ArrayList<>();
        for (CodemodeDetails.NestedCall call : calls) {
            names.add(call.name() + " (" + call.status() + ")");
        }
        return "Tool calls made before the failure (they are not undone): " + String.join(", ", names);
    }

    /** Formats the script error tail. */
    static String formatError(CodeModeData.CodemodeError error, List<CodemodeDetails.NestedCall> calls) {
        String kind = error.kind();
        String head;
        if ("script".equals(kind)) {
            head = error.stack() == null || error.stack().isEmpty()
                ? (error.name() == null ? "Error" : error.name()) + ": " + error.message()
                : error.stack();
        } else if ("timeout".equals(kind)) {
            head = "Script timed out: " + error.message();
        } else if ("aborted".equals(kind)) {
            head = "Script aborted: " + error.message();
        } else {
            head = "Script sandbox failed: " + error.message();
        }
        return head + "\n\n" + formatCallSummary(calls);
    }

    /** Returns the current monotonic time in milliseconds. */
    static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Converts elapsed milliseconds to pi's one-decimal seconds string. */
    static String wallTime(long startedAt) {
        return String.format(java.util.Locale.ROOT, "%.1f", (now() - startedAt) / 1000.0);
    }
}

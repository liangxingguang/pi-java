package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;

/**
 * File editing tool using exact string replacement.
 * Aligned with pi's {@code createEditTool}.
 *
 * <p>Schema: { path: String, edits: Array<{ oldText: String, newText: String }> }
 * Each edit's oldText must be unique and non-overlapping.
 * Creates .bak file before modification.</p>
 */
public final class EditTool {
    private EditTool() {}

    public record EditInput(String path, List<Edit> edits) {}
    public record Edit(String oldText, String newText) {}
    public record EditDetails(String diff, String patch, int firstChangedLine) {}

    public static AgentTool<EditInput, EditDetails> create() {
        return new AgentTool<>() {
            @Override public String name() { return "edit"; }
            @Override public String label() { return "edit"; }
            @Override public String description() {
                return "Edit a single file using exact text replacement. "
                    + "Every edits[].oldText must match a unique, non-overlapping region "
                    + "of the original file.";
            }
            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string",
                            "description", "Path to the file to edit (relative or absolute)"),
                        "edits", Map.of(
                            "type", "array",
                            "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "oldText", Map.of("type", "string",
                                        "description", "Exact text to replace"),
                                    "newText", Map.of("type", "string",
                                        "description", "Replacement text")
                                ),
                                "required", List.of("oldText", "newText")
                            )
                        )
                    ),
                    "required", List.of("path", "edits")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

            @Override
            @SuppressWarnings("unchecked")
            public EditInput prepareArguments(Map<String, Object> raw) {
                String path = (String) raw.get("path");
                List<Map<String, Object>> editsRaw = (List<Map<String, Object>>) raw.get("edits");
                var edits = editsRaw.stream()
                    .map(e -> new Edit((String) e.get("oldText"), (String) e.get("newText")))
                    .toList();
                return new EditInput(path, edits);
            }

            @Override
            public ToolResult<EditDetails> execute(String toolCallId, EditInput params,
                    AbortSignal signal, ToolUpdateCallback<EditDetails> onUpdate,
                    ToolContext context) throws Exception {
                if (params.edits() == null || params.edits().isEmpty()) {
                    throw new IllegalArgumentException(
                        "edits must contain at least one replacement");
                }

                String absolutePath = PathUtils.resolveToolPath(context, params.path());
                var info = context.fs().fileInfo(absolutePath);
                if (!"file".equals(info.kind()) && !"symlink".equals(info.kind())) {
                    throw new IllegalArgumentException("Path is not a file: " + params.path());
                }

                String originalContent = Files.readString(Path.of(absolutePath));
                // Create .bak
                Files.copy(Path.of(absolutePath), Path.of(absolutePath + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);

                String newContent = originalContent;
                int firstLine = -1;
                for (var edit : params.edits()) {
                    String oldText = edit.oldText();
                    String newText = edit.newText();
                    int idx = newContent.indexOf(oldText);
                    if (idx == -1) {
                        throw new IllegalArgumentException(
                            "Could not find oldText in file: " + params.path()
                            + "\noldText: " + truncateForError(oldText));
                    }
                    if (newContent.indexOf(oldText, idx + 1) != -1) {
                        throw new IllegalArgumentException(
                            "oldText is not unique in file: " + params.path()
                            + "\noldText: " + truncateForError(oldText));
                    }
                    if (firstLine == -1) {
                        firstLine = (int) newContent.substring(0, idx).lines().count() + 1;
                    }
                    newContent = newContent.substring(0, idx) + newText
                        + newContent.substring(idx + oldText.length());
                }

                context.fs().writeFile(absolutePath, newContent);
                String diffStr = generateSimpleDiff(originalContent, newContent);
                return new ToolResult<>(
                    List.of(new ContentBlock.TextContent(
                        "Successfully replaced " + params.edits().size()
                        + " block(s) in " + params.path() + ".")),
                    new EditDetails(diffStr, "", firstLine),
                    null, false, List.of());
            }

            private String truncateForError(String text) {
                if (text.length() <= 100) return text;
                return text.substring(0, 100) + "...";
            }

            private String generateSimpleDiff(String original, String newContent) {
                var sb = new StringBuilder();
                var origLines = original.lines().toList();
                var newLines = newContent.lines().toList();
                int maxLen = Math.max(origLines.size(), newLines.size());
                int diffStart = -1;
                int diffEnd = -1;
                for (int i = 0; i < maxLen; i++) {
                    String origLine = i < origLines.size() ? origLines.get(i) : "";
                    String newLine = i < newLines.size() ? newLines.get(i) : "";
                    if (!origLine.equals(newLine)) {
                        if (diffStart == -1) diffStart = i;
                        diffEnd = i + 1;
                    }
                }
                if (diffStart >= 0) {
                    for (int i = Math.max(0, diffStart - 2);
                         i < Math.min(maxLen, diffEnd + 2); i++) {
                        String origLine = i < origLines.size() ? origLines.get(i) : "";
                        String newLine = i < newLines.size() ? newLines.get(i) : "";
                        char marker = origLine.equals(newLine) ? ' ' : '-';
                        sb.append(marker).append(" ").append(origLine).append("\n");
                        if (!origLine.equals(newLine)) {
                            sb.append("+ ").append(newLine).append("\n");
                        }
                    }
                }
                return sb.toString();
            }
        };
    }
}

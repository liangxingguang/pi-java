package com.pijava.agent.tool.builtin;

import com.pijava.ai.AbortSignal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.PathUtils;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.message.ContentBlock;

/**
 * File editing tool using exact string replacement.
 * Aligned with pi's {@code createEditTool} + {@code edit-diff.ts}.
 *
 * <p>Schema: { path: String, edits: Array<{ oldText: String, newText: String }> }
 * Each edit's oldText must be unique and non-overlapping. All edits are matched
 * against the original (LF-normalized) content — exact match first, then a
 * fuzzy NFKC-normalized fallback — and applied in reverse order. Line endings
 * and a leading BOM are preserved. Mutations to the same file are serialized
 * through {@link FileMutationQueue}. Creates a .bak before modification.</p>
 */
public final class EditTool {
    private EditTool() {}

    /** Serializes edits to the same canonical path across concurrent tool calls. */
    private static final FileMutationQueue QUEUE = new FileMutationQueue();

    public record EditInput(String path, List<Edit> edits) {}
    public record Edit(String oldText, String newText) {}
    public record EditDetails(String diff, String patch, int firstChangedLine) {}

    /** Create the edit tool. */
    public static AgentTool<EditInput, EditDetails> create() {
        return new AgentTool<>() {
            @Override public String name() { return "edit"; }
            @Override public String label() { return "edit"; }
            @Override public String description() {
                return "Edit a single file using exact text replacement. "
                    + "Every edits[].oldText must match a unique, non-overlapping region "
                    + "of the original file. If two changes affect the same block or nearby "
                    + "lines, merge them into one edit instead of emitting overlapping edits.";
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
            // unchecked cast unavoidable: Map<String,Object>::get returns Object,
            // and generic array elements erase to raw Map at runtime
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
                    throw new IllegalArgumentException(
                        "Could not edit file: " + params.path() + ". Path is not a file.");
                }

                final Path target = Path.of(absolutePath);
                // The read→apply→write is atomic under the queue so two concurrent
                // edits to the same file never read stale content and overwrite each other.
                return QUEUE.withQueue(absolutePath, () -> {
                    String read = Files.readString(target);
                    var bom = EditDiff.stripBom(read);
                    String originalEnding = EditDiff.detectLineEnding(bom.text());
                    String normalizedContent = EditDiff.normalizeToLF(bom.text());

                    var normalizedEdits = params.edits().stream()
                        .map(e -> new EditDiff.Edit(e.oldText(), e.newText()))
                        .toList();
                    var applied = EditDiff.applyEditsToNormalizedContent(
                        normalizedContent, normalizedEdits, params.path());

                    String newContent = bom.bom()
                        + EditDiff.restoreLineEndings(applied.newContent(), originalEnding);

                    Files.copy(target, Path.of(absolutePath + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
                    context.fs().writeFile(absolutePath, newContent);

                    var diffResult = LineDiff.generateDiffString(
                        applied.baseContent(), applied.newContent(), 4);
                    String patchStr = LineDiff.generateUnifiedPatch(
                        params.path(), params.path(),
                        applied.baseContent(), applied.newContent(), 4);

                    var blocks = new ArrayList<ContentBlock>();
                    blocks.add(new ContentBlock.TextContent(
                        "Successfully replaced " + params.edits().size()
                        + " block(s) in " + params.path() + "."));
                    if (diffResult.diff() != null && !diffResult.diff().isEmpty()) {
                        blocks.add(new ContentBlock.DiffContent(diffResult.diff()));
                    }
                    int firstLine = diffResult.firstChangedLine() == null
                        ? -1 : diffResult.firstChangedLine();
                    return new ToolResult<>(
                        blocks,
                        new EditDetails(diffResult.diff(), patchStr, firstLine),
                        null, false, List.of());
                });
            }
        };
    }
}

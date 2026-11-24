package com.pijava.agent.tool.builtin;

import com.pijava.ai.AbortSignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.FileInfo;
import com.pijava.agent.tool.FileSystem;
import com.pijava.agent.tool.PathUtils;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.api.JsonSchemaSampling;
import com.pijava.ai.api.StrictMode;
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

    /** Serializes edits to the same canonical path across concurrent tool calls（与 write 共享，pi 模块级单例）。 */
    private static final FileMutationQueue QUEUE = FileMutationQueue.shared();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static boolean hasTextFields(Map<?, ?> map) {
        return map.get("oldText") instanceof String && map.get("newText") instanceof String;
    }

    private static Edit fromMap(Map<?, ?> map) {
        return new Edit((String) map.get("oldText"), (String) map.get("newText"));
    }

    public record EditInput(String path, List<Edit> edits) {}
    public record Edit(String oldText, String newText) {}
    public record EditDetails(String diff, String patch, int firstChangedLine) {}

    /**
     * 文件编辑接缝（对位 pi {@code EditOperations}，B287）：默认实现走
     * {@link ToolContext#fs()}；远程后端可注入自己的实现。
     */
    public interface Operations {
        byte[] readFile(String absolutePath) throws IOException;
        void writeFile(String absolutePath, String content) throws IOException;
        void access(String absolutePath) throws IOException;

        static Operations from(ToolContext context) {
            FileSystem fs = context.fs();
            return new Operations() {
                @Override public byte[] readFile(String absolutePath) throws IOException {
                    return fs.readBinary(absolutePath);
                }
                @Override public void writeFile(String absolutePath, String content)
                        throws IOException {
                    fs.writeFile(absolutePath, content);
                }
                @Override public void access(String absolutePath) throws IOException {
                    FileInfo info = fs.fileInfo(absolutePath);
                    if (!"file".equals(info.kind()) && !"symlink".equals(info.kind())) {
                        throw new IOException("Path is not a file.");
                    }
                }
            };
        }
    }

    /** Create the edit tool. */
    public static AgentTool<EditInput, EditDetails> create() {
        return create(null);
    }

    /** Create the edit tool with an injectable filesystem seam. */
    public static AgentTool<EditInput, EditDetails> create(Operations operations) {
        return new AgentTool<>() {
            @Override public String name() { return "edit"; }
            @Override public String label() { return "edit"; }
            @Override public String promptSnippet() {
                // pi editToolSystemPromptContribution.snippet（edit.ts:44）逐字。
                return "Make precise file edits with exact text replacement, "
                    + "including multiple disjoint edits in one call";
            }
            @Override public List<String> promptGuidelines() {
                // pi editToolSystemPromptContribution.guidelines（edit.ts:45-48）逐字两条。
                return List.of(
                    "Use edit for targeted changes. Batch changes to one file in a single call.",
                    "Each edits[].oldText must exactly and uniquely match the original file. "
                        + "Keep matches short and non-overlapping; merge nearby changes without "
                        + "including large unchanged regions.");
            }
            @Override public String description() {
                // pi edit.ts:146-148 逐字。
                return "Edit a single file using exact text replacement. "
                    + "Every edits[].oldText must match a unique, non-overlapping region "
                    + "of the original file. If two changes affect the same block or nearby "
                    + "lines, merge them into one edit instead of emitting overlapping edits. "
                    + "Do not include large unchanged regions just to connect distant changes.";
            }
            @Override
            public Map<String, Object> inputSchema() {
                // pi edit.ts:20-36 逐字。
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string",
                            "description", "Path to the file to edit (relative or absolute)"),
                        "edits", Map.of(
                            "type", "array",
                            "description", "One or more targeted replacements. Each edit is "
                                + "matched against the original file, not incrementally. Do not "
                                + "include overlapping or nested edits. If two changes touch the "
                                + "same block or nearby lines, merge them into one edit instead.",
                            "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "oldText", Map.of("type", "string",
                                        "description", "Exact text for one targeted replacement. "
                                            + "It must be unique in the original file and must not "
                                            + "overlap with any other edits[].oldText in the same call."),
                                    "newText", Map.of("type", "string",
                                        "description", "Replacement text for this targeted edit.")
                                ),
                                "required", List.of("oldText", "newText")
                            )
                        )
                    ),
                    "required", List.of("path", "edits")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

            // 原 docs/66：pi 的 edit 默认 strict-prefer。
            @Override public JsonSchemaSampling constrainedSampling() {
                return new JsonSchemaSampling(StrictMode.PREFER);
            }

            @Override
            public EditInput prepareArguments(Map<String, Object> raw) {
                // pi prepareEditArguments（edit.ts:98-125）：edits 可能是 JSON 字符串或单个
                // edit 对象；模型也可能发 legacy 的顶层 oldText/newText，一并折成 edits[]。
                Object editsRaw = raw.get("edits");
                if (editsRaw instanceof String json) {
                    try {
                        editsRaw = JSON.readValue(json, Object.class);
                    } catch (JsonProcessingException ignored) {
                        editsRaw = null;
                    }
                }
                var edits = new ArrayList<Edit>();
                if (editsRaw instanceof Map<?, ?> map) {
                    if (hasTextFields(map)) {
                        edits.add(fromMap(map));
                    }
                } else if (editsRaw instanceof List<?> list) {
                    for (Object element : list) {
                        if (element instanceof Map<?, ?> map && hasTextFields(map)) {
                            edits.add(fromMap(map));
                        }
                    }
                }
                if (raw.get("oldText") instanceof String oldText
                        && raw.get("newText") instanceof String newText) {
                    edits.add(new Edit(oldText, newText));
                }
                return new EditInput((String) raw.get("path"), List.copyOf(edits));
            }

            @Override
            public ToolResult<EditDetails> execute(String toolCallId, EditInput params,
                    AbortSignal signal, ToolUpdateCallback<EditDetails> onUpdate,
                    ToolContext context) throws Exception {
                if (params.edits() == null || params.edits().isEmpty()) {
                    throw new IllegalArgumentException(
                        "Edit tool input is invalid. edits must contain at least one replacement.");
                }

                String absolutePath = PathUtils.resolveToolPath(context, params.path());
                Operations ops = operations != null ? operations : Operations.from(context);
                try {
                    ops.access(absolutePath);
                } catch (IOException e) {
                    throw new IllegalArgumentException(
                        "Could not edit file: " + params.path() + ". " + e.getMessage(), e);
                }

                // The read→apply→write is atomic under the queue so two concurrent
                // edits to the same file never read stale content and overwrite each other.
                return QUEUE.withQueue(absolutePath, () -> {
                    String read = new String(ops.readFile(absolutePath), StandardCharsets.UTF_8);
                    var bom = EditDiff.stripBom(read);
                    String normalizedContent = EditDiff.normalizeToLF(bom.text());

                    var normalizedEdits = params.edits().stream()
                        .map(e -> new EditDiff.Edit(e.oldText(), e.newText()))
                        .toList();
                    var applied = EditDiff.applyEditsToNormalizedContent(
                        normalizedContent, normalizedEdits, params.path());

                    // Diff before writing, so diff computation cannot fail after the write.
                    var diffResult = LineDiff.generateDiffString(
                        applied.baseContent(), applied.newContent(), 4);
                    String patchStr = LineDiff.generateUnifiedPatch(
                        params.path(), params.path(),
                        applied.baseContent(), applied.newContent(), 4);

                    // Splice into the original text so untouched lines keep their line endings;
                    // without any CR the original already is the normalized text.
                    String newContent = bom.bom() + (bom.text().equals(normalizedContent)
                        ? applied.newContent()
                        : EditDiff.applyReplacementsToOriginal(bom.text(), applied.replacements()));

                    ops.writeFile(absolutePath + ".bak", read);
                    ops.writeFile(absolutePath, newContent);

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

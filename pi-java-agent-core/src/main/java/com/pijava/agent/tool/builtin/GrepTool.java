package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;

/**
 * Regular expression search in files. Aligned with pi's grep tool.
 *
 * <p>Schema: { pattern: String, path?: String, glob?: String }
 * Uses Java regex. path defaults to cwd; glob filters files.</p>
 */
public final class GrepTool {
    private static final int MAX_LINE_LENGTH = 500;

    private GrepTool() {}

    public record GrepInput(String pattern, Optional<String> path, Optional<String> glob) {}

    public static AgentTool<GrepInput, Void> create() {
        return new AgentTool<>() {
            @Override public String name() { return "grep"; }
            @Override public String label() { return "grep"; }
            @Override public String description() {
                return "Search files for a regular expression pattern. "
                    + "Returns matches with file path, line number, and matching line content.";
            }
            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pattern", Map.of("type", "string",
                            "description", "Regular expression pattern to search for"),
                        "path", Map.of("type", "string",
                            "description", "Directory or file to search in (defaults to cwd)"),
                        "glob", Map.of("type", "string",
                            "description", "Glob pattern to filter files (e.g. *.java)")
                    ),
                    "required", List.of("pattern")
                );
            }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }

            @Override
            @SuppressWarnings("unchecked")
            public GrepInput prepareArguments(Map<String, Object> raw) {
                String pattern = (String) raw.get("pattern");
                Optional<String> path = Optional.ofNullable((String) raw.get("path"));
                Optional<String> glob = Optional.ofNullable((String) raw.get("glob"));
                return new GrepInput(pattern, path, glob);
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, GrepInput params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate,
                    ToolContext context) throws Exception {
                String searchPath = params.path().orElse(context.cwd());
                String absolutePath = PathUtils.resolveToolPath(context, searchPath);
                Pattern pattern = Pattern.compile(params.pattern());

                var matches = new ArrayList<String>();
                var info = context.fs().fileInfo(absolutePath);

                if ("file".equals(info.kind())) {
                    grepFile(absolutePath, pattern, matches);
                } else {
                    grepDir(absolutePath, pattern, params.glob().orElse(null), context, matches);
                }

                if (matches.isEmpty()) {
                    return ToolResult.success("No matches found for pattern: "
                        + params.pattern());
                }
                return ToolResult.success(String.join("\n", matches));
            }

            private void grepFile(String filePath, Pattern pattern, List<String> matches) {
                try {
                    var lines = Files.readString(Path.of(filePath)).split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (pattern.matcher(lines[i]).find()) {
                            String line = lines[i].length() > MAX_LINE_LENGTH
                                ? lines[i].substring(0, MAX_LINE_LENGTH) + "... [truncated]"
                                : lines[i];
                            matches.add(filePath + ":" + (i + 1) + ": " + line);
                        }
                    }
                } catch (Exception ignored) {
                    // skip unreadable files
                }
            }

            private void grepDir(String dirPath, Pattern pattern, String glob,
                    ToolContext context, List<String> matches) {
                try {
                    var files = context.fs().listDir(dirPath, true);
                    for (var file : files) {
                        if (!"file".equals(file.kind())) continue;
                        if (glob != null && !matchesGlob(file.path(), glob)) continue;
                        grepFile(file.path(), pattern, matches);
                    }
                } catch (Exception ignored) {
                    // skip unreadable directories
                }
            }

            private boolean matchesGlob(String path, String globPattern) {
                var pathObj = Path.of(path);
                var matcher = pathObj.getFileSystem()
                    .getPathMatcher("glob:" + globPattern);
                return matcher.matches(pathObj.getFileName());
            }
        };
    }
}

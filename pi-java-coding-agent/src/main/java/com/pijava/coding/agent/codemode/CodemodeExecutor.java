package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolNamespace;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.codemode.api.*;
import com.pijava.codemode.backend.EndiveCodeModeLauncher;
import com.pijava.codemode.runtime.CodeModeExecuteOptions;
import com.pijava.codemode.runtime.CodeModeSandboxBase;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionToolInfo;

/** Runs one codemode script, mirroring pi's {@code execute.ts:500-652}. */
public final class CodemodeExecutor {
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 10_000;
    private static final int CHARS_PER_TOKEN = 4;
    private static final long MEMORY_LIMIT_BYTES = 256L * 1024 * 1024;
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
        "image/png", ".png", "image/jpeg", ".jpg", "image/gif", ".gif", "image/webp", ".webp");

    private final ExtensionContext ctx;
    private final Supplier<CodeModeVmLauncher> launcherFactory;
    private final CodemodeOutputFiles outputFiles;

    /** Creates an executor using the Endive launcher and temp output files. */
    public CodemodeExecutor(ExtensionContext ctx) {
        this(ctx, EndiveCodeModeLauncher::new, CodemodeOutputFiles.tempFiles());
    }

    /**
     * Creates an executor with injectable VM and output sinks.
     *
     * @param ctx extension context
     * @param launcherFactory launcher factory
     * @param outputFiles output file saver
     */
    public CodemodeExecutor(ExtensionContext ctx, Supplier<CodeModeVmLauncher> launcherFactory,
                            CodemodeOutputFiles outputFiles) {
        this.ctx = ctx;
        this.launcherFactory = launcherFactory;
        this.outputFiles = outputFiles;
    }

    /**
     * Executes a codemode call.
     *
     * @param toolCallId the parent tool call id
     * @param params raw tool arguments
     * @param signal abort signal, may be {@code null}
     * @param onUpdate progress callback, may be {@code null}
     * @return the final tool result
     * @throws Exception on source parsing or sandbox launch failures
     */
    public ToolResult<CodemodeDetails> execute(String toolCallId, Map<String, Object> params, AbortSignal signal,
                                               ToolUpdateCallback<CodemodeDetails> onUpdate) throws Exception {
        long startedAt = CodemodeExecutorSupport.now();
        Object rawCode = params == null ? null : params.get("code");
        if (!(rawCode instanceof String source)) {
            throw new IllegalArgumentException("codemode expects a string `code` argument");
        }
        CodeModeSource.Parsed parsed = CodeModeSource.parse(source);
        List<CodemodeDetails.NestedCall> calls = new ArrayList<>();
        publish(onUpdate, calls, null);

        List<AgentTool<?, ?>> callable = callableTools();
        Map<String, String> samples = new LinkedHashMap<>();
        Map<String, ToolNamespace> namespaces = new LinkedHashMap<>();
        Map<String, List<String>> guidelines = new LinkedHashMap<>();
        Map<String, ExtensionToolInfo> infos = new LinkedHashMap<>();
        for (AgentTool<?, ?> tool : callable) {
            var declaration = CodemodeDescription.toDeclaration(tool, tool.promptGuidelines());
            samples.put(tool.name(), CodeModeDeclarations.renderToolSample(declaration));
            namespaces.put(tool.name(), tool.namespace());
            guidelines.put(tool.name(), tool.promptGuidelines());
            infos.put(tool.name(), new ExtensionToolInfo(tool.name(), tool.description(),
                tool.promptGuidelines(), tool.exposure(), tool.inputSchema(), tool.namespace(),
                tool.annotations()));
        }

        Map<String, ScriptTool> tools = new LinkedHashMap<>();
        List<CodemodeDetails.NestedCall> capturedCalls = calls;
        for (AgentTool<?, ?> tool : callable) {
            tools.put(tool.name(), args -> callTool(tool, toolCallId, args, signal, onUpdate, capturedCalls));
        }
        Map<String, ScriptTool> globals = new LinkedHashMap<>(CodemodeDiscovery.globals(
            callable, samples, namespaces, guidelines, infos));
        globals.putAll(CodemodeModels.globals(ctx));

        // 等待 worker 的时间计入脚本截止时间（execute.ts:554-558）。
        Long sourceTimeout = parsed.options().timeoutMs();
        long deadline = sourceTimeout == null ? Long.MAX_VALUE : sourceTimeout;
        long waitStartedAt = CodemodeExecutorSupport.now();
        CodemodeWorkers.Admission admission =
            CodemodeWorkers.reserve(signal, Math.min(deadline, CodemodeWorkers.WAIT_MS));
        long waitedMs = CodemodeExecutorSupport.now() - waitStartedAt;
        if (admission != CodemodeWorkers.Admission.RESERVED) {
            return admissionFailure(admission, deadline, startedAt, calls);
        }
        List<CodeModeData.ToolDecl> declarations = new ArrayList<>();
        for (AgentTool<?, ?> tool : callable) {
            declarations.add(new CodeModeData.ToolDecl(
                tool.name(),
                CodeModeIdentifier.toIdentifier(tool.name()),
                samples.getOrDefault(tool.name(), tool.description())));
        }
        CodeModeData.Result result;
        try {
            long executionTimeout = deadline == Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(1, deadline - waitedMs);
            CodeModeSandboxBase sandbox = new CodeModeSandboxBase(tools, globals, declarations,
                executionTimeout, MEMORY_LIMIT_BYTES, launcherFactory.get());
            try {
                Map<String, Object> store = CodemodeStore.read(ctx);
                result = sandbox.execute(parsed.code(),
                    new CodeModeExecuteOptions(executionTimeout, store, cancellation(signal))).join();
            } finally {
                sandbox.close();
            }
        } finally {
            CodemodeWorkers.release();
        }
        calls = CodemodeExecutorSupport.cancelRunning(calls);
        List<CodeModeData.OutputItem> output = new ArrayList<>(result.output());
        String fullOutputPath = null;
        if (result.ok()) {
            CodeModeData.StoreWrites writes = result.storeWrites();
            CodemodeStore.write(ctx, writes.set(), writes.delete());
            if (result.value() != null) {
                output.add(CodemodeExecutorSupport.text(CodemodeToolSchemas.stringify(result.value())));
            }
        }
        List<CodeModeData.OutputItem> items = CodemodeExecutorSupport.formatOutput(output);
        Truncation truncation = truncate(items, parsed);
        items = truncation.items();
        fullOutputPath = truncation.fullOutputPath();
        if (!result.ok()) {
            items.add(CodemodeExecutorSupport.text(
                "Script error:\n" + CodemodeExecutorSupport.formatError(result.error(), calls)));
        }
        items = saveImages(CodemodeExecutorSupport.joinAdjacentText(items));
        String header = (result.ok() ? "Script completed" : "Script failed")
            + "\nWall time " + CodemodeExecutorSupport.wallTime(startedAt) + " seconds\nOutput:\n";
        List<ContentBlock> content = new ArrayList<>();
        content.add(new ContentBlock.TextContent(header));
        for (CodeModeData.OutputItem item : items) {
            if (item instanceof CodeModeData.Text text) {
                content.add(new ContentBlock.TextContent(text.text()));
            } else {
                CodeModeData.Image image = (CodeModeData.Image) item;
                content.add(new ContentBlock.ImageContent(image.mimeType(), image.data()));
            }
        }
        return new ToolResult<>(content, new CodemodeDetails(calls, fullOutputPath), null, false,
            List.of(), !result.ok(), null);
    }

    /** Failure result when no worker slot could be reserved (execute.ts:559-575). */
    private ToolResult<CodemodeDetails> admissionFailure(CodemodeWorkers.Admission admission, long deadline,
                                                         long startedAt, List<CodemodeDetails.NestedCall> calls) {
        String error;
        if (admission == CodemodeWorkers.Admission.ABORTED) {
            error = "Script aborted: Execution aborted";
        } else if (deadline <= CodemodeWorkers.WAIT_MS) {
            error = "Script timed out: Execution timed out after " + deadline
                + " ms while waiting for a free codemode worker";
        } else {
            error = "No codemode worker became free within " + (CodemodeWorkers.WAIT_MS / 1000)
                + " seconds. All " + CodemodeWorkers.maxWorkers()
                + " workers, shared by every session in this process, are held by other scripts."
                + " Retry after they finish.";
        }
        List<ContentBlock> content = List.of(
            new ContentBlock.TextContent("Script failed\nWall time "
                + CodemodeExecutorSupport.wallTime(startedAt) + " seconds\nOutput:\n"),
            new ContentBlock.TextContent("Script error:\n" + error + "\n\nThe script did not start."));
        return new ToolResult<>(content, new CodemodeDetails(new ArrayList<>(calls), null),
            null, false, List.of(), true, null);
    }

    /** Adapts the agent cancellation signal to the codemode runtime API. */
    private static CodeModeAbortSignal cancellation(AbortSignal signal) {
        if (signal == null) {
            return null;
        }
        return new CodeModeAbortSignal() {
            @Override
            public boolean isAborted() {
                return signal.isAborted();
            }

            @Override
            public Runnable onAbort(Runnable listener) {
                return signal.onAbort(listener);
            }
        };
    }

    private List<AgentTool<?, ?>> callableTools() {
        List<AgentTool<?, ?>> tools = new ArrayList<>();
        for (AgentTool<?, ?> tool : ctx.callableTools()) {
            if (!CodemodeTool.NAME.equals(tool.name())) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private Object callTool(AgentTool<?, ?> tool, String toolCallId, Object args, AbortSignal signal,
                            ToolUpdateCallback<CodemodeDetails> onUpdate,
                            List<CodemodeDetails.NestedCall> calls) {
        Map<String, Object> arguments = asArguments(args);
        int sequence = calls.size() + 1;
        CodemodeDetails.NestedCall record = new CodemodeDetails.NestedCall(
            toolCallId + "/?", tool.name(), CodemodeExecutorSupport.previewArgs(arguments), "running");
        calls.add(record);
        publish(onUpdate, calls, null);
        long callStartedAt = CodemodeExecutorSupport.now();
        com.pijava.agent.harness.PiLoop.ToolOutcome outcome =
            ctx.executeTool(toolCallId, signal, tool.name(), arguments, update -> publish(onUpdate, calls, null));
        int index = calls.indexOf(record);
        long durationMs = CodemodeExecutorSupport.now() - callStartedAt;
        String status = outcome.isError() ? "error" : "ok";
        String error = outcome.isError()
            ? CodemodeExecutorSupport.truncateText(
                CodemodeExecutorSupport.textOf(outcome.result().content()), 500) : null;
        String id = outcome.message() == null ? toolCallId + "/" + sequence : outcome.message().toolUseId();
        calls.set(index, new CodemodeDetails.NestedCall(id, tool.name(), record.args(), status,
            (double) durationMs, error, null));
        publish(onUpdate, calls, null);
        return CodemodeExecutorSupport.toScriptValue(tool, outcome);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asArguments(Object args) {
        if (args instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static void publish(ToolUpdateCallback<CodemodeDetails> onUpdate,
                                List<CodemodeDetails.NestedCall> calls, String fullOutputPath) {
        if (onUpdate != null) {
            onUpdate.onUpdate(new ToolResult<>(List.of(),
                new CodemodeDetails(new ArrayList<>(calls), fullOutputPath), null, false, List.of()));
        }
    }

    private record Truncation(List<CodeModeData.OutputItem> items, String fullOutputPath) {
    }

    private Truncation truncate(List<CodeModeData.OutputItem> items, CodeModeSource.Parsed parsed) {
        List<CodeModeData.OutputItem> joined = CodemodeExecutorSupport.joinAdjacentText(items);
        List<CodeModeData.OutputItem> texts = joined.stream()
            .filter(item -> item instanceof CodeModeData.Text).toList();
        if (texts.isEmpty()) {
            return new Truncation(joined, null);
        }
        StringBuilder combined = new StringBuilder();
        for (CodeModeData.OutputItem item : texts) {
            if (combined.length() > 0) {
                combined.append('\n');
            }
            combined.append(((CodeModeData.Text) item).text());
        }
        int budget = (int) ((parsed.options().maxOutputTokens() == null
            ? DEFAULT_MAX_OUTPUT_TOKENS : parsed.options().maxOutputTokens()) * CHARS_PER_TOKEN);
        if (combined.length() <= budget) {
            return new Truncation(joined, null);
        }
        int headChars = budget / 2;
        int tailChars = budget - headChars;
        int removed = combined.length() - headChars - tailChars;
        String text = "Warning: truncated output (original token count: "
            + (combined.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + ")\nTotal output lines: "
            + (combined.toString().split("\n", -1).length) + "\n\n"
            + combined.substring(0, headChars) + "…"
            + (removed + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + " tokens truncated…"
            + combined.substring(combined.length() - tailChars);
        String fullOutputPath = null;
        try {
            fullOutputPath = outputFiles.saveText(combined.toString());
            text += "\n\n[Full output: " + fullOutputPath + " (read with offset/limit)]";
        } catch (Exception error) {
            text += "\n\n[Could not save the full output: " + error.getMessage() + "]";
        }
        List<CodeModeData.OutputItem> result = new ArrayList<>();
        result.add(CodemodeExecutorSupport.text(text));
        for (CodeModeData.OutputItem item : joined) {
            if (item instanceof CodeModeData.Image) {
                result.add(item);
            }
        }
        return new Truncation(result, fullOutputPath);
    }

    private List<CodeModeData.OutputItem> saveImages(List<CodeModeData.OutputItem> items) {
        List<CodeModeData.OutputItem> result = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (CodeModeData.OutputItem item : items) {
            if (!(item instanceof CodeModeData.Image image)) {
                result.add(item);
                continue;
            }
            String label = labels.computeIfAbsent(image.data(), data -> imageLabel(image));
            result.add(CodemodeExecutorSupport.text(label));
            result.add(item);
        }
        return result;
    }

    private String imageLabel(CodeModeData.Image image) {
        String extension = IMAGE_EXTENSIONS.get(image.mimeType());
        String kind = image.mimeType() + ", " + CodemodeOutputFiles.formatSize(
            Base64.getDecoder().decode(image.data()).length);
        if (extension == null) {
            return "[Image (" + kind + ") could not be saved: No file extension for image type "
                + image.mimeType() + "]";
        }
        try {
            return "[Image saved to " + outputFiles.save(Base64.getDecoder().decode(image.data()), extension)
                + " (" + kind + ")]";
        } catch (Exception error) {
            return "[Image (" + kind + ") could not be saved: " + error.getMessage() + "]";
        }
    }
}

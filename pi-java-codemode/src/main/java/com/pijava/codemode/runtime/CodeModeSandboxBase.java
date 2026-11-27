package com.pijava.codemode.runtime;

import com.pijava.codemode.api.CodeModeData;
import com.pijava.codemode.api.CodeModeVmLauncher;
import com.pijava.codemode.api.ScriptTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Tool table, defaults, and in-flight executions shared by every VM launcher. */
public class CodeModeSandboxBase {
    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

    private final Map<String, ScriptTool> toolsByName = new ConcurrentHashMap<>();
    private final Map<String, ScriptTool> globalsByName = new ConcurrentHashMap<>();
    private final List<CodeModeData.ToolDecl> declarations;
    private final long timeoutMs;
    private final Long memoryLimitBytes;
    private final CodeModeVmLauncher launcher;
    private final Set<CodeModeExecution> running = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * Creates a sandbox with registered tools and globals.
     *
     * @param tools tool implementations, keyed by tool name
     * @param globals global implementations, keyed by global name
     * @param timeoutMs default execution timeout; non-positive values mean no deadline
     * @param memoryLimitBytes optional VM memory limit
     * @param launcher VM launcher supplied by the concrete sandbox
     */
    public CodeModeSandboxBase(Map<String, ScriptTool> tools, Map<String, ScriptTool> globals,
                               long timeoutMs, Long memoryLimitBytes, CodeModeVmLauncher launcher) {
        this(tools, globals, null, timeoutMs, memoryLimitBytes, launcher);
    }

    /**
     * Creates a sandbox with explicit tool declarations used by the prelude's {@code ALL_TOOLS}.
     *
     * @param tools tool implementations, keyed by tool name
     * @param globals global implementations, keyed by global name
     * @param toolDeclarations optional declarations with script names and descriptions
     * @param timeoutMs default execution timeout; non-positive values mean no deadline
     * @param memoryLimitBytes optional VM memory limit
     * @param launcher VM launcher supplied by the concrete sandbox
     */
    public CodeModeSandboxBase(Map<String, ScriptTool> tools, Map<String, ScriptTool> globals,
                               List<CodeModeData.ToolDecl> toolDeclarations, long timeoutMs,
                               Long memoryLimitBytes, CodeModeVmLauncher launcher) {
        this.declarations = toolDeclarations == null ? List.of() : List.copyOf(toolDeclarations);
        this.timeoutMs = timeoutMs;
        this.memoryLimitBytes = memoryLimitBytes;
        this.launcher = launcher;
        if (tools != null) {
            for (Map.Entry<String, ScriptTool> tool : tools.entrySet()) {
                registerTool(tool.getKey(), tool.getValue());
            }
        }
        if (globals != null) {
            List<CodeModeData.GlobalDecl> declarations = globals.keySet().stream()
                    .map(name -> new CodeModeData.GlobalDecl(name, false)).toList();
            CodeModeExecution.checkGlobals(declarations);
            globalsByName.putAll(globals);
        }
    }

    /**
     * Creates a sandbox with default timeout and no memory limit.
     *
     * @param tools tool implementations, keyed by tool name
     * @param globals global implementations, keyed by global name
     * @param launcher VM launcher supplied by the concrete sandbox
     */
    public CodeModeSandboxBase(Map<String, ScriptTool> tools, Map<String, ScriptTool> globals,
                               CodeModeVmLauncher launcher) {
        this(tools, globals, DEFAULT_TIMEOUT_MS, null, launcher);
    }

    /**
     * Registers a tool, throwing if a tool with the same name is already registered.
     *
     * @param name tool name
     * @param tool tool implementation
     */
    public void registerTool(String name, ScriptTool tool) {
        if (toolsByName.putIfAbsent(name, tool) != null) {
            throw new IllegalStateException("Tool \"" + name + "\" is already registered");
        }
    }

    /**
     * Unregisters a tool.
     *
     * @param name tool name
     * @return whether a tool was removed
     */
    public boolean unregisterTool(String name) {
        return toolsByName.remove(name) != null;
    }

    /** Returns the registered tools. */
    public List<CodeModeData.ToolDecl> tools() {
        return declarations.isEmpty()
            ? toolsByName.keySet().stream().map(name -> new CodeModeData.ToolDecl(name, name, "")).toList()
            : declarations;
    }

    /** Returns the registered globals. */
    public List<CodeModeData.GlobalDecl> globals() {
        return globalsByName.keySet().stream().map(name -> new CodeModeData.GlobalDecl(name, false)).toList();
    }

    /**
     * Executes code in a fresh VM. Script failures are returned as failed results.
     *
     * @param code async function body
     * @param options per-execution options
     * @return the result future
     */
    public CompletableFuture<CodeModeData.Result> execute(String code, CodeModeExecuteOptions options) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Sandbox is closed"));
        }
        return execute(code, options, timeoutMs);
    }

    private CompletableFuture<CodeModeData.Result> execute(
			String code, CodeModeExecuteOptions options, long defaultTimeoutMs) {
        CodeModeExecuteOptions effective = options == null ? new CodeModeExecuteOptions() : options;
        long executionTimeout = effective.timeoutMs() == CodeModeExecution.DEFAULT_TIMEOUT_MS
                ? defaultTimeoutMs : effective.timeoutMs();
        Map<String, ScriptTool> tools = new LinkedHashMap<>(toolsByName);
        CodeModeData.StartData data = new CodeModeData.StartData(
                code,
                declarations.isEmpty()
                    ? tools.keySet().stream().map(name -> new CodeModeData.ToolDecl(name, name, "")).toList()
                    : declarations,
                globalsByName.keySet().stream().map(name -> new CodeModeData.GlobalDecl(name, false)).toList(),
                memoryLimitBytes,
                serializeStore(effective.store()));
        CodeModeExecution execution = new CodeModeExecution(data, tools, globalsByName,
                executionTimeout, launcher, effective.signal());
        running.add(execution);
        return execution.promise().whenComplete((result, error) -> running.remove(execution));
    }

    /**
     * Executes code with default options.
     *
     * @param code async function body
     * @return the result future
     */
    public CompletableFuture<CodeModeData.Result> execute(String code) {
        return execute(code, null);
    }

    /** Aborts in-flight executions and rejects new ones. */
    public void close() {
        closed = true;
        List<CodeModeExecution> active = new ArrayList<>(running);
        for (CodeModeExecution execution : active) {
            execution.abort("Sandbox closed");
        }
        running.clear();
    }

    private static Map<String, String> serializeStore(Map<String, Object> store) {
        Map<String, String> serialized = new LinkedHashMap<>();
        if (store == null) {
            return serialized;
        }
        for (Map.Entry<String, Object> entry : store.entrySet()) {
            String json = CodeModeBridgeDecoder.stringify(entry.getValue());
            if (json != null) {
                serialized.put(entry.getKey(), json);
            }
        }
        return serialized;
    }
}

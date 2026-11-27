package com.pijava.codemode.runtime;

import com.pijava.codemode.api.CodeModeAbortSignal;
import com.pijava.codemode.api.CodeModeData;
import com.pijava.codemode.api.CodeModeVmLauncher;
import com.pijava.codemode.api.Protocol;
import com.pijava.codemode.api.ScriptTool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One script run in its own VM; ported from execution.ts without deterministic/control support. */
public final class CodeModeExecution {
    public static final long DEFAULT_TIMEOUT_MS = 300_000L;

    private final CompletableFuture<CodeModeData.Result> promise = new CompletableFuture<>();
    private CodeModeVmLauncher.VmChannel channel;
    private final List<Protocol.HostToVm> unsent = new ArrayList<>();
    private Runnable unsubscribe;
    private final Map<String, ScriptTool> tools;
    private final Map<String, ScriptTool> globals;
    private final ScheduledExecutorService timerService;
    private final ScheduledFuture<?> timer;
    private final List<CodeModeData.OutputItem> output = new ArrayList<>();
    private final List<MutableCallRecord> calls = new ArrayList<>();
    private final Map<Integer, PendingCall> pending = new LinkedHashMap<>();
    private volatile boolean finished;

    /**
     * Starts one execution; messages may arrive before the launcher returns.
     *
     * @param data start data sent to the VM
     * @param tools tool executors
     * @param globals global executors
     * @param timeoutMs deadline in milliseconds; non-positive disables the timer
     * @param launcher VM launcher
     */
    public CodeModeExecution(CodeModeData.StartData data, Map<String, ScriptTool> tools,
                             Map<String, ScriptTool> globals, long timeoutMs, CodeModeVmLauncher launcher) {
        this(data, tools, globals, timeoutMs, launcher, null);
    }

    /**
     * Starts one execution with an optional cancellation signal.
     *
     * @param data start data sent to the VM
     * @param tools tool executors
     * @param globals global executors
     * @param timeoutMs deadline in milliseconds; non-positive or {@link Long#MAX_VALUE} disables the timer
     * @param launcher VM launcher
     * @param signal cancellation signal, may be {@code null}
     */
    public CodeModeExecution(CodeModeData.StartData data, Map<String, ScriptTool> tools,
                             Map<String, ScriptTool> globals, long timeoutMs, CodeModeVmLauncher launcher,
                             CodeModeAbortSignal signal) {
        this.tools = tools;
        this.globals = globals;
        this.timerService = timeoutMs > 0 && timeoutMs != Long.MAX_VALUE
                ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "codemode-execution-deadline");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        this.timer = timerService == null ? null : timerService.schedule(
                () -> finish(CodeModeData.CodemodeError.of("timeout",
                        "Execution timed out after " + timeoutMs + " ms")),
                timeoutMs, TimeUnit.MILLISECONDS);
        // pi runtime/execution.ts:220-227：先看已中止，其余登记一次性监听。
        if (signal != null) {
            this.unsubscribe = signal.onAbort(this::onAbort);
        }
        if (finished) {
            return;
        }
        CodeModeVmLauncher.VmEvents events = new CodeModeVmLauncher.VmEvents() {
            @Override
            public void message(Protocol.VmToHost message) {
                receive(message);
            }

            @Override
            public void failure(CodeModeData.CodemodeError error) {
                finish(error);
            }
        };
        try {
            CodeModeVmLauncher.VmChannel started = launcher.launch(data, events);
            onLaunched(started);
        } catch (Exception e) {
            finish(CodeModeData.CodemodeError.of("sandbox", errorMessage(e)));
        }
    }

    /**
     * Returns the result of this execution.
     *
     * @return result future
     */
    public CompletableFuture<CodeModeData.Result> promise() {
        return promise;
    }

    /**
     * Whether this execution has its outcome.
     *
     * @return true when finished
     */
    public boolean finished() {
        return finished;
    }

    /**
     * Finishes this execution with an aborted error.
     *
     * @param message abort message
     */
    public void abort(String message) {
        finish(CodeModeData.CodemodeError.of("aborted", message));
    }

    /** pi runtime/execution.ts:305-308：宿主信号的取消文案。 */
    private void onAbort() {
        finish(CodeModeData.CodemodeError.of("aborted", "Execution aborted"));
    }

    private void onLaunched(CodeModeVmLauncher.VmChannel launched) {
        if (finished) {
            try {
                launched.stop();
            } catch (Exception ignored) {
                // The VM is gone; the first terminal event already won.
            }
            return;
        }
        channel = launched;
        for (Protocol.HostToVm message : unsent) {
            channel.post(message);
        }
        unsent.clear();
    }

    private void receive(Protocol.VmToHost message) {
        try {
            handleMessage(message);
        } catch (Exception e) {
            String detail = e instanceof CodeModeBridgeDecoder.BridgeError
                    ? "Sandbox bridge broken: " + e.getMessage()
                            + ". The script may have modified built-ins such as a prototype's toJSON."
                    : "Sandbox host failed: " + errorMessage(e);
            finish(CodeModeData.CodemodeError.of("sandbox", detail));
        }
    }

    private void handleMessage(Protocol.VmToHost message) {
        if (finished) {
            return;
        }
        if (message == null) {
            throw new CodeModeBridgeDecoder.BridgeError("unknown message from the worker");
        }
        if (message instanceof Protocol.Output outputMessage) {
            output.add(outputMessage.item());
        } else if (message instanceof Protocol.Call call) {
            if (pending.containsKey(call.id())) {
                throw new CodeModeBridgeDecoder.BridgeError("duplicate call id " + call.id());
            }
            handleCall(call);
        } else if (message instanceof Protocol.DoneOk done) {
            handleDone(done);
        } else if (message instanceof Protocol.DoneError error) {
            finish(CodeModeBridgeDecoder.parseScriptError(error.errorJson()));
        } else if (message instanceof Protocol.Crash crash) {
            finish(CodeModeData.CodemodeError.of("sandbox", crash.message()));
        } else {
            throw new CodeModeBridgeDecoder.BridgeError("unknown message from the worker");
        }
    }

    private void handleDone(Protocol.DoneOk message) {
        // Decode everything before finish(), which must not throw once it starts.
        if (message.valueJson() == null) {
            finish(null, null, CodeModeData.StoreWrites.empty());
        } else {
            Object value = CodeModeBridgeDecoder.toObject(
                    CodeModeBridgeDecoder.parseBridgeJson(message.valueJson(), "return value"));
            CodeModeData.StoreWrites writes = CodeModeBridgeDecoder.parseStoreWrites(message.writesJson());
            finish(null, value, writes);
        }
    }

    private void handleCall(Protocol.Call message) {
        boolean isTool = "tool".equals(message.target());
        MutableCallRecord record = isTool
                ? new MutableCallRecord(message.name(), "cancelled", 0.0) : null;
        if (record != null) {
            calls.add(record);
        }
        PendingCall pendingCall = new PendingCall(record, System.nanoTime());
        pending.put(message.id(), pendingCall);

        Thread worker = new Thread(() -> executeCall(message, isTool, record, pendingCall),
                "codemode-tool-call-" + message.id());
        worker.setDaemon(true);
        worker.start();
    }

    private void executeCall(Protocol.Call message, boolean isTool, MutableCallRecord record,
                             PendingCall pendingCall) {
        Protocol.Result reply;
        try {
            Map<String, ScriptTool> executors = isTool ? tools : globals;
            ScriptTool tool = executors.get(message.name());
            if (tool == null) {
                throw new IllegalStateException("Unknown " + (isTool ? "tool" : "global")
                        + " \"" + message.name() + "\"");
            }
            Object args = message.argsJson() == null ? null
                    : CodeModeBridgeDecoder.toObject(CodeModeBridgeDecoder.parseBridgeJson(
                            message.argsJson(), "call arguments"));
            Object value = tool.execute(args);
            reply = new Protocol.Result(message.id(), true, CodeModeBridgeDecoder.stringify(value), null);
        } catch (Exception e) {
            reply = new Protocol.Result(message.id(), false, errorMessage(e), null);
        }
        if (pending.remove(message.id()) == null) {
            return;
        }
        if (record != null) {
            record.status = reply.ok() ? "ok" : "error";
            record.durationMs = elapsedMs(pendingCall.startedAt);
        }
        post(reply);
    }

    private void post(Protocol.HostToVm message) {
        if (finished) {
            return;
        }
        if (channel != null) {
            channel.post(message);
        } else {
            unsent.add(message);
        }
    }

    private void finish(CodeModeData.CodemodeError error) {
        finish(error, null, CodeModeData.StoreWrites.empty());
    }

    private void finish(CodeModeData.CodemodeError error, Object value, CodeModeData.StoreWrites writes) {
        if (finished) {
            return;
        }
        finished = true;
        if (timer != null) {
            timer.cancel(false);
        }
        Runnable remove = unsubscribe;
        unsubscribe = null;
        if (remove != null) {
            remove.run();
        }
        if (timerService != null) {
            timerService.shutdownNow();
        }
        long now = System.nanoTime();
        for (PendingCall pendingCall : pending.values()) {
            if (pendingCall.record != null) {
                pendingCall.record.durationMs = (now - pendingCall.startedAt) / 1_000_000.0;
            }
        }
        pending.clear();
        unsent.clear();
        CodeModeData.Result result = error != null
                ? CodeModeData.Result.failure(error, output, records())
                : CodeModeData.Result.success(value, output, records(), writes);
        if (channel == null) {
            promise.complete(result);
            return;
        }
        try {
            channel.stop();
        } catch (Exception ignored) {
            // Stop errors are ignored, as in the TypeScript implementation.
        }
        promise.complete(result);
    }

    private List<CodeModeData.CallRecord> records() {
        List<CodeModeData.CallRecord> result = new ArrayList<>();
        for (MutableCallRecord record : calls) {
            result.add(new CodeModeData.CallRecord(record.name, record.status, record.durationMs));
        }
        return result;
    }

    private static double elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000.0;
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? String.valueOf(error) : error.getMessage();
    }

    private static final class MutableCallRecord {
        private final String name;
        private String status;
        private double durationMs;

        MutableCallRecord(String name, String status, double durationMs) {
            this.name = name;
            this.status = status;
            this.durationMs = durationMs;
        }
    }

    private static final class PendingCall {
        private final MutableCallRecord record;
        private final long startedAt;

        PendingCall(MutableCallRecord record, long startedAt) {
            this.record = record;
            this.startedAt = startedAt;
        }
    }

    /**
     * Validates global declarations; ported from execution.ts.
     *
     * @param globals declarations to validate
     */
    public static void checkGlobals(List<CodeModeData.GlobalDecl> globals) {
        Set<String> names = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        for (CodeModeData.GlobalDecl global : globals) {
            String[] parts = global.name().split("\\.", -1);
            if (parts.length > 2 || !isIdentifier(parts[0])
                    || (parts.length == 2 && !isIdentifier(parts[1]))
                    || isReserved(parts[0])) {
                throw new IllegalArgumentException("Invalid global name \"" + global.name() + "\"");
            }
            if (names.contains(global.name())) {
                throw new IllegalArgumentException("Global \"" + global.name() + "\" is already registered");
            }
            if (parts.length == 2) {
                namespaces.add(parts[0]);
            }
            names.add(global.name());
        }
        for (String name : namespaces) {
            if (names.contains(name)) {
                throw new IllegalArgumentException(
                        "Global \"" + name + "\" conflicts with the namespace \"" + name + "\"");
            }
        }
    }

    private static boolean isIdentifier(String part) {
        if (part == null || part.isEmpty()) {
            return false;
        }
        if (!Character.isLetter(part.charAt(0)) && part.charAt(0) != '_' && part.charAt(0) != '$') {
            return false;
        }
        for (int i = 1; i < part.length(); i++) {
            char c = part.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                return false;
            }
        }
        return true;
    }

    private static boolean isReserved(String name) {
        return Set.of("tools", "ALL_TOOLS", "console", "text", "image", "exit",
                "globalThis", "store", "load").contains(name);
    }

}

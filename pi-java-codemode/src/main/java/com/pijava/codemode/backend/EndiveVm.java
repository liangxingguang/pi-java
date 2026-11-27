package com.pijava.codemode.backend;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.codemode.api.CodeModeData;
import com.pijava.codemode.api.Protocol;
import com.pijava.codemode.api.CodeModeVmLauncher.VmEvents;

import run.endive.runtime.Instance;
import run.endive.runtime.WasmFunctionHandle;

/** Runs one script in one Endive-backed QuickJS instance. */
public final class EndiveVm implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int JOBS_PER_POLL = 50;

    private final QuickJsBinding vm;
    private final VmEvents events;
    private final ConcurrentLinkedQueue<Protocol.HostToVm> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean done = new AtomicBoolean();
    private long api;
    private long settle;
    private long stalled;
    private long run;

    /**
     * Creates and starts a VM for one script.
     *
     * @param data script and API declarations
     * @param events host callbacks
     * @throws IOException if the prelude cannot be loaded
     */
    public EndiveVm(CodeModeData.StartData data, VmEvents events) throws IOException {
        this.events = events;
        Map<String, WasmFunctionHandle> callbacks = Map.of("host_call", this::hostCall);
        this.vm = new QuickJsBinding(callbacks, data.memoryLimitBytes());
        try {
            this.vm.setInterrupt(this::shouldInterrupt);
            long bridge = vm.newHostFunction("bridge", 0);
            vm.setProperty(vm.global(), "bridge", bridge);
            long prelude = check(vm.eval(QuickJsBinding.preludeSource(), "codemode-prelude.js"), "prelude");
            api = check(vm.callFunction(prelude, vm.undefinedHandle(), bridge,
                    vm.newString(json(data.tools())), vm.newString(json(data.globals())),
                    vm.newString(json(data.store()))), "prelude invocation");
            settle = property(api, "settle");
            stalled = property(api, "stalled");
            run = property(api, "run");
            String script = "(async (tools, console) => {" + data.code() + "\n})";
            long function = vm.eval(script, "codemode.js");
            String error = vm.exception(function);
            if (error != null) {
                done.set(true);
                events.message(new Protocol.DoneError(scriptErrorJson(error)));
                return;
            }
            long result = vm.callFunction(run, api, function);
            checkException(result, "script run");
            drain();
        } catch (RuntimeException failure) {
            crash(failure);
        }
    }

    private long[] hostCall(Instance instance, long... args) {
        int argc = (int) args[3];
        int argvPtr = (int) args[4];
        if (argc != 4) {
            return new long[] {0};
        }
        long kind = vm.argument(argvPtr, 0);
        long first = vm.argument(argvPtr, 1);
        long second = vm.argument(argvPtr, 2);
        long third = vm.argument(argvPtr, 3);
        switch (vm.readString(kind)) {
            case "call" -> events.message(new Protocol.Call((int) vm.readNumber(first), "tool",
                    vm.readString(second), jsonOrNull(third)));
            case "global" -> events.message(new Protocol.Call((int) vm.readNumber(first), "global",
                    vm.readString(second), jsonOrNull(third)));
            case "output" -> emitOutput(first, second, third);
            case "done" -> emitDone(first, second, third);
            default -> crash(new IllegalArgumentException("Unknown bridge kind: " + vm.readString(kind)));
        }
        return new long[] {vm.undefinedHandle()};
    }

    private String jsonOrNull(long handle) {
        String text = vm.readString(handle);
        return text.isEmpty() || "undefined".equals(text) ? null : text;
    }

    private void emitOutput(long kind, long data, long mimeType) {
        String type = vm.readString(kind);
        String text = vm.readString(data);
        CodeModeData.OutputItem item = switch (type) {
            case "image" -> new CodeModeData.Image(text, vm.readString(mimeType));
            case "console" -> new CodeModeData.Text(text, true);
            default -> new CodeModeData.Text(text, false);
        };
        events.message(new Protocol.Output(item));
    }

    private void emitDone(long ok, long payload, long writes) {
        done.set(true);
        String value = jsonOrNull(payload);
        String writesJson = jsonOrNull(writes);
        if (vm.readNumber(ok) != 0) {
            events.message(new Protocol.DoneOk(value, writesJson == null ? "[]" : writesJson));
        } else {
            events.message(new Protocol.DoneError(value == null ? "{}" : value));
        }
    }

    private static String scriptErrorJson(String message) {
        String head = message == null ? "Error" : message;
        String name = head;
        int colon = head.indexOf(':');
        if (colon > 0) {
            name = head.substring(0, colon);
            head = head.substring(colon + 1).trim();
        }
        try {
            return JSON.writeValueAsString(Map.of("name", name, "message", head, "stack", head));
        } catch (Exception failure) {
            return "{\"name\":\"Error\",\"message\":\"Syntax error\"}";
        }
    }

    private boolean shouldInterrupt() {
        return interrupted.get();
    }

    /**
     * Queues a host result for the VM thread.
     *
     * @param message host result
     */
    public void post(Protocol.HostToVm message) {
        if (!closed.get()) {
            inbox.add(message);
        }
    }

    /** Applies queued results and advances promise jobs. */
    public void pump() {
        if (closed.get()) {
            return;
        }
        try {
            Protocol.HostToVm message;
            while ((message = inbox.poll()) != null && !closed.get()) {
                Protocol.Result result = (Protocol.Result) message;
                long ok = result.ok() ? vm.trueHandle() : vm.falseHandle();
                long resultHandle = vm.callFunction(settle, api, vm.newNumber(result.id()), ok,
                        stringOrUndefined(result.payload()), stringOrUndefined(result.propertiesJson()));
                checkException(resultHandle, "settle");
                drain();
            }
        } catch (RuntimeException failure) {
            crash(failure);
        }
    }

    private long stringOrUndefined(String value) {
        return value == null ? vm.undefinedHandle() : vm.newString(value);
    }

    private void drain() {
        int jobs = 0;
        while (vm.call("qjs_is_job_pending")[0] != 0) {
            long result = vm.call("qjs_execute_pending_job")[0];
            if (result < 0 || interrupted.get()) {
                throw new IllegalStateException("Execution interrupted");
            }
            if (++jobs % JOBS_PER_POLL == 0 && interrupted.get()) {
                throw new IllegalStateException("Execution interrupted");
            }
        }
        long result = vm.callFunction(stalled, api);
        checkException(result, "stalled");
    }

    /** Requests asynchronous termination. */
    public void stop() {
        interrupted.set(true);
    }

    /** @return whether stop was requested */
    public boolean isStopped() {
        return interrupted.get();
    }

    /** @return whether the script reported its terminal result */
    public boolean isDone() {
        return done.get();
    }

    private long check(long handle, String operation) {
        String error = vm.exception(handle);
        if (error != null) {
            throw new IllegalStateException(operation + ": " + error);
        }
        return handle;
    }

    private void checkException(long handle, String operation) {
        String error = vm.exception(handle);
        if (error != null) {
            throw new IllegalStateException(operation + ": " + error);
        }
    }

    private long property(long object, String name) {
        long handle = vm.property(object, name);
        if (handle == 0) {
            throw new IllegalStateException("Missing prelude API property: " + name);
        }
        return handle;
    }

    private static String json(Object value) throws IOException {
        return JSON.writeValueAsString(value == null ? List.of() : value);
    }

    private void crash(Throwable failure) {
        events.message(new Protocol.Crash(failure.getMessage() == null ? failure.toString() : failure.getMessage()));
    }

    /** Releases the wasm instance. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            interrupted.set(true);
            vm.close();
        }
    }

    /** @return the underlying binding for tests */
    public QuickJsBinding binding() {
        return vm;
    }
}

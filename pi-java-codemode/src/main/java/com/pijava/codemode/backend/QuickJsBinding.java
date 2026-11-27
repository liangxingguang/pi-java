package com.pijava.codemode.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BooleanSupplier;

import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.runtime.WasmFunctionHandle;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.FunctionImport;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.Import;
import run.endive.wasm.types.ImportSection;

/** Thin wrapper around the AOT-compiled QuickJS wasm exports. */
public final class QuickJsBinding implements AutoCloseable {
    private static final String PRELUDE_RESOURCE = "codemode-prelude.js";
    private static WasmModule module;

    private final Instance instance;
    private final WasiPreview1 wasi;
    private BooleanSupplier interrupt;
    private long interruptPolls;
    private boolean closed;

    /** Creates one initialized QuickJS instance. */
    public QuickJsBinding() {
        this(null, null);
    }

    /**
     * Creates one initialized QuickJS instance with a heap limit.
     *
     * @param memoryLimitBytes optional QuickJS heap limit
     */
    public QuickJsBinding(Long memoryLimitBytes) {
        this(null, memoryLimitBytes);
    }

    /**
     * Creates one initialized QuickJS instance with custom env callbacks.
     *
     * @param callbacks env import callbacks keyed by import name
     * @param memoryLimitBytes optional QuickJS heap limit
     */
    public QuickJsBinding(Map<String, WasmFunctionHandle> callbacks, Long memoryLimitBytes) {
        WasmModule wasmModule = loadModule();
        WasiOptions options = WasiOptions.builder().build();
        this.wasi = WasiPreview1.builder().withOptions(options).build();
        Store store = new Store().addFunction(wasi.toHostFunctions());
        Map<String, WasmFunctionHandle> overrides = callbacks == null ? Map.of() : callbacks;
        appendEnvImports(store, wasmModule, overrides, this::pollInterrupt);
        this.instance = Instance.builder(wasmModule)
                .withInitialize(true)
                .withImportValues(store.toImportValues())
                .withMachineFactory(QuickJsModule::create)
                .build();
        instance.export("_initialize").apply();
        instance.export("qjs_init").apply();
        if (memoryLimitBytes != null && memoryLimitBytes > 0) {
            setMemoryLimit(memoryLimitBytes);
        }
    }

    private static synchronized WasmModule loadModule() {
        if (module == null) {
            module = QuickJsModule.load();
        }
        return module;
    }

    private static void appendEnvImports(Store store, WasmModule wasmModule,
                                         Map<String, WasmFunctionHandle> overrides,
                                         BooleanSupplier interruptPoll) {
        ImportSection imports = wasmModule.importSection();
        for (int index = 0; index < imports.importCount(); index++) {
            Import item = imports.getImport(index);
            if (item.importType() != ExternalType.FUNCTION || !"env".equals(item.module())) {
                continue;
            }
            FunctionImport functionImport = (FunctionImport) item;
            FunctionType type = wasmModule.typeSection().getType(functionImport.typeIndex());
            String name = item.name();
            WasmFunctionHandle callback = overrides.get(name);
            if (callback == null && "host_interrupt".equals(name)) {
                callback = (unused, args) -> new long[] {interruptPoll.getAsBoolean() ? 1L : 0L};
            }
            if (callback == null) {
                callback = (unused, args) -> new long[type.returns().size()];
            }
            store.addFunction(new HostFunction("env", name, type.params(), type.returns(), callback));
        }
    }

    private boolean pollInterrupt() {
        interruptPolls++;
        return interrupt != null && interrupt.getAsBoolean();
    }

    /** @return the current interrupt predicate, or null */
    public BooleanSupplier interruptPredicate() {
        return interrupt;
    }

    /**
     * Reads the vendored prelude from the classpath.
     *
     * @return prelude source
     * @throws IOException if the resource cannot be read
     */
    public static String preludeSource() throws IOException {
        try (InputStream stream = QuickJsBinding.class.getClassLoader().getResourceAsStream(PRELUDE_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing classpath resource: " + PRELUDE_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Allocates a NUL-terminated UTF-8 copy of a string in wasm memory.
     *
     * @param text source text
     * @return wasm pointer, valid until freed with {@code wasm_free}
     */
    public long allocCString(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long pointer = call("wasm_malloc", bytes.length + 1L)[0];
        instance.memory().write((int) pointer, bytes, 0, bytes.length);
        instance.memory().writeByte((int) pointer + bytes.length, (byte) 0);
        return pointer;
    }

    /**
     * Evaluates JavaScript source in the VM.
     *
     * @param code source code
     * @param name source name used in stack traces
     * @return JS value handle; check {@link #exception(long)} before using it
     */
    public long eval(String code, String name) {
        byte[] bytes = code.getBytes(StandardCharsets.UTF_8);
        long source = call("wasm_malloc", bytes.length + 1L)[0];
        long fileName = 0;
        try {
            instance.memory().write((int) source, bytes, 0, bytes.length);
            instance.memory().writeByte((int) source + bytes.length, (byte) 0);
            fileName = allocCString(name);
            return call("qjs_eval", source, bytes.length, fileName, 0)[0];
        } finally {
            if (fileName != 0) {
                call("wasm_free", fileName);
            }
            call("wasm_free", source);
        }
    }

    /**
     * Reads a JS value as a string.
     *
     * @param handle JS value handle
     * @return decoded string
     */
    public String readString(long handle) {
        long pointer = call("qjs_get_string", handle)[0];
        if (pointer == 0) {
            return "";
        }
        try {
            return instance.memory().readCString((int) pointer);
        } finally {
            call("qjs_free_cstring", pointer);
        }
    }

    /**
     * Returns the exception attached to a failed eval or call.
     *
     * @param handle JS value handle
     * @return exception text, or null when the value has no exception
     */
    public String exception(long handle) {
        long exceptionHandle = call("qjs_get_exception")[0];
        if (exceptionHandle == 0 || call("qjs_is_exception", exceptionHandle)[0] == 0) {
            exceptionHandle = handle;
        }
        if (exceptionHandle == 0 || call("qjs_is_exception", exceptionHandle)[0] == 0) {
            return null;
        }
        long pointer = call("qjs_get_string", exceptionHandle)[0];
        if (pointer == 0) {
            return "Error";
        }
        try {
            String message = instance.memory().readCString((int) pointer);
            return message == null || message.isEmpty() ? "Error" : message;
        } finally {
            call("qjs_free_cstring", pointer);
        }
    }

    /**
     * Calls a JS function through the wasm {@code qjs_call(fn, this, argc, argv)} export.
     *
     * @param function function handle
     * @param thisArg value used as {@code this}
     * @param args argument handles
     * @return return value handle
     */
    public long callFunction(long function, long thisArg, long... args) {
        long argv = call("wasm_malloc", Math.max(4L, (long) args.length * 4))[0];
        try {
            for (int index = 0; index < args.length; index++) {
                instance.memory().writeI32((int) argv + index * 4, (int) args[index]);
            }
            return call("qjs_call", function, thisArg, args.length, argv)[0];
        } finally {
            call("wasm_free", argv);
        }
    }

    /** Runs all pending promise jobs, polling the interrupt every 50 jobs. */
    public void drainJobs() {
        int jobs = 0;
        while (call("qjs_is_job_pending")[0] != 0) {
            if (call("qjs_execute_pending_job")[0] < 0) {
                throw new IllegalStateException("Promise job failed");
            }
            if (++jobs % 50 == 0 && pollInterrupt()) {
                throw new IllegalStateException("Execution interrupted");
            }
        }
    }

    /**
     * Installs the interrupt predicate.
     *
     * @param interrupt returns true when execution must stop, or null to disable
     */
    public void setInterrupt(BooleanSupplier interrupt) {
        this.interrupt = interrupt;
        call("qjs_set_interrupt_handler", interrupt == null ? 0 : 1);
    }

    /**
     * Sets the QuickJS heap limit.
     *
     * @param limitBytes heap limit in bytes
     */
    public void setMemoryLimit(long limitBytes) {
        call("qjs_set_memory_limit", limitBytes);
    }

    /**
     * Creates a JS function backed by a Java callback.
     *
     * @param name function name
     * @param bytecode ignored; the ABI only needs the name and length
     * @return JS function handle
     */
    public long newHostFunction(String name, int bytecode) {
        long pointer = allocCString(name);
        try {
            return call("qjs_new_host_function", pointer, name.getBytes(StandardCharsets.UTF_8).length, bytecode)[0];
        } finally {
            call("wasm_free", pointer);
        }
    }

    /**
     * Reads a callback argument pointer from the argv array.
     *
     * @param argvPtr argv base pointer
     * @param index argument index
     * @return JS value handle
     */
    public long argument(long argvPtr, int index) {
        return Integer.toUnsignedLong(instance.memory().readInt((int) argvPtr + index * 4));
    }

    /**
     * Reads a numeric JS value as a double.
     *
     * @param handle JS value handle
     * @return numeric value
     */
    public double readNumber(long handle) {
        return Double.longBitsToDouble(call("qjs_get_float64", handle)[0]);
    }

    /**
     * Creates a JS string from wasm memory.
     *
     * @param pointer C string pointer
     * @param length byte length without the terminator
     * @return JS string handle
     */
    public long newString(long pointer, int length) {
        return call("qjs_new_string", pointer, length)[0];
    }

    /**
     * Creates a JS string from a Java string.
     *
     * @param text source text
     * @return JS string handle
     */
    public long newString(String text) {
        long pointer = allocCString(text);
        try {
            return newString(pointer, text.getBytes(StandardCharsets.UTF_8).length);
        } finally {
            call("wasm_free", pointer);
        }
    }

    /**
     * Creates a JS number.
     *
     * @param value numeric value
     * @return JS number handle
     */
    public long newNumber(double value) {
        return call("qjs_new_number", Double.doubleToRawLongBits(value))[0];
    }

    /**
     * Sets a string property on a JS object.
     *
     * @param object object handle
     * @param name property name
     * @param value property value handle
     */
    public void setProperty(long object, String name, long value) {
        long pointer = allocCString(name);
        try {
            call("qjs_set_prop_string", object, pointer, value);
        } finally {
            call("wasm_free", pointer);
        }
    }

    /** @return the current global object handle */
    public long global() {
        return call("qjs_get_global")[0];
    }

    /**
     * Reads a JS object property by C string name.
     *
     * @param object object handle
     * @param name property name
     * @return property handle
     */
    public long property(long object, String name) {
        long pointer = allocCString(name);
        try {
            return call("qjs_get_prop_string", object, pointer)[0];
        } finally {
            call("wasm_free", pointer);
        }
    }

    /** @return undefined handle */
    public long undefinedHandle() {
        return call("qjs_get_undefined")[0];
    }

    /** @return boolean true handle */
    public long trueHandle() {
        return call("qjs_get_true")[0];
    }

    /** @return boolean false handle */
    public long falseHandle() {
        return call("qjs_get_false")[0];
    }

    /**
     * Invokes a wasm export.
     *
     * @param name export name
     * @param args arguments
     * @return raw export result
     */
    public long[] call(String name, long... args) {
        return instance.export(name).apply(args);
    }

    /** Releases the wasm instance and WASI shim. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            instance.close();
        } finally {
            wasi.close();
        }
    }
}

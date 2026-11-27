package com.pijava.codemode.api;

/** Messages crossing the host/VM boundary. All payloads are JSON strings. */
public final class Protocol {
    private Protocol() {
    }

    /** Messages a VM sends to the host. */
    public sealed interface VmToHost permits Call, Output, DoneOk, DoneError, Crash {
    }

    /** The script called a tool or an injected global. */
    public record Call(int id, String target, String name, String argsJson) implements VmToHost {
    }

    /** The script emitted an output item. */
    public record Output(CodeModeData.OutputItem item) implements VmToHost {
    }

    /** The script finished successfully. */
    public record DoneOk(String valueJson, String writesJson) implements VmToHost {
    }

    /** The script threw. */
    public record DoneError(String errorJson) implements VmToHost {
    }

    /** The VM failed outside the script's control. */
    public record Crash(String message) implements VmToHost {
    }

    /** Messages the host sends to a VM. */
    public sealed interface HostToVm permits Result {
    }

    /** The answer to a script call. */
    public record Result(int id, boolean ok, String payload, String propertiesJson) implements HostToVm {
    }
}

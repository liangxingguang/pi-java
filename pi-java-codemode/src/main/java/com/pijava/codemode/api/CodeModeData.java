package com.pijava.codemode.api;

import java.util.List;
import java.util.Map;

/** Plain data exchanged between the host and a codemode VM. */
public final class CodeModeData {
    private CodeModeData() {
    }

    /** One item the script emitted through {@code text}, {@code image}, or {@code console}. */
    public sealed interface OutputItem permits Text, Image {
    }

    /** A text output item. */
    public record Text(String text, boolean console) implements OutputItem {
    }

    /** A base64 image output item. */
    public record Image(String data, String mimeType) implements OutputItem {
    }

    /** How a tool is named and described to the script. */
    public record ToolDecl(String name, String jsName, String description) {
    }

    /** A global exposed to the script; dotted names form a frozen namespace. */
    public record GlobalDecl(String name, boolean spread) {
    }

    /** A pending call issued by the script. */
    public record Call(int id, String target, String name, String argsJson) {
    }

    /** Bookkeeping for one tool call. */
    public record CallRecord(String name, String status, double durationMs) {
    }

    /** Values a successful script wrote to the session store. */
    public record StoreWrites(Map<String, Object> set, List<String> delete) {
        /** Returns an empty write set. */
        public static StoreWrites empty() {
            return new StoreWrites(Map.of(), List.of());
        }
    }

    /** An error that terminated a script or an execution. */
    public record CodemodeError(String kind, String name, String message, String stack) {
        /** Creates an error with only a kind and message. */
        public static CodemodeError of(String kind, String message) {
            return new CodemodeError(kind, null, message, null);
        }
    }

    /** The outcome of one script execution. */
    public record Result(
            boolean ok,
            Object value,
            List<OutputItem> output,
            List<CallRecord> calls,
            StoreWrites storeWrites,
            CodemodeError error) {
        /** Creates a successful result. */
        public static Result success(Object value, List<OutputItem> output, List<CallRecord> calls, StoreWrites writes) {
            return new Result(true, value, List.copyOf(output), List.copyOf(calls), writes, null);
        }

        /** Creates a failed result. */
        public static Result failure(CodemodeError error, List<OutputItem> output, List<CallRecord> calls) {
            return new Result(false, null, List.copyOf(output), List.copyOf(calls), StoreWrites.empty(), error);
        }
    }

    /** Everything a VM needs to run one script. */
    public record StartData(
            String code,
            List<ToolDecl> tools,
            List<GlobalDecl> globals,
            Long memoryLimitBytes,
            Map<String, String> store) {
    }
}

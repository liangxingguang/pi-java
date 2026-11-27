package com.pijava.codemode;

import com.pijava.codemode.api.CodeModeAbortSignal;
import com.pijava.codemode.api.CodeModeData;
import com.pijava.codemode.api.CodeModeVmLauncher;
import com.pijava.codemode.api.Protocol;
import com.pijava.codemode.api.ScriptTool;
import com.pijava.codemode.runtime.CodeModeExecution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the host execution layer. */
class CodeModeExecutionTest {
    @Test
    void doneOkCarriesValueOutputCallsAndWrites() throws Exception {
        FakeVm vm = new FakeVm();
        CodeModeExecution execution = newExecution(vm, Map.of("echo", args -> args), Map.of());
        vm.events.message(new Protocol.Call(7, "tool", "echo", "[1,2]"));
        Protocol.Result reply = (Protocol.Result) vm.posted.poll(2, TimeUnit.SECONDS);
        vm.events.message(new Protocol.Output(new CodeModeData.Text("hello", false)));
        vm.events.message(new Protocol.DoneOk("{\"a\":1}", "[[\"k\",\"42\"],[\"gone\"]]"));

        CodeModeData.Result result = execution.promise().get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo(Map.of("a", 1L));
        assertThat(result.output()).containsExactly(new CodeModeData.Text("hello", false));
        assertThat(result.calls()).hasSize(1);
        assertThat(result.calls().get(0).name()).isEqualTo("echo");
        assertThat(result.calls().get(0).status()).isEqualTo("ok");
        assertThat(result.calls().get(0).durationMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(((Number) result.storeWrites().set().get("k")).intValue()).isEqualTo(42);
        assertThat(result.storeWrites().delete()).containsExactly("gone");
        assertThat(reply.ok()).isTrue();
        assertThat(reply.payload()).isEqualTo("[1,2]");
        assertThat(vm.stopped).isTrue();
    }

    @Test
    void doneErrorAndCrashReturnSandboxFailures() throws Exception {
        FakeVm scriptVm = new FakeVm();
        CodeModeExecution script = newExecution(scriptVm, Map.of(), Map.of());
        scriptVm.events.message(new Protocol.DoneError(
                "{\"name\":\"TypeError\",\"message\":\"bad\",\"stack\":\"trace\"}"));
        CodeModeData.Result scriptResult = script.promise().get(2, TimeUnit.SECONDS);
        assertThat(scriptResult.ok()).isFalse();
        assertThat(scriptResult.error()).isEqualTo(
                new CodeModeData.CodemodeError("script", "TypeError", "bad", "trace"));

        FakeVm crashVm = new FakeVm();
        CodeModeExecution crash = newExecution(crashVm, Map.of(), Map.of());
        crashVm.events.message(new Protocol.Crash("worker died"));
        assertThat(crash.promise().get(2, TimeUnit.SECONDS).error())
                .isEqualTo(CodeModeData.CodemodeError.of("sandbox", "worker died"));

        FakeVm failureVm = new FakeVm();
        CodeModeExecution failure = newExecution(failureVm, Map.of(), Map.of());
        failureVm.events.failure(CodeModeData.CodemodeError.of("sandbox", "external"));
        assertThat(failure.promise().get(2, TimeUnit.SECONDS).error().message()).isEqualTo("external");
    }

    @Test
    void firstTerminalEventWins() throws Exception {
        FakeVm vm = new FakeVm();
        CodeModeExecution execution = newExecution(vm, Map.of(), Map.of());
        vm.events.message(new Protocol.DoneOk("1", "[]"));
        vm.events.message(new Protocol.Crash("later"));
        vm.events.message(new Protocol.DoneError("{\"message\":\"later\"}"));

        CodeModeData.Result result = execution.promise().get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo(1L);
        assertThat(execution.finished()).isTrue();
    }

    @Test
    void repliesBeforeLauncherReturnsAreFlushed() throws Exception {
        LinkedBlockingQueue<Protocol.HostToVm> posted = new LinkedBlockingQueue<>();
        CodeModeVmLauncher launcher = (data, events) -> {
            events.message(new Protocol.Call(1, "tool", "echo", "\"x\""));
            return new CodeModeVmLauncher.VmChannel() {
                @Override
                public void post(Protocol.HostToVm message) {
                    posted.add(message);
                }

                @Override
                public void stop() {
                }
            };
        };
        CodeModeExecution execution = new CodeModeExecution(
                new CodeModeData.StartData("code", List.of(), List.of(), null, Map.of()),
                Map.of("echo", args -> args), Map.of(), CodeModeExecution.DEFAULT_TIMEOUT_MS, launcher);
        assertThat(posted.poll(2, TimeUnit.SECONDS)).isInstanceOf(Protocol.Result.class);
    }

    @Test
    void malformedPayloadBecomesSandboxError() throws Exception {
        FakeVm vm = new FakeVm();
        CodeModeExecution execution = newExecution(vm, Map.of(), Map.of());
        vm.events.message(new Protocol.DoneOk("not json", "[]"));
        CodeModeData.Result result = execution.promise().get(2, TimeUnit.SECONDS);
        assertThat(result.error().message()).isEqualTo(
                "Sandbox bridge broken: return value is not valid JSON. "
                        + "The script may have modified built-ins such as a prototype's toJSON.");
    }

    @Test
    void pendingCallIsCancelledAndRecorded() throws Exception {
        FakeVm vm = new FakeVm();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ScriptTool slow = args -> {
            release.await(5, TimeUnit.SECONDS);
            return "late";
        };
        CodeModeExecution execution = newExecution(vm, Map.of("slow", slow), Map.of());
        vm.events.message(new Protocol.Call(3, "tool", "slow", "null"));
        vm.events.message(new Protocol.DoneOk("null", "[]"));

        CodeModeData.Result result = execution.promise().get(2, TimeUnit.SECONDS);
        assertThat(result.calls()).hasSize(1);
        assertThat(result.calls().get(0).status()).isEqualTo("cancelled");
        assertThat(result.calls().get(0).durationMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(vm.posted).isEmpty();
        release.countDown();
    }

    @Test
    void unknownToolProducesErrorRecordAndReply() throws Exception {
        FakeVm vm = new FakeVm();
        CodeModeExecution execution = newExecution(vm, Map.of(), Map.of());
        vm.events.message(new Protocol.Call(9, "tool", "missing", "null"));
        Protocol.Result reply = (Protocol.Result) vm.posted.poll(2, TimeUnit.SECONDS);
        vm.events.message(new Protocol.DoneOk("null", "[]"));
        CodeModeData.Result result = execution.promise().get(2, TimeUnit.SECONDS);
        assertThat(result.calls().get(0).status()).isEqualTo("error");
        assertThat(reply.ok()).isFalse();
        assertThat(reply.payload()).isEqualTo("Unknown tool \"missing\"");
    }

    @Test
    void abortSignalFinishesWithAbortedError() throws Exception {
        FakeVm vm = new FakeVm();
        FakeSignal signal = new FakeSignal();
        CodeModeExecution execution = new CodeModeExecution(
                new CodeModeData.StartData("code", List.of(), List.of(), null, Map.of()),
                Map.of(), Map.of(), CodeModeExecution.DEFAULT_TIMEOUT_MS, (data, events) -> {
                    vm.events = events;
                    return vm;
                }, signal);
        signal.abort();

        CodeModeData.Result result = execution.promise().get(2, TimeUnit.SECONDS);
        assertThat(result.error()).isEqualTo(
                CodeModeData.CodemodeError.of("aborted", "Execution aborted"));
        assertThat(vm.stopped).isTrue();
        assertThat(execution.finished()).isTrue();
    }

    private static CodeModeExecution newExecution(FakeVm vm, Map<String, ScriptTool> tools,
                                                  Map<String, ScriptTool> globals) {
        return new CodeModeExecution(
                new CodeModeData.StartData("code", List.of(), List.of(), null, Map.of()),
                tools, globals, CodeModeExecution.DEFAULT_TIMEOUT_MS, (data, events) -> {
                    vm.events = events;
                    return vm;
                });
    }

    /** Signal that keeps a single listener, like the runtime consumes it. */
    private static final class FakeSignal implements CodeModeAbortSignal {
        private boolean aborted;
        private Runnable listener = () -> { };

        @Override
        public boolean isAborted() {
            return aborted;
        }

        @Override
        public Runnable onAbort(Runnable callback) {
            listener = callback;
            if (aborted) {
                callback.run();
            }
            return () -> { };
        }

        void abort() {
            aborted = true;
            listener.run();
        }
    }

    private static final class FakeVm implements CodeModeVmLauncher.VmChannel {
        private final LinkedBlockingQueue<Protocol.HostToVm> posted = new LinkedBlockingQueue<>();
        private CodeModeVmLauncher.VmEvents events;
        private boolean stopped;

        @Override
        public void post(Protocol.HostToVm message) {
            posted.add(message);
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}

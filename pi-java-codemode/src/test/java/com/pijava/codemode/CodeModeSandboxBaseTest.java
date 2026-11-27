package com.pijava.codemode;

import com.pijava.codemode.api.CodeModeData;
import com.pijava.codemode.api.CodeModeVmLauncher;
import com.pijava.codemode.api.Protocol;
import com.pijava.codemode.api.ScriptTool;
import com.pijava.codemode.runtime.CodeModeExecuteOptions;
import com.pijava.codemode.runtime.CodeModeSandboxBase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for the shared sandbox registration and lifecycle. */
class CodeModeSandboxBaseTest {
    @Test
    void duplicateToolRegistrationThrows() {
        CodeModeSandboxBase sandbox = new CodeModeSandboxBase(
                Map.of("echo", args -> args), Map.of(), (data, events) -> noopChannel());
        assertThatThrownBy(() -> sandbox.registerTool("echo", args -> args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tool \"echo\" is already registered");
    }

    @Test
    void globalsAreValidated() {
        assertThatThrownBy(() -> new CodeModeSandboxBase(Map.of(),
                Map.of("1bad", args -> args), (data, events) -> noopChannel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid global name \"1bad\"");
        assertThatThrownBy(() -> new CodeModeSandboxBase(Map.of(),
                Map.of("store", args -> args), (data, events) -> noopChannel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid global name \"store\"");
        Map<String, ScriptTool> conflicting = new java.util.LinkedHashMap<>();
        conflicting.put("ns.member", args -> args);
        conflicting.put("ns", args -> args);
        assertThatThrownBy(() -> new CodeModeSandboxBase(Map.of(), conflicting,
                (data, events) -> noopChannel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Global \"ns\" conflicts with the namespace \"ns\"");
    }

    @Test
    void closedSandboxRejectsExecuteAndAbortsRunning() throws Exception {
        LinkedBlockingQueue<CodeModeVmLauncher.VmEvents> events = new LinkedBlockingQueue<>();
        CodeModeSandboxBase sandbox = new CodeModeSandboxBase(Map.of(), Map.of(), (data, e) -> {
            events.add(e);
            return noopChannel();
        });
        CompletableFuture<CodeModeData.Result> result = sandbox.execute("code");
        CodeModeVmLauncher.VmEvents running = events.poll(2, TimeUnit.SECONDS);
        assertThat(running).isNotNull();
        sandbox.close();
        assertThat(result.get(2, TimeUnit.SECONDS).error())
                .isEqualTo(CodeModeData.CodemodeError.of("aborted", "Sandbox closed"));
        assertThatThrownBy(() -> sandbox.execute("again").join())
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void executionsAreIsolated() throws Exception {
        List<CodeModeVmLauncher.VmEvents> allEvents = new CopyOnWriteArrayList<>();
        AtomicInteger starts = new AtomicInteger();
        CodeModeSandboxBase sandbox = new CodeModeSandboxBase(Map.of(), Map.of(), (data, events) -> {
            allEvents.add(events);
            starts.incrementAndGet();
            return noopChannel();
        });
        CompletableFuture<CodeModeData.Result> first = sandbox.execute("one");
        CompletableFuture<CodeModeData.Result> second = sandbox.execute("two");
        allEvents.get(0).message(new Protocol.DoneOk("1", "[]"));
        allEvents.get(1).message(new Protocol.DoneOk("2", "[]"));
        assertThat(first.get(2, TimeUnit.SECONDS).value()).isEqualTo(1L);
        assertThat(second.get(2, TimeUnit.SECONDS).value()).isEqualTo(2L);
        assertThat(starts.get()).isEqualTo(2);
    }

    @Test
    void deadlineFiresWithTimeoutMessage() throws Exception {
        LinkedBlockingQueue<CodeModeVmLauncher.VmEvents> events = new LinkedBlockingQueue<>();
        CodeModeSandboxBase sandbox = new CodeModeSandboxBase(Map.of(), Map.of(), 30L, null, (data, e) -> {
            events.add(e);
            return noopChannel();
        });
        CodeModeData.Result result = sandbox.execute("hang",
                new CodeModeExecuteOptions(30L, Map.of())).get(2, TimeUnit.SECONDS);
        assertThat(result.error()).isEqualTo(CodeModeData.CodemodeError.of(
                "timeout", "Execution timed out after 30 ms"));
    }

    @Test
    void storeIsSerializedAsJsonText() throws Exception {
        List<CodeModeData.StartData> starts = new ArrayList<>();
        CodeModeSandboxBase sandbox = new CodeModeSandboxBase(Map.of(), Map.of(), (data, events) -> {
            starts.add(data);
            events.message(new Protocol.DoneOk("null", "[]"));
            return noopChannel();
        });
        sandbox.execute("code", new CodeModeExecuteOptions(Map.of("answer", 42))).get(2, TimeUnit.SECONDS);
        assertThat(starts.get(0).store()).containsEntry("answer", "42");
    }

    private static CodeModeVmLauncher.VmChannel noopChannel() {
        return new CodeModeVmLauncher.VmChannel() {
            @Override
            public void post(Protocol.HostToVm message) {
            }

            @Override
            public void stop() {
            }
        };
    }
}

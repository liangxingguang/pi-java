package com.pijava.codemode;

import com.pijava.codemode.api.CodeModeData;
import com.pijava.codemode.api.CodeModeVmLauncher;
import com.pijava.codemode.api.Protocol;
import com.pijava.codemode.backend.EndiveCodeModeLauncher;
import com.pijava.codemode.backend.QuickJsBinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for the Endive + quickjs-wasi backend. */
class EndiveVmSpikeTest {
    @Test
    void bindingEvaluatesAndReadsValues() throws Exception {
        try (QuickJsBinding binding = new QuickJsBinding()) {
            long handle = binding.eval("1 + 1", "spike.js");
            assertThat(binding.exception(handle)).isNull();
            assertThat(binding.readNumber(handle)).isEqualTo(2.0);
        }
    }

    @Test
    void preludeRunsScriptAndEmitsOutput() throws Exception {
        Harness harness = new Harness();
        harness.start("text('hello'); return 42;");

        assertThat(harness.await(Protocol.DoneOk.class)).isNotNull();
        assertThat(harness.texts()).containsExactly("hello");
        assertThat(harness.done.valueJson()).isEqualTo("42");
        harness.channel.stop();
    }

    @Test
    void scriptToolCallRoundTripsThroughHost() throws Exception {
        Harness harness = new Harness();
        harness.start("const value = await tools.read({path: 'x'}); text(value); return null;");

        Protocol.Call call = harness.await(Protocol.Call.class);
        assertThat(call.target()).isEqualTo("tool");
        assertThat(call.name()).isEqualTo("read");
        harness.channel.post(new Protocol.Result(call.id(), true, "\"from-host\"", null));

        Protocol.DoneOk done = harness.awaitDone();
        assertThat(harness.texts()).containsExactly("from-host");
        assertThat(done.valueJson()).isEqualTo("null");
        harness.channel.stop();
    }

    @Test
    void interruptStopsUnterminatedScript() throws Exception {
        Harness harness = new Harness();
        harness.start("while (true) {}");
        Thread.sleep(150);
        harness.channel.stop();

        Protocol.VmToHost terminal = harness.awaitTerminal();
        assertThat(terminal).isInstanceOfAny(Protocol.DoneError.class, Protocol.Crash.class);
        harness.channel.stop();
    }

    private static final class Harness implements CodeModeVmLauncher.VmEvents {
        private final BlockingQueue<Protocol.VmToHost> messages = new LinkedBlockingQueue<>();
        private final List<String> texts = new ArrayList<>();
        private CodeModeVmLauncher.VmChannel channel;
        private Protocol.DoneOk done;

        private void start(String code) throws Exception {
            CodeModeData.StartData data = new CodeModeData.StartData(
                    code,
                    List.of(new CodeModeData.ToolDecl("read", "read", "read a file")),
                    List.of(),
                    null,
                    Map.of());
            channel = new EndiveCodeModeLauncher().launch(data, this);
        }

        @Override
        public void message(Protocol.VmToHost message) {
            if (message instanceof Protocol.Output output && output.item() instanceof CodeModeData.Text text) {
                texts.add(text.text());
            }
            if (message instanceof Protocol.DoneOk ok) {
                done = ok;
            }
            messages.add(message);
        }

        @Override
        public void failure(CodeModeData.CodemodeError error) {
            messages.add(new Protocol.Crash(error.message()));
        }

        private Protocol.DoneOk awaitDone() throws InterruptedException {
            if (done != null) {
                return done;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Protocol.VmToHost message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message instanceof Protocol.DoneOk ok) {
                    done = ok;
                    return ok;
                }
                if (message instanceof Protocol.DoneError error) {
                    throw new AssertionError("Script failed: " + error.errorJson());
                }
            }
            throw new AssertionError("Timed out waiting for DoneOk");
        }

        private <T extends Protocol.VmToHost> T await(Class<T> type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Protocol.VmToHost message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (type.isInstance(message)) {
                    return type.cast(message);
                }
            }
            throw new AssertionError("Timed out waiting for " + type.getSimpleName());
        }

        private Protocol.VmToHost awaitTerminal() throws InterruptedException {
            Protocol.VmToHost message;
            while ((message = poll()) != null) {
                if (message instanceof Protocol.DoneOk || message instanceof Protocol.DoneError
                        || message instanceof Protocol.Crash) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for terminal message");
        }

        private Protocol.VmToHost poll() throws InterruptedException {
            return messages.poll(10, TimeUnit.SECONDS);
        }

        private List<String> texts() {
            return texts;
        }
    }
}

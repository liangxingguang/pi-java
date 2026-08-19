package com.pijava.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.pijava.client.PiClient;
import com.pijava.client.PiClientException;
import com.pijava.client.PiClientOptions;
import com.pijava.client.UnixSocketTransport;
import com.pijava.protocol.Command;
import com.pijava.protocol.CommandResult;
import com.pijava.protocol.ModelMetadata;
import com.pijava.protocol.ModelRef;
import com.pijava.protocol.ProtocolThinkingLevel;
import com.pijava.protocol.SessionMetadata;
import com.pijava.protocol.SessionPhase;
import com.pijava.protocol.SessionSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-9b/c: PiServer + PiClient 集成 —— 本地 AF_UNIX 全流程 +
 * 并发 attach 同一会话 → SESSION_LOCKED。
 */
class PiServerClientIntegrationTest {

    @TempDir
    Path tmp;

    @Test
    void fullLifecycleOverUnixSocket() throws Exception {
        var mem = new MemService();
        var socket = tmp.resolve("test.sock");
        try (var server = startServer(mem, socket)) {
            try (var client = new PiClient(
                    PiClientOptions.of(new UnixSocketTransport(socket)))) {
                client.connect();

                // list
                var list = client.send(new Command.List());
                assertThat(list).isInstanceOf(CommandResult.ListResult.class);

                // create
                var create = client.send(new Command.Create(
                    "/cwd", "my-session",
                    new ModelRef("anthropic", "claude-fable-5"),
                    ProtocolThinkingLevel.MEDIUM));
                assertThat(create).isInstanceOf(CommandResult.CreateResult.class);
                String sessionId = ((CommandResult.CreateResult) create).session().id();

                // attach → lease
                try (var handle = client.acquire(sessionId)) {
                    assertThat(handle.snapshot().name()).isEqualTo("my-session");

                    // prompt → snapshot update
                    var afterPrompt = handle.prompt("hi");
                    assertThat(afterPrompt.id()).isEqualTo(sessionId);
                    assertThat(handle.snapshot().id()).isEqualTo(sessionId);

                    handle.abort();
                } // close → detach

                // 重新 attach 应成功（已释放）
                try (var handle2 = client.acquire(sessionId)) {
                    assertThat(handle2.snapshot().id()).isEqualTo(sessionId);
                }
            }
        }
    }

    @Test
    void concurrentAttachSameSessionGetsLocked() throws Exception {
        var mem = new MemService();
        var socket = tmp.resolve("lock.sock");
        try (var server = startServer(mem, socket)) {
            try (var client1 = new PiClient(
                    PiClientOptions.of(new UnixSocketTransport(socket)));
                 var client2 = new PiClient(
                    PiClientOptions.of(new UnixSocketTransport(socket)))) {
                client1.connect();
                client2.connect();

                var create = client1.send(new Command.Create(
                    "/cwd", "locked-session",
                    new ModelRef("deepseek", "deepseek-chat"),
                    ProtocolThinkingLevel.OFF));
                String sessionId = ((CommandResult.CreateResult) create).session().id();

                try (var handle1 = client1.acquire(sessionId)) {
                    // client2 并发 attach 同一会话 → SESSION_LOCKED
                    assertThatThrownBy(() -> client2.acquire(sessionId))
                        .isInstanceOf(PiClientException.class);
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private PiServer startServer(MemService service, Path socket) {
        var server = new PiServer(service,
            PiServerOptions.of(new UnixSocketListener(socket), "test-server"));
        server.start();
        return server;
    }

    // ── 内存服务实现 ─────────────────────────────────────────────────────

    private static final class MemService implements PiServerService {
        final Map<String, MemRuntime> sessions = new ConcurrentHashMap<>();
        final Map<String, Boolean> leased = new ConcurrentHashMap<>();
        long seq;

        @Override
        public List<SessionMetadata> listSessions() {
            return sessions.values().stream()
                .map(r -> new SessionMetadata(r.snapshot().id(),
                    r.snapshot().createdAt(), r.snapshot().updatedAt(),
                    null, r.snapshot().name(), r.snapshot().cwd()))
                .toList();
        }

        @Override
        public List<ModelMetadata> listModels() {
            return List.of(new ModelMetadata("anthropic", "claude-fable-5",
                "Claude Fable 5", "anthropic-messages", true,
                List.of("text", "image"), 200_000, 64_000, 5, 15,
                List.of(ProtocolThinkingLevel.LOW, ProtocolThinkingLevel.MEDIUM,
                    ProtocolThinkingLevel.HIGH), false));
        }

        @Override
        public PiSessionRuntime createSession(CreateSessionOptions options) {
            String id = "s" + (++seq);
            var snapshot = new SessionSnapshot(id, options.name(),
                options.cwd() == null ? "" : options.cwd(), 1000L, 1000L,
                SessionPhase.IDLE, options.model(),
                options.thinkingLevel() == null ? ProtocolThinkingLevel.OFF
                    : options.thinkingLevel(),
                false, false, 0L, List.of(), List.of(), 0);
            var runtime = new MemRuntime(this, snapshot);
            sessions.put(id, runtime);
            // create 不建立租约；attach 时 openSession 才标记
            return runtime;
        }

        @Override
        public PiSessionRuntime openSession(String sessionId) {
            var runtime = sessions.get(sessionId);
            if (runtime == null) {
                throw new IllegalArgumentException("Not found: " + sessionId);
            }
            if (leased.putIfAbsent(sessionId, true) != null) {
                throw new SessionLockedException(sessionId);
            }
            return runtime;
        }

        void release(String sessionId) {
            leased.remove(sessionId);
        }
    }

    private static final class MemRuntime implements PiSessionRuntime {
        private final MemService service;
        private final SessionSnapshot snapshot;
        private final List<Consumer<RuntimeEvent>> listeners = new CopyOnWriteArrayList<>();

        MemRuntime(MemService service, SessionSnapshot snapshot) {
            this.service = service;
            this.snapshot = snapshot;
        }

        @Override public SessionSnapshot snapshot() { return snapshot; }
        @Override public SessionPhase getPhase() { return snapshot.phase(); }

        @Override
        public void prompt(PromptInput input) {
            emit(new RuntimeEvent(snapshot, null));
        }

        @Override public void steer(SteerInput input) { }
        @Override public void abort() { }
        @Override public void setModel(ModelRef model) { }
        @Override public void setThinking(ProtocolThinkingLevel level) { }

        @Override
        public Runnable subscribe(Consumer<RuntimeEvent> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public void close() {
            service.release(snapshot.id());
        }

        private void emit(RuntimeEvent event) {
            for (var listener : listeners) {
                listener.accept(event);
            }
        }
    }
}

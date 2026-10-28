package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P13：event-bus——/emit ⇒ 总线广播，监听者收通知
 * （{@code Event from /emit command: <msg>}）；session_start 自动 emit 一次；
 * 扩展间可跨件收（BusProbeExtension 互发断言）；会话失效统一退订。
 */
class EventBusExtensionTest {

    @TempDir Path tmp;

    @BeforeEach
    void resetProbe() {
        BusProbeExtension.reset();
    }

    @Test
    void sessionStartAutoEmitsAndProbeReceivesAcrossExtensions() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp,
                EventBusExtension.class, BusProbeExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-eb", "ok"))) {
                // session_start 自动 emit（create 期发射）⇒ 探针跨件收到
                assertThat(BusProbeExtension.RECEIVED).anySatisfy(m -> {
                    assertThat(m.get("message")).isEqualTo("Session started");
                    assertThat(m.get("from")).isEqualTo("event-bus-example");
                });
            }
        }
    }

    @Test
    void emitCommandBroadcastsAndListenerNotifies() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp,
                EventBusExtension.class, BusProbeExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-eb2", "ok"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                BusProbeExtension.RECEIVED.clear();

                var command = session.services().slashCommands().get("emit");
                assertThat(command).isNotNull();
                command.execute("hello there", SlashContext.of(session))
                    .toCompletableFuture().join();

                // 监听者（本扩展）收通知；探针跨件收载荷
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Event from /emit command: hello there", NotifyType.INFO));
                assertThat(BusProbeExtension.RECEIVED).anySatisfy(m ->
                    assertThat(m.get("message")).isEqualTo("hello there"));

                // 空参数 ⇒ 默认 "hello"
                command.execute("  ", SlashContext.of(session)).toCompletableFuture().join();
                assertThat(BusProbeExtension.RECEIVED).anySatisfy(m ->
                    assertThat(m.get("message")).isEqualTo("hello"));
            }
        }
    }

    @Test
    void invalidationUnsubscribesChannelSubscriptions() {
        // 跟踪视图订阅（扩展的 events() 同一机制）——失效后旧订阅不收
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var wired = ExampleFixtures.wireWith(null, "print");
        var closer = wired.context().events().on("probe:call", data -> received.incrementAndGet());

        wired.context().events().emit("probe:call", Map.of("message", "a"));
        assertThat(received.get()).isEqualTo(1);

        wired.context().invalidateChannels();
        wired.context().events().emit("probe:call", Map.of("message", "b"));
        assertThat(received.get()).isEqualTo(1); // 失效后旧订阅不收
        assertThat(closer).isNotNull();
    }
}

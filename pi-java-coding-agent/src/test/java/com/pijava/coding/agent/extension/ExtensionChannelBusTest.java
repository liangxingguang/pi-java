package com.pijava.coding.agent.extension;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 26（docs/37 T7，E5-28）：pi {@code createEventBus}（core/event-bus.ts:1-33
 * 逐字语义）——emit/on/clear、异常吞掉续链、退订按实例、trackedView 的跟踪。
 */
class ExtensionChannelBusTest {

    @Test
    void emitDeliversInRegistrationOrder() {
        var bus = new ExtensionChannelBus();
        var seen = new ArrayList<String>();
        bus.on("ch", data -> seen.add("a:" + data));
        bus.on("ch", data -> seen.add("b:" + data));

        bus.emit("ch", 1);

        assertThat(seen).containsExactly("a:1", "b:1");
    }

    @Test
    void handlerErrorIsSwallowedAndChainContinues() {
        var bus = new ExtensionChannelBus();
        var seen = new ArrayList<String>();
        bus.on("ch", data -> {
            throw new RuntimeException("boom");
        });
        bus.on("ch", data -> seen.add("ok"));

        bus.emit("ch", null); // 不抛

        assertThat(seen).containsExactly("ok");
    }

    @Test
    void unsubscribeRemovesByInstanceAndClearsEmptyBucket() throws Exception {
        var bus = new ExtensionChannelBus();
        var seen = new ArrayList<String>();
        var first = bus.on("ch", data -> seen.add("a"));
        bus.on("ch", data -> seen.add("b"));

        first.close();
        bus.emit("ch", null);
        assertThat(seen).containsExactly("b");

        bus.clear();
        bus.emit("ch", null);
        assertThat(seen).containsExactly("b");
    }

    @Test
    void trackedViewRegistersSubscriptionsForBulkInvalidation() {
        var bus = new ExtensionChannelBus();
        var tracked = new ArrayList<AutoCloseable>();
        var view = bus.trackedView(tracked::add);
        var seen = new ArrayList<String>();
        view.on("ch", data -> seen.add("x"));

        assertThat(tracked).hasSize(1);
        // 宿主失效 ⇒ 统一退订（pi runtime.invalidate 的 eventBusUnsubscribers 循环）。
        tracked.forEach(s -> {
            try {
                s.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        bus.emit("ch", null);
        assertThat(seen).isEmpty();
    }

    @Test
    void rootBusOnIsUntracked() {
        var bus = new ExtensionChannelBus();
        var seen = new ArrayList<String>();
        bus.on("ch", data -> seen.add("x"));
        bus.emit("ch", null);
        assertThat(seen).containsExactly("x");
    }
}

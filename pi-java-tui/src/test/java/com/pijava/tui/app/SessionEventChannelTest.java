package com.pijava.tui.app;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.component.StatusIndicator;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑤的<b>结构改动</b>本身：会话事件必须经渲染线程队列才落到屏幕上
 * （pi {@code InteractiveMode.subscribeToAgent} + {@code handleEvent} 的对应物）。
 *
 * <p>这是「注入点写下就跑一次」的钉子：事件到达时屏幕上必须<b>还什么都没有</b>
 * （证明它走的是队列而不是生产线程直调），drain 之后才上屏。</p>
 */
class SessionEventChannelTest {

    @Test
    void sessionEventsReachTheScreenOnlyAfterTheRenderThreadDrains() {
        var dispatcher = new TuiEventDispatcher();
        var screen = new ChatScreen();
        var emitter = new AtomicReference<Consumer<AgentSessionEvent>>();
        var live = new AtomicInteger();
        var channel = new SessionEventChannel(dispatcher, screen::onSessionEvent);

        try (channel) {
            channel.open(null, listener -> {
                live.incrementAndGet();
                emitter.set(listener);
                return live::decrementAndGet;
            });
            assertThat(channel.isOpen()).isTrue();
            assertThat(live.get()).isEqualTo(1);

            emitter.get().accept(new AgentSessionEvent.AutoRetryStart(1, 3, 4000L, "boom"));

            // 渲染线程还没跑 ⇒ 屏幕上什么也没有（直调就会在这里红）
            assertThat(screen.indicator()).isNull();

            dispatcher.drain();
            assertThat(screen.indicator()).isInstanceOf(StatusIndicator.Retry.class);
        }

        assertThat(channel.isOpen()).isFalse();
        assertThat(live.get()).isZero();
    }

    @Test
    void reopeningReplacesThePreviousSubscription() {
        var dispatcher = new TuiEventDispatcher();
        var screen = new ChatScreen();
        var live = new AtomicInteger();
        var channel = new SessionEventChannel(dispatcher, screen::onSessionEvent);

        try (channel) {
            channel.open(null, listener -> {
                live.incrementAndGet();
                return live::decrementAndGet;
            });
            channel.open(null, listener -> {
                live.incrementAndGet();
                return live::decrementAndGet;
            });

            // 重开先摘旧句柄 ⇒ 换会话不会留下第二个订阅者
            assertThat(live.get()).isEqualTo(1);
        }

        assertThat(live.get()).isZero();
    }

    @Test
    void closingTwiceIsHarmless() {
        var dispatcher = new TuiEventDispatcher();
        var channel = new SessionEventChannel(dispatcher, event -> { });

        channel.close();
        channel.close();
        assertThat(channel.isOpen()).isFalse();
    }
}
